package io.andchamber.app.phone.core

import java.util.UUID
import io.andchamber.app.phone.standing.PhoneStandingGrantResult
import io.andchamber.app.phone.standing.PhoneStandingPolicy
import io.andchamber.app.phone.standing.PhoneStandingRefusal
import io.andchamber.app.phone.standing.PhoneStandingResume
import io.andchamber.app.phone.standing.PhoneStandingScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

/**
 * Pure phone-control state machine.
 *
 * Default is disabled. Only native UI calls [enable], [grant], [pause] and [revoke]; an agent call
 * can never grant itself authority. Authorization is independent from the connection, task and
 * runtime identity, but a short lease expiry, lock, host return, device disconnect or runtime
 * change drops it. A new connection never restores an old lease.
 *
 * Every mutating call is bound to the current observation generation, target package and window.
 * The generation and target are checked at admission and again immediately before dispatch. The
 * driver repeats the live window check before injecting. A refused precondition never reaches the
 * driver, and an uncertain in-flight action stops new mutations instead of claiming a rollback.
 *
 * Capabilities are read once on connect and gate observe, capture and each mutation individually.
 * Non-ASCII input is refused with [PhoneErrorCode.UNICODE_UNSUPPORTED] unless the driver declared
 * `unicodeInput`; this version never pretends a Unicode input succeeded.
 *
 * Exactly one agent driver operation runs at a time. A second operation is refused with
 * [PhoneErrorCode.BUSY] and is never queued. Pause/revoke cancel but do not queue. A sent mutation
 * always discards the old observation, so the next mutation needs a fresh snapshot.
 */
class PhoneController(
    private val driver: PhoneDriver,
    private val clock: () -> Long,
    private val targetPolicy: PhoneTargetPolicy = PhoneTargetPolicy(),
    requestCapacity: Int = PhoneLimits.MAX_REQUESTS,
    private val newObservationId: () -> String = { "obs-" + UUID.randomUUID() },
) {
    init {
        require(requestCapacity in 1..PhoneLimits.MAX_REQUESTS) { "request capacity" }
    }

    private val requestCapacity = requestCapacity
    private val lock = Any()
    private val mutableState = MutableStateFlow(PhoneState())
    val state: StateFlow<PhoneState> = mutableState.asStateFlow()

    /**
     * Native-only: a stop/handover suspends standing chat requests until a native person resume.
     * It is not a lease and never grants; the existing grant/pause/unknown fields remain independent.
     */
    private var standingBlock: PhoneStandingBlockReason? = null

    /** (epoch, requestId). A refused call is never retried under the same key. */
    private val requestRecords = LinkedHashSet<PhoneRequestKey>()

    /** One driver operation at a time. There is no queue: a second admission gets BUSY. */
    private var activeCall: ActiveCall? = null
    private var observationGeneration = 0L
    private var currentObservation: PhoneObservation? = null
    private var lastExpiryEpoch: Long? = null

    /** Invalidates driver work admitted before a pause/unknown/invalidation, even in the same epoch. */
    private var controlEpoch = 0L

    fun nowMs(): Long = clock()

    /** Native UI only. Enabling never grants a target. */
    fun enable() {
        synchronized(lock) {
            if (mutableState.value.enabled) return
            publishLocked(PhoneEvent.ENABLED) { it.copy(enabled = true) }
        }
    }

    /** Native UI only. Disabling revokes, cancels in-flight work and keeps the connection status. */
    fun disable() {
        val calls = synchronized(lock) {
            val current = mutableState.value
            val open = activeCall
            requestRecords.clear()
            lastExpiryEpoch = null
            currentObservation = null
            standingBlock = null
            publishLocked(PhoneEvent.DISABLED) {
                it.copy(
                    enabled = false,
                    grant = null,
                    epoch = current.epoch + 1,
                    observation = null,
                    paused = false,
                    blocked = null,
                    pausing = false,
                )
            }
            open
        }
        cancelCall(calls, PhoneErrorCode.DISABLED)
    }

    /**
     * Native UI only. One grant at a time, independent from connection/task/runtime. A new grant
     * always starts a new epoch and clears the old request records: old ids are rejected by epoch
     * and old actions are never replayed. [ttlMs] may be the explicit
     * [PhoneTimeLimits.PERMANENT] sentinel, which only native configuration can produce; a finite
     * value still stays within the short lease range.
     */
    fun grant(
        targetPackage: String,
        allowedActions: Set<PhoneActionKind>,
        ttlMs: Long,
    ): PhoneGrantResult {
        if (!mutableState.value.enabled) return PhoneGrantResult.Refused(PhoneErrorCode.DISABLED)
        targetPolicy.refusal(targetPackage)?.let { return PhoneGrantResult.Refused(it) }
        if (!PhoneTimeLimits.isPermanent(ttlMs) &&
            ttlMs !in PhoneLimits.MIN_LEASE_MS..PhoneLimits.MAX_LEASE_MS
        ) {
            return PhoneGrantResult.Refused(PhoneErrorCode.INVALID_REQUEST)
        }
        val now = clock()
        val (grant, calls) = synchronized(lock) {
            if (!mutableState.value.enabled) {
                return PhoneGrantResult.Refused(PhoneErrorCode.DISABLED)
            }
            val epoch = mutableState.value.epoch + 1
            val next = PhoneGrant(
                epoch = epoch,
                targetPackage = targetPackage,
                allowedActions = allowedActions.toSet(),
                grantedAtMs = now,
                expiresAtMs = PhoneTimeLimits.expiresAt(now, ttlMs),
            )
            val open = activeCall
            requestRecords.clear()
            lastExpiryEpoch = null
            currentObservation = null
            publishLocked(PhoneEvent.GRANTED) {
                it.copy(
                    epoch = epoch,
                    grant = next,
                    observation = null,
                    paused = false,
                    blocked = null,
                    pausing = false,
                )
            }
            next to open
        }
        cancelCall(calls, PhoneErrorCode.EPOCH_CHANGED)
        return PhoneGrantResult.Granted(grant)
    }

    /**
     * Native-only chat entry: create a per-task grant inside the person's preauthorized standing
     * scope. The model/tool surface cannot create a [PhoneStandingScope]; it reaches the native
     * starter port instead. The scope check and the grant happen under one lock, so a request can
     * never slip past a just-set pause, unknown block, standing standby or runtime change, and it
     * never clears those locks. Every call creates a new epoch; the lease is bounded by the scope's
     * per-task duration and the remaining standing window. Either bound may be the explicit
     * [PhoneTimeLimits.PERMANENT] sentinel: only two permanent bounds yield a permanent lease; a
     * permanent per-task inside a finite window stays bounded by that window.
     */
    fun grantFromPreauthorization(
        scope: PhoneStandingScope,
        targetPackage: String,
    ): PhoneStandingGrantResult {
        val now = clock()
        var open: ActiveCall? = null
        val result =
            synchronized(lock) {
                val current = mutableState.value
                val refusal = PhoneStandingPolicy.refusal(scope, targetPackage, now, current, targetPolicy)
                if (refusal != null) return@synchronized PhoneStandingGrantResult.Refused(refusal)
                val ttl =
                    PhoneStandingPolicy.perTaskTtl(scope, now)
                        ?: return@synchronized PhoneStandingGrantResult.Refused(PhoneStandingRefusal.EXPIRED)
                val epoch = current.epoch + 1
                val grant =
                    PhoneGrant(
                        epoch = epoch,
                        targetPackage = targetPackage,
                        allowedActions = scope.allowedActions.toSet(),
                        grantedAtMs = now,
                        expiresAtMs = PhoneTimeLimits.expiresAt(now, ttl),
                    )
                open = activeCall
                requestRecords.clear()
                lastExpiryEpoch = null
                currentObservation = null
                // The policy just proved paused/blocked/standing standby are clear. This grant must
                // not clear them for a racing native stop.
                publishLocked(PhoneEvent.GRANTED) {
                    it.copy(
                        epoch = epoch,
                        grant = grant,
                        observation = null,
                        pausing = false,
                    )
                }
                PhoneStandingGrantResult.Granted(grant)
            }
        if (result is PhoneStandingGrantResult.Granted) {
            cancelCall(open, PhoneErrorCode.EPOCH_CHANGED)
        }
        return result
    }

    /**
     * Native integration only: a need_user/takeover stop suspends standing chat requests until a
     * native person resume. It does not revoke or change the current grant; the caller's stop path
     * does that. Keeping the first reason prevents a late event from rewriting the person-facing
     * reason of the stop that actually happened.
     */
    fun blockStanding(reason: PhoneStandingBlockReason) {
        synchronized(lock) {
            if (standingBlock != null) return
            standingBlock = reason
            publishLocked(PhoneEvent.STANDING_BLOCKED) { it }
        }
    }

    /**
     * Native person resume of the standing scope. The caller has already verified the native
     * confirmation; this only clears the standby block for the current runtime. It does not
     * un-pause, acknowledge an unknown outcome or create a grant: the next request still passes the
     * standing policy and the normal admission. A different runtime is refused.
     */
    fun resumeStanding(resume: PhoneStandingResume): Boolean =
        synchronized(lock) {
            val current = mutableState.value
            if (!current.enabled || current.runtimeId != resume.runtimeId) return@synchronized false
            if (standingBlock == null) return@synchronized true
            standingBlock = null
            publishLocked(PhoneEvent.STANDING_RESUMED) { it }
            true
        }

    /** Read-only native/UI fact: standing chat requests are suspended until a native resume. */
    fun standingBlocked(): Boolean = synchronized(lock) { standingBlock != null }

    /**
     * Native-only person continuation after a confirmed stop. Unlike [resumeStanding], this also
     * clears the ordinary pause in the same lock/publish, because the person just confirmed the
     * resume from the native screen. It never clears an unknown-outcome block: the person must first
     * mark that outcome checked, so a continuation cannot silently assert that a dispatched action
     * had no effect. A different runtime/disabled controller is refused and nothing is changed.
     *
     * This does not grant or replay anything; the caller still creates a fresh epoch through
     * [grantFromPreauthorization] and re-observes the target before waking the chat.
     */
    fun resumeStandingForPerson(resume: PhoneStandingResume): Boolean =
        synchronized(lock) {
            val current = mutableState.value
            if (!current.enabled || current.runtimeId != resume.runtimeId) return@synchronized false
            // Unknown outcomes stay blocked until the person explicitly checks the device.
            if (current.blocked != null) return@synchronized false
            if (standingBlock == null && !current.paused) return@synchronized true
            standingBlock = null
            publishLocked(PhoneEvent.STANDING_RESUMED) { it.copy(paused = false) }
            true
        }

    /** Native UI only. Stops accepting new mutations immediately and cancels cancellable calls. */
    fun pause() {
        val calls = synchronized(lock) {
            if (mutableState.value.paused) return
            currentObservation = null
            controlEpoch += 1
            publishLocked(PhoneEvent.PAUSED) {
                it.copy(
                    paused = true,
                    observation = null,
                    pausing = it.inFlightMutation,
                )
            }
            activeCall
        }
        cancelCall(calls, PhoneErrorCode.PAUSED)
    }

    /** Native UI only. Does not clear an unknown-outcome block. */
    fun resume() {
        synchronized(lock) {
            if (!mutableState.value.paused) return
            publishLocked(PhoneEvent.RESUMED) { it.copy(paused = false) }
        }
    }

    /**
     * Native UI only, after a person checked the device. It clears the unknown block but does not
     * restore the old observation or replay any action.
     */
    fun acknowledgeUnknownOutcome() {
        synchronized(lock) {
            if (mutableState.value.blocked == null) return
            publishLocked(PhoneEvent.UNKNOWN_ACKNOWLEDGED) { it.copy(blocked = null) }
        }
    }

    /** Native UI only. */
    fun revoke() = revokeFor(PhoneEvent.REVOKED)

    /** Native UI/service only. A locked device drops the lease without disconnecting. */
    fun onDeviceLocked() {
        synchronized(lock) { if (standingBlock == null) standingBlock = PhoneStandingBlockReason.DEVICE_LOCKED }
        revokeFor(PhoneEvent.LOCKED)
    }

    /** Native UI/service only. Returning to the host app drops the lease. */
    fun onHostReturned() = revokeFor(PhoneEvent.HOST_RETURNED)

    /** Service only. A lost device connection always drops the lease and suspends standing requests. */
    fun onConnectionLost() {
        val calls = synchronized(lock) {
            if (standingBlock == null) standingBlock = PhoneStandingBlockReason.CONNECTION_LOST
            revokeLocked(PhoneEvent.CONNECTION_LOST, disconnect = true)
        }
        cancelCall(calls, PhoneErrorCode.NOT_CONNECTED)
    }

    /**
     * Service only. A different runtime identity revokes the lease; a first assignment just records
     * the identity. Old epochs can never execute against the new runtime.
     */
    fun onRuntimeChanged(runtimeId: String) {
        val calls = synchronized(lock) {
            val current = mutableState.value
            if (current.runtimeId == runtimeId) return
            if (current.runtimeId == null) {
                publishLocked(PhoneEvent.RUNTIME_CHANGED) { it.copy(runtimeId = runtimeId) }
                null
            } else {
                if (standingBlock == null) standingBlock = PhoneStandingBlockReason.RUNTIME_CHANGED
                revokeLocked(
                    PhoneEvent.RUNTIME_CHANGED,
                    disconnect = true,
                    runtimeId = runtimeId,
                )
            }
        }
        cancelCall(calls, PhoneErrorCode.EPOCH_CHANGED)
    }

    /**
     * Native UI/service only. A reconnect while connected replaces the connection and drops the
     * lease. Connecting after a disconnect does not restore the lease that disconnect revoked.
     */
    suspend fun connect(deadlineMs: Long = PhoneLimits.DEFAULT_CALL_MS): PhoneConnectionResult {
        val replaced = synchronized(lock) {
            if (mutableState.value.connection == PhoneConnectionState.CONNECTED) {
                revokeLocked(PhoneEvent.DISCONNECTED, disconnect = true)
            } else {
                null
            }
        }
        cancelCall(replaced, PhoneErrorCode.EPOCH_CHANGED)

        val timeout = deadlineMs.coerceIn(1L, PhoneLimits.MAX_CALL_MS)
        val connection = try {
            withTimeout(timeout) { driver.connect(timeout) }
        } catch (_: TimeoutCancellationException) {
            return PhoneConnectionResult.Failed(PhoneErrorCode.DEADLINE)
        } catch (failure: PhoneDriverException) {
            return PhoneConnectionResult.Failed(mapFailure(failure.failure))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PhoneConnectionResult.Failed(PhoneErrorCode.DRIVER_UNAVAILABLE)
        }
        if (!validConnection(connection)) {
            return PhoneConnectionResult.Failed(PhoneErrorCode.DRIVER_UNAVAILABLE)
        }
        val capabilities = runCatching { driver.capabilities() }.getOrNull()
        synchronized(lock) {
            publishLocked(PhoneEvent.CONNECTED) {
                it.copy(
                    connection = PhoneConnectionState.CONNECTED,
                    connectionId = connection.connectionId,
                    runtimeId = connection.runtimeId,
                    capabilities = capabilities,
                )
            }
        }
        return PhoneConnectionResult.Connected(connection)
    }

    /** Native UI/service only. Revokes first, then tears the transport down best-effort. */
    suspend fun disconnect(deadlineMs: Long = PhoneLimits.DEFAULT_CALL_MS) {
        val calls = synchronized(lock) {
            revokeLocked(PhoneEvent.DISCONNECTED, disconnect = true)
        }
        cancelCall(calls, PhoneErrorCode.NOT_CONNECTED)
        val timeout = deadlineMs.coerceIn(1L, PhoneLimits.MAX_CALL_MS)
        try {
            withTimeout(timeout) { driver.disconnect(timeout) }
        } catch (_: TimeoutCancellationException) {
            // Best effort. Leases are already gone.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best effort.
        }
    }

    /** Publishes lease expiry so UI/notification do not have to wait for the next agent call. */
    fun refresh() {
        val calls = synchronized(lock) {
            val grant = mutableState.value.grant ?: return
            if (!PhoneTimeLimits.isExpired(clock(), grant.expiresAtMs)) return
            revokeLocked(PhoneEvent.LEASE_EXPIRED, expiredEpoch = grant.epoch)
        }
        cancelCall(calls, PhoneErrorCode.LEASE_EXPIRED)
    }

    /**
     * One agent call. Admission preconditions are the native grant, its short lease, the current
     * observation generation and this call's own epoch. [PhoneAgentRequest.sessionLabel] is routing
     * only: no field in the request is authentication. Duplicate (epoch, requestId) pairs are
     * refused, and a full request table refuses new ids instead of evicting old records. A second
     * concurrent operation gets [PhoneErrorCode.BUSY]; nothing is queued behind it.
     */
    suspend fun execute(
        request: PhoneAgentRequest,
        deadlineMs: Long = PhoneLimits.DEFAULT_CALL_MS,
    ): PhoneCallResult {
        refresh()
        val planned = plan(request, deadlineMs)
        val plan = when (planned) {
            is PlanResult.Refused -> {
                if (planned.error == PhoneErrorCode.LEASE_EXPIRED) refresh()
                return refused(request, planned.error)
            }
            is PlanResult.Admitted -> planned.plan
        }

        return supervisorScope {
            val token = CallToken(plan.key, plan.epoch, plan.call is Plan.Call.Dispatch)
            val job = async(start = CoroutineStart.LAZY) {
                runDriver(plan, token)
            }
            val refusal = synchronized(lock) { reserveLocked(plan, token, job) }
            if (refusal != null) {
                token.cancelReason = refusal
                job.cancel()
                return@supervisorScope refused(request, refusal)
            }
            try {
                job.start()
                when (val outcome = job.await()) {
                    is DriverOutcome.Snapshot -> onSnapshot(request, plan, outcome.snapshot)
                    is DriverOutcome.Image -> onImage(request, plan, outcome.image)
                    is DriverOutcome.Receipt ->
                        onReceipt(request, plan, outcome.receipt, token)
                }
            } catch (timedOut: TimeoutCancellationException) {
                onTimedOut(request, plan, token)
            } catch (cancelled: CancellationException) {
                val reason = token.cancelReason
                if (token.mutation && token.started) {
                    // The driver may have injected before cancellation; never claim a rollback.
                    markOutcomeUnknown(plan.epoch)
                    if (reason == null) throw cancelled
                    PhoneCallResult.Dispatched(
                        request.requestId,
                        request.epoch,
                        PhoneDelivery.UNKNOWN,
                        error = reason,
                    )
                } else {
                    if (reason == null) throw cancelled
                    refused(request, reason)
                }
            } catch (failure: PhoneDriverException) {
                val mutationStarted = token.mutation && token.started
                // Record an uncertain mutation before any read-timeout revoke publishes state.
                if (mutationStarted) markOutcomeUnknown(plan.epoch)
                noteDriverFailure(failure.failure, plan)
                if (mutationStarted) {
                    PhoneCallResult.Dispatched(
                        request.requestId,
                        request.epoch,
                        PhoneDelivery.UNKNOWN,
                        error = token.cancelReason ?: mapFailure(failure.failure),
                    )
                } else {
                    refused(request, token.cancelReason ?: mapFailure(failure.failure))
                }
            } catch (_: Exception) {
                if (token.mutation && token.started) {
                    markOutcomeUnknown(plan.epoch)
                    PhoneCallResult.Dispatched(
                        request.requestId,
                        request.epoch,
                        PhoneDelivery.UNKNOWN,
                        error = token.cancelReason ?: PhoneErrorCode.DRIVER_UNAVAILABLE,
                    )
                } else {
                    refused(request, token.cancelReason ?: PhoneErrorCode.DRIVER_UNAVAILABLE)
                }
            } finally {
                finishCall(token)
            }
        }
    }

    private fun plan(request: PhoneAgentRequest, deadlineMs: Long): PlanResult = synchronized(lock) {
        val state = mutableState.value
        if (!state.enabled) return@synchronized PlanResult.Refused(PhoneErrorCode.DISABLED)
        if (!REQUEST_ID_PATTERN.matches(request.requestId) || !validSessionLabel(request.sessionLabel)) {
            return@synchronized PlanResult.Refused(PhoneErrorCode.INVALID_REQUEST)
        }
        if (request.epoch == lastExpiryEpoch && state.grant == null) {
            return@synchronized PlanResult.Refused(PhoneErrorCode.LEASE_EXPIRED)
        }
        if (request.epoch != state.epoch) {
            return@synchronized PlanResult.Refused(PhoneErrorCode.EPOCH_CHANGED)
        }
        val grant = state.grant ?: return@synchronized PlanResult.Refused(PhoneErrorCode.NOT_GRANTED)
        val now = clock()
        if (PhoneTimeLimits.isExpired(now, grant.expiresAtMs)) {
            return@synchronized PlanResult.Refused(PhoneErrorCode.LEASE_EXPIRED)
        }
        if (state.connection != PhoneConnectionState.CONNECTED) {
            return@synchronized PlanResult.Refused(PhoneErrorCode.NOT_CONNECTED)
        }

        // Idempotency and capacity are checked before payload preconditions so a duplicate still
        // reports DUPLICATE after its old observation was invalidated by a sent action.
        val key = PhoneRequestKey(request.epoch, request.requestId)
        if (key in requestRecords) return@synchronized PlanResult.Refused(PhoneErrorCode.DUPLICATE)
        if (requestRecords.size >= requestCapacity) {
            return@synchronized PlanResult.Refused(PhoneErrorCode.CAPACITY)
        }

        val capabilities = state.capabilities
        if (request.action is PhoneAgentAction.Observe) {
            if (capabilities == null) {
                return@synchronized PlanResult.Refused(PhoneErrorCode.DRIVER_UNAVAILABLE)
            }
            if (!capabilities.observe) {
                return@synchronized PlanResult.Refused(PhoneErrorCode.UNSUPPORTED_ACTION)
            }
        }
        if (request.action is PhoneAgentAction.Capture) {
            if (capabilities == null) {
                return@synchronized PlanResult.Refused(PhoneErrorCode.DRIVER_UNAVAILABLE)
            }
            if (!capabilities.capture) {
                return@synchronized PlanResult.Refused(PhoneErrorCode.UNSUPPORTED_ACTION)
            }
        }

        val kind = request.action.mutationKind()
        if (kind != null) {
            if (state.paused) return@synchronized PlanResult.Refused(PhoneErrorCode.PAUSED)
            if (state.blocked != null) {
                return@synchronized PlanResult.Refused(PhoneErrorCode.OUTCOME_UNKNOWN)
            }
            if (kind !in grant.allowedActions) {
                return@synchronized PlanResult.Refused(PhoneErrorCode.ACTION_NOT_GRANTED)
            }
            val caps = capabilities
                ?: return@synchronized PlanResult.Refused(PhoneErrorCode.DRIVER_UNAVAILABLE)
            if (!caps.supports(kind)) {
                return@synchronized PlanResult.Refused(PhoneErrorCode.UNSUPPORTED_ACTION)
            }
        }

        val call = if (request.action is PhoneAgentAction.Observe) {
            Plan.Call.Observe
        } else {
            val binding = request.observation
                ?: return@synchronized PlanResult.Refused(PhoneErrorCode.OBSERVATION_REQUIRED)
            val current = currentObservation
                ?: return@synchronized PlanResult.Refused(PhoneErrorCode.OBSERVATION_REQUIRED)
            if (current.binding != binding) {
                return@synchronized PlanResult.Refused(PhoneErrorCode.STALE_OBSERVATION)
            }
            if (binding.packageName != grant.targetPackage) {
                return@synchronized PlanResult.Refused(PhoneErrorCode.TARGET_MISMATCH)
            }
            targetPolicy.refusal(binding.packageName)?.let {
                return@synchronized PlanResult.Refused(it)
            }
            if (!validWindowId(binding.windowId)) {
                return@synchronized PlanResult.Refused(PhoneErrorCode.WINDOW_CHANGED)
            }
            if (!validGeometry(binding.geometry)) {
                return@synchronized PlanResult.Refused(PhoneErrorCode.GEOMETRY_CHANGED)
            }
            when (val action = request.action) {
                PhoneAgentAction.Observe -> Plan.Call.Observe
                PhoneAgentAction.Capture -> Plan.Call.Capture(binding)
                is PhoneAgentAction.TapRef -> {
                    if (!REF_PATTERN.matches(action.ref)) {
                        return@synchronized PlanResult.Refused(PhoneErrorCode.INVALID_REQUEST)
                    }
                    val rect = current.refs[action.ref]
                        ?: return@synchronized PlanResult.Refused(PhoneErrorCode.STALE_OBSERVATION)
                    if (!rect.isWithin(binding.geometry)) {
                        return@synchronized PlanResult.Refused(PhoneErrorCode.OUT_OF_BOUNDS)
                    }
                    Plan.Call.Dispatch(
                        binding = binding,
                        action = PhoneDriverAction.Tap(PhonePoint(rect.centerX, rect.centerY), target = rect),
                        kind = PhoneActionKind.TAP,
                    )
                }
                is PhoneAgentAction.TapPoint -> {
                    if (!action.at.isWithin(binding.geometry)) {
                        return@synchronized PlanResult.Refused(PhoneErrorCode.OUT_OF_BOUNDS)
                    }
                    Plan.Call.Dispatch(
                        binding,
                        PhoneDriverAction.Tap(action.at),
                        PhoneActionKind.TAP,
                    )
                }
                is PhoneAgentAction.Swipe -> {
                    if (!action.from.isWithin(binding.geometry) ||
                        !action.to.isWithin(binding.geometry)
                    ) {
                        return@synchronized PlanResult.Refused(PhoneErrorCode.OUT_OF_BOUNDS)
                    }
                    if (action.durationMs !in PhoneLimits.MIN_SWIPE_MS..PhoneLimits.MAX_SWIPE_MS ||
                        action.from == action.to
                    ) {
                        return@synchronized PlanResult.Refused(PhoneErrorCode.INVALID_REQUEST)
                    }
                    Plan.Call.Dispatch(
                        binding,
                        PhoneDriverAction.Swipe(action.from, action.to, action.durationMs),
                        PhoneActionKind.SWIPE,
                    )
                }
                is PhoneAgentAction.Input -> {
                    if (action.text.isBlank() ||
                        action.text.length > PhoneLimits.MAX_INPUT_CHARS ||
                        action.text.any { it.isISOControl() }
                    ) {
                        return@synchronized PlanResult.Refused(PhoneErrorCode.INVALID_REQUEST)
                    }
                    if (action.text.any { it.code > 0x7F } &&
                        capabilities?.supportsUnicodeInput() != true
                    ) {
                        return@synchronized PlanResult.Refused(PhoneErrorCode.UNICODE_UNSUPPORTED)
                    }
                    val focus = if (action.ref == null) {
                        null
                    } else {
                        if (!REF_PATTERN.matches(action.ref)) {
                            return@synchronized PlanResult.Refused(PhoneErrorCode.INVALID_REQUEST)
                        }
                        val node = current.nodes.firstOrNull { it.ref == action.ref }
                            ?: return@synchronized PlanResult.Refused(PhoneErrorCode.STALE_OBSERVATION)
                        if (!node.editable) {
                            return@synchronized PlanResult.Refused(PhoneErrorCode.UNSUPPORTED_ACTION)
                        }
                        if (!node.bounds.isWithin(binding.geometry)) {
                            return@synchronized PlanResult.Refused(PhoneErrorCode.OUT_OF_BOUNDS)
                        }
                        PhonePoint(node.bounds.centerX, node.bounds.centerY)
                    }
                    Plan.Call.Dispatch(
                        binding,
                        PhoneDriverAction.Input(action.text, focus),
                        PhoneActionKind.INPUT,
                    )
                }
                PhoneAgentAction.Back -> Plan.Call.Dispatch(
                    binding,
                    PhoneDriverAction.Back,
                    PhoneActionKind.BACK,
                )
            }
        }

        // A permanent lease has no countdown; the single call still keeps its own MAX_CALL bound.
        val leaseRemaining = PhoneTimeLimits.remainingMs(now, grant.expiresAtMs)
        val timeout =
            deadlineMs.coerceIn(1L, PhoneLimits.MAX_CALL_MS)
                .let { if (leaseRemaining == null) it else it.coerceAtMost(leaseRemaining) }
                .coerceAtLeast(1L)
        PlanResult.Admitted(
            Plan(
                key = key,
                epoch = request.epoch,
                targetPackage = grant.targetPackage,
                controlEpoch = controlEpoch,
                connectionId = state.connectionId,
                timeoutMs = timeout,
                deadlineAtMs = now + timeout,
                call = call,
            ),
        )
    }

    /**
     * Checks the native grant, lease, deadline, pause/block state, current observation and the
     * call's own admission token. Used before the driver starts and again by the driver-provided
     * guard immediately before each real injection.
     */
    private fun admissionRefusalLocked(plan: Plan, token: CallToken?): PhoneErrorCode? =
        synchronized(lock) {
            val state = mutableState.value
            if (!state.enabled) return@synchronized PhoneErrorCode.DISABLED
            if (state.epoch != plan.epoch) return@synchronized PhoneErrorCode.EPOCH_CHANGED
            val grant = state.grant ?: return@synchronized PhoneErrorCode.NOT_GRANTED
            val now = clock()
            if (PhoneTimeLimits.isExpired(now, grant.expiresAtMs)) {
                return@synchronized PhoneErrorCode.LEASE_EXPIRED
            }
            if (now >= plan.deadlineAtMs) return@synchronized PhoneErrorCode.DEADLINE
            if (state.connection != PhoneConnectionState.CONNECTED) {
                return@synchronized PhoneErrorCode.NOT_CONNECTED
            }
            when (val call = plan.call) {
                Plan.Call.Observe -> Unit
                is Plan.Call.Capture -> {
                    if (currentObservation?.binding != call.binding) {
                        return@synchronized PhoneErrorCode.STALE_OBSERVATION
                    }
                }
                is Plan.Call.Dispatch -> {
                    if (state.paused) return@synchronized PhoneErrorCode.PAUSED
                    if (state.blocked != null) {
                        return@synchronized PhoneErrorCode.OUTCOME_UNKNOWN
                    }
                    if (currentObservation?.binding != call.binding) {
                        return@synchronized PhoneErrorCode.STALE_OBSERVATION
                    }
                }
            }
            if (controlEpoch != plan.controlEpoch) {
                return@synchronized PhoneErrorCode.CANCELLED
            }
            if (token != null && activeCall?.token !== token) {
                return@synchronized PhoneErrorCode.CANCELLED
            }
            null
        }

    /** Reserves the single driver slot. A second operation is refused immediately; nothing queues. */
    private fun reserveLocked(plan: Plan, token: CallToken, job: Job): PhoneErrorCode? {
        admissionRefusalLocked(plan, null)?.let { return it }
        if (plan.key in requestRecords) return PhoneErrorCode.DUPLICATE
        if (activeCall != null) return PhoneErrorCode.BUSY
        if (requestRecords.size >= requestCapacity) return PhoneErrorCode.CAPACITY
        requestRecords.add(plan.key)
        activeCall = ActiveCall(token, job)
        if (token.mutation) {
            publishLocked(PhoneEvent.MUTATION_STARTED) {
                it.copy(inFlightMutation = true, pausing = false)
            }
        }
        return null
    }

    private fun injectionGuard(plan: Plan, token: CallToken): PhoneAdmissionGuard =
        PhoneAdmissionGuard { admissionRefusalLocked(plan, token) }

    private fun finishCall(token: CallToken) {
        synchronized(lock) {
            if (activeCall?.token !== token) return
            activeCall = null
            if (!token.mutation) return
            publishLocked(PhoneEvent.MUTATION_SETTLED) {
                it.copy(inFlightMutation = false, pausing = false)
            }
        }
    }

    /**
     * Restores the original total call deadline: the fresh remaining time from [Plan.deadlineAtMs]
     * bounds the outer wait, and the driver gets a small margin below it so its own hard watchdog
     * failure (for example READ_TIMEOUT) can be reported first. This is a reporting opportunity,
     * not a guarantee: if the outer bound still wins, the caller gets the ordinary DEADLINE and no
     * claim about the link is made. Mutation admission still stops at [Plan.deadlineAtMs].
     */
    private suspend fun runDriver(plan: Plan, token: CallToken): DriverOutcome {
        val budget = driverCallBudget(plan.deadlineAtMs, clock())
            ?: throw PhoneDriverException(PhoneDriverFailure.TIMEOUT)
        return withTimeout(budget.outerTimeoutMs) {
            // Set only here: a plan that expires before any driver call is refused as a deadline,
            // never reported as an uncertain mutation.
            token.started = true
            when (val call = plan.call) {
                Plan.Call.Observe ->
                    DriverOutcome.Snapshot(driver.observe(budget.driverTimeoutMs))
                is Plan.Call.Capture ->
                    DriverOutcome.Image(driver.capture(call.binding, budget.driverTimeoutMs))
                is Plan.Call.Dispatch -> DriverOutcome.Receipt(
                    driver.dispatch(
                        PhoneDispatchRequest(
                            binding = call.binding,
                            action = call.action,
                            guard = injectionGuard(plan, token),
                        ),
                        budget.driverTimeoutMs,
                    ),
                )
            }
        }
    }

    private fun onSnapshot(
        request: PhoneAgentRequest,
        plan: Plan,
        snapshot: PhoneSnapshot,
    ): PhoneCallResult {
        authorityError(plan)?.let { return refused(request, it) }
        when (val check = validateSnapshot(snapshot, plan.targetPackage)) {
            SnapshotCheck.Valid -> Unit
            is SnapshotCheck.Invalid -> return refused(request, check.error)
        }
        val observation = publishObservation(plan, snapshot)
            ?: return refused(request, observationRefusal(plan))
        return PhoneCallResult.Observed(request.requestId, request.epoch, observation)
    }

    private fun onImage(
        request: PhoneAgentRequest,
        plan: Plan,
        image: PhoneImage,
    ): PhoneCallResult {
        val call = plan.call as Plan.Call.Capture
        authorityError(plan)?.let { return refused(request, it) }
        if (!observationCurrent(call.binding, plan.epoch, plan.controlEpoch)) {
            return refused(request, observationRefusal(plan))
        }
        if (!validImage(image)) {
            return refused(request, PhoneErrorCode.DRIVER_UNAVAILABLE)
        }
        synchronized(lock) {
            if (mutableState.value.epoch == plan.epoch &&
                controlEpoch == plan.controlEpoch &&
                currentObservation?.binding == call.binding
            ) {
                publishLocked(PhoneEvent.CAPTURED) { it }
            }
        }
        return PhoneCallResult.Captured(
            request.requestId,
            request.epoch,
            PhoneCapture(call.binding, image, clock()),
        )
    }

    private fun onReceipt(
        request: PhoneAgentRequest,
        plan: Plan,
        receipt: PhoneDispatchReceipt,
        token: CallToken,
    ): PhoneCallResult {
        val call = plan.call as Plan.Call.Dispatch
        return when (receipt) {
            PhoneDispatchReceipt.Sent -> {
                // A sent mutation may have changed the screen; the old snapshot is never reused.
                invalidateObservation(plan.epoch, call.binding)
                PhoneCallResult.Dispatched(
                    request.requestId,
                    request.epoch,
                    PhoneDelivery.SENT,
                    PhoneBusinessResult(call.kind, deviceAcknowledged = true),
                )
            }
            is PhoneDispatchReceipt.NotSent -> {
                val post = admissionRefusalLocked(plan, token)
                if (receipt.failure == PhoneDriverFailure.READ_TIMEOUT) {
                    // A read-watchdog abandonment is real even when the plan deadline has already
                    // passed; publish it before any authority return so the controller never keeps
                    // claiming Connected. The read failure stays the first cause: a later plain
                    // DEADLINE check must not mask it. A genuine earlier pause/epoch/lease refusal
                    // still wins, and the disconnect is fenced to this plan's own connection.
                    noteDriverFailure(receipt.failure, plan)
                    if (post != null && post != PhoneErrorCode.DEADLINE) {
                        if (post == PhoneErrorCode.NOT_CONNECTED) {
                            noteDriverFailure(PhoneDriverFailure.NOT_CONNECTED)
                        }
                        if (post == PhoneErrorCode.LEASE_EXPIRED) refresh()
                        return refused(request, post)
                    }
                    return refused(request, PhoneErrorCode.READ_TIMEOUT)
                }
                if (post != null) {
                    if (post == PhoneErrorCode.NOT_CONNECTED) {
                        noteDriverFailure(PhoneDriverFailure.NOT_CONNECTED)
                    }
                    if (post == PhoneErrorCode.LEASE_EXPIRED) refresh()
                    return refused(request, post)
                }
                noteDriverFailure(receipt.failure, plan)
                if (receipt.failure in LOST_BINDING_FAILURES) {
                    invalidateObservation(plan.epoch, call.binding)
                }
                refused(request, mapFailure(receipt.failure))
            }
            is PhoneDispatchReceipt.Unknown -> {
                // Record the unknown outcome before a read-timeout revoke publishes state: a
                // mutation whose result is uncertain must still require a person check even when
                // the same read abandoned the control connection.
                markOutcomeUnknown(plan.epoch)
                noteDriverFailure(
                    receipt.failure ?: PhoneDriverFailure.OUTCOME_UNKNOWN,
                    plan,
                )
                PhoneCallResult.Dispatched(
                    request.requestId,
                    request.epoch,
                    PhoneDelivery.UNKNOWN,
                    error = receipt.failure?.let(::mapFailure) ?: PhoneErrorCode.OUTCOME_UNKNOWN,
                )
            }
        }
    }

    private fun onTimedOut(
        request: PhoneAgentRequest,
        plan: Plan,
        token: CallToken,
    ): PhoneCallResult {
        val reason = token.cancelReason
        if (token.mutation && token.started) {
            markOutcomeUnknown(plan.epoch)
            return PhoneCallResult.Dispatched(
                request.requestId,
                request.epoch,
                PhoneDelivery.UNKNOWN,
                error = reason ?: PhoneErrorCode.DEADLINE,
            )
        }
        return refused(request, reason ?: PhoneErrorCode.DEADLINE)
    }

    private fun authorityError(plan: Plan): PhoneErrorCode? = synchronized(lock) {
        val state = mutableState.value
        val grant = state.grant
        when {
            !state.enabled -> PhoneErrorCode.DISABLED
            state.epoch != plan.epoch -> PhoneErrorCode.EPOCH_CHANGED
            grant == null -> PhoneErrorCode.NOT_GRANTED
            PhoneTimeLimits.isExpired(clock(), grant.expiresAtMs) -> PhoneErrorCode.LEASE_EXPIRED
            clock() >= plan.deadlineAtMs -> PhoneErrorCode.DEADLINE
            state.connection != PhoneConnectionState.CONNECTED -> PhoneErrorCode.NOT_CONNECTED
            else -> null
        }
    }

    private fun observationCurrent(
        binding: PhoneObservationBinding,
        epoch: Long,
        expectedControlEpoch: Long,
    ): Boolean = synchronized(lock) {
        val state = mutableState.value
        state.epoch == epoch &&
            controlEpoch == expectedControlEpoch &&
            currentObservation?.binding == binding
    }

    /** Fixed error for a driver result admitted under an older pause/unknown/revoke state. */
    private fun observationRefusal(plan: Plan): PhoneErrorCode = synchronized(lock) {
        val state = mutableState.value
        when {
            !state.enabled -> PhoneErrorCode.DISABLED
            state.epoch != plan.epoch -> PhoneErrorCode.EPOCH_CHANGED
            state.paused -> PhoneErrorCode.PAUSED
            state.blocked != null -> PhoneErrorCode.OUTCOME_UNKNOWN
            clock() >= plan.deadlineAtMs -> PhoneErrorCode.DEADLINE
            controlEpoch != plan.controlEpoch -> PhoneErrorCode.CANCELLED
            else -> PhoneErrorCode.CANCELLED
        }
    }

    private fun publishObservation(plan: Plan, snapshot: PhoneSnapshot): PhoneObservation? =
        synchronized(lock) {
            val state = mutableState.value
            if (!state.enabled || state.epoch != plan.epoch) return@synchronized null
            if (controlEpoch != plan.controlEpoch) return@synchronized null
            if (clock() >= plan.deadlineAtMs) return@synchronized null
            val grant = state.grant ?: return@synchronized null
            if (PhoneTimeLimits.isExpired(clock(), grant.expiresAtMs)) return@synchronized null
            if (state.connection != PhoneConnectionState.CONNECTED) return@synchronized null

            observationGeneration += 1
            val binding = PhoneObservationBinding(
                observationId = newObservationId(),
                generation = observationGeneration,
                packageName = snapshot.packageName,
                windowId = snapshot.windowId,
                rotation = snapshot.geometry.rotation,
                geometry = snapshot.geometry,
            )
            val nodes = snapshot.nodes.map {
                it.copy(
                    role = it.role.take(PhoneLimits.MAX_ROLE_CHARS),
                    label = it.label.take(PhoneLimits.MAX_NODE_LABEL_CHARS),
                )
            }
            val refs = nodes.associate { it.ref to it.bounds }
            val observedAtMs = clock()
            val observation = PhoneObservation(binding, nodes, refs, observedAtMs)
            currentObservation = observation
            publishLocked(PhoneEvent.OBSERVED) {
                it.copy(
                    observation = PhoneObservationSummary(
                        binding = binding,
                        nodeCount = nodes.size,
                        observedAtMs = observedAtMs,
                    ),
                )
            }
            observation
        }

    private fun validateSnapshot(
        snapshot: PhoneSnapshot,
        targetPackage: String,
    ): SnapshotCheck {
        if (snapshot.packageName != targetPackage) {
            return SnapshotCheck.Invalid(PhoneErrorCode.TARGET_MISMATCH)
        }
        targetPolicy.refusal(snapshot.packageName)?.let {
            return SnapshotCheck.Invalid(it)
        }
        if (!validWindowId(snapshot.windowId)) {
            return SnapshotCheck.Invalid(PhoneErrorCode.WINDOW_CHANGED)
        }
        if (!validGeometry(snapshot.geometry)) {
            return SnapshotCheck.Invalid(PhoneErrorCode.GEOMETRY_CHANGED)
        }
        if (snapshot.nodes.size > PhoneLimits.MAX_NODES ||
            snapshot.refs.size > PhoneLimits.MAX_NODES
        ) {
            return SnapshotCheck.Invalid(PhoneErrorCode.DRIVER_UNAVAILABLE)
        }
        val seen = LinkedHashSet<String>()
        for (node in snapshot.nodes) {
            if (!REF_PATTERN.matches(node.ref) || !seen.add(node.ref)) {
                return SnapshotCheck.Invalid(PhoneErrorCode.DRIVER_UNAVAILABLE)
            }
            if (!node.bounds.isWithin(snapshot.geometry)) {
                return SnapshotCheck.Invalid(PhoneErrorCode.OUT_OF_BOUNDS)
            }
            val ref = snapshot.refs[node.ref]
                ?: return SnapshotCheck.Invalid(PhoneErrorCode.DRIVER_UNAVAILABLE)
            if (ref != node.bounds || !ref.isWithin(snapshot.geometry)) {
                return SnapshotCheck.Invalid(PhoneErrorCode.DRIVER_UNAVAILABLE)
            }
        }
        if (seen != snapshot.refs.keys) {
            return SnapshotCheck.Invalid(PhoneErrorCode.DRIVER_UNAVAILABLE)
        }
        return SnapshotCheck.Valid
    }

    private fun markOutcomeUnknown(epoch: Long) {
        synchronized(lock) {
            val state = mutableState.value
            if (state.epoch != epoch || state.grant == null) return
            controlEpoch += 1
            currentObservation = null
            if (standingBlock == null) standingBlock = PhoneStandingBlockReason.UNKNOWN_OUTCOME
            publishLocked(PhoneEvent.OUTCOME_UNKNOWN) {
                it.copy(
                    blocked = PhoneBlockReason.UNKNOWN_OUTCOME,
                    observation = null,
                )
            }
        }
    }

    private fun invalidateObservation(epoch: Long, binding: PhoneObservationBinding? = null) {
        synchronized(lock) {
            val state = mutableState.value
            if (state.epoch != epoch) return
            if (binding != null && currentObservation?.binding != binding) return
            if (currentObservation == null && state.observation == null) return
            controlEpoch += 1
            currentObservation = null
            publishLocked(PhoneEvent.OBSERVATION_INVALIDATED) { it.copy(observation = null) }
        }
    }

    private fun noteDriverFailure(failure: PhoneDriverFailure, plan: Plan? = null) {
        when (failure) {
            PhoneDriverFailure.NOT_CONNECTED -> {
                val calls = synchronized(lock) {
                    revokeLocked(PhoneEvent.CONNECTION_LOST, disconnect = true)
                }
                cancelCall(calls, PhoneErrorCode.NOT_CONNECTED)
            }
            PhoneDriverFailure.READ_TIMEOUT -> {
                // The read watchdog already abandoned the exact connection. Publish the real
                // logical disconnect/revoke in this same call instead of waiting for a later
                // Observe to report NOT_CONNECTED. The caller still returns READ_TIMEOUT as the
                // first error. An already recorded unknown-outcome block stays in place.
                if (plan != null) revokeReadTimeoutOwner(plan)
            }
            else -> Unit
        }
    }

    /**
     * A READ_TIMEOUT may only revoke the exact connection the failing call was planned against.
     * A newer reconnect owns control and survives a late old failure; the same (now abandoned)
     * connection is revoked even if a newer grant reused it, because it can no longer be trusted.
     */
    private fun revokeReadTimeoutOwner(plan: Plan) {
        val calls = synchronized(lock) {
            if (!readTimeoutOwnsConnection(plan.connectionId, mutableState.value.connectionId)) {
                return
            }
            revokeLocked(
                event = PhoneEvent.CONNECTION_LOST,
                disconnect = true,
                keepUnknownBlock = true,
            )
        }
        cancelCall(calls, PhoneErrorCode.READ_TIMEOUT)
    }

    private fun revokeFor(event: PhoneEvent) {
        val calls = synchronized(lock) { revokeLocked(event) }
        cancelCall(calls, PhoneErrorCode.EPOCH_CHANGED)
    }

    private fun revokeLocked(
        event: PhoneEvent,
        expiredEpoch: Long? = null,
        disconnect: Boolean = false,
        runtimeId: String? = null,
        keepUnknownBlock: Boolean = false,
    ): ActiveCall? {
        val call = activeCall
        controlEpoch += 1
        lastExpiryEpoch = expiredEpoch
        requestRecords.clear()
        currentObservation = null
        publishLocked(event) { state ->
            state.copy(
                grant = null,
                epoch = state.epoch + 1,
                connection = if (disconnect) {
                    PhoneConnectionState.DISCONNECTED
                } else {
                    state.connection
                },
                connectionId = if (disconnect) null else state.connectionId,
                capabilities = if (disconnect) null else state.capabilities,
                runtimeId = runtimeId ?: state.runtimeId,
                observation = null,
                paused = false,
                blocked = if (keepUnknownBlock) state.blocked else null,
                pausing = false,
            )
        }
        return call
    }

    private fun cancelCall(call: ActiveCall?, reason: PhoneErrorCode) {
        if (call == null) return
        call.token.cancelReason = reason
        call.job.cancel()
    }

    private fun publishLocked(event: PhoneEvent, mutate: (PhoneState) -> PhoneState) {
        val current = mutableState.value
        val next = mutate(current).copy(
            standingBlocked = standingBlock != null,
            standingBlockReason = standingBlock,
        )
        mutableState.value = next.copy(
            sequence = current.sequence + 1,
            lastEvent = event,
            phase = phaseFor(next),
        )
    }

    private fun phaseFor(state: PhoneState): PhonePhase = when {
        !state.enabled -> PhonePhase.DISABLED
        state.grant == null -> PhonePhase.NO_GRANT
        state.blocked != null -> PhonePhase.BLOCKED
        state.paused -> PhonePhase.PAUSED
        else -> PhonePhase.READY
    }

    private fun mapFailure(failure: PhoneDriverFailure): PhoneErrorCode = when (failure) {
        PhoneDriverFailure.NOT_CONNECTED -> PhoneErrorCode.NOT_CONNECTED
        PhoneDriverFailure.UNSUPPORTED -> PhoneErrorCode.UNSUPPORTED_ACTION
        PhoneDriverFailure.TARGET_CHANGED -> PhoneErrorCode.TARGET_MISMATCH
        PhoneDriverFailure.WINDOW_CHANGED -> PhoneErrorCode.WINDOW_CHANGED
        PhoneDriverFailure.GEOMETRY_CHANGED -> PhoneErrorCode.GEOMETRY_CHANGED
        PhoneDriverFailure.POLICY_DENIED -> PhoneErrorCode.TARGET_FORBIDDEN
        PhoneDriverFailure.OVERLAY_OBSTRUCTED -> PhoneErrorCode.OVERLAY_OBSTRUCTED
        PhoneDriverFailure.INVALID_REQUEST -> PhoneErrorCode.INVALID_REQUEST
        PhoneDriverFailure.ADMISSION_REFUSED -> PhoneErrorCode.CANCELLED
        PhoneDriverFailure.TIMEOUT -> PhoneErrorCode.DEADLINE
        PhoneDriverFailure.CANCELLED -> PhoneErrorCode.CANCELLED
        PhoneDriverFailure.OUTCOME_UNKNOWN -> PhoneErrorCode.OUTCOME_UNKNOWN
        PhoneDriverFailure.HIERARCHY_NOT_IDLE -> PhoneErrorCode.HIERARCHY_NOT_IDLE
        PhoneDriverFailure.READ_TIMEOUT -> PhoneErrorCode.READ_TIMEOUT
        PhoneDriverFailure.DRIVER -> PhoneErrorCode.DRIVER_UNAVAILABLE
    }

    private fun PhoneAgentAction.mutationKind(): PhoneActionKind? = when (this) {
        PhoneAgentAction.Observe, PhoneAgentAction.Capture -> null
        is PhoneAgentAction.TapRef, is PhoneAgentAction.TapPoint -> PhoneActionKind.TAP
        is PhoneAgentAction.Swipe -> PhoneActionKind.SWIPE
        is PhoneAgentAction.Input -> PhoneActionKind.INPUT
        PhoneAgentAction.Back -> PhoneActionKind.BACK
    }

    private fun validImage(image: PhoneImage): Boolean =
        image.bytes.isNotEmpty() &&
            image.bytes.size <= PhoneLimits.MAX_CAPTURE_BYTES &&
            image.mimeType in PhoneLimits.CAPTURE_MIME_TYPES &&
            image.width in 1..PhoneLimits.MAX_DISPLAY_PX &&
            image.height in 1..PhoneLimits.MAX_DISPLAY_PX

    private fun validGeometry(geometry: PhoneGeometry): Boolean =
        geometry.rotation in 0..3 &&
            geometry.displayWidth in 1..PhoneLimits.MAX_DISPLAY_PX &&
            geometry.displayHeight in 1..PhoneLimits.MAX_DISPLAY_PX

    private fun validWindowId(windowId: String): Boolean =
        windowId.isNotBlank() &&
            windowId.length <= PhoneLimits.MAX_WINDOW_ID_CHARS &&
            windowId.none { it.isISOControl() }

    private fun validSessionLabel(label: String): Boolean =
        label.isNotBlank() &&
            label.length <= PhoneLimits.MAX_SESSION_LABEL_CHARS &&
            label.none { it.isISOControl() }

    private fun validConnection(connection: PhoneConnection): Boolean =
        connection.connectionId.isNotBlank() &&
            connection.connectionId.length <= PhoneLimits.MAX_WINDOW_ID_CHARS &&
            connection.runtimeId.isNotBlank() &&
            connection.runtimeId.length <= PhoneLimits.MAX_WINDOW_ID_CHARS

    private fun refused(request: PhoneAgentRequest, error: PhoneErrorCode): PhoneCallResult =
        PhoneCallResult.Refused(request.requestId, request.epoch, error)

    private data class PhoneRequestKey(
        val epoch: Long,
        val requestId: String,
    )

    private data class Plan(
        val key: PhoneRequestKey,
        val epoch: Long,
        val targetPackage: String,
        val controlEpoch: Long,
        /** Exact connection this call was planned against; fences a late READ_TIMEOUT. */
        val connectionId: String?,
        val timeoutMs: Long,
        val deadlineAtMs: Long,
        val call: Call,
    ) {
        sealed interface Call {
            data object Observe : Call

            data class Capture(val binding: PhoneObservationBinding) : Call

            data class Dispatch(
                val binding: PhoneObservationBinding,
                val action: PhoneDriverAction,
                val kind: PhoneActionKind,
            ) : Call
        }
    }

    private sealed interface PlanResult {
        data class Admitted(val plan: Plan) : PlanResult

        data class Refused(val error: PhoneErrorCode) : PlanResult
    }

    private sealed interface DriverOutcome {
        data class Snapshot(val snapshot: PhoneSnapshot) : DriverOutcome

        data class Image(val image: PhoneImage) : DriverOutcome

        data class Receipt(val receipt: PhoneDispatchReceipt) : DriverOutcome
    }

    private sealed interface SnapshotCheck {
        data object Valid : SnapshotCheck

        data class Invalid(val error: PhoneErrorCode) : SnapshotCheck
    }

    private class CallToken(
        val key: PhoneRequestKey,
        val epoch: Long,
        val mutation: Boolean,
    ) {
        @Volatile
        var started: Boolean = false

        @Volatile
        var cancelReason: PhoneErrorCode? = null
    }

    private class ActiveCall(
        val token: CallToken,
        val job: Job,
    )

    private companion object {
        val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")
        val REF_PATTERN = Regex("^[A-Za-z0-9:_-]{1,64}$")

        val LOST_BINDING_FAILURES = setOf(
            PhoneDriverFailure.TARGET_CHANGED,
            PhoneDriverFailure.WINDOW_CHANGED,
            PhoneDriverFailure.GEOMETRY_CHANGED,
        )
    }
}

/**
 * Fresh start budget for one driver call, computed at the moment the driver would start.
 *
 * The outer bound is the fresh remaining time to the original plan deadline, so the total call
 * deadline never grows. The driver gets a margin below it so its own hard watchdog failure (for
 * example READ_TIMEOUT) can be reported first; this is a reporting opportunity, not a guarantee.
 * If the outer bound still wins, the caller reports the ordinary DEADLINE and makes no claim about
 * the link. A null result means the plan deadline already passed and no driver call may start.
 */
internal data class DriverCallBudget(
    val outerTimeoutMs: Long,
    val driverTimeoutMs: Long,
)

internal const val DRIVER_FAILURE_MARGIN_MS: Long = 500L

internal fun driverCallBudget(deadlineAtMs: Long, nowMs: Long): DriverCallBudget? {
    val remaining = deadlineAtMs - nowMs
    if (remaining <= 0L) return null
    val margin = minOf(DRIVER_FAILURE_MARGIN_MS, remaining / 10L)
    return DriverCallBudget(
        outerTimeoutMs = remaining,
        driverTimeoutMs = (remaining - margin).coerceAtLeast(1L),
    )
}

/**
 * A READ_TIMEOUT disconnect is authoritative only for the exact connection the failing call was
 * planned against. A newer reconnect owns control and must survive a late old failure, while the
 * same (now abandoned) connection is revoked even if a newer grant reused it.
 */
internal fun readTimeoutOwnsConnection(
    planConnectionId: String?,
    currentConnectionId: String?,
): Boolean = planConnectionId != null && planConnectionId == currentConnectionId

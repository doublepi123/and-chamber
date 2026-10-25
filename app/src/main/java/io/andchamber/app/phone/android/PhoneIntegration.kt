package io.andchamber.app.phone.android

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import io.andchamber.app.MainActivity
import io.andchamber.app.phone.adb.AdbDeviceException
import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneAgentAction
import io.andchamber.app.phone.core.PhoneAgentRequest
import io.andchamber.app.phone.core.PhoneBlockReason
import io.andchamber.app.phone.core.PhoneCallResult
import io.andchamber.app.phone.core.PhoneConnectionResult
import io.andchamber.app.phone.core.PhoneConnectionState
import io.andchamber.app.phone.core.PhoneController
import io.andchamber.app.phone.core.PhoneErrorCode
import io.andchamber.app.phone.core.PhoneEvent
import io.andchamber.app.phone.core.PhoneGrant
import io.andchamber.app.phone.core.PhoneGrantResult
import io.andchamber.app.phone.core.PhoneLimits
import io.andchamber.app.phone.core.PhoneState
import io.andchamber.app.ui.phone.PhoneBusy
import io.andchamber.app.ui.phone.PhoneConnectRequest
import io.andchamber.app.ui.phone.PhoneControlActions
import io.andchamber.app.ui.phone.PhoneDiagnosticTokens
import io.andchamber.app.ui.phone.PhoneDeviceProblem
import io.andchamber.app.ui.phone.PhoneEndpointHints
import io.andchamber.app.ui.phone.PhoneGrantRequest
import io.andchamber.app.ui.phone.PhoneHaltReason
import io.andchamber.app.ui.phone.PhoneLastAction
import io.andchamber.app.ui.phone.PhonePairRequest
import io.andchamber.app.ui.phone.PhonePairingNotice
import io.andchamber.app.ui.phone.PhonePending
import io.andchamber.app.ui.phone.PhoneScreenState
import io.andchamber.app.ui.phone.PhoneStopChannel
import io.andchamber.app.ui.phone.PhoneTarget
import io.andchamber.app.ui.phone.phoneHaltFacts
import io.github.muntashirakon.adb.AdbPairingException
import io.github.muntashirakon.adb.AdbPairingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The Android side of phone control: maps the pure controller into the screen's model, owns the
 * device link (pairing/connect), the native grant gate, the foreground service and the minimal
 * task/halt memory.
 *
 * It never grants on its own: every grant comes from a [PhoneGrantRequest] the screen already
 * confirmed with the person, and the controller re-checks it. The agent's tool only proposes a task;
 * its proposal is a prefill and is dismissed when a person grants or replaces it.
 *
 * All fields are touched on the main thread. Coroutines run on [scope] (main) and only the driver
 * calls hop to IO.
 */
internal class PhoneIntegration(
    private val context: Context,
    private val store: PhoneIntegrationStore,
    private val driver: PhoneDriverFacade,
    private val controller: PhoneController,
    private val proposals: PhoneProposalSource = NoPhoneProposals,
    /** Native-only: publishes the granted epoch so exactly one chat may claim it. Fail-closed. */
    private val grantRoutes: PhoneGrantRoutePublisher = PhoneGrantRoutePublisher.Unavailable,
    /** Native readiness probes: is the runtime up, and did this launch load the phone tool? */
    private val readiness: PhoneAgentToolReadiness = PhoneAgentToolReadiness.Unavailable,
    private val notifications: PhoneControlNotifications = PhoneControlNotifications(context),
    private val catalog: PhoneTargetCatalog =
        PhoneTargetCatalog(hostPackage = context.packageName) {
            PhoneTargetCatalog.androidQuery(context, context.packageName)
        },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val hostPackage: String = context.packageName,
    private val launchTarget: (String) -> Boolean = { launchDefaultApp(context, it) },
    private val openDeveloperSettings: () -> Unit = { openDevelopmentSettings(context) },
    private val openNotificationSettings: () -> Unit = { openAppNotificationSettings(context) },
    private val serviceStart: (Long) -> Boolean = { epoch -> PhoneControlService.start(context, epoch) },
    /** Bounded acknowledgement that the foreground stop entry really exists for [epoch]. */
    private val serviceAwaitReady: suspend (Long) -> Boolean = { epoch -> PhoneControlService.awaitReady(epoch) },
    private val serviceStop: () -> Unit = { PhoneControlService.stop(context) },
    private val keyguardLocked: () -> Boolean = {
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    },
    private val available: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
) {
    private val pairing = PhonePairingSession(clock)

    /** Freezes one confirmed grant attempt; every stop control invalidates it. */
    private val grantAttempts = PhoneGrantAttemptOwner()

    private val facts =
        MutableStateFlow(
            PhoneScreenFacts(
                hostPackage = hostPackage,
                available = available,
                enabled = available && store.enabled(),
                task = store.task(),
                stopChannel = notifications.stopChannelState(),
            ),
        )

    private val mutableState = MutableStateFlow(phoneScreenState(controller.state.value, facts.value))
    val state: StateFlow<PhoneScreenState> = mutableState.asStateFlow()

    /**
     * The screen's countdown reads the controller's own clock, not a copy: the lease timestamps and the
     * countdown can never disagree, and a wall-clock change can neither extend nor expire a lease.
     * `PhoneControlScreen` must be given this function, never `System.currentTimeMillis`.
     */
    val monotonicClock: () -> Long = { controller.nowMs() }

    /** The lease the coordinator remembers from the last native grant (never authority by itself). */
    private var lease: PhoneLeaseFacts? = null

    /**
     * Routing memory for the current task's chat. In memory only: it lets a native resume publish the
     * same route instead of falling back to the first-action-claim path. Never authentication.
     */
    private var taskSession: String? = null

    /** Pairing attempt identity: every cancel/disable/new attempt invalidates late results. */
    private var pairingOperationSeq = 0L
    private var activePairingOperation: Long? = null

    private var discovery: PhonePortDiscovery? = null
    private val observers = mutableListOf<Job>()

    init {
        notifications.ensureChannels()
        if (facts.value.enabled) controller.enable()
        observeController()
        observeFacts()
        observeProposals()
        startTicker()
    }

    // --- screen callbacks -------------------------------------------------------------------

    /** Builds the callback surface. [onBack] is navigation, owned by the UI route. */
    fun actions(onBack: () -> Unit): PhoneControlActions =
        PhoneControlActions(
            onBack = onBack,
            onEnable = ::setEnabled,
            onOpenWirelessSettings = ::openWirelessDebuggingSettings,
            onOpenNotificationSettings = { openNotificationSettings() },
            onRecheckStopChannel = ::recheckStopChannel,
            onBeginPairingNotification = ::beginPairingNotification,
            onCancelPairingNotification = ::cancelPairingNotification,
            onPair = ::pairManually,
            onConnect = ::connect,
            onDisconnect = ::disconnect,
            onGrant = ::grant,
            onPause = ::pause,
            onTakeOver = ::takeOver,
            onFinish = ::finish,
            onObserve = ::observeTarget,
            onOpenTarget = { packageName -> launchTarget(packageName) },
            onAcknowledgeChecked = ::acknowledgeChecked,
            onDismissResult = ::dismissResult,
        )

    /** Called when the native screen appears: refresh the picker and the stop entry, find ports. */
    fun onScreenOpened() {
        if (!facts.value.available) return
        recheckStopChannel()
        refreshAgentToolBlocker()
        loadTargets()
        refreshPairingState()
        startDiscovery()
    }

    fun onScreenClosed() {
        stopDiscovery()
    }

    // --- lifecycle from the activity/service ------------------------------------------------

    /**
     * Returning to AndChamber drops the lease. This is a separate stop path from the notification
     * and it is never a pause: the grant is revoked, so nothing can act while the host is in front.
     */
    fun onHostResumed() {
        invalidateGrantAttempt()
        recheckStopChannel()
        val core = controller.state.value
        if (!hostReturnDropsLease(hasGrant = core.grant != null, paused = core.paused)) return
        // A paused lease is still a lease: returning to the host stops Observe/Capture as well.
        val remembered = lease ?: facts.value.halt?.lease
        halt(
            PhoneHaltReason.HostReturned,
            remembered,
            actionInFlight = core.inFlightMutation || core.pausing,
        )
        controller.onHostReturned()
        serviceStop()
    }

    /** A locked screen drops the lease without touching the transport. */
    fun onScreenOff() {
        invalidateGrantAttempt()
        val core = controller.state.value
        if (core.grant == null) return
        val actionInFlight = core.inFlightMutation
        halt(PhoneHaltReason.ScreenLocked, actionInFlight = actionInFlight)
        controller.onDeviceLocked()
        serviceStop()
    }

    /** A lost transport drops the lease; the coordinator keeps what is known for the screen. */
    fun onConnectionLost() {
        invalidateGrantAttempt()
        if (controller.state.value.grant == null && !controller.state.value.paused) return
        halt(PhoneHaltReason.ConnectionLost, actionInFlight = controller.state.value.inFlightMutation)
        controller.onConnectionLost()
        serviceStop()
    }

    fun onNotificationPause(epoch: Long) {
        if (epoch != controller.state.value.epoch) return
        pause()
    }

    /**
     * The notification's Return action, after the caller already proved the nonce.
     *
     * It stops exactly [epoch]: a stale action is a no-op and can never revoke a newer grant. Revoke
     * and service stop happen here; opening AndChamber is the caller's job from a real Activity
     * context ([openPhone]), because Android 12+ blocks a notification trampoline that starts an
     * Activity from a service. The legacy service path passes no lambda: it revokes and stops only.
     */
    fun onNotificationReturn(
        epoch: Long,
        openPhone: (() -> Unit)? = null,
    ): Boolean {
        val core = controller.state.value
        if (!serviceActionIsCurrent(epoch, core.grant?.epoch)) return false
        invalidateGrantAttempt()
        halt(PhoneHaltReason.HostReturned, actionInFlight = core.inFlightMutation || core.pausing)
        controller.onHostReturned()
        serviceStop()
        openPhone?.invoke()
        return true
    }

    /** The live lease epoch, or null. The foreground service validates every action against it. */
    fun liveLeaseEpoch(): Long? = controller.state.value.grant?.epoch

    /**
     * The stop-entry notification for exactly [epoch], or null when that lease is not live. It shows
     * only the target app's label and a fixed phase line, never the task summary.
     */
    fun serviceNotificationFor(epoch: Long): Notification? {
        val core = controller.state.value
        val grant = core.grant ?: return null
        if (grant.epoch != epoch) return null
        val current = lease ?: return null
        return notifications.running(
            epoch = epoch,
            appLabel = current.target.label,
            paused = core.paused,
            // A pause is only settled once the in-flight action has finished; until then the
            // notification must not claim the agent is paused.
            pausing = core.pausing || (core.paused && core.inFlightMutation),
        )
    }

    /**
     * The stop entry died, was stopped, or could not enter the foreground for [epoch]. Fail closed:
     * revoke exactly that lease (a newer epoch is left alone) and keep the reason for the screen.
     */
    fun onServiceLost(epoch: Long) {
        val core = controller.state.value
        val grant = core.grant ?: return
        if (grant.epoch != epoch) return
        invalidateGrantAttempt()
        halt(PhoneHaltReason.StopChannelLost, actionInFlight = core.inFlightMutation)
        controller.revoke()
        serviceStop()
        mutateFacts { it.copy(stopChannel = notifications.stopChannelState()) }
    }

    // --- pairing ---------------------------------------------------------------------------

    fun beginPairingNotification(host: String) {
        if (!facts.value.available || !facts.value.enabled) return
        if (!notifications.notificationsAllowed()) {
            mutateFacts { it.copy(pairingNotice = PhonePairingNotice.NotificationsBlocked) }
            return
        }
        // A previous attempt may still be blocked inside the driver's synchronized pair(); cancel its
        // real socket first so this draft is not stuck behind it, and never on the main thread.
        invalidatePairing()
        pairing.cancel()
        notifications.ensureChannels()
        val target = host.trim().ifEmpty { DEFAULT_HOST }
        val draft = pairing.begin(target)
        notifications.notifyPairing(PhonePairNotifications.PAIRING_ID, notifications.pairingPort(draft.generation))
        mutateFacts {
            it.copy(
                pairingNotice = PhonePairingNotice.Waiting,
                hints = it.hints.copy(host = draft.host),
                problem = null,
            )
        }
        // The notification exists before the system screen opens, so nothing has to be retyped later.
        openDeveloperSettings()
        schedulePairingExpiry(draft.generation)
    }

    fun onPairingPortReply(generation: Long, text: String) {
        when (val result = pairing.acceptPort(generation, text)) {
            is PhonePairingSession.PortResult.Accepted -> {
                val draft = result.draft
                val port = draft.pairingPort ?: return
                // configurePairing takes the driver's lock that pair() holds; run it off the main thread.
                scope.launch {
                    val configured =
                        withContext(Dispatchers.IO) {
                            runCatching { driver.configurePairing(draft.host, port) }
                        }
                    // A cancel, disable or newer attempt makes this reply irrelevant.
                    if (!facts.value.enabled || pairing.current()?.generation != generation) return@launch
                    if (configured.isFailure) {
                        failPairing(PhonePairingOutcome.POLICY_REFUSED)
                        return@launch
                    }
                    notifications.notifyPairing(
                        PhonePairNotifications.PAIRING_ID,
                        notifications.pairingCode(generation),
                    )
                    mutateFacts {
                        it.copy(
                            pairingNotice = PhonePairingNotice.Waiting,
                            hints = it.hints.copy(pairingPort = port),
                            problem = null,
                        )
                    }
                }
            }
            PhonePairingSession.PortResult.InvalidPort -> {
                // Keep the same step open so the person can retype; no state error is invented.
                val draft = pairing.current() ?: return
                notifications.notifyPairing(
                    PhonePairNotifications.PAIRING_ID,
                    notifications.pairingPort(draft.generation),
                )
            }
            PhonePairingSession.PortResult.Stale,
            PhonePairingSession.PortResult.Expired,
            -> Unit
        }
    }

    fun onPairingCodeReply(generation: Long, text: String) {
        when (val result = pairing.acceptCode(generation, text)) {
            is PhonePairingSession.CodeResult.Ready -> runPairing(result.draft, result.code)
            PhonePairingSession.CodeResult.InvalidCode -> {
                val draft = pairing.current() ?: return
                notifications.notifyPairing(
                    PhonePairNotifications.PAIRING_ID,
                    notifications.pairingCode(draft.generation),
                )
            }
            PhonePairingSession.CodeResult.Stale,
            PhonePairingSession.CodeResult.Expired,
            PhonePairingSession.CodeResult.NotReady,
            -> Unit
        }
    }

    fun onPairingCancelRequested() = cancelPairingNotification()

    fun openWirelessDebuggingSettings() {
        openDeveloperSettings()
    }

    private fun cancelPairingNotification() {
        invalidatePairing()
        pairing.cancel()
        notifications.cancelPairing()
        mutateFacts { it.copy(pairingNotice = PhonePairingNotice.Idle, pending = null) }
    }

    /**
     * Makes every in-flight pairing result stale and closes a blocked driver socket.
     *
     * This cancels the local exchange only; it never claims that the device's system pairing window or
     * any authorization it granted was revoked.
     */
    private fun invalidatePairing() {
        pairingOperationSeq += 1
        activePairingOperation = null
        runCatching { driver.cancelPairing() }
    }

    private fun schedulePairingExpiry(generation: Long) {
        scope.launch {
            delay(PhonePairingSession.DEFAULT_TTL_MS + 1_000L)
            val expired = pairing.expireIfNeeded()
            if (expired == generation) {
                invalidatePairing()
                notifications.cancelPairing()
                mutateFacts { it.copy(pairingNotice = PhonePairingNotice.Idle) }
            }
        }
    }

    private fun runPairing(draft: PhonePairingSession.Draft, code: CharArray) {
        val operation = ++pairingOperationSeq
        activePairingOperation = operation
        notifications.notifyPairing(
            PhonePairNotifications.PAIRING_ID,
            notifications.pairingBusy(draft.generation),
        )
        mutateFacts { it.copy(pending = PhonePending.Pairing, problem = null) }
        scope.launch {
            val outcome =
                try {
                    withContext(Dispatchers.IO) { driver.pair(code, PAIRING_DEADLINE_MS) }
                    PhonePairingOutcome.PAIRED
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: AdbPairingException) {
                    pairingOutcome(failure.failure)
                } catch (_: Exception) {
                    PhonePairingOutcome.UNAVAILABLE
                } finally {
                    code.fill('\u0000')
                }
            // Cancel, disable or a newer attempt invalidates this result. A late success must never
            // cancel the newer notification, mark the UI paired or finish a newer draft.
            val current =
                pairingResultIsCurrent(
                    operation = operation,
                    activeOperation = activePairingOperation,
                    draftGeneration = draft.generation,
                    currentGeneration = pairing.current()?.generation,
                    enabled = facts.value.enabled,
                )
            if (!current) return@launch
            activePairingOperation = null
            pairing.finish(draft.generation)
            if (outcome == PhonePairingOutcome.PAIRED) {
                notifications.cancelPairing()
                notifications.notifyPairing(
                    PhonePairNotifications.PAIRING_ID,
                    notifications.paired(),
                )
                mutateFacts {
                    it.copy(
                        paired = true,
                        problem = null,
                        pairingNotice = PhonePairingNotice.Idle,
                        pending = null,
                    )
                }
            } else {
                // Fixed local error. This never claims the device's system authorization was revoked.
                notifications.notifyPairing(
                    PhonePairNotifications.PAIRING_ID,
                    notifications.pairingFailed(outcome),
                )
                mutateFacts {
                    it.copy(
                        paired = false,
                        problem = problemFor(outcome),
                        pairingNotice = PhonePairingNotice.Idle,
                        pending = null,
                        busy = null,
                    )
                }
            }
        }
    }

    private fun pairManually(request: PhonePairRequest) {
        if (guardBusy()) return
        val code = PhonePairingSession.sanitizePairingCode(request.code)
        if (code == null) {
            mutateFacts { it.copy(problem = PhoneDeviceProblem.PairingCodeRejected) }
            return
        }
        // Manual pairing supersedes any notification attempt; the draft must not stay waiting.
        invalidatePairing()
        pairing.cancel()
        notifications.cancelPairing()
        val operation = ++pairingOperationSeq
        activePairingOperation = operation
        mutateFacts { it.copy(pending = PhonePending.Pairing, problem = null, busy = null) }
        scope.launch {
            val outcome =
                try {
                    withContext(Dispatchers.IO) {
                        driver.configurePairing(request.host.trim().ifEmpty { DEFAULT_HOST }, request.port)
                        driver.pair(code, PAIRING_DEADLINE_MS)
                    }
                    PhonePairingOutcome.PAIRED
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: AdbPairingException) {
                    pairingOutcome(failure.failure)
                } catch (_: AdbDeviceException) {
                    PhonePairingOutcome.POLICY_REFUSED
                } catch (_: Exception) {
                    PhonePairingOutcome.UNAVAILABLE
                } finally {
                    code.fill('\u0000')
                }
            // A cancel or disable makes this result irrelevant; it must not change the UI.
            if (!facts.value.enabled || activePairingOperation != operation) return@launch
            activePairingOperation = null
            if (outcome == PhonePairingOutcome.PAIRED) {
                notifications.notifyPairing(
                    PhonePairNotifications.PAIRING_ID,
                    notifications.paired(),
                )
            }
            mutateFacts {
                it.copy(
                    pending = null,
                    paired = outcome == PhonePairingOutcome.PAIRED,
                    problem = problemFor(outcome),
                    hints =
                        it.hints.copy(
                            host = request.host.trim().ifEmpty { DEFAULT_HOST },
                            pairingPort = request.port,
                        ),
                )
            }
        }
    }

    private fun refreshPairingState() {
        if (!facts.value.available) return
        val host = facts.value.hints.host
        scope.launch {
            val paired = withContext(Dispatchers.IO) { runCatching { driver.hasPairingReceipt(host) }.getOrDefault(false) }
            mutateFacts { it.copy(paired = paired) }
        }
    }

    /** Fixed local failure: cancels the draft and shows one error; no system trust is claimed. */
    private fun failPairing(outcome: PhonePairingOutcome) {
        invalidatePairing()
        pairing.cancel()
        notifications.notifyPairing(
            PhonePairNotifications.PAIRING_ID,
            notifications.pairingFailed(outcome),
        )
        mutateFacts {
            it.copy(
                pending = null,
                paired = false,
                problem = problemFor(outcome),
                pairingNotice = PhonePairingNotice.Idle,
            )
        }
    }

    // --- device link -----------------------------------------------------------------------

    private fun connect(request: PhoneConnectRequest) {
        if (guardBusy()) return
        if (!facts.value.available) {
            mutateFacts { it.copy(problem = PhoneDeviceProblem.Other) }
            return
        }
        val host = request.host.trim().ifEmpty { DEFAULT_HOST }
        mutateFacts { it.copy(pending = PhonePending.Connecting, problem = null, busy = null) }
        scope.launch {
            val paired = withContext(Dispatchers.IO) { runCatching { driver.hasPairingReceipt(host) }.getOrDefault(false) }
            if (!paired) {
                mutateFacts {
                    it.copy(
                        pending = null,
                        paired = false,
                        problem = PhoneDeviceProblem.NotAuthorized,
                        hints = it.hints.copy(host = host, connectPort = request.port),
                    )
                }
                return@launch
            }
            // Await the teardown a runtime change may have left behind, then select the endpoint in the
            // same IO block. Both are awaited inside this connect operation: a late close can never
            // race the connection this attempt is about to make.
            val configured =
                withContext(Dispatchers.IO) {
                    runCatching {
                        driver.prepareForConnect(PhoneLimits.DEFAULT_CALL_MS)
                        driver.configure(host, request.port)
                    }
                }
            if (configured.isFailure) {
                mutateFacts {
                    it.copy(
                        pending = null,
                        problem = PhoneDeviceProblem.Other,
                        hints = it.hints.copy(host = host, connectPort = request.port),
                    )
                }
                return@launch
            }
            val result = runCatching { controller.connect(PhoneLimits.DEFAULT_CALL_MS) }
                .getOrElse { PhoneConnectionResult.Failed(PhoneErrorCode.DRIVER_UNAVAILABLE) }
            when (result) {
                is PhoneConnectionResult.Connected ->
                    mutateFacts {
                        it.copy(
                            pending = null,
                            paired = true,
                            problem = null,
                            hints = it.hints.copy(host = host, connectPort = request.port),
                        )
                    }
                is PhoneConnectionResult.Failed ->
                    mutateFacts {
                        it.copy(
                            pending = null,
                            problem = problemFor(result.error),
                            hints = it.hints.copy(host = host, connectPort = request.port),
                        )
                    }
            }
        }
    }

    private fun disconnect() {
        if (guardBusy()) return
        invalidateGrantAttempt()
        mutateFacts { it.copy(pending = PhonePending.Disconnecting, busy = null) }
        scope.launch {
            controller.disconnect()
            closeLease(keepTask = true)
            mutateFacts {
                it.copy(
                    pending = null,
                    lease = null,
                    halt = null,
                    problem = null,
                )
            }
            serviceStop()
        }
    }

    // --- grant and stop controls -------------------------------------------------------------

    private fun grant(request: PhoneGrantRequest) {
        if (guardBusy()) return
        val current = facts.value
        if (!current.available) return
        val core = controller.state.value
        if (core.connection != PhoneConnectionState.CONNECTED) {
            mutateFacts { it.copy(problem = PhoneDeviceProblem.ConnectRefused) }
            return
        }
        if (core.inFlightMutation || core.pausing) {
            // A new grant would clear the controller's unknown-outcome block while the previous
            // action may still settle; wait for it instead of silently replacing the lease.
            mutateFacts { it.copy(busy = PhoneBusy.RequestIgnored) }
            return
        }
        if (keyguardLocked()) {
            // A locked screen cannot show the target app; refuse instead of granting into the lock.
            mutateFacts {
                it.copy(
                    problem = PhoneDeviceProblem.Other,
                    diagnostics = it.diagnostics.copy(lastError = PhoneDiagnosticTokens.SCREEN_LOCKED),
                )
            }
            return
        }
        val stopChannel = notifications.stopChannelState()
        if (stopChannel != PhoneStopChannel.Verified) {
            mutateFacts { it.copy(stopChannel = stopChannel) }
            return
        }
        val target =
            current.targets.firstOrNull { it.packageName == request.target.packageName }
                ?: request.target.takeIf { it.packageName == current.hostPackage }
        if (target == null) {
            // Unknown package: not in the native picker, so it is not a target.
            mutateFacts {
                it.copy(
                    problem = PhoneDeviceProblem.Other,
                    diagnostics = it.diagnostics.copy(lastError = PhoneDiagnosticTokens.TARGET_UNAVAILABLE),
                )
            }
            return
        }
        // The agent tool must already be loaded by this launch. Blocking here keeps the person's
        // task draft and the agent's proposal intact; nothing is consumed or cleared on this path.
        if (refreshAgentToolBlocker() != null) {
            mutateFacts { it.copy(busy = null) }
            return
        }
        val kinds = actionKinds(request.actions)
        if (kinds.isEmpty()) return
        // Freeze the confirmed object now, before any suspension. Waiting for the foreground stop
        // entry must never change what the person confirmed: the displayed proposal revision (null
        // for a manual task) and, for a resume, the chat that owned the task.
        val frozenRevision = facts.value.requestRevision
        val haltAtConfirm = facts.value.halt
        val resumingAtConfirm =
            haltAtConfirm != null &&
                haltAtConfirm.lease.target.packageName == target.packageName &&
                haltAtConfirm.lease.summary == request.summary.trim().take(PhoneLimits.MAX_NODE_LABEL_CHARS)
        val frozenResumeSession = if (resumingAtConfirm) taskSession else null
        // Freeze this confirmation as the only live attempt. A later stop invalidates it; a newer
        // grant replaces it. The controller epoch check remains independent.
        val attempt = grantAttempts.begin()
        // A fresh attempt supersedes the previous explanation; only a new failure may set one.
        mutateFacts {
            it.copy(
                pending = PhonePending.Granting,
                busy = null,
                problem = null,
                diagnostics = it.diagnostics.copy(lastError = null),
            )
        }
        // Order is part of the guarantee: admit the epoch, prove the foreground stop entry, publish the
        // route for exactly one chat, then launch the app. Any current failure revokes what was created;
        // an invalidated continuation touches nothing.
        scope.launch {
            if (!grantAttempts.isCurrent(attempt)) return@launch
            when (val result = controller.grant(target.packageName, kinds, request.durationMs)) {
                is PhoneGrantResult.Granted -> {
                    if (!grantAttempts.isCurrent(attempt)) {
                        // A stop control ran while this coroutine was queued. Revoke only the epoch this
                        // attempt created; never touch the newer task, service or facts.
                        revokeAttemptEpoch(result.grant.epoch)
                        return@launch
                    }
                    completeGrant(
                        attempt = attempt,
                        granted = result.grant,
                        request = request,
                        target = target,
                        stopChannel = stopChannel,
                        frozenRevision = frozenRevision,
                        frozenResumeSession = frozenResumeSession,
                    )
                }
                is PhoneGrantResult.Refused -> {
                    if (!grantAttempts.finish(attempt)) return@launch
                    mutateFacts {
                        it.copy(
                            pending = null,
                            problem = problemFor(result.error),
                        )
                    }
                }
            }
        }
    }

    private suspend fun completeGrant(
        attempt: PhoneGrantAttemptOwner.Attempt,
        granted: PhoneGrant,
        request: PhoneGrantRequest,
        target: PhoneTarget,
        stopChannel: PhoneStopChannel,
        /** The proposal revision displayed when the person confirmed; null for a manual task. */
        frozenRevision: Long?,
        /** The chat that owned a resumed task, captured at confirmation; null for a new task. */
        frozenResumeSession: String?,
    ) {
        if (!grantAttempts.isCurrent(attempt)) return
        val epoch = granted.epoch
        val remembered =
            PhoneLeaseFacts(
                target = target,
                summary = request.summary.trim().take(PhoneLimits.MAX_NODE_LABEL_CHARS),
                actions = request.actions,
            )
        // The service notification needs the target label; the lease is memory-only display data and
        // is not authority. Record it before asking the service to show it, and clear it on failure.
        lease = remembered
        // 1) + 2) Prove the foreground stop entry, re-check the frozen epoch and revision, then publish
        // the route exactly once. The frozen revision is the one the screen displayed at confirmation;
        // waiting for the service never changes the confirmed object. Every step is also gated by the
        // attempt owner, so a stop that happened during the wait touches nothing.
        val outcome =
            runGrantAttempt(
                owner = grantAttempts,
                attempt = attempt,
                epoch = epoch,
                frozenRevision = frozenRevision,
                frozenResumeSession = frozenResumeSession,
                awaitStopEntry = { serviceStart(epoch) && serviceAwaitReady(epoch) },
                currentEpoch = { controller.state.value.grant?.epoch },
                currentRevision = { facts.value.requestRevision },
                consume = { proposals.consume(it) },
                publish = { routeEpoch, label ->
                    runCatching { grantRoutes.publishGrantRoute(routeEpoch, label) }.getOrDefault(false)
                },
            )
        applyGrantAttemptOutcome(
            owner = grantAttempts,
            attempt = attempt,
            outcome = outcome,
            commit = { confirmedSession ->
                commitGrant(
                    confirmedSession = confirmedSession,
                    target = target,
                    stopChannel = stopChannel,
                    remembered = remembered,
                )
            },
            fail = { error -> failGrant(epoch, error) },
        )
    }

    /** Commits a grant whose attempt was already claimed by [applyGrantAttemptOutcome]. */
    private fun commitGrant(
        confirmedSession: String?,
        target: PhoneTarget,
        stopChannel: PhoneStopChannel,
        remembered: PhoneLeaseFacts,
    ) {
        taskSession = confirmedSession
        store.setTask(
            PhoneTaskRecord(
                targetPackage = target.packageName,
                summary = remembered.summary,
                needsCheck = true,
            ),
        )
        mutateFacts {
            it.copy(
                pending = null,
                halt = null,
                lease = remembered,
                task = store.task(),
                request = null,
                requestRevision = null,
                stopChannel = stopChannel,
                problem = null,
                agentToolBlocker = null,
            )
        }
        if (!launchTarget(target.packageName)) {
            halt(PhoneHaltReason.TargetLeft)
            controller.revoke()
            serviceStop()
            mutateFacts { it.copy(pending = null, lease = null) }
        }
    }

    /**
     * Fail-closed cleanup for the current grant attempt that never became usable. The caller has
     * already claimed the attempt, so this cannot erase a newer grant, service, task or facts.
     */
    private fun failGrant(epoch: Long?, error: String) {
        // Revoke only the epoch this attempt created: a newer grant must survive a late failure.
        val current = controller.state.value.grant
        if (epoch == null || current?.epoch == epoch) controller.revoke()
        closeLease(keepTask = false)
        taskSession = null
        serviceStop()
        mutateFacts {
            it.copy(
                pending = null,
                lease = null,
                halt = null,
                task = null,
                request = null,
                requestRevision = null,
                busy = null,
                problem = PhoneDeviceProblem.Other,
                diagnostics = it.diagnostics.copy(lastError = error),
            )
        }
    }

    /**
     * A stop control invalidates the frozen confirmation before it touches the service or task.
     * Settling the attempt's own pending here is what keeps the screen from staying "Granting";
     * a newer grant has already replaced the owner, so its pending is not touched.
     */
    private fun invalidateGrantAttempt() {
        val invalidated = grantAttempts.invalidate() ?: return
        val nextPending = pendingAfterGrantInvalidation(invalidated, facts.value.pending)
        if (nextPending != facts.value.pending) {
            mutateFacts { it.copy(pending = nextPending) }
        }
    }

    /** Core-only cleanup for a grant created after its attempt was invalidated. Never native state. */
    private fun revokeAttemptEpoch(epoch: Long) {
        if (controller.state.value.grant?.epoch == epoch) controller.revoke()
    }

    private fun pause() {
        invalidateGrantAttempt()
        val core = controller.state.value
        if (core.grant == null) return
        halt(PhoneHaltReason.Paused)
        controller.pause()
        serviceStart(core.epoch)
    }

    /**
     * "I'll take over" is a stop, not a pause. A paused lease still allows Observe/Capture, so the
     * whole lease is revoked (cancelling any in-flight read) before the confirmed app is opened.
     * A remembered task without a live grant still opens the app; it never grants again.
     */
    private fun takeOver() {
        invalidateGrantAttempt()
        val core = controller.state.value
        val remembered = lease ?: facts.value.halt?.lease
        val plan = takeoverPlan(hasGrant = core.grant != null, rememberedTarget = remembered?.target?.packageName)
        if (plan.revokeGrant) {
            halt(
                PhoneHaltReason.TakenOver,
                remembered,
                actionInFlight = core.inFlightMutation || core.pausing,
            )
            controller.revoke()
            serviceStop()
        }
        plan.launchTarget?.let { launchTarget(it) }
    }

    /**
     * Ends the task. The grant is revoked by the controller, so the coordinator keeps the minimal
     * record (target, summary, needs-check) for the result card; it never claims a verified result.
     */
    private fun finish() {
        invalidateGrantAttempt()
        val remembered = lease
        controller.pause()
        if (remembered != null) {
            store.setTask(
                PhoneTaskRecord(
                    targetPackage = remembered.target.packageName,
                    summary = remembered.summary,
                    needsCheck = true,
                    haltReason = facts.value.halt?.reason?.name,
                    acknowledged = facts.value.halt?.acknowledged == true,
                ),
            )
        }
        controller.revoke()
        taskSession = null
        mutateFacts {
            it.copy(
                halt = null,
                lease = null,
                task = store.task(),
                pending = null,
                busy = null,
            )
        }
        serviceStop()
    }

    private fun acknowledgeChecked() {
        controller.acknowledgeUnknownOutcome()
        val current = facts.value.halt ?: return
        mutateFacts {
            it.copy(
                halt = current.copy(lastAction = PhoneLastAction.UserChecked, acknowledged = true),
                task = store.task()?.copy(acknowledged = true),
            )
        }
        store.task()?.let { store.setTask(it.copy(acknowledged = true)) }
    }

    private fun dismissResult() {
        store.clearTask()
        taskSession = null
        mutateFacts { it.copy(task = null) }
    }

    private fun observeTarget() {
        val core = controller.state.value
        if (core.grant == null || core.paused) {
            // No lease: never read the target screen quietly. The screen shows the connect/grant step.
            mutateFacts { it.copy(problem = PhoneDeviceProblem.Other) }
            return
        }
        if (guardBusy()) return
        mutateFacts { it.copy(pending = PhonePending.Observing, busy = null) }
        val request =
            PhoneAgentRequest(
                requestId = "native-" + UUID.randomUUID().toString(),
                epoch = core.epoch,
                sessionLabel = NATIVE_SESSION,
                action = PhoneAgentAction.Observe,
            )
        scope.launch {
            val result =
                runCatching { controller.execute(request, PhoneLimits.DEFAULT_CALL_MS) }
                    .getOrElse {
                        PhoneCallResult.Refused(
                            request.requestId,
                            request.epoch,
                            PhoneErrorCode.DRIVER_UNAVAILABLE,
                        )
                    }
            val error =
                when (result) {
                    is PhoneCallResult.Refused -> result.error
                    is PhoneCallResult.Dispatched -> result.error
                    else -> null
                }
            // The driver verified the window; a mismatch means the target app left the screen. The
            // native side stops instead of pretending the observation failed harmlessly.
            if (facts.value.halt == null) {
                val stopReason =
                    when (error) {
                        PhoneErrorCode.TARGET_MISMATCH -> PhoneHaltReason.TargetLeft
                        PhoneErrorCode.WINDOW_CHANGED, PhoneErrorCode.GEOMETRY_CHANGED,
                        PhoneErrorCode.STALE_OBSERVATION,
                        -> PhoneHaltReason.ScreenUncertain
                        else -> null
                    }
                if (stopReason != null) {
                    halt(stopReason, actionInFlight = false)
                    controller.pause()
                    runCatching { serviceStart(controller.state.value.epoch) }
                }
            }
            mutateFacts {
                it.copy(
                    pending = null,
                    diagnostics =
                        it.diagnostics.copy(
                            lastError = error?.name,
                            observedAtMs = (result as? PhoneCallResult.Observed)?.observation?.observedAtMs
                                ?: it.diagnostics.observedAtMs,
                            observedNodes = (result as? PhoneCallResult.Observed)?.observation?.nodes?.size
                                ?: it.diagnostics.observedNodes,
                        ),
                )
            }
        }
    }

    // --- enable/disable ---------------------------------------------------------------------

    private fun setEnabled(value: Boolean) {
        if (!facts.value.available) return
        if (value) {
            store.setEnabled(true)
            controller.enable()
            mutateFacts { it.copy(enabled = true, problem = null, busy = null) }
            recheckStopChannel()
            refreshAgentToolBlocker()
            loadTargets()
            refreshPairingState()
            startDiscovery()
        } else {
            store.setEnabled(false)
            invalidateGrantAttempt()
            cancelPairingNotification()
            controller.disable()
            scope.launch { runCatching { controller.disconnect() } }
            closeLease(keepTask = false)
            taskSession = null
            serviceStop()
            notifications.cancelPairing()
            mutateFacts { it.copy(agentToolBlocker = null) }
            mutateFacts {
                it.copy(
                    enabled = false,
                    lease = null,
                    halt = null,
                    task = null,
                    targets = emptyList(),
                    request = null,
                    pending = null,
                    busy = null,
                    pairingNotice = PhonePairingNotice.Idle,
                    hints = PhoneEndpointHints(host = it.hints.host),
                )
            }
            stopDiscovery()
        }
    }

    private fun recheckStopChannel() {
        mutateFacts { it.copy(stopChannel = notifications.stopChannelState()) }
    }

    /**
     * Re-reads the two native probes and publishes the one missing step, if any. Only reads: the
     * runtime is never restarted here and no grant is created or extended.
     */
    private fun refreshAgentToolBlocker(): PhoneAgentToolBlocker? {
        val blocker =
            runCatching {
                agentToolBlocker(
                    enabled = facts.value.enabled,
                    presence = readiness.runtimePresence(),
                    toolReady = readiness.toolReady(),
                )
            }.getOrNull()
        mutateFacts { if (it.agentToolBlocker == blocker) it else it.copy(agentToolBlocker = blocker) }
        return blocker
    }

    private fun loadTargets() {
        if (!facts.value.available) return
        scope.launch {
            val targets = withContext(Dispatchers.IO) { runCatching { catalog.load() }.getOrDefault(emptyList()) }
            mutateFacts { it.copy(targets = targets) }
        }
    }

    // --- discovery -------------------------------------------------------------------------

    private fun startDiscovery() {
        if (!facts.value.available) return
        if (discovery != null) return
        val created =
            PhonePortDiscovery(context) { pairingPort, connectPort ->
                mutateFacts {
                    it.copy(
                        hints =
                            it.hints.copy(
                                pairingPort = it.hints.pairingPort ?: pairingPort,
                                connectPort = it.hints.connectPort ?: connectPort,
                            ),
                    )
                }
            }
        if (created.start()) {
            discovery = created
        } else {
            created.stop()
        }
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
    }

    // --- state plumbing --------------------------------------------------------------------

    private fun observeController() {
        observers +=
            scope.launch {
                var previous: PhoneState? = null
                controller.state.collect { core ->
                    deriveHalt(previous, core)
                    surfaceUnknownOutcome(core)
                    settleHalt(core)
                    previous = core
                    publish()
                }
            }
    }

    /**
     * An uncertain mutation is a native stop, not just a tool error: the controller already refuses
     * further mutations while blocked, and the screen must say so and offer the person the check/ack
     * path. It is never retried automatically and never reported as "nothing happened".
     */
    private fun surfaceUnknownOutcome(core: PhoneState) {
        if (core.blocked != PhoneBlockReason.UNKNOWN_OUTCOME) return
        if (facts.value.halt != null) return
        val remembered = lease ?: return
        halt(PhoneHaltReason.OutcomeUnknown, remembered, actionInFlight = true)
        // Directly, so the recorded reason stays OutcomeUnknown instead of becoming Paused.
        controller.pause()
        runCatching { serviceStart(core.epoch) }
    }

    private fun observeFacts() {
        observers += scope.launch { facts.collect { publish() } }
    }

    private fun observeProposals() {
        observers +=
            scope.launch {
                proposals.pending.collect { proposal ->
                    mutateFacts {
                        it.copy(
                            request =
                                proposal?.let {
                                    io.andchamber.app.ui.phone.PhoneAgentRequest(
                                        summary = it.summary,
                                        packageName = it.packageName,
                                    )
                                },
                            // The revision the next grant may consume is exactly the displayed one.
                            requestRevision = proposal?.revision,
                        )
                    }
                }
            }
    }

    private fun startTicker() {
        observers +=
            scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    // Cheap read-only probes: the notice appears/clears without any user action.
                    refreshAgentToolBlocker()
                    val core = controller.state.value
                    if (core.grant != null) controller.refresh()
                    if (core.grant == null) continue
                    // A lease is only usable while the stop entry can be reached from another app.
                    if (notifications.stopChannelState() != PhoneStopChannel.Verified) {
                        invalidateGrantAttempt()
                        halt(PhoneHaltReason.StopChannelLost)
                        controller.pause()
                        serviceStop()
                        mutateFacts { it.copy(stopChannel = notifications.stopChannelState()) }
                    }
                }
            }
    }

    /**
     * Keeps the reason the controller dropped a lease. The controller clears its grant (and the
     * unknown-outcome block) on revoke, so the coordinator records the halt itself, once.
     */
    private fun deriveHalt(previous: PhoneState?, core: PhoneState) {
        val remembered = lease ?: return
        if (previous?.grant == null || core.grant != null) return
        if (facts.value.halt != null) return
        val reason =
            when (core.lastEvent) {
                PhoneEvent.LOCKED -> PhoneHaltReason.ScreenLocked
                PhoneEvent.HOST_RETURNED -> PhoneHaltReason.HostReturned
                PhoneEvent.CONNECTION_LOST -> PhoneHaltReason.ConnectionLost
                PhoneEvent.LEASE_EXPIRED -> PhoneHaltReason.Expired
                PhoneEvent.RUNTIME_CHANGED -> PhoneHaltReason.RuntimeChanged
                else -> null
            } ?: return
        halt(reason, remembered, actionInFlight = previous?.inFlightMutation == true)
    }

    /** Latches the halt facts: a dispatched action never becomes "nothing happened". */
    private fun settleHalt(core: PhoneState) {
        val current = facts.value.halt ?: return
        if (current.confirmed) return
        if (core.pausing || core.inFlightMutation) return
        val factsNow =
            phoneHaltFacts(
                pausing = core.pausing,
                inFlightMutation = core.inFlightMutation,
                outcomeUnknown = core.blocked == PhoneBlockReason.UNKNOWN_OUTCOME,
                acknowledged = current.acknowledged,
            )
        halt(
            current.lease,
            current.reason,
            confirmed = true,
            lastAction = if (current.lastAction == PhoneLastAction.MayStillApply) {
                PhoneLastAction.MayStillApply
            } else {
                factsNow.lastAction
            },
            acknowledged = current.acknowledged,
        )
    }

    private fun halt(
        reason: PhoneHaltReason,
        remembered: PhoneLeaseFacts? = lease,
        actionInFlight: Boolean = controller.state.value.inFlightMutation,
    ) {
        if (remembered == null) return
        val core = controller.state.value
        val derived =
            phoneHaltFacts(
                pausing = core.pausing || actionInFlight,
                inFlightMutation = core.inFlightMutation || actionInFlight,
                outcomeUnknown = core.blocked == PhoneBlockReason.UNKNOWN_OUTCOME,
            )
        halt(
            remembered,
            reason,
            // A dispatched action can never become "nothing happened" after a revoke cleared the block,
            // and an earlier "may still apply" survives this halt too.
            confirmed = derived.confirmed && !actionInFlight,
            lastAction =
                latchedHaltLastAction(
                    existing = facts.value.halt?.lastAction,
                    actionInFlight = actionInFlight || core.inFlightMutation,
                    derived =
                        if (derived.lastAction == PhoneLastAction.MayStillApply) {
                            PhoneLastAction.MayStillApply
                        } else {
                            PhoneLastAction.NoneInFlight
                        },
                    acknowledged = false,
                ),
            acknowledged = false,
        )
    }

    private fun halt(
        remembered: PhoneLeaseFacts,
        reason: PhoneHaltReason,
        confirmed: Boolean,
        lastAction: PhoneLastAction,
        acknowledged: Boolean,
    ) {
        mutateFacts {
            it.copy(
                halt =
                    PhoneHaltSnapshot(
                        lease = remembered,
                        reason = reason,
                        confirmed = confirmed,
                        lastAction = lastAction,
                        acknowledged = acknowledged,
                    ),
            )
        }
        store.setTask(
            store.task()?.copy(
                needsCheck = true,
                haltReason = reason.name,
                acknowledged = acknowledged,
            ),
        )
    }

    private fun closeLease(keepTask: Boolean) {
        lease = null
        if (!keepTask) store.clearTask()
    }

    private fun guardBusy(): Boolean {
        if (facts.value.pending == null) return false
        mutateFacts { it.copy(busy = PhoneBusy.RequestIgnored) }
        return true
    }

    private fun publish() {
        mutableState.value = phoneScreenState(controller.state.value, facts.value)
    }

    private fun mutateFacts(transform: (PhoneScreenFacts) -> PhoneScreenFacts) {
        while (true) {
            val current = facts.value
            val next = transform(current)
            if (next == current) return
            if (facts.compareAndSet(current, next)) {
                publish()
                return
            }
        }
    }

    private fun problemFor(outcome: PhonePairingOutcome): PhoneDeviceProblem? =
        when (outcome) {
            PhonePairingOutcome.PAIRED -> null
            PhonePairingOutcome.CODE_REJECTED -> PhoneDeviceProblem.PairingCodeRejected
            PhonePairingOutcome.PORT_UNREACHABLE -> PhoneDeviceProblem.PairingPortUnreachable
            PhonePairingOutcome.POLICY_REFUSED -> PhoneDeviceProblem.Other
            PhonePairingOutcome.CANCELLED -> PhoneDeviceProblem.Other
            PhonePairingOutcome.TIMEOUT -> PhoneDeviceProblem.Other
            PhonePairingOutcome.UNAVAILABLE -> PhoneDeviceProblem.Other
        }

    private fun problemFor(error: PhoneErrorCode): PhoneDeviceProblem =
        when (error) {
            PhoneErrorCode.NOT_CONNECTED -> PhoneDeviceProblem.ConnectRefused
            PhoneErrorCode.DEADLINE -> PhoneDeviceProblem.ConnectRefused
            PhoneErrorCode.TARGET_FORBIDDEN -> PhoneDeviceProblem.Other
            PhoneErrorCode.DISABLED -> PhoneDeviceProblem.Other
            PhoneErrorCode.NOT_GRANTED, PhoneErrorCode.LEASE_EXPIRED, PhoneErrorCode.EPOCH_CHANGED,
            PhoneErrorCode.PAUSED, PhoneErrorCode.CANCELLED, PhoneErrorCode.OUTCOME_UNKNOWN,
            PhoneErrorCode.INVALID_REQUEST, PhoneErrorCode.CAPACITY, PhoneErrorCode.DUPLICATE,
            PhoneErrorCode.BUSY, PhoneErrorCode.UNSUPPORTED_ACTION, PhoneErrorCode.UNICODE_UNSUPPORTED,
            PhoneErrorCode.ACTION_NOT_GRANTED, PhoneErrorCode.TARGET_MISMATCH,
            PhoneErrorCode.WINDOW_CHANGED, PhoneErrorCode.GEOMETRY_CHANGED,
            PhoneErrorCode.STALE_OBSERVATION, PhoneErrorCode.OBSERVATION_REQUIRED,
            PhoneErrorCode.OUT_OF_BOUNDS, PhoneErrorCode.DRIVER_UNAVAILABLE,
            -> PhoneDeviceProblem.Other
        }

    private fun pairingOutcome(failure: AdbPairingFailure): PhonePairingOutcome =
        when (failure) {
            AdbPairingFailure.WRONG_CODE -> PhonePairingOutcome.CODE_REJECTED
            AdbPairingFailure.UNREACHABLE -> PhonePairingOutcome.PORT_UNREACHABLE
            AdbPairingFailure.POLICY, AdbPairingFailure.INVALID_INPUT -> PhonePairingOutcome.POLICY_REFUSED
            AdbPairingFailure.DEADLINE -> PhonePairingOutcome.TIMEOUT
            AdbPairingFailure.CANCELLED -> PhonePairingOutcome.CANCELLED
            AdbPairingFailure.TLS_UNAVAILABLE,
            AdbPairingFailure.TLS_HANDSHAKE,
            AdbPairingFailure.PROTOCOL,
            -> PhonePairingOutcome.UNAVAILABLE
        }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val NATIVE_SESSION = "native-ui"

        /** Technical token for the details pane when the foreground stop entry could not start. */
        const val GRANT_TOKEN_SERVICE_UNAVAILABLE = "service_unavailable"

        /** Long enough for one handshake, within the pairing entry point's own bound. */
        const val PAIRING_DEADLINE_MS = 60_000L

        private const val TICK_MS = 1_000L

        internal fun launchDefaultApp(context: Context, packageName: String): Boolean =
            runCatching {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.getOrDefault(false)

        internal fun openDevelopmentSettings(context: Context) {
            val opened =
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    true
                }.getOrDefault(false)
            if (!opened) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }

        internal fun openAppNotificationSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

/** Notification ids used by the pairing flow. The control id lives with the notifications class. */
internal object PhonePairNotifications {
    const val PAIRING_ID = PhoneControlNotifications.PAIRING_NOTIFICATION_ID
}

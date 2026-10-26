package io.andchamber.app.runtime.process

import io.andchamber.app.phone.core.PhoneController
import io.andchamber.app.phone.core.PhoneLimits
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventHandler
import io.andchamber.app.phone.standing.PhoneTaskStarter
import io.andchamber.app.phone.tool.PhonePendingRequest
import io.andchamber.app.phone.tool.PhonePendingRequestStore
import io.andchamber.app.phone.tool.PhoneToolEndpoint
import io.andchamber.app.phone.tool.PhoneToolProtocol
import io.andchamber.app.phone.tool.PhoneToolServer
import io.andchamber.app.phone.tool.PhoneToolTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the loopback phone tool server for one local launch: binds it, writes the endpoint file, and
 * tears all of it down when the launch ends.
 *
 * This is independent from [BrowserToolHost]: separate endpoint file, separate server, separate
 * failure handling. A phone-tool failure never disables the browser tool and the reverse.
 */
interface PhoneToolHost {
    /**
     * Starts the server for [launchId], records the runtime epoch with the controller, writes the
     * endpoint file atomically once listening. Returns false when the server or endpoint could not
     * be set up; the launch itself must still proceed.
     */
    fun start(
        launchId: Long,
        home: File,
        trustedRoot: File,
    ): Boolean

    /**
     * Stops the server for [launchId] (if it is still the current one), deletes this launch's
     * endpoint and synchronously drops the controller grant. Idempotent; a late stop for an older
     * launch never touches a newer launch.
     */
    fun stop(launchId: Long)

    /**
     * Removes the endpoint file only when no installed launch owns it, e.g. one left by an earlier
     * process. The binding is the file's single owner, so this never deletes a live file.
     */
    fun clearStaleEndpoint(
        home: File,
        trustedRoot: File,
    ): Boolean

    /**
     * Read-only. True only while this launch has an installed tool, the injected [readyLaunch]
     * reports that same launch, and readiness was proved for that exact launch. Production uses the
     * native RPC registration probe (`andchamber-phone/ready`, only after the plugin's tool transform
     * and `ctx.tool.list`); the legacy transport `/v1/ready` handshake remains only for tests/non-local
     * launches when no RPC probe is wired. An endpoint file, a live port, an active launch or a
     * permission grant is never readiness by itself. It performs no authorization change and exposes
     * no token or port. `None` is false, so native UI can prompt for a manual restart instead of
     * letting the person pair/confirm only to fail a later publish.
     */
    fun isReady(): Boolean = false

    /**
     * Native-only. Publishes the session route for a newly granted [epoch]: a label pins the
     * consumed proposal's session, null explicitly allows the first action to claim (manual task).
     * Returns false when this launch is not current/ready, [epoch] is not the controller's live
     * grant epoch, or the transport cannot publish. The default is fail-closed; the caller must
     * revoke the grant when this returns false. A route is routing, never authentication.
     */
    fun publishGrantRoute(
        epoch: Long,
        confirmedSessionLabel: String?,
    ): Boolean = false

    /** Used by launches that do not run a local phone tool (tests, non-local runtime). */
    object None : PhoneToolHost {
        override fun start(
            launchId: Long,
            home: File,
            trustedRoot: File,
        ): Boolean = false

        override fun stop(launchId: Long) = Unit

        override fun clearStaleEndpoint(
            home: File,
            trustedRoot: File,
        ): Boolean = PhoneToolEndpoint.delete(PhoneToolEndpoint.hostFile(home), trustedRoot)
    }
}

/**
 * The production binding. The server it starts is not an authority: before each real action it asks
 * [readyLaunch] whether this exact launch is still ready and re-reads the controller's enabled,
 * connection and grant state. The session label only routes a chat, and one chat owns a grant epoch.
 *
 * The launch owner must call [start] after the server is ready and [stop] on every teardown path
 * (normal stop, failed start, child death). [stop] revokes the grant synchronously, so no action can
 * outlive the runtime. A late [stop] for an older launch is ignored for a newer launch.
 */
class PhoneToolBinding(
    private val controller: PhoneController,
    private val readyLaunch: () -> Long?,
    /** Native task-progress bridge; defaults to fail-closed Unavailable. */
    private val taskEvents: PhoneTaskEventHandler = PhoneTaskEventHandler.Unavailable,
    /** Native standing starter for direct chat requests; defaults to fail-closed Unavailable. */
    private val taskStarter: PhoneTaskStarter = PhoneTaskStarter.Unavailable,
    /**
     * Production RPC registration gate. Default Unavailable keeps the legacy transport handshake
     * path for tests/non-local runtimes; the real probe runs off Main and is bounded.
     */
    private val registrationProbe: PhonePluginRegistrationWaiter = PhonePluginRegistrationWaiter.Unavailable,
    /**
     * Shared with the loopback server; holds at most one native-confirmation proposal. Internal so
     * the runtime owner and tests can inspect ownership; UI uses [pendingRequest]/consume/dismiss.
     */
    internal val pendingRequests: PhonePendingRequestStore = PhonePendingRequestStore(),
    private val transportFactory: ((launchId: Long, token: String) -> PhoneToolTransport)? = null,
    private val tokenSource: () -> String = { PhoneToolEndpoint.newToken() },
    /** Receives a short, non-sensitive reason when the tool server is skipped for this launch. */
    private val onUnavailable: (String) -> Unit = {},
) : PhoneToolHost {
    /** Used by launches that do not inject a fake transport (production and integration tests). */
    private val resolvedTransportFactory: (Long, String) -> PhoneToolTransport =
        transportFactory ?: { launchId, token ->
            PhoneToolServer(
                launchId = launchId,
                token = token,
                controller = controller,
                readyLaunch = readyLaunch,
                taskEvents = taskEvents,
                taskStarter = taskStarter,
                pendingRequests = pendingRequests,
            )
        }

    /**
     * The single agent-proposed task waiting for native confirmation, or null.
     *
     * This is native-only detail: it carries the untrusted summary. Never put it in `status`, a log
     * or a notification; the integration layer maps it into the screen's `request` prefill.
     */
    val pendingRequest: StateFlow<PhonePendingRequest?> = pendingRequests.pending

    /**
     * Native UI only. Takes exactly the proposal the screen displayed and clears it. A stale
     * [revision] returns null and leaves a newer proposal in place; this never grants or starts.
     *
     * Consuming does not publish authority or a route. After granting, native code must call
     * [publishGrantRoute] with the consumed proposal's session label; only an explicit manual grant
     * passes null to allow first-action routing. A session label is never authentication.
     */
    fun consumePendingRequest(revision: Long): PhonePendingRequest? = pendingRequests.consume(revision)

    /** Native UI only. Dismisses exactly the displayed proposal; a stale revision changes nothing. */
    fun dismissPendingRequest(revision: Long): Boolean = pendingRequests.dismiss(revision)

    /** Native/runtime owner only. Drops any proposal, e.g. on teardown. */
    fun clearPendingRequest() {
        pendingRequests.clear()
    }
    /** One lifecycle lock: install, start, stop and endpoint ownership all order through it. */
    private val lock = Any()
    private var active: Active? = null

    /** The launch currently between `start()` entry and install. */
    private var pending: Long? = null

    /**
     * Monotonic revoked watermark. Launch ids only grow, so a stop is terminal for that launch and
     * every older one; an old start can never clear the mark of a newer launch.
     */
    private var revokedThrough = Long.MIN_VALUE

    /** The last launch whose grant was already dropped by [stop]. Guarded by [lock]. */
    private var authorityRevokedThrough = Long.MIN_VALUE

    /**
     * The launch that wrote the shared endpoint file, recorded under [lock]. This is the only owner
     * allowed to delete it: a delayed cleanup of launch A must never remove launch B's file.
     */
    private var endpointOwner: EndpointOwner? = null

    /** Owns bounded RPC registration waits off Main; one job per current launch only. */
    private val registrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guarded by [lock]: invalidates late registration results after stop/new launch. */
    private var registrationGeneration = 0L
    private var registrationJob: Job? = null
    private var registeredLaunch: Long? = null

    private data class EndpointOwner(
        val launchId: Long,
        val file: File,
        val trustedRoot: File,
    )

    private data class Active(
        val launchId: Long,
        val transport: PhoneToolTransport,
    )

    override fun start(
        launchId: Long,
        home: File,
        trustedRoot: File,
    ): Boolean {
        // Register the launch before any blocking work. A stop that arrives first, or while the
        // transport is starting, is terminal: this start tears its own transport down and never
        // publishes the endpoint.
        val previous = synchronized(lock) {
            if (launchId <= revokedThrough) return false
            if ((active?.launchId ?: Long.MIN_VALUE) > launchId || (pending ?: Long.MIN_VALUE) > launchId) {
                return false
            }
            pending = launchId
            // A new launch owns readiness now: cancel and invalidate the previous probe before any
            // start/install work, so a late success for the old launch can never publish.
            registrationGeneration += 1
            registrationJob?.cancel()
            registrationJob = null
            registeredLaunch = null
            val detached = active.also {
                active = null
                discardEndpointLocked(it?.launchId)
            }
            // Owner-checked in the same critical section as the launch claim: a newer launch can
            // never interleave between the check and these writes. Clearing an old proposal and
            // recording the runtime epoch here means a late older start never writes them back.
            pendingRequests.clear()
            // DI builds the phone driver with the same `launch-<id>` runtime id, so a reconnect
            // agrees with the epoch recorded here; a mismatch revokes rather than extending.
            controller.onRuntimeChanged(PhoneToolProtocol.serverEpoch(launchId))
            detached
        }
        previous?.let { runCatching { it.transport.stop() } }
        if (!stillPending(launchId)) {
            finishPending(launchId)
            return false
        }

        val token = try {
            tokenSource()
        } catch (error: Exception) {
            finishPending(launchId)
            onUnavailable("phone-tool token unavailable: ${error.javaClass.simpleName}")
            return false
        }
        val transport = try {
            resolvedTransportFactory(launchId, token)
        } catch (error: Exception) {
            finishPending(launchId)
            onUnavailable("phone-tool transport unavailable: ${error.javaClass.simpleName}")
            return false
        }
        if (!stillPending(launchId)) {
            finishPending(launchId)
            runCatching { transport.stop() }
            return false
        }
        val port = try {
            transport.start()
        } catch (error: Exception) {
            finishPending(launchId)
            runCatching { transport.stop() }
            onUnavailable("phone-tool server did not start: ${error.javaClass.simpleName}")
            return false
        }
        return when (val installed = synchronized(lock) {
            installLocked(launchId, home, trustedRoot, port, token, transport)
        }) {
            is Install.Installed -> {
                beginRegistrationProbe(installed.active.launchId)
                true
            }
            Install.Aborted -> {
                runCatching { transport.stop() }
                false
            }
            is Install.Failed -> {
                runCatching { transport.stop() }
                onUnavailable("phone-tool endpoint not written: ${installed.error.javaClass.simpleName}")
                false
            }
        }
    }

    override fun stop(launchId: Long) {
        val removed = synchronized(lock) {
            // A stop for a launch that is still starting wins over that start. The watermark is
            // permanent, so a repeated start of the same launch stays refused.
            if (launchId > revokedThrough) revokedThrough = launchId
            val pendingWasThis = pending == launchId
            if (pendingWasThis) pending = null
            // Owner-checked and under the lock: a newer launch's endpoint is never touched.
            discardEndpointLocked(launchId)
            val current = active
            val detached = if (current != null && current.launchId == launchId) {
                active = null
                current
            } else {
                null
            }
            if (detached != null) {
                // Stop cancels this launch's registration wait; a newer launch's state is untouched.
                registrationGeneration += 1
                registrationJob?.cancel()
                registrationJob = null
                registeredLaunch = null
            }
            if ((detached != null || pendingWasThis) && launchId > authorityRevokedThrough) {
                // Same critical section as the ownership checks. While this lock is held no newer
                // launch can install, so a newer grant or pending proposal cannot appear between
                // deciding to clean up and doing it. The blocking transport stop stays outside.
                val newerActive = (active?.launchId ?: Long.MIN_VALUE) > launchId
                val newerPending = (pending ?: Long.MIN_VALUE) > launchId
                if (!newerActive && !newerPending) {
                    authorityRevokedThrough = launchId
                    pendingRequests.clear()
                    if (controller.state.value.grant != null) controller.revoke()
                }
            }
            detached
        }
        // Blocking teardown only after the owner-checked cleanup; it must never run under the lock.
        removed?.let { runCatching { it.transport.stop() } }
    }

    /**
     * Removes an endpoint that no installed launch owns, e.g. one left by an earlier process. An
     * endpoint owned by a live launch is left alone: it may belong to a concurrent newer start.
     */
    override fun clearStaleEndpoint(
        home: File,
        trustedRoot: File,
    ): Boolean =
        synchronized(lock) {
            if (endpointOwner != null) return true
            PhoneToolEndpoint.delete(PhoneToolEndpoint.hostFile(home), trustedRoot)
        }

    /**
     * Read-only readiness for native UI. True only when the installed tool's launch id equals the
     * injected ready launch *and* that exact launch proved plugin registration: either the wired
     * native RPC probe succeeded, or (only when no probe is available) the legacy transport setup
     * handshake arrived. A server that is merely listening, an active endpoint file or an old
     * launch's success is never enough. No grant, pending, endpoint or controller state is read or
     * changed here.
     */
    override fun isReady(): Boolean =
        synchronized(lock) {
            val current = active ?: return false
            val ready = readyLaunch() ?: return false
            if (current.launchId != ready) return false
            if (registrationProbe.available) {
                // Production: the exact launch must have registered its read-only RPC. Active,
                // endpoint or a legacy transport flag alone is never readiness.
                return registeredLaunch == current.launchId
            }
            // Backward-compatible path for tests/non-local launches without the RPC probe.
            runCatching { current.transport.pluginReady }.getOrDefault(false)
        }

    /**
     * Starts one bounded registration wait after install. It is fully asynchronous on IO, so
     * `start()` never blocks. A generation token plus the active launch check makes a late result
     * from a stopped/replaced launch harmless.
     */
    private fun beginRegistrationProbe(launchId: Long) {
        if (!registrationProbe.available) return
        val generation = synchronized(lock) {
            if (active?.launchId != launchId || launchId <= revokedThrough) return
            registrationGeneration += 1
            registrationJob?.cancel()
            registeredLaunch = null
            registrationGeneration
        }
        val job =
            registrationScope.launch {
                val registered =
                    try {
                        registrationProbe.awaitRegistered(launchId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                if (!registered) return@launch
                synchronized(lock) {
                    if (registrationGeneration == generation &&
                        active?.launchId == launchId &&
                        launchId > revokedThrough
                    ) {
                        registeredLaunch = launchId
                    }
                }
            }
        synchronized(lock) {
            if (registrationGeneration == generation && active?.launchId == launchId) {
                registrationJob = job
            } else {
                job.cancel()
            }
        }
    }

    /**
     * Native UI only, immediately after a successful `controller.grant`. It relays the route to the
     * current launch's transport. A stale launch/epoch, a missing live grant or an invalid label is
     * refused without touching any route; a transport that cannot publish (including [None]) fails
     * closed. The caller must revoke the grant when this returns false.
     *
     * A route is routing, not authentication: it only prevents an ordinary concurrent session from
     * claiming a task that was confirmed for another chat.
     */
    override fun publishGrantRoute(
        epoch: Long,
        confirmedSessionLabel: String?,
    ): Boolean =
        synchronized(lock) {
            val current = active ?: return false
            if (readyLaunch() != current.launchId) return false
            val state = controller.state.value
            // The epoch and a live grant must both still match; a stale or newer epoch is refused.
            if (state.grant == null || state.epoch != epoch) return false
            if (confirmedSessionLabel != null && !validSessionLabel(confirmedSessionLabel)) return false
            // Publishing is a bounded in-memory operation, unlike start/stop; holding the lock keeps
            // a concurrent launch replacement from moving the route to a different transport.
            runCatching { current.transport.publishGrantRoute(epoch, confirmedSessionLabel) }
                .getOrDefault(false)
        }

    private fun validSessionLabel(label: String): Boolean =
        label.isNotBlank() &&
            label.length <= PhoneLimits.MAX_SESSION_LABEL_CHARS &&
            label.none(Char::isISOControl)

    /** Must hold [lock]. Writes the endpoint and publishes [transport] only if still wanted. */
    private fun installLocked(
        launchId: Long,
        home: File,
        trustedRoot: File,
        port: Int,
        token: String,
        transport: PhoneToolTransport,
    ): Install {
        if (pending != launchId || launchId <= revokedThrough) {
            // Only this launch's own pending mark is cleared; a newer start keeps its pending.
            if (pending == launchId) pending = null
            return Install.Aborted
        }
        // Owner-checked: drop anything the previous server's in-flight request may have left after
        // the start-entry clear. A newer launch can never reach this branch.
        pendingRequests.clear()
        return try {
            val file = PhoneToolEndpoint.hostFile(home)
            PhoneToolEndpoint.write(
                file = file,
                home = home,
                trustedRoot = trustedRoot,
                port = port,
                token = token,
            )
            endpointOwner = EndpointOwner(launchId = launchId, file = file, trustedRoot = trustedRoot)
            val next = Active(launchId = launchId, transport = transport)
            active = next
            Install.Installed(next)
        } catch (error: Exception) {
            if (pending == launchId) pending = null
            // Runs under [lock]; only the file this launch just wrote is removed.
            discardEndpointLocked(launchId)
            Install.Failed(error)
        }
    }

    private fun stillPending(launchId: Long): Boolean =
        synchronized(lock) { pending == launchId && launchId > revokedThrough }

    private fun finishPending(launchId: Long) {
        synchronized(lock) {
            if (pending == launchId) pending = null
        }
    }

    /** Must hold [lock]. Deletes the endpoint only when [launchId] (or its own owner) wrote it. */
    private fun discardEndpointLocked(launchId: Long?) {
        val owner = endpointOwner ?: return
        if (launchId != null && owner.launchId != launchId) return
        endpointOwner = null
        PhoneToolEndpoint.delete(owner.file, owner.trustedRoot)
    }

    private sealed class Install {
        class Installed(val active: Active) : Install()

        object Aborted : Install()

        class Failed(val error: Exception) : Install()
    }
}

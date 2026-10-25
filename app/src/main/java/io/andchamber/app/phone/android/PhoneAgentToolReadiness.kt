package io.andchamber.app.phone.android

import io.andchamber.app.runtime.RuntimeState

/**
 * Whether the on-device runtime is up, and whether *this* launch actually loaded the phone tool.
 *
 * Read-only native probes. Neither one grants, connects, publishes a route or changes the runtime.
 */
internal interface PhoneAgentToolReadiness {
    fun runtimePresence(): PhoneRuntimePresence

    /**
     * `true` only while the current launch's phone tool is installed and ready.
     * `null` means "not observable right now"; the UI must not invent a blocker for it.
     */
    fun toolReady(): Boolean?

    /** No probes wired (previews/tests): nothing is known, so no blocker is shown. */
    object Unavailable : PhoneAgentToolReadiness {
        override fun runtimePresence(): PhoneRuntimePresence = PhoneRuntimePresence.UNKNOWN

        override fun toolReady(): Boolean? = null
    }
}

internal enum class PhoneRuntimePresence {
    RUNNING,
    NOT_RUNNING,

    /** Detecting, installing, starting or stopping: the answer is still changing. */
    TRANSITIONING,
    UNKNOWN,
}

/**
 * The one step missing before the agent's phone tool can work. The token is for the designer to map
 * to a localised banner; it is never a user-facing English sentence and never a new permission.
 */
internal enum class PhoneAgentToolBlocker(val token: String) {
    /** The local OpenChamber server is not running, so there is no launch to carry the tool. */
    RUNTIME_NOT_RUNNING("runtime_not_running"),

    /**
     * The server is running but this launch started without the phone tool (the experiment was
     * enabled afterwards). Only a manual restart of the local server registers it.
     */
    RUNTIME_RESTART_REQUIRED("runtime_restart_required"),
}

/**
 * Production probes: the local runtime's state plus the tool binding's read-only readiness.
 *
 * Both lambdas are sampled on every call, so a manual restart of the local server clears the notice
 * without any further action. [toolReadyProbe] is the tool lane's `PhoneToolHost.isReady()`; an
 * exception there is "unknown", never "not ready", so a probe failure cannot invent a restart request.
 */
internal class RuntimePhoneToolReadiness(
    private val runtimeState: () -> RuntimeState,
    private val toolReadyProbe: () -> Boolean,
) : PhoneAgentToolReadiness {
    override fun runtimePresence(): PhoneRuntimePresence =
        when (val state = runtimeState()) {
            is RuntimeState.Running -> PhoneRuntimePresence.RUNNING
            RuntimeState.Detecting,
            is RuntimeState.Installing,
            is RuntimeState.Starting,
            is RuntimeState.Stopping,
            -> PhoneRuntimePresence.TRANSITIONING
            else -> PhoneRuntimePresence.NOT_RUNNING
        }

    override fun toolReady(): Boolean? = runCatching { toolReadyProbe() }.getOrNull()
}

/**
 * Decides which missing step, if any, to show. Nothing here restarts the runtime: the person does
 * that from Home, and the notice clears by itself once the tool is ready.
 */
internal fun agentToolBlocker(
    enabled: Boolean,
    presence: PhoneRuntimePresence,
    toolReady: Boolean?,
): PhoneAgentToolBlocker? =
    when {
        !enabled -> null
        presence == PhoneRuntimePresence.RUNNING ->
            when (toolReady) {
                true -> null
                false -> PhoneAgentToolBlocker.RUNTIME_RESTART_REQUIRED
                null -> null
            }
        presence == PhoneRuntimePresence.NOT_RUNNING -> PhoneAgentToolBlocker.RUNTIME_NOT_RUNNING
        // Transitioning or unknown: wait instead of telling the person to do something wrong.
        else -> null
    }

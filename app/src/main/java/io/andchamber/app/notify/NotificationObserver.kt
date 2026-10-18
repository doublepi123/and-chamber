package io.andchamber.app.notify

import io.andchamber.app.data.LocalApiClient
import io.andchamber.app.runtime.LocalRuntimeController
import io.andchamber.app.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Runs for the whole process: shows AndChamber's own runtime errors, watches OpenCode health while the
 * server runs, and performs the one-time notification bootstrap after the server is ready and signed in.
 */
class NotificationObserver(
    private val runtime: LocalRuntimeController,
    private val notifier: OpenChamberNotifier,
    private val api: LocalApiClient,
    private val bootstrap: NotificationSettingsBootstrap,
    private val watch: RuntimeErrorWatch = RuntimeErrorWatch(),
    private val healthIntervalMillis: Long = 15_000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthJob: Job? = null

    fun start() {
        scope.launch {
            runtime.state.collectLatest { state ->
                watch.onState(state).forEach(notifier::postAlert)
                when (state) {
                    is RuntimeState.Running -> {
                        bootstrapOnce()
                        watchHealth()
                    }
                    is RuntimeState.Stopped, is RuntimeState.NotInstalled -> {
                        // A user Stop is never an alert; just stop watching and clear the dedupe.
                        healthJob?.cancel()
                        watch.reset()
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun bootstrapOnce() {
        runCatching { bootstrap.ensureEnabled() }
    }

    private fun watchHealth() {
        healthJob?.cancel()
        healthJob =
            scope.launch {
                while (true) {
                    delay(healthIntervalMillis)
                    val report = api.getJson(HEALTH_PATH) ?: continue
                    val error = report.text("lastOpenCodeError")
                    val running = report["openCodeRunning"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
                    watch.onHealth(error, running).forEach(notifier::postAlert)
                }
            }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    companion object {
        const val HEALTH_PATH = "/health"
    }
}

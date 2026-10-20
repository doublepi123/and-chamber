package io.andchamber.app.browser

import io.andchamber.app.browser.provider.BrowserProviderClient
import io.andchamber.app.browser.provider.ProviderHttpTransport
import io.andchamber.app.browser.provider.ProviderProtocol
import io.andchamber.app.runtime.process.ProviderPageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * androidTest-only bridge for a host OpenChamber process. The instrumentation supplies the loopback
 * port that `adb reverse` mapped. The token is supplied by the test invocation and is not compiled
 * into the release APK. This is not a production DI path and it does not start a local PRoot runtime.
 */
object ProviderEmulatorBridge {
    fun client(token: String, port: Int, router: ProviderPageRouter): BrowserProviderClient {
        require(token.isNotEmpty()) { "test token required" }
        require(port in 1..65535) { "test port refused" }
        val origin = "http://127.0.0.1:$port"
        require(ProviderProtocol.isLoopbackOrigin(origin)) { "test origin refused" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return BrowserProviderClient(
            origin = origin,
            token = token,
            runtimeLaunchId = 1L,
            instanceKey = "local",
            clock = { android.os.SystemClock.elapsedRealtime() },
            transport = ProviderHttpTransport(origin, token),
            executor = router,
            scope = scope,
        )
    }
}

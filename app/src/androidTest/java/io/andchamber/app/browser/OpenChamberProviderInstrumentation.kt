package io.andchamber.app.browser

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import io.andchamber.app.MainActivity
import io.andchamber.app.runtime.LocalRuntimeController
import io.andchamber.app.runtime.RuntimeInfo
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionAuthenticator
import io.andchamber.app.runtime.process.BrowserProviderSession
import io.andchamber.app.runtime.process.LocalBrowserProviderBinding
import io.andchamber.app.runtime.process.OpenChamberServer
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

/**
 * androidTest-only Koin override. It is not referenced by release source, has no BuildConfig switch,
 * intent extra, system property, or debug menu, and cannot be triggered from the release APK.
 * The override supplies a local Running target so production WebScreen treats the patched console
 * as the trusted local page and production BrowserProviderSession.connect performs the launch.
 */
class OpenChamberProviderInstrumentation : Instrumentation() {
    private var args: Bundle = Bundle()

    override fun onCreate(arguments: Bundle?) {
        args = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        val port = args.getString("port")?.toIntOrNull() ?: 0
        val token = args.getString("token").orEmpty()
        val password = args.getString("password").orEmpty()
        val code = try {
            check(port in 1..65535 && token.length == 64 && password.isNotEmpty()) { "args" }
            loadKoinModules(module {
                single<OpenChamberServer> { ReadyServer(port) }
                single<LocalRuntimeController> { RunningRuntime(port) }
                single<WebSessionAuthenticator> { TestAuthenticator(port, password) }
                // Production presenter; only the page factory wraps the production WebViewClient with
                // the in-memory HTTPS fixture.
                single {
                    io.andchamber.app.ui.browser.NativeBrowserPresenter(
                        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate),
                        sessionFactory = { context ->
                            context?.let { ctx ->
                                io.andchamber.app.ui.browser.BrowserSession.tryCreate(ctx) { io.andchamber.app.browser.BrowserUrlPolicy() }?.let { page ->
                                    OpenChamberProviderFixtures.attach(page.view, page.view.webViewClient)
                                    io.andchamber.app.ui.browser.WebControlledPage(page)
                                }
                            }
                        },
                    )
                }
                single<BrowserProviderSession> {
                    // The host server was started with this test token; production issue() must
                    // produce the same one, so the random source is pinned here (androidTest only).
                    BrowserProviderSession(random = PinnedTokenRandom(token), sink = get<LocalBrowserProviderBinding>())
                }
            })
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            val session = org.koin.core.context.GlobalContext.get().get<BrowserProviderSession>()
            val hostDeadline = android.os.SystemClock.uptimeMillis() + 20_000
            var attached = false
            while (android.os.SystemClock.uptimeMillis() < hostDeadline) {
                attached = session.trustedHostAttached()
                if (attached) break
                Thread.sleep(500)
            }
            if (!attached) {
                writeStatus("host-not-attached")
                error("trusted host was not attached; no coordinate tap is used")
            }
            session.issue(1L)
            session.connect(1L, "http://127.0.0.1:$port", true)
            writeStatus("attached")
            // Stay alive until the host writes files/openchamber-provider-stop, at most 20 minutes.
            val stop = java.io.File(targetContext.filesDir, "openchamber-provider-stop").also { it.delete() }
            val deadline = android.os.SystemClock.uptimeMillis() + 20 * 60_000
            while (android.os.SystemClock.uptimeMillis() < deadline && !stop.exists()) Thread.sleep(500)
            stop.delete()
            writeStatus("finished")
            0
        } catch (error: Throwable) {
            runCatching { writeStatus("failed:${error.javaClass.simpleName}") }
            1
        }
        finish(code, Bundle().apply { putString("summary", if (code == 0) "ATTACHED" else "FAIL") })
    }

    private fun tap(x: Float, y: Float) {
        val downTime = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(downTime, downTime + 50, android.view.MotionEvent.ACTION_UP, x, y, 0)
        sendPointerSync(down)
        sendPointerSync(up)
        down.recycle()
        up.recycle()
    }

    private fun writeStatus(phase: String) {
        val file = java.io.File(targetContext.filesDir, "openchamber-provider-status.json")
        file.writeText("""{"phase":"$phase"}""")
    }
}

private class PinnedTokenRandom(hex: String) : java.security.SecureRandom() {
    private val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    override fun nextBytes(target: ByteArray) {
        check(target.size == bytes.size) { "unexpected token size" }
        bytes.copyInto(target)
    }
}

private class ReadyServer(private val port: Int) : OpenChamberServer {
    override fun start(runtime: io.andchamber.app.runtime.install.InstalledRuntime, uiPassword: String, onLog: (String) -> Unit, isCancelled: () -> Boolean): Long = 1L
    override fun stop() = Unit
    override fun isAlive(): Boolean = true
    override fun readyLaunchId(): Long = 1L
}

private class RunningRuntime(port: Int) : LocalRuntimeController {
    override val state = MutableStateFlow<RuntimeState>(RuntimeState.Running("http://127.0.0.1:$port", port, 0L))
    override val busy = MutableStateFlow(false)
    override val logTail = MutableStateFlow(emptyList<String>())
    override val info = MutableStateFlow<RuntimeInfo?>(null)
    override fun install() = Unit
    override fun start() = Unit
    override fun stop() = Unit
    override fun repair() = Unit
    override fun uninstall() = Unit
    override fun restart() = Unit
    override fun perform(operation: io.andchamber.app.runtime.RuntimeOperation) = Unit
}

/** Logs in to the host test server with the test UI password and stores the cookies in the bound profile. */
private class TestAuthenticator(private val port: Int, private val password: String) : WebSessionAuthenticator {
    override suspend fun prepare(target: ServerTarget, cookies: io.andchamber.app.runtime.WebSessionCookies): io.andchamber.app.runtime.WebSessionResult {
        val base = "http://127.0.0.1:$port"
        val setCookies = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val connection = java.net.URL("$base/auth/session").openConnection() as java.net.HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val body = org.json.JSONObject().put("password", password).toString()
                connection.outputStream.use { it.write(body.toByteArray()) }
                if (connection.responseCode != 200) return@withContext null
                connection.headerFields.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }.flatMap { it.value }
            } finally {
                connection.disconnect()
            }
        } ?: return io.andchamber.app.runtime.WebSessionResult.NeedsLogin(base)
        cookies.storeCookies("$base/", setCookies)
        return io.andchamber.app.runtime.WebSessionResult.Ready(base)
    }
}

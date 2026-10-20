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
    @Volatile private var activity: android.app.Activity? = null

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
                                    HeldControlledPage(io.andchamber.app.ui.browser.WebControlledPage(page), targetContext.filesDir)
                                }
                            }
                        },
                    )
                }
                single {
                    val presenter = get<io.andchamber.app.ui.browser.NativeBrowserPresenter>()
                    val router = io.andchamber.app.runtime.process.ProviderPageRouter(presenter)
                    io.andchamber.app.runtime.process.LocalBrowserProviderBinding(
                        presenter = presenter,
                        router = router,
                        clientFactory = { origin, token, launchId ->
                            io.andchamber.app.browser.provider.BrowserProviderClient(
                                origin = origin,
                                token = token,
                                runtimeLaunchId = launchId,
                                instanceKey = "local",
                                clock = { android.os.SystemClock.elapsedRealtime() },
                                transport = io.andchamber.app.browser.provider.ProviderHttpTransport(origin, token),
                                executor = HoldExecutor(router, targetContext.filesDir),
                                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
                                registry = presenter.registry,
                                onConnection = io.andchamber.app.runtime.process.LocalBrowserProviderBinding.ownConnection(presenter, launchId),
                                onCancel = { requestId -> presenter.cancel(requestId) },
                            )
                        },
                    )
                }
                single<BrowserProviderSession> {
                    // The host server was started with this test token; production issue() must
                    // produce the same one, so the random source is pinned here (androidTest only).
                    BrowserProviderSession(random = PinnedTokenRandom(token), sink = get<LocalBrowserProviderBinding>())
                }
            })
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
            // The trusted console rebinds the host on every committed document. Report ready only
            // once the console has finished loading and the host has stayed attached for a while.
            val presenter = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>()
            val stableDeadline = android.os.SystemClock.uptimeMillis() + 60_000
            var stableSince = 0L
            var lastGeneration = -1L
            while (android.os.SystemClock.uptimeMillis() < stableDeadline) {
                val ready = java.util.concurrent.CompletableFuture<String?>()
                runOnMainSync {
                    val web = activity?.window?.decorView?.let { findWebView(it, presenter.currentView()) }
                    if (web == null) ready.complete(null) else web.evaluateJavascript("document.readyState") { ready.complete(it) }
                }
                val state = runCatching { ready.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()?.trim('"')
                val generation = presenter.state.value.let { if (it.eligible) it.documentEpoch xor it.viewGeneration else -1L }
                val now = android.os.SystemClock.uptimeMillis()
                if (state == "complete" && presenter.state.value.eligible && presenter.state.value.connected && generation == lastGeneration) {
                    if (stableSince == 0L) stableSince = now
                    if (now - stableSince >= 3_000) break
                } else {
                    stableSince = 0L
                }
                lastGeneration = generation
                Thread.sleep(500)
            }
            writeStatus("attached")
            // Stay alive until the host writes files/openchamber-provider-stop, at most 20 minutes.
            val stop = java.io.File(targetContext.filesDir, "openchamber-provider-stop").also { it.delete() }
            val deadline = android.os.SystemClock.uptimeMillis() + 20 * 60_000
            val command = java.io.File(targetContext.filesDir, "openchamber-provider-command")
            while (android.os.SystemClock.uptimeMillis() < deadline && !stop.exists()) {
                val text = if (command.isFile) command.readText() else ""
                try {
                if (text.startsWith("select:")) {
                    val tabId = text.removePrefix("select:").trim()
                    runOnMainSync { evalChat("window.AndChamberBrowserHost && window.AndChamberBrowserHost.select(${org.json.JSONObject.quote(tabId)})") }
                    command.delete()
                    writeStatus("selected")
                }
                if (text == "open-browser") {
                    val opened = java.util.concurrent.CompletableFuture<String>()
                    runOnMainSync {
                        evalChat(
                            """
                            (function(){
                              var button = document.querySelector('[data-context-surface="browser"]')
                                || document.querySelector('[aria-label="Browser"]');
                              if (!button) return 'missing';
                              button.click();
                              return 'clicked';
                            })()
                            """.trimIndent(),
                        ) { opened.complete(it) }
                    }
                    command.delete()
                    writeStatus(if (opened.get(3, java.util.concurrent.TimeUnit.SECONDS)?.contains("clicked") == true) "browser-opened" else "browser-missing")
                }
                if (text.startsWith("eval:")) {
                    // androidTest-only DOM probe of the chat page; the result is written verbatim.
                    val value = java.util.concurrent.CompletableFuture<String?>()
                    runOnMainSync { evalChat(text.removePrefix("eval:")) { value.complete(it) } }
                    command.delete()
                    val raw = runCatching { value.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
                    java.io.File(targetContext.filesDir, "openchamber-provider-status.json")
                        .writeText(org.json.JSONObject().put("phase", "eval").put("value", raw ?: "null").toString())
                }
                if (text == "bridge") {
                    val value = java.util.concurrent.CompletableFuture<String>()
                    runOnMainSync {
                        evalChat("JSON.stringify({tabs:(window.AndChamberBrowserHost&&window.AndChamberBrowserHost.tabs)?window.AndChamberBrowserHost.tabs():null,panel:!!document.querySelector('[data-testid=\"android-browser-host\"]')})") { value.complete(it) }
                    }
                    command.delete()
                    val raw = runCatching { value.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull().orEmpty()
                    // evaluateJavascript returns a JSON string literal; decode it, then tag the phase.
                    val decoded = runCatching { org.json.JSONArray("[$raw]").getString(0) }.getOrNull().orEmpty()
                    val body = runCatching { org.json.JSONObject(decoded) }.getOrNull() ?: org.json.JSONObject().put("tabs", org.json.JSONObject.NULL)
                    java.io.File(targetContext.filesDir, "openchamber-provider-status.json").writeText(body.put("phase", "bridge").toString())
                }
                if (text.startsWith("context:")) {
                    val sessionId = text.removePrefix("context:").trim()
                    runOnMainSync { evalChat("window.AndChamberBrowserHost && window.AndChamberBrowserHost.context({sessionId:${org.json.JSONObject.quote(sessionId)}})") }
                    command.delete()
                    writeStatus("context")
                }
                if (text == "reload") {
                    runOnMainSync { evalChat("location.reload()") }
                    command.delete()
                    writeStatus("reloaded")
                }
                if (text.startsWith("present:")) {
                    val url = text.removePrefix("present:").trim()
                    runOnMainSync { evalChat("window.AndChamberBrowserHost && window.AndChamberBrowserHost.present({url:${org.json.JSONObject.quote(url)},ownerSessionId:'provider-emulator'})") }
                    command.delete()
                    writeStatus("presented")
                }
                if (text == "iframes") {
                    val count = countIframes()
                    java.io.File(targetContext.filesDir, "openchamber-provider-status.json").writeText("""{"phase":"iframes","iframes":$count}""")
                    command.delete()
                }
                } catch (error: java.util.concurrent.TimeoutException) {
                    command.delete()
                    writeStatus("command-timeout")
                }
                Thread.sleep(300)
            }
            stop.delete()
            writeStatus("finished")
            0
        } catch (error: Throwable) {
            android.util.Log.e("OcProviderTest", "instrumentation failed", error)
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

    /** Counts iframes in the production chat page (not the floating page), on the main thread. */
    private fun evalChat(script: String, callback: android.webkit.ValueCallback<String>? = null) {
        val presenter = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>()
        // The chat page lives in the started Activity; the app context has no view tree.
        val root = activity?.window?.decorView
        val web = root?.let { findWebView(it, presenter.currentView()) }
        if (web == null) {
            callback?.onReceiveValue(null)
            return
        }
        web.evaluateJavascript(script, callback)
    }

    private fun countIframes(): Int {
        val presenter = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>()
        val result = java.util.concurrent.CompletableFuture<String?>()
        runOnMainSync {
            val floatView = presenter.currentView()
            val root = activity?.window?.decorView
            val web = root?.let { findWebView(it, floatView) }
            if (web == null) result.complete(null) else web.evaluateJavascript("document.querySelectorAll('iframe').length") { result.complete(it) }
        }
        return runCatching { result.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()?.trim('"')?.toIntOrNull() ?: -1
    }

    private fun findWebView(root: android.view.View, excluded: android.view.View?): android.webkit.WebView? {
        if (root is android.webkit.WebView && root !== excluded) return root
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) {
                findWebView(root.getChildAt(index), excluded)?.let { return it }
            }
        }
        return null
    }

    private fun android.content.Context.findActivityRoot(): android.view.View? {
        var current = this
        while (current is android.content.ContextWrapper) {
            if (current is android.app.Activity) return current.window.decorView
            current = current.baseContext
        }
        return null
    }

    private fun writeStatus(phase: String) {
        val file = java.io.File(targetContext.filesDir, "openchamber-provider-status.json")
        file.writeText("""{"phase":"$phase"}""")
    }
}

private class HeldControlledPage(
    private val page: io.andchamber.app.ui.browser.ControlledBrowserPage,
    private val files: java.io.File,
) : io.andchamber.app.ui.browser.ControlledBrowserPage by page {
    override fun driver(policy: () -> io.andchamber.app.browser.BrowserUrlPolicy): io.andchamber.app.browser.BrowserDriver {
        val real = page.driver(policy)
        return io.andchamber.app.browser.BrowserDriver { request ->
            val reply = real.execute(request)
            val hold = java.io.File(files, "openchamber-provider-dhold")
            val mutation = request.method == io.andchamber.app.browser.BrowserMethod.TYPE ||
                request.method == io.andchamber.app.browser.BrowserMethod.CLICK ||
                request.method == io.andchamber.app.browser.BrowserMethod.SCROLL
            // Hold only after a mutation has run in the page: the action happened, the reply waits.
            if (mutation && hold.isFile) {
                java.io.File(files, "openchamber-provider-status.json").writeText("""{"phase":"dispatched","id":"${request.id}"}""")
                while (hold.isFile) kotlinx.coroutines.delay(200)
            }
            reply
        }
    }
}

private class HoldExecutor(
    private val router: io.andchamber.app.runtime.process.ProviderPageRouter,
    private val files: java.io.File,
) : io.andchamber.app.browser.provider.ProviderExecutor {
    override suspend fun execute(
        request: io.andchamber.app.browser.provider.ProviderRequest,
        registry: io.andchamber.app.browser.provider.ProviderRegistry,
        mode: io.andchamber.app.browser.provider.ProviderMode,
        visibleTabId: String?,
        deadlineElapsed: Long,
        clock: () -> Long,
    ): io.andchamber.app.browser.provider.ProviderResult {
        val hold = java.io.File(files, "openchamber-provider-hold")
        if (hold.isFile) {
            java.io.File(files, "openchamber-provider-status.json").writeText(
                """{"phase":"held","requestId":"${request.requestId}","connectionId":"${request.connectionId}","action":"${request.action}"}""",
            )
            while (hold.isFile) kotlinx.coroutines.delay(200)
        }
        return router.execute(request, registry, mode, visibleTabId, deadlineElapsed, clock)
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

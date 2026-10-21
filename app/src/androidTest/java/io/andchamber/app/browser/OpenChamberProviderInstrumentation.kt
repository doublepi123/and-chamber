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
import org.json.JSONObject
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
    @Volatile private var orientationSaved = false
    private var originalOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    @Volatile private var relay: LoopbackRelay? = null
    // One instrumentation-owned HandlerThread carries every PixelCopy callback. It is quit once at exit, never per page.
    @Volatile private var copyThread: android.os.HandlerThread? = null
    // One recorder for the whole run: it outlives every destroyed page, so a retired view keeps its record.
    private val lifecycleRecorder = LifecycleEventRecorder()
    private val lifecycleCommands by lazy { LifecycleGateCommands(this, lifecycleRecorder) }

    private fun pixelCopyThread(): android.os.HandlerThread {
        copyThread?.let { return it }
        val created = android.os.HandlerThread("oc-provider-pixelcopy").also { it.start() }
        copyThread = created
        return created
    }

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
                                val bridge = io.andchamber.app.ui.browser.PixelCopyBridge(
                                    request = { window, rect, bitmap, listener, handler ->
                                        GeometryGateCounters.lastBitmap = bitmap
                                        // The real PixelCopy callback runs on one instrumentation-owned thread, never on Main.
                                        // The production listener is reached only through handler.post, so Main never blocks.
                                        val background = android.os.Handler(pixelCopyThread().looper)
                                        val wrapped = android.view.PixelCopy.OnPixelCopyFinishedListener { result ->
                                            GeometryGateCounters.completed.incrementAndGet()
                                            val hold = java.io.File(targetContext.filesDir, "openchamber-provider-chold")
                                            if (hold.isFile) {
                                                java.io.File(targetContext.filesDir, "openchamber-provider-capture-held").writeText("held")
                                                val deadline = android.os.SystemClock.uptimeMillis() + 20_000
                                                while (hold.isFile && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
                                            }
                                            handler.post {
                                                GeometryGateCounters.forwarded.incrementAndGet()
                                                listener.onPixelCopyFinished(result)
                                            }
                                        }
                                        android.view.PixelCopy.request(window, rect, bitmap, wrapped, background)
                                        GeometryGateCounters.queued.incrementAndGet()
                                    },
                                    onRequested = {
                                        // Marker only: returns at once, the copy is already queued.
                                        java.io.File(targetContext.filesDir, "openchamber-provider-status.json")
                                            .writeText("""{"phase":"capture-enqueued"}""")
                                    },
                                )
                                io.andchamber.app.ui.browser.BrowserSession.tryCreate(ctx, { io.andchamber.app.browser.BrowserUrlPolicy() }, bridge)?.let { page ->
                                    OpenChamberProviderFixtures.attach(page.view, page.view.webViewClient)
                                    val script = javaClass.classLoader?.getResourceAsStream("lifecycle-recorder.js")?.bufferedReader()?.readText().orEmpty()
                                    check(lifecycleRecorder.attach(page.view, script)) { "document-start recorder unsupported" }
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
            relay = if (args.getString("relay") == "app") LoopbackRelay(port) else null
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
                if (text == "lifecycle-diag" || text.startsWith("gate-finger:")) {
                    val located = java.util.concurrent.CompletableFuture<android.webkit.WebView?>()
                    runOnMainSync {
                        val presenter = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>()
                        located.complete(presenter.currentView() as? android.webkit.WebView)
                    }
                    val status = lifecycleCommands.handle(text, located.get(2, java.util.concurrent.TimeUnit.SECONDS))
                        ?: org.json.JSONObject().put("phase", "gate-missing")
                    command.delete()
                    java.io.File(targetContext.filesDir, "openchamber-provider-status.json").writeText(status.toString())
                }
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
                if (text.startsWith("tap:")) {
                    // Real touch on a chat-page element found by CSS selector: its centre is mapped from
                    // CSS px to screen px through the WebView's on-screen position, then injected as a
                    // MotionEvent, the same input path a finger uses.
                    val selector = text.removePrefix("tap:")
                    val rect = java.util.concurrent.CompletableFuture<String?>()
                    var webLoc = IntArray(2)
                    var scale = 1f
                    runOnMainSync {
                        val presenter = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>()
                        val web = activity?.window?.decorView?.let { findWebView(it, presenter.currentView()) }
                        if (web == null) {
                            rect.complete(null)
                        } else {
                            web.getLocationOnScreen(webLoc)
                            scale = web.resources.displayMetrics.density
                            web.evaluateJavascript(
                                "(function(){var e=document.querySelector(${org.json.JSONObject.quote(selector)});if(!e)return null;var r=e.getBoundingClientRect();var c=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,hit:!!(c&&(e===c||e.contains(c)))});})()",
                            ) { rect.complete(it) }
                        }
                    }
                    command.delete()
                    val raw = runCatching { rect.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
                    val decoded = raw?.let { runCatching { org.json.JSONArray("[$it]").optString(0, "") }.getOrNull() }.orEmpty()
                    val point = runCatching { org.json.JSONObject(decoded) }.getOrNull()
                    if (point == null) {
                        writeStatus("tap-missing")
                    } else {
                        val x = webLoc[0] + point.getDouble("x").toFloat() * scale
                        val y = webLoc[1] + point.getDouble("y").toFloat() * scale
                        tap(x, y)
                        java.io.File(targetContext.filesDir, "openchamber-provider-status.json").writeText(
                            org.json.JSONObject().put("phase", "tapped").put("hit", point.optBoolean("hit")).toString(),
                        )
                    }
                }
                if (text.startsWith("page-eval:")) {
                    // androidTest-only read of the floating page itself (the fixture's own report).
                    val value = java.util.concurrent.CompletableFuture<String?>()
                    runOnMainSync {
                        val presenter = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>()
                        val web = presenter.currentView() as? android.webkit.WebView
                        if (web == null) value.complete(null) else web.evaluateJavascript(text.removePrefix("page-eval:")) { value.complete(it) }
                    }
                    command.delete()
                    val raw = runCatching { value.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
                    java.io.File(targetContext.filesDir, "openchamber-provider-status.json")
                        .writeText(org.json.JSONObject().put("phase", "page-eval").put("value", raw ?: "null").toString())
                }
                if (text.startsWith("finger:")) {
                    // One real finger gesture on screen: "finger:x,y,dx,dy,holdMs,steps". Hold still for
                    // holdMs, then move by (dx,dy) in steps; injected through the input system.
                    val v = text.removePrefix("finger:").split(",").map { it.toFloat() }
                    val (fx, fy, fdx, fdy) = v
                    val hold = v[4].toLong()
                    val steps = v[5].toInt().coerceAtLeast(1)
                    val down = android.os.SystemClock.uptimeMillis()
                    fun send(action: Int, x: Float, y: Float, t: Long) {
                        val e = android.view.MotionEvent.obtain(down, t, action, x, y, 0)
                        e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        sendPointerSync(e)
                        e.recycle()
                    }
                    send(android.view.MotionEvent.ACTION_DOWN, fx, fy, down)
                    if (hold > 0) {
                        Thread.sleep(hold)
                        send(android.view.MotionEvent.ACTION_MOVE, fx, fy, down + hold)
                    }
                    for (i in 1..steps) {
                        Thread.sleep(16)
                        send(android.view.MotionEvent.ACTION_MOVE, fx + fdx * i / steps, fy + fdy * i / steps, android.os.SystemClock.uptimeMillis())
                    }
                    send(android.view.MotionEvent.ACTION_UP, fx + fdx, fy + fdy, android.os.SystemClock.uptimeMillis())
                    command.delete()
                    writeStatus("fingered")
                }
                if (text.startsWith("two-finger-scroll:")) {
                    // Real two-finger touch gesture on the floating page (screen coordinates), injected
                    // through the input system so it reaches the production touch listener.
                    val (sx, sy, dy) = text.removePrefix("two-finger-scroll:").split(",").map { it.toFloat() }
                    val down = android.os.SystemClock.uptimeMillis()
                    fun props() = arrayOf(
                        android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER },
                        android.view.MotionEvent.PointerProperties().apply { id = 1; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER },
                    )
                    fun coords(y: Float) = arrayOf(
                        android.view.MotionEvent.PointerCoords().apply { x = sx - 60f; this.y = y; pressure = 1f; size = 1f },
                        android.view.MotionEvent.PointerCoords().apply { x = sx + 60f; this.y = y; pressure = 1f; size = 1f },
                    )
                    fun send(action: Int, count: Int, y: Float, t: Long) {
                        val e = android.view.MotionEvent.obtain(down, t, action, count, props(), coords(y), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                        sendPointerSync(e)
                        e.recycle()
                    }
                    send(android.view.MotionEvent.ACTION_DOWN, 1, sy, down)
                    send(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, sy, down + 10)
                    val steps = 8
                    for (i in 1..steps) send(android.view.MotionEvent.ACTION_MOVE, 2, sy + dy * i / steps, down + 20 + i * 16L)
                    send(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, sy + dy, down + 200)
                    send(android.view.MotionEvent.ACTION_UP, 1, sy + dy, down + 210)
                    command.delete()
                    writeStatus("scrolled")
                }
                if (text.startsWith("mouse-exp:")) {
                    // androidTest-only experiment: which MotionEvent shapes does this WebView turn into
                    // pointerdown/up/click? Variant letters: a=touch path only, b=touch + button press via
                    // generic path, c=generic button press only.
                    val parts = text.removePrefix("mouse-exp:").split(",")
                    val variant = parts[0]
                    val x = parts[1].toFloat()
                    val y = parts[2].toFloat()
                    runOnMainSync {
                        val presenter = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>()
                        val web = presenter.currentView() ?: return@runOnMainSync
                        val now = android.os.SystemClock.uptimeMillis()
                        val props = arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE })
                        val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
                        fun ev(action: Int, buttons: Int) = android.view.MotionEvent.obtain(now, now, action, 1, props, coords, 0, buttons, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
                        fun withActionButton(e: android.view.MotionEvent): android.view.MotionEvent {
                            runCatching { android.view.MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType).invoke(e, android.view.MotionEvent.BUTTON_PRIMARY) }
                                .onFailure { android.util.Log.w("OcProviderTest", "setActionButton ${it.javaClass.simpleName}") }
                            return e
                        }
                        val primary = android.view.MotionEvent.BUTTON_PRIMARY
                        when (variant) {
                            "a" -> { web.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, primary)); web.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, 0)) }
                            "b" -> {
                                web.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, primary))
                                web.dispatchGenericMotionEvent(withActionButton(ev(android.view.MotionEvent.ACTION_BUTTON_PRESS, primary)))
                                web.dispatchGenericMotionEvent(withActionButton(ev(android.view.MotionEvent.ACTION_BUTTON_RELEASE, 0)))
                                web.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, 0))
                            }
                            "c" -> {
                                web.dispatchGenericMotionEvent(withActionButton(ev(android.view.MotionEvent.ACTION_BUTTON_PRESS, primary)))
                                web.dispatchGenericMotionEvent(withActionButton(ev(android.view.MotionEvent.ACTION_BUTTON_RELEASE, 0)))
                            }
                            "d" -> {
                                web.onTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, primary))
                                web.onTouchEvent(ev(android.view.MotionEvent.ACTION_UP, 0))
                            }
                        }
                    }
                    command.delete()
                    writeStatus("mouse-exp")
                }
                if (text.startsWith("orientation:")) {
                    val mode = text.removePrefix("orientation:").trim()
                    val requested = if (mode == "landscape") android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    val result = java.util.concurrent.CompletableFuture<org.json.JSONObject>()
                    runOnMainSync {
                        val current = activity
                        if (current == null) result.complete(org.json.JSONObject().put("error", "no-activity")) else {
                            if (!orientationSaved) {
                                originalOrientation = current.requestedOrientation
                                orientationSaved = true
                            }
                            current.requestedOrientation = requested
                            val wanted = if (requested == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
                            val started = android.os.SystemClock.uptimeMillis()
                            var stable = 0
                            fun poll() {
                                val view = current.window.decorView
                                val matches = current.resources.configuration.orientation == wanted && ((wanted == android.content.res.Configuration.ORIENTATION_LANDSCAPE && view.width > view.height) || (wanted == android.content.res.Configuration.ORIENTATION_PORTRAIT && view.height > view.width))
                                stable = if (matches) stable + 1 else 0
                                if (stable >= 2) result.complete(org.json.JSONObject().put("orientation", if (wanted == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait").put("width", view.width).put("height", view.height).put("stableFrames", stable))
                                else if (android.os.SystemClock.uptimeMillis() - started > 5000) result.complete(org.json.JSONObject().put("error", "orientation-timeout"))
                                else view.postOnAnimation { poll() }
                            }
                            current.window.decorView.postOnAnimation { poll() }
                        }
                    }
                    command.delete()
                    val body = runCatching { result.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { org.json.JSONObject().put("error", "orientation-timeout") }
                    java.io.File(targetContext.filesDir, "openchamber-provider-status.json").writeText(body.put("phase", "oriented").toString())
                }
                if (text.startsWith("read-label:")) {
                    val label = text.removePrefix("read-label:")
                    val value = java.util.concurrent.CompletableFuture<String>()
                    runOnMainSync {
                        val web = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>().currentView() as? android.webkit.WebView
                        if (web == null) value.complete("null") else web.evaluateJavascript("(function(){var n=document.querySelector('[aria-label='+JSON.stringify(${org.json.JSONObject.quote(label)})+']');return JSON.stringify(n&&'value'in n?n.value:null)})()") { value.complete(it) }
                    }
                    val raw = runCatching { value.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
                    val decoded = runCatching { org.json.JSONArray("[$raw]").optString(0) }.getOrNull()
                    command.delete()
                    java.io.File(targetContext.filesDir, "openchamber-provider-status.json").writeText(org.json.JSONObject().put("phase", "label-read").put("value", org.json.JSONArray("[$decoded]").optString(0)).toString())
                }
                if (text == "fixture-state") {
                    val out = java.util.concurrent.CompletableFuture<org.json.JSONObject>()
                    runOnMainSync {
                        val presenter = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>()
                        val state = presenter.state.value
                        val web = presenter.currentView() as? android.webkit.WebView
                        val active = state.tabs.firstOrNull { it.active }
                        val native = org.json.JSONObject()
                            .put("selectedTabId", state.selectedTabId)
                            .put("owner", active?.owner)
                            .put("viewHandle", state.viewHandle)
                            .put("viewGeneration", state.viewGeneration)
                            .put("documentEpoch", state.documentEpoch)
                            .put("shown", state.shown)
                            .put("pageReady", state.pageReady)
                            .put("mouseMode", state.mouseMode)
                            .put("agentOn", state.agentOn)
                            .put("mode", state.mode.name)
                            .put("error", when (state.pageError) {
                                null, "" -> "none"
                                "reopen-required", "mode-reopen-required", "Re-open this page before allowing control" -> "reopen-required"
                                else -> "other"
                            })
                        if (web != null) {
                            native.put("hasFocus", web.hasFocus())
                            native.put("hasWindowFocus", web.hasWindowFocus())
                            val frame = IntArray(2)
                            web.getLocationOnScreen(frame)
                            native.put("physicalFrame", org.json.JSONObject().put("left", frame[0]).put("top", frame[1]).put("width", web.width).put("height", web.height))
                        }
                        if (web == null) out.complete(org.json.JSONObject().put("page", org.json.JSONObject.NULL).put("native", native)) else {
                            web.evaluateJavascript("(function(){var n=document.querySelector('[aria-label=login-user]');var a=document.activeElement;return JSON.stringify({url:location.href,ready:document.readyState,inputValue:n?n.value:null,formPresent:!!document.querySelector('form'),posts:window.__posts||0,loads:window.__loads||0,clicks:window.__clicks||0,submits:window.__submits||0,inputFocused:!!n&&a===n,activeTag:a?a.tagName:null,selectionStart:n&&a===n?n.selectionStart:null,selectionEnd:n&&a===n?n.selectionEnd:null})})()") { raw ->
                                val page = runCatching { org.json.JSONObject(org.json.JSONArray("[$raw]").optString(0)) }.getOrNull()
                                val allowed = page?.optString("url") in setOf(OpenChamberProviderFixtures.ORIGIN, OpenChamberProviderFixtures.ORIGIN2, OpenChamberProviderFixtures.LOGIN_URL, OpenChamberProviderFixtures.LOGIN_SUBMIT_URL, OpenChamberProviderFixtures.WELCOME_URL, OpenChamberProviderFixtures.MOUSE_URL)
                                out.complete(org.json.JSONObject()
                                    .put("page", if (allowed) page else org.json.JSONObject().put("error", "not-fixture"))
                                    .put("native", native)
                                    .put("postArrivals", OpenChamberProviderFixtures.postArrivals)
                                    .put("ime", org.json.JSONObject.NULL))
                            }
                        }
                    }
                    command.delete()
                    val body = runCatching { out.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { org.json.JSONObject().put("error", "fixture-state-timeout") }
                    java.io.File(targetContext.filesDir, "openchamber-provider-status.json").writeText(body.put("phase", "fixture-state").toString())
                }
                if (text.startsWith("tap-label:")) {
                    val label = text.removePrefix("tap-label:")
                    val point = java.util.concurrent.CompletableFuture<org.json.JSONObject>()
                    runOnMainSync {
                        val web = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>().currentView() as? android.webkit.WebView
                        if (web == null) point.complete(org.json.JSONObject().put("error", "no-webview")) else {
                            val loc = IntArray(2); web.getLocationOnScreen(loc)
                            val before = "${web.width}x${web.height}@${loc[0]},${loc[1]}"
                            web.evaluateJavascript("(function(){${tapLabelScript(context)};return JSON.stringify(tapLabelPoint(${org.json.JSONObject.quote(label)}))})()") { raw ->
                                val afterLoc = IntArray(2); web.getLocationOnScreen(afterLoc)
                                val after = "${web.width}x${web.height}@${afterLoc[0]},${afterLoc[1]}"
                                val decoded = runCatching { org.json.JSONObject(org.json.JSONArray("[$raw]").optString(0)) }.getOrNull()
                                val state = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>().state.value
                                val miss = org.json.JSONObject()
                                    .put("beforeFrame", before).put("afterFrame", after)
                                    .put("viewHandle", state.viewHandle).put("documentEpoch", state.documentEpoch)
                                    .put("visible", false).put("hit", false).put("sent", false)
                                if (decoded == null) point.complete(miss.put("reason", "eval-malformed").put("raw", raw ?: org.json.JSONObject.NULL))
                                else if (decoded.optString("error") == "no-label") point.complete(miss.put("reason", "no-label").put("decoded", decoded))
                                else if (before != after) point.complete(miss.put("reason", "native-frame-changed").put("decoded", decoded))
                                else if (decoded.optBoolean("pointVisible") != true || decoded.optBoolean("hit") != true) point.complete(miss.put("reason", "label-outside-viewport").put("decoded", decoded))
                                else {
                                    val mapped = cssClientToScreen(afterLoc[0], afterLoc[1], web.width, decoded.getDouble("visualWidth"), decoded.getDouble("x"), decoded.getDouble("y"), decoded.getDouble("offsetLeft"), decoded.getDouble("offsetTop"))
                                    point.complete(decoded.put("sx", mapped.first).put("sy", mapped.second).put("scale", web.width / decoded.getDouble("visualWidth")))
                                }
                            }
                        }
                    }
                    val found = runCatching { point.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { org.json.JSONObject().put("error", "tap-timeout") }
                    val sent = found.optBoolean("hit") && found.has("sx")
                    if (sent) tap(found.getDouble("sx").toFloat(), found.getDouble("sy").toFloat())
                    command.delete()
                    java.io.File(targetContext.filesDir, "openchamber-provider-status.json").writeText(found.put("phase", if (sent) "tapped" else "tap-missing").put("sent", sent).toString())
                }
                if (text.startsWith("pinch:")) {
                    val (px, py) = text.removePrefix("pinch:").split(",").map { it.toFloat() }
                    val down = android.os.SystemClock.uptimeMillis()
                    fun props() = arrayOf(
                        android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER },
                        android.view.MotionEvent.PointerProperties().apply { id = 1; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER },
                    )
                    fun coords(spread: Float) = arrayOf(
                        android.view.MotionEvent.PointerCoords().apply { x = px - spread; y = py; pressure = 1f; size = 1f },
                        android.view.MotionEvent.PointerCoords().apply { x = px + spread; y = py; pressure = 1f; size = 1f },
                    )
                    fun send(action: Int, count: Int, spread: Float, t: Long) {
                        val event = android.view.MotionEvent.obtain(down, t, action, count, props(), coords(spread), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                        sendPointerSync(event)
                        event.recycle()
                    }
                    send(android.view.MotionEvent.ACTION_DOWN, 1, 40f, down)
                    send(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 40f, down + 20)
                    send(android.view.MotionEvent.ACTION_MOVE, 2, 140f, down + 80)
                    send(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 140f, down + 120)
                    send(android.view.MotionEvent.ACTION_UP, 1, 140f, down + 140)
                    command.delete()
                    writeStatus("pinched")
                }
                if (text.startsWith("page-rect")) {
                    // Where the floating page sits on screen, plus read-only geometry. No grant and no injection.
                    val out = java.util.concurrent.CompletableFuture<String>()
                    runOnMainSync {
                        val presenter = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>()
                        val v = presenter.currentView() as? android.webkit.WebView
                        if (v == null) out.complete("{}") else {
                            val loc = IntArray(2); v.getLocationOnScreen(loc)
                            val script = "(function(){var g=window.__geometry?window.__geometry():{innerWidth:innerWidth,innerHeight:innerHeight,clientWidth:document.documentElement.clientWidth,clientHeight:document.documentElement.clientHeight,media1000:matchMedia('(min-width:1000px)').matches,viewport:[].map.call(document.querySelectorAll('meta[name=viewport]'),function(m){return m.content}),visualViewport:visualViewport?{width:visualViewport.width,height:visualViewport.height,offsetLeft:visualViewport.offsetLeft,offsetTop:visualViewport.offsetTop,scale:visualViewport.scale}:null,devicePixelRatio:devicePixelRatio,scrollX:scrollX,scrollY:scrollY};return JSON.stringify(g)})()"
                            v.evaluateJavascript(script) { raw ->
                                val page = runCatching { org.json.JSONObject(org.json.JSONArray("[$raw]").optString(0)) }.getOrDefault(org.json.JSONObject())
                                val pointer = presenter.state.value.pointerUi
                                val native = org.json.JSONObject()
                                    .put("viewHandle", pointer.viewHandle)
                                    .put("geometryEpoch", pointer.geometryEpoch)
                                    .put("pointerViewX", pointer.pointerViewX)
                                    .put("pointerViewY", pointer.pointerViewY)
                                    .put("geometryReady", pointer.geometryReady)
                                out.complete(org.json.JSONObject()
                                    .put("x", loc[0]).put("y", loc[1]).put("w", v.width).put("h", v.height)
                                    .put("physical", org.json.JSONObject().put("left", loc[0]).put("top", loc[1]).put("width", v.width).put("height", v.height))
                                    .put("page", page)
                                    .put("pageGrant", false)
                                    .put("viewHandle", presenter.state.value.viewHandle)
                                    .put("documentEpoch", presenter.state.value.documentEpoch)
                                    .put("pointerUi", native)
                                    .toString())
                            }
                        }
                    }
                    command.delete()
                    val body = org.json.JSONObject(runCatching { out.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault("{}")).put("phase", "page-rect")
                    java.io.File(targetContext.filesDir, "openchamber-provider-status.json").writeText(body.toString())
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
                if (text == "composer-layout") {
                    val result = org.json.JSONObject().put("phase", "composer-layout")
                    runOnMainSync {
                        val presenter = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>()
                        val web = activity?.window?.decorView?.let { findWebView(it, presenter.currentView()) }
                        val snapshot = web?.tag as? io.andchamber.app.ui.web.ComposerLayoutSnapshot
                        if (web != null) {
                            val xy = IntArray(2)
                            web.getLocationOnScreen(xy)
                            val frame = android.graphics.Rect()
                            web.getWindowVisibleDisplayFrame(frame)
                            result.put("mainWebView", org.json.JSONObject().put("x", xy[0]).put("y", xy[1])
                                .put("width", web.width).put("height", web.height))
                            result.put("usableFrame", org.json.JSONObject().put("left", frame.left).put("top", frame.top)
                                .put("right", frame.right).put("bottom", frame.bottom))
                        }
                        if (snapshot == null) result.put("validityReason", "no-layout-snapshot") else {
                            result.put("consoleGeneration", snapshot.consoleGeneration).put("nativeGeneration", snapshot.viewGeneration)
                                .put("contentWidth", snapshot.contentWidth).put("contentHeight", snapshot.contentHeight)
                                .put("selectedClearancePx", snapshot.selectedClearancePx).put("fallback", snapshot.fallback)
                                .put("validityReason", snapshot.validityReason)
                            snapshot.measurement?.let { m ->
                                result.put("measurement", org.json.JSONObject().put("consoleGeneration", m.consoleGeneration)
                                    .put("nativeGeneration", m.viewGeneration).put("nativeWidth", m.nativeWidth).put("nativeHeight", m.nativeHeight)
                                    .put("top", m.metrics.top).put("bottom", m.metrics.bottom)
                                    .put("viewportWidth", m.metrics.viewportWidth).put("viewportHeight", m.metrics.viewportHeight)
                                    .put("visible", m.metrics.visible))
                            }
                        }
                    }
                    command.delete()
                    java.io.File(targetContext.filesDir, "openchamber-provider-status.json").writeText(result.toString())
                }
                if (text == "native-state") {
                    val state = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>().state.value
                    val error = when {
                        state.pageError.isNullOrBlank() -> "none"
                        state.pageError == "reopen-required" || state.pageError == "mode-reopen-required" || state.pageError == "Re-open this page before allowing control" -> "reopen-required"
                        state.pageError.contains("keyboard", true) -> "tight"
                        else -> "other"
                    }
                    java.io.File(targetContext.filesDir, "openchamber-provider-status.json").writeText(org.json.JSONObject()
                        .put("phase", "native-state")
                        .put("orientation", if (activity?.resources?.configuration?.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait")
                        .put("shown", state.shown)
                        .put("eligible", state.eligible)
                        .put("paused", state.paused)
                        .put("connected", state.connected)
                        .put("pageReady", state.pageReady)
                        .put("desktopMode", state.desktopMode)
                        .put("mouseMode", state.mouseMode)
                        .put("agentOn", state.agentOn)
                        .put("mode", state.mode.name)
                        .put("viewHandle", state.viewHandle)
                        .put("viewGeneration", state.viewGeneration)
                        .put("pointerUi", org.json.JSONObject().put("viewHandle", state.pointerUi.viewHandle).put("geometryEpoch", state.pointerUi.geometryEpoch).put("geometryReady", state.pointerUi.geometryReady))
                        .put("tabCount", state.tabs.size)
                        .put("pageError", error)
                        .toString())
                    command.delete()
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
                    val sessionId = text.removePrefix("context:")
                    runOnMainSync { evalChat("window.AndChamberBrowserHost && window.AndChamberBrowserHost.context({sessionId:${org.json.JSONObject.quote(sessionId)}})") }
                    command.delete()
                    writeStatus("context")
                }
                if (text.startsWith("navigate:")) {
                    val sessionId = text.removePrefix("navigate:").trim()
                    runOnMainSync { evalChat("location.assign('/?session=' + ${org.json.JSONObject.quote(sessionId)})") }
                    command.delete()
                    writeStatus("navigated")
                }
                if (text.startsWith("open-entry:")) {
                    val url = text.removePrefix("open-entry:").trim()
                    // React mounts the drawer and the tab after each click, so every step is its own
                    // script with a wait for the next element instead of one synchronous click chain.
                    fun step(script: String): String {
                        val out = java.util.concurrent.CompletableFuture<String>()
                        runOnMainSync { evalChat(script) { out.complete(it) } }
                        return runCatching { out.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull().orEmpty()
                    }
                    fun waitFor(selector: String): Boolean {
                        val deadline = android.os.SystemClock.uptimeMillis() + 8_000
                        while (android.os.SystemClock.uptimeMillis() < deadline) {
                            if (step("!!document.querySelector(${org.json.JSONObject.quote(selector)})").contains("true")) return true
                            Thread.sleep(250)
                        }
                        return false
                    }
                    fun click(selector: String): Boolean =
                        waitFor(selector) && step("(function(){var e=document.querySelector(${org.json.JSONObject.quote(selector)});if(!e)return 'missing';e.click();return 'clicked';})()").contains("clicked")
                    val outcome = when {
                        !click("[aria-label=\"Open workspace panel\"]") -> "missing-drawer"
                        !click("[data-testid=\"mobile-workspace-tab-browser\"]") -> "missing-tab"
                        !waitFor("[data-testid=\"android-browser-address\"]") -> "missing-address"
                        else -> {
                            step(
                                """
                                (function(){
                                  var input = document.querySelector('[data-testid="android-browser-address"]');
                                  var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                                  setter.call(input, ${org.json.JSONObject.quote(url)});
                                  input.dispatchEvent(new Event('input', {bubbles:true}));
                                  return 'filled';
                                })()
                                """.trimIndent(),
                            )
                            Thread.sleep(300)
                            if (click("[data-testid=\"android-browser-open\"]")) "opened" else "missing-open"
                        }
                    }
                    val value = java.util.concurrent.CompletableFuture<String>().also { it.complete(outcome) }
                    command.delete()
                    val raw = runCatching { value.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull().orEmpty()
                    if (raw.contains("opened")) {
                        val tabs = java.util.concurrent.CompletableFuture<String>()
                        runOnMainSync { evalChat("JSON.stringify((window.AndChamberBrowserHost&&window.AndChamberBrowserHost.tabs)?window.AndChamberBrowserHost.tabs():null)") { tabs.complete(it) } }
                        val decoded = runCatching { org.json.JSONArray("[${tabs.get(5, java.util.concurrent.TimeUnit.SECONDS)}]").getString(0) }.getOrNull().orEmpty()
                        val body = org.json.JSONObject().put("phase", "entry-opened").put("result", "opened")
                        body.put("tabs", runCatching { org.json.JSONArray(decoded) }.getOrElse { org.json.JSONArray() })
                        java.io.File(targetContext.filesDir, "openchamber-provider-status.json").writeText(body.toString())
                    } else {
                        writeStatus("entry-${raw.trim('"')}")
                    }
                }
                if (text == "relay-stop") {
                    relay?.stop()
                    command.delete()
                    writeStatus("relay-stopped")
                }
                if (text == "relay-start") {
                    relay = LoopbackRelay(port)
                    command.delete()
                    writeStatus("relay-started")
                }
                if (text == "reload") {
                    runOnMainSync { evalChat("location.reload()") }
                    command.delete()
                    writeStatus("reloaded")
                }
                if (text == "geometry-diag" || text.startsWith("marker:") || text.startsWith("marker-clear:") || text == "frame-offset" || text == "frame-restore") {
                    handleGeometryDiagnostic(text)
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
            if (orientationSaved) runOnMainSync { activity?.requestedOrientation = originalOrientation }
            stop.delete()
            relay?.stop()
            copyThread?.quitSafely()
            copyThread = null
            writeStatus("finished")
            0
        } catch (error: Throwable) {
            android.util.Log.e("OcProviderTest", "instrumentation failed", error)
            runCatching { writeStatus("failed:${error.javaClass.simpleName}") }
            1
        }
        finish(code, Bundle().apply { putString("summary", if (code == 0) "ATTACHED" else "FAIL") })
    }

    private fun handleGeometryDiagnostic(text: String) {
        val command = java.io.File(targetContext.filesDir, "openchamber-provider-command")
        val files = targetContext.filesDir
        if (text.startsWith("marker:") || text.startsWith("marker-clear:")) {
            val clearing = text.startsWith("marker-clear:")
            val name = text.removePrefix(if (clearing) "marker-clear:" else "marker:").trim()
            val allowed = setOf("dhold", "fhold", "shold", "chold")
            if (name !in allowed) {
                command.delete()
                writeStatus("marker-refused")
                return
            }
            val marker = java.io.File(files, "openchamber-provider-$name")
            if (clearing) marker.delete() else marker.writeText("held")
            command.delete()
            writeStatus(if (clearing) "marker-cleared" else "marker-set")
            return
        }
        if (text == "frame-offset" || text == "frame-restore") {
            // Frame-only offset of the floating window's view. The frame guard, not PixelCopy, must refuse it.
            val delta = if (text == "frame-offset") 8f else -8f
            val moved = java.util.concurrent.CompletableFuture<Boolean>()
            runOnMainSync {
                val presenter = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>()
                val view = presenter.currentView()
                if (view != null) view.translationX += delta
                moved.complete(view != null)
            }
            val applied = runCatching { moved.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
            command.delete()
            val phase = if (text == "frame-offset") "frame-offset" else "frame-restored"
            java.io.File(files, "openchamber-provider-status.json").writeText("""{"phase":"$phase","applied":$applied}""")
            return
        }
        val presenter = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>()
        val state = presenter.state.value
        val diag = java.util.concurrent.CompletableFuture<org.json.JSONObject>()
        runOnMainSync { diag.complete(readDiagOnMain(presenter, state, files)) }
        val body = runCatching { diag.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { org.json.JSONObject().put("phase", "geometry-diag").put("error", "timeout") }
        command.delete()
        java.io.File(files, "openchamber-provider-status.json").writeText(body.toString())
    }

    /**
     * Read-only snapshot on Main. No token, nonce, or connection id is copied. Private presenter state
     * (pages, selectedTabId) is read by reflection because this is an androidTest-only probe.
     */
    private fun readDiagOnMain(
        presenter: io.andchamber.app.ui.browser.NativeBrowserPresenter,
        state: io.andchamber.app.ui.browser.NativeBrowserState,
        files: java.io.File,
    ): org.json.JSONObject {
        val bitmap = GeometryGateCounters.lastBitmap
        // Only the selected tab's own bound page is read. A stale unknown page from an earlier open is not the selected one.
        // A reflection failure is null, never false: the Node side refuses a null field.
        val selected = runCatching { reflectStrict(presenter, "selectedTabId") as? String }.getOrNull()?.takeIf { it.isNotEmpty() }
        val selectedBound = selected?.let { id ->
            runCatching { (reflectStrict(presenter, "pages") as? Map<*, *>)?.get(id) }.getOrNull()
        }
        val boundTabId = selectedBound?.let { runCatching { (reflectStrict(it, "page") as? io.andchamber.app.ui.browser.ControlledBrowserPage)?.tabId }.getOrNull() }
        val boundUnknown = selectedBound?.let { runCatching { reflectStrict(it, "outcomeUnknown") as? Boolean }.getOrNull() }
        val outcomeUnknown = GeometryDiagPolicy.outcomeUnknownFor(selected, boundTabId, boundUnknown)
        val grant = presenter.registry.currentGrant()
        val owner = selected?.let { presenter.registry.find(it)?.ownerSessionId }
        return org.json.JSONObject()
            .put("phase", "geometry-diag")
            .put("agentOn", state.agentOn)
            .put("registryGrantPresent", grant != null)
            .put("grantForSelected", GeometryDiagPolicy.grantForSelected(selected, grant?.tabId, grant?.sessionId, owner) ?: JSONObject.NULL)
            .put("selectedTabId", selected ?: JSONObject.NULL)
            .put("mode", state.mode.name)
            .put("pageError", state.pageError ?: "none")
            .put("outcomeUnknown", outcomeUnknown ?: JSONObject.NULL)
            .put("currentOwner", owner ?: "none")
            .put("dispatchCount", GeometryGateCounters.dispatched.get())
            .put("pageReady", state.pageReady)
            .put("eligible", state.eligible)
            .put("connected", state.connected)
            .put("hostAttached", state.hostAttached)
            .put("documentEpoch", state.documentEpoch)
            .put("viewHandle", state.viewHandle)
            .put("viewGeneration", state.viewGeneration)
            .put("geometryEpoch", state.pointerUi.geometryEpoch)
            .put("pixelCopyQueued", GeometryGateCounters.queued.get())
            .put("pixelCopyCompleted", GeometryGateCounters.completed.get())
            .put("pixelCopyForwarded", GeometryGateCounters.forwarded.get())
            .put("bitmapRecycled", bitmap?.isRecycled ?: false)
            .put("dhold", java.io.File(files, "openchamber-provider-dhold").isFile)
            .put("fhold", java.io.File(files, "openchamber-provider-fhold").isFile)
            .put("shold", java.io.File(files, "openchamber-provider-shold").isFile)
            .put("chold", java.io.File(files, "openchamber-provider-chold").isFile)
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

/** androidTest-only counters for the geometry gate. They are written by instrumented code paths and only read by geometry-diag. */
internal object GeometryGateCounters {
    val dispatched = java.util.concurrent.atomic.AtomicLong(0)
    val queued = java.util.concurrent.atomic.AtomicLong(0)
    val completed = java.util.concurrent.atomic.AtomicLong(0)
    val forwarded = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile var lastBitmap: android.graphics.Bitmap? = null
}

private fun reflectField(target: Any, name: String): Any? = runCatching {
    val field = target.javaClass.getDeclaredField(name)
    field.isAccessible = true
    field.get(target)
}.getOrNull()

/** Same as reflectField, but a missing field throws instead of returning null silently. */
private fun reflectStrict(target: Any, name: String): Any? {
    val field = target.javaClass.getDeclaredField(name)
    field.isAccessible = true
    return field.get(target)
}



/** One hold lasts at most this long. Each hold has its own deadline, outside the whole action budget. */
internal const val DISPATCH_HOLD_MS = 20_000L

private class HeldControlledPage(
    private val page: io.andchamber.app.ui.browser.ControlledBrowserPage,
    private val files: java.io.File,
) : io.andchamber.app.ui.browser.ControlledBrowserPage by page {
    override suspend fun currentField(ref: String): io.andchamber.app.browser.provider.PageField? {
        val field = page.currentField(ref) ?: return null
        val fieldHold = java.io.File(files, "openchamber-provider-fhold")
        if (fieldHold.isFile) {
            java.io.File(files, "openchamber-provider-status.json").writeText("""{"phase":"field-held","ref":"$ref"}""")
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            while (fieldHold.isFile && android.os.SystemClock.elapsedRealtime() < deadline) kotlinx.coroutines.delay(200)
        }
        return field
    }

    override fun driver(policy: () -> io.andchamber.app.browser.BrowserUrlPolicy): io.andchamber.app.browser.BrowserDriver {
        val real = page.driver(policy)
        return io.andchamber.app.browser.BrowserDriver { request ->
            val reply = real.execute(request)
            val snapshotHold = java.io.File(files, "openchamber-provider-shold")
            if (request.method == io.andchamber.app.browser.BrowserMethod.SNAPSHOT && snapshotHold.isFile) {
                java.io.File(files, "openchamber-provider-status.json").writeText("""{"phase":"snapshot-held","id":"${request.id}"}""")
                val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
                while (snapshotHold.isFile && android.os.SystemClock.elapsedRealtime() < deadline) kotlinx.coroutines.delay(200)
            }
            val hold = java.io.File(files, "openchamber-provider-dhold")
            val mutation = request.method == io.andchamber.app.browser.BrowserMethod.TYPE ||
                request.method == io.andchamber.app.browser.BrowserMethod.CLICK ||
                request.method == io.andchamber.app.browser.BrowserMethod.SCROLL
            // Hold only after a mutation has run in the page: the action happened, the reply waits.
            if (mutation) GeometryGateCounters.dispatched.incrementAndGet()
            if (mutation && hold.isFile) {
                java.io.File(files, "openchamber-provider-status.json").writeText("""{"phase":"dispatched","id":"${request.id}"}""")
                // Independent 20 s deadline for this hold only. On expiry the real reply is returned once,
                // the timeout phase is recorded, and nothing is retried or replayed.
                val deadline = android.os.SystemClock.elapsedRealtime() + DISPATCH_HOLD_MS
                while (hold.isFile && android.os.SystemClock.elapsedRealtime() < deadline) kotlinx.coroutines.delay(200)
                if (hold.isFile) {
                    java.io.File(files, "openchamber-provider-status.json").writeText("""{"phase":"dispatch-hold-timeout","id":"${request.id}"}""")
                }
            }
            reply
        }
    }
}

private fun tapLabelScript(testContext: android.content.Context): String =
    testContext.assets.open("tap-label.js").bufferedReader().use { it.readText() }

internal fun cssClientToScreen(screenLeft: Int, screenTop: Int, viewWidth: Int, visualWidth: Double, clientX: Double, clientY: Double, offsetLeft: Double, offsetTop: Double): Pair<Double, Double> {
    val scale = viewWidth / visualWidth
    return screenLeft + (clientX - offsetLeft) * scale to screenTop + (clientY - offsetTop) * scale
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
    override val updates = MutableStateFlow(io.andchamber.app.runtime.RuntimeUpdateState())
    override fun install() = Unit
    override fun start() = Unit
    override fun stop() = Unit
    override fun repair() = Unit
    override fun uninstall() = Unit
    override fun restart() = Unit
    override fun checkForUpdates() = Unit
    override fun update() = Unit
    override fun dismissUpdateFailure() = Unit
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

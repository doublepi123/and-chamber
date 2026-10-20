@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.andchamber.app.browser

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import io.andchamber.app.MainActivity
import io.andchamber.app.ui.browser.BrowserDriverLogic
import io.andchamber.app.ui.browser.BrowserPageDriver
import io.andchamber.app.ui.browser.BrowserSession
import io.andchamber.app.ui.browser.ProbeAcceptance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test-only file bridge to the production controller and page driver.
 * The default smoke runner does not enter this path. Finish reports BRIDGE_FINISHED, not host PASS.
 */
internal class BrowserMcpBridgeHarness(
    private val instrumentation: Instrumentation,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val finished = AtomicBoolean(false)
    private val runId = UUID.randomUUID().toString()
    private var activity: MainActivity? = null
    private var host: FrameLayout? = null
    private var pages: PageHolder? = null
    private var controller: BrowserControllerImpl? = null
    private var server: BrowserRpcServer? = null
    private var phase = "disabled"
    private var lastCommandId: String? = null
    private var port: Int? = null
    private var error: String? = null

    fun run() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "bridge runner must stay off the main looper" }
        try {
            runBlocking {
                withTimeout(DEADLINE_MS) {
                    prepare()
                    while (!finished.get()) {
                        pollCommand()
                        delay(POLL_MS)
                    }
                }
            }
        } catch (error: Throwable) {
            fail(error.javaClass.simpleName)
        } finally {
            if (!finished.get()) runBlocking { finishBridge() }
        }
    }

    private suspend fun prepare() {
        cleanOwnFiles()
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        activity?.application?.registerActivityLifecycleCallbacks(callbacks)
        val current = activity ?: error("activity missing")
        val policy = bridgePolicy()
        val frame = withContext(Dispatchers.Main.immediate) {
            FrameLayout(current).also { created ->
                created.keepScreenOn = true
                current.window.decorView.findViewById<ViewGroup>(android.R.id.content).addView(
                    created,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
            }
        }
        host = frame
        val holder = PageHolder(current, frame, policy, scope)
        pages = holder
        val browser = BrowserControllerImpl(
            credentialFile = credential(),
            trustedRoot = current.filesDir,
            mainContext = Dispatchers.Main.immediate,
            currentRuntimeLaunchId = { 1L },
        )
        val rpc = BrowserRpcServer(browser)
        rpc.registerWithController()
        val driver = BrowserPageDriver(
            session = { holder.current },
            policy = { policy },
            generation = { browser.state.value.generation },
            navigateControlled = { url -> holder.openControlled(url) },
        )
        withContext(Dispatchers.Main.immediate) {
            browser.attach(driver)
            browser.setVisible(true)
        }
        controller = browser
        server = rpc
        holder.open(FIXTURE_URL)
        awaitLayout(holder.current?.view)
        publish("disabled", null)
    }

    private suspend fun pollCommand() {
        val command = withContext(Dispatchers.IO) { readCommand() } ?: return
        val id = command.optString("id")
        if (id.isBlank() || id == lastCommandId) return
        lastCommandId = id
        when (command.optString("action")) {
            "enable" -> {
                val enabled = withContext(Dispatchers.Main.immediate) { controller?.enableFromUi() == true }
                if (!enabled) fail("enable") else publish("enabled", server?.port)
            }
            "takeover" -> {
                withContext(Dispatchers.Main.immediate) { controller?.takeOverFromUi() }
                awaitCredentialGone()
                publish("taken_over", null)
            }
            "finish" -> finishBridge()
            else -> fail("command")
        }
    }

    private suspend fun awaitCredentialGone() = withTimeout(5_000) {
        while (credential().exists()) delay(50)
    }

    private suspend fun awaitLayout(view: WebView?) = withTimeout(8_000) {
        while (view == null || view.width <= 1 || view.height <= 1 || !view.isShown) delay(50)
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(paused: Activity) {
            if (paused !== activity) return
            controller?.setVisible(false)
            scope.launch {
                controller?.disable()
                awaitCredentialGone()
                publish("hidden", null)
            }
        }

        override fun onActivityResumed(resumed: Activity) {
            if (resumed !== activity || finished.get()) return
            controller?.setVisible(true)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private suspend fun publish(next: String, nextPort: Int?) {
        phase = next
        port = nextPort
        val view = pages?.current?.view
        val status = JSONObject()
            .put("version", 1)
            .put("runId", runId)
            .put("phase", phase)
            .put("lastCommandId", lastCommandId ?: JSONObject.NULL)
            .put("port", port ?: JSONObject.NULL)
            .put("credentialFile", CREDENTIAL_RELATIVE)
            .put("viewport", JSONObject().put("width", view?.width ?: 0).put("height", view?.height ?: 0))
        if (error != null) status.put("error", error)
        withContext(Dispatchers.IO) {
            writeAtomic(statusFile(), status.toString())
            instrumentation.sendStatus(0, Bundle().apply { putString("phase", phase) })
        }
    }

    private suspend fun finishBridge() {
        if (!finished.compareAndSet(false, true)) return
        withContext(Dispatchers.Main.immediate) {
            controller?.disable()
            controller?.detach()
        }
        server?.stop()
        pages?.close()
        activity?.application?.unregisterActivityLifecycleCallbacks(callbacks)
        withContext(Dispatchers.IO) { credential().delete() }
        publish("finished", null)
        scope.cancel()
        withContext(Dispatchers.IO) {
            instrumentation.finish(0, Bundle().apply { putString("summary", "BRIDGE_FINISHED") })
        }
    }

    private fun fail(reason: String) {
        error = reason.take(80)
        scope.launch { publish("failed", null) }
    }

    private suspend fun cleanOwnFiles() = withContext(Dispatchers.IO) {
        directory().listFiles()?.forEach { child ->
            if (child.name == "status.json" || child.name == "command.json" || child.name == "credential.json" || child.name.endsWith(".tmp")) {
                child.delete()
            }
        }
    }

    private fun readCommand(): JSONObject? {
        val file = commandFile()
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun directory(): File = File(instrumentation.targetContext.filesDir, DIR)
    private fun statusFile(): File = File(directory(), "status.json")
    private fun commandFile(): File = File(directory(), "command.json")
    private fun credential(): File = File(directory(), "credential.json")

    private fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(content)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private class PageHolder(
        private val activity: MainActivity,
        private val host: FrameLayout,
        private val policy: BrowserUrlPolicy,
        private val scope: CoroutineScope,
    ) {
        var current: BrowserSession? = null
        private val opened = mutableListOf<BrowserSession>()

        suspend fun open(url: String): BrowserSession = withContext(Dispatchers.Main.immediate) {
            val ready = CompletableDeferred<BrowserSession>()
            openOnMain(url, ready)
            withTimeout(8_000) { ready.await() }
        }

        suspend fun openControlled(url: String): Boolean {
            val decision = withContext(Dispatchers.IO) { policy.preflight(url) }
            if (decision !is UrlDecision.Allow) return false
            open(url)
            return true
        }

        private fun openOnMain(url: String, ready: CompletableDeferred<BrowserSession>) {
            val created = BrowserSession.tryCreate(activity) { policy } ?: run {
                ready.completeExceptionally(IllegalStateException("tryCreate returned null"))
                return
            }
            created.beginControlledGet()
            if (!created.installWitness()) {
                created.destroy()
                ready.completeExceptionally(IllegalStateException("witness was not installed"))
                return
            }
            created.onProbe = { viewId, ticket -> probe(created, viewId, ticket) }
            host.addView(created.view, FrameLayout.LayoutParams(VIEW_PX, VIEW_PX))
            installFixture(created)
            val previous = current
            opened += created
            current = created
            created.load(url, created.epoch())
            previous?.let { old ->
                host.removeView(old.view)
                old.destroy()
                opened.remove(old)
            }
            ready.complete(created)
        }

        private fun probe(page: BrowserSession, viewId: String, ticket: Long) {
            scope.launch(Dispatchers.Main.immediate) {
                if (current !== page || !page.owns(viewId, ticket)) return@launch
                val raw = page.eval(BrowserDriverLogic.probeScript(), page.epoch()) ?: return@launch
                if (current !== page || !page.owns(viewId, ticket)) return@launch
                val allowed = page.displayUrl?.let { policy.checkSyntax(it) is UrlDecision.Allow } == true
                when (val accepted = BrowserDriverLogic.acceptProbe(page.acceptedDocumentId, raw, allowed)) {
                    is ProbeAcceptance.Accept -> page.acceptReady(ticket, accepted.documentId)
                    is ProbeAcceptance.Pending -> Unit
                    is ProbeAcceptance.Reject -> page.markUnknownNavigation()
                }
            }
        }

        private fun installFixture(page: BrowserSession) {
            val production = page.view.webViewClient
            page.view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    production.shouldOverrideUrlLoading(view, request)

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val blocked = production.shouldInterceptRequest(view, request)
                    if (blocked != null) return blocked
                    if (!request.method.equals("GET", ignoreCase = true)) return null
                    val url = request.url?.toString().orEmpty().substringBefore('?')
                    if (request.isForMainFrame && url == FIXTURE_URL) {
                        return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(FIXTURE.toByteArray()))
                    }
                    return production.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = production.onPageStarted(view, url, favicon)
                override fun onPageCommitVisible(view: WebView, url: String?) = production.onPageCommitVisible(view, url)
                override fun onPageFinished(view: WebView, url: String?) = production.onPageFinished(view, url)
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean =
                    production.onRenderProcessGone(view, detail)
            }
        }

        suspend fun close() = withContext(Dispatchers.Main.immediate) {
            opened.forEach { page ->
                host.removeView(page.view)
                page.destroy()
            }
            opened.clear()
            current = null
        }
    }

    private fun bridgePolicy(): BrowserUrlPolicy = BrowserUrlPolicy(resolver = DnsResolver { host ->
        check(host == "example.com")
        listOf(InetAddress.getByName("93.184.216.34"))
    })

    private companion object {
        const val DIR = "browser-mcp-e2e"
        const val CREDENTIAL_RELATIVE = "files/browser-mcp-e2e/credential.json"
        const val FIXTURE_URL = "https://example.com/browser-mcp-e2e"
        const val VIEW_PX = 320
        const val POLL_MS = 250L
        const val DEADLINE_MS = 140_000L
        val FIXTURE = """
            <!doctype html><html><body style="height:2400px">
            <input aria-label="note" id="note">
            <button onclick="document.getElementById('count').textContent='click-count:1'">Apply</button>
            <div id="count">click-count:0</div>
            <input type="password" aria-label="secret">
            <input type="file" aria-label="file">
            <div id="scroll">scroll-y:0</div>
            <script>addEventListener('scroll',()=>{document.getElementById('scroll').textContent='scroll-y:'+scrollY})</script>
            </body></html>
        """.trimIndent()
    }
}

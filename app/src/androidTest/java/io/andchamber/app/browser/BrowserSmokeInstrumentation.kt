@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.andchamber.app.browser

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * Device smoke for the independent browser. Instrumentation thread owns activity startup.
 * WebView work stays on Main. Readiness follows the production probe, not a forced acceptReady.
 */
class BrowserSmokeInstrumentation : Instrumentation() {
    private val results = linkedMapOf<String, String>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        check(Looper.myLooper() != Looper.getMainLooper()) { "onStart must stay off the main looper" }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            fail("multiProfile", "MULTI_PROFILE is not supported")
        } else {
            pass("multiProfile", "supported")
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            fail("documentStart", "DOCUMENT_START_SCRIPT is not supported")
        } else {
            pass("documentStart", "supported")
        }
        val activity = if (failed()) null else startActivitySync(
            Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        if (activity != null) {
            waitForIdleSync()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            runBlocking {
                val host = withContext(Dispatchers.Main.immediate) {
                    FrameLayout(activity).also { frame ->
                        activity.window.decorView.findViewById<ViewGroup>(android.R.id.content).addView(
                            frame,
                            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                        )
                    }
                }
                waitForIdleSync()
                try {
                    withTimeout(75_000) {
                        checkProfileIsolation(activity)
                        checkDriver(activity, host, scope)
                    }
                } catch (error: Throwable) {
                    fail("runner", "${error.javaClass.simpleName}: ${error.message ?: "no message"}")
                } finally {
                    scope.cancel()
                    withContext(Dispatchers.Main.immediate) {
                        (host.parent as? ViewGroup)?.removeView(host)
                        host.removeAllViews()
                    }
                }
            }
        }
        val bundle = Bundle()
        results.forEach { (key, value) -> bundle.putString(key, value) }
        val failed = results.filterValues { it.startsWith("FAIL") }
        bundle.putString("summary", if (failed.isEmpty()) "PASS" else "FAIL")
        bundle.putString("failed", failed.keys.joinToString(","))
        finish(if (failed.isEmpty()) 0 else 1, bundle)
    }

    private suspend fun checkProfileIsolation(activity: MainActivity) = withContext(Dispatchers.Main.immediate) {
        val browser = boundWebView(activity, PROFILE_NAME)
        val other = boundWebView(activity, OTHER_PROFILE)
        try {
            check(WebViewCompat.getProfile(browser).name == PROFILE_NAME)
            check(WebViewCompat.getProfile(other).name == OTHER_PROFILE)
            browser.loadDataWithBaseURL(ORIGIN, fixture("browser"), "text/html", "utf-8", null)
            other.loadDataWithBaseURL(ORIGIN, fixture("other"), "text/html", "utf-8", null)
            awaitDocument(browser, "browser")
            awaitDocument(other, "other")
            val browserCookies = WebViewCompat.getProfile(browser).cookieManager
            val otherCookies = WebViewCompat.getProfile(other).cookieManager
            browserCookies.setCookie(ORIGIN, "$COOKIE_KEY=$BROWSER_VALUE; Path=/")
            otherCookies.setCookie(ORIGIN, "$COOKIE_KEY=$OTHER_VALUE; Path=/")
            browserCookies.flush()
            otherCookies.flush()
            eval(browser, "localStorage.setItem('$STORAGE_KEY', ${JSONObject.quote(BROWSER_VALUE)})")
            eval(other, "localStorage.setItem('$STORAGE_KEY', ${JSONObject.quote(OTHER_VALUE)})")
            val browserCookie = cookieValue(browserCookies.getCookie(ORIGIN), COOKIE_KEY)
            val otherCookie = cookieValue(otherCookies.getCookie(ORIGIN), COOKIE_KEY)
            check(browserCookie == BROWSER_VALUE) { "browser cookie changed: $browserCookie" }
            check(otherCookie == OTHER_VALUE) { "other cookie changed: $otherCookie" }
            check(eval(browser, "localStorage.getItem('$STORAGE_KEY')") == JSONObject.quote(BROWSER_VALUE))
            check(eval(other, "localStorage.getItem('$STORAGE_KEY')") == JSONObject.quote(OTHER_VALUE))
            val leaked = cookieValue(CookieManager.getInstance().getCookie(ORIGIN), COOKIE_KEY)
            check(leaked != BROWSER_VALUE && leaked != OTHER_VALUE) { "cookie leaked to process CookieManager" }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) {
                val info = CookieManagerCompat.getCookieInfo(browserCookies, ORIGIN).joinToString(";")
                check(!info.contains(OTHER_VALUE))
            }
            pass("profile", "cookie and storage isolated after both pages loaded")
        } catch (error: Throwable) {
            fail("profile", error.message ?: error.javaClass.simpleName)
        } finally {
            withContext(Dispatchers.Main.immediate) {
                browser.destroy()
                other.destroy()
            }
        }
    }

    private suspend fun checkDriver(activity: MainActivity, host: FrameLayout, scope: CoroutineScope) = withContext(Dispatchers.Main.immediate) {
        val policy = smokePolicy()
        val harness = PageHarness(activity, host, policy, scope)
        try {
            val first = harness.open(FIRST_URL)
            pass("freshView", "view ${first.viewId} ticket ${first.ticket}")
            val bridge = eval(first.view, "typeof AndChamberBridge")
            val windowBridge = eval(first.view, "typeof window.AndChamberBridge")
            if (bridge == "\"undefined\"" && windowBridge == "\"undefined\"") {
                pass("hostBridge", "AndChamberBridge undefined")
            } else {
                fail("hostBridge", "defined bridge=$bridge window=$windowBridge")
            }
            val blank = withContext(Dispatchers.Main.immediate) { BrowserSession.tryCreate(activity) { policy } }
            if (blank == null) {
                fail("aboutBlank", "tryCreate returned null")
            } else {
                withContext(Dispatchers.Main.immediate) { host.addView(blank.view, FrameLayout.LayoutParams(VIEW_PX, VIEW_PX)) }
                awaitLayout(blank.view)
                val blankDriver = BrowserPageDriver({ blank }, { policy })
                val reply = blankDriver.execute(request("blank-snap", BrowserMethod.SNAPSHOT))
                if (!reply.ok && reply.error?.message?.contains("loading") == true) {
                    pass("aboutBlank", "snapshot refused while not ready")
                } else {
                    fail("aboutBlank", "expected not ready, got ${reply.error?.message ?: reply.result}")
                }
                withContext(Dispatchers.Main.immediate) {
                    host.removeView(blank.view)
                    blank.destroy()
                }
            }
            val snap1 = snapshot(harness.driver, "snap-1")
            if (snap1.toString().contains("do-not-read") || names(snap1).any { it.contains("secret") || it.contains("file") }) {
                fail("passwordFile", "secret or file field leaked")
            } else {
                pass("passwordFile", "password and file excluded")
            }
            val oldButton = element(snap1, "button", "Go").getString("ref")
            val field = element(snap1, "input", "note").getString("ref")
            val typed = harness.driver.execute(request("type-1", BrowserMethod.TYPE, mapOf("ref" to field, "text" to "typed-value")))
            if (!typed.ok) {
                fail("type", typed.error?.message ?: "type failed")
            } else {
                awaitValue(first.view, "note", "typed-value")
                pass("type", "plain input value changed")
            }
            val snap2 = snapshot(harness.driver, "snap-2")
            val freshButton = element(snap2, "button", "Go").getString("ref")
            val stale = harness.driver.execute(request("click-old", BrowserMethod.CLICK, mapOf("ref" to oldButton)))
            if (stale.ok) fail("staleRef", "old snapshot ref still clicked") else pass("staleRef", "old snapshot ref refused")
            val clicked = harness.driver.execute(request("click-1", BrowserMethod.CLICK, mapOf("ref" to freshButton)))
            if (!clicked.ok) {
                fail("click", clicked.error?.message ?: "click failed")
            } else {
                awaitEval(first.view, "document.getElementById('clicked').textContent") { it == "\"clicked\"" }
                pass("click", "fresh ref clicked")
            }
            val snap3 = snapshot(harness.driver, "snap-3")
            val removeRef = element(snap3, "button", "Go").getString("ref")
            eval(first.view, "document.getElementById('go').remove()")
            awaitEval(first.view, "document.getElementById('go')") { it == "null" }
            val removed = harness.driver.execute(request("click-removed", BrowserMethod.CLICK, mapOf("ref" to removeRef)))
            if (removed.ok) fail("removedRef", "removed node ref still clicked") else pass("removedRef", "removed node ref refused")
            val snap4 = snapshot(harness.driver, "snap-4")
            val placeholder = element(snap4, "input", "note").getString("ref")
            val placeholderReply = harness.driver.execute(request("type-placeholder", BrowserMethod.TYPE, mapOf("ref" to placeholder, "text" to "__AC_DIR__")))
            if (!placeholderReply.ok) {
                fail("placeholder", placeholderReply.error?.message ?: "type failed")
            } else {
                awaitValue(first.view, "note", "__AC_DIR__")
                pass("placeholder", "plain DOM value only, not a React model")
            }
            val image = harness.driver.execute(request("capture-1", BrowserMethod.CAPTURE))
            if (!image.ok || image.result["mimeType"] != "image/png") {
                fail("capture", image.error?.message ?: "capture failed")
            } else {
                val bitmap = decodePng(image.result.getValue("data"))
                try {
                    check(bitmap.width == first.view.width && bitmap.height == first.view.height)
                    check(bitmap.byteCount > 0)
                    pass("capture", "${bitmap.width}x${bitmap.height} png")
                } catch (error: Throwable) {
                    fail("capture", error.message ?: "png mismatch")
                } finally {
                    bitmap.recycle()
                }
            }
            val beforeGeneration = element(snapshot(harness.driver, "snap-5"), "input", "note").getString("ref")
            harness.generation += 1
            val afterGeneration = harness.driver.execute(request("click-generation", BrowserMethod.CLICK, mapOf("ref" to beforeGeneration)))
            val recovered = snapshot(harness.driver, "snap-6")
            if (afterGeneration.ok) {
                fail("generation", "old ref survived generation change")
            } else if (recovered.getJSONArray("elements").length() == 0) {
                fail("generation", "snapshot empty after ref invalidation")
            } else {
                pass("generation", "refs cleared and snapshot still readable")
            }
            val beforeNav = element(snapshot(harness.driver, "snap-7"), "input", "note").getString("ref")
            val started = harness.driver.execute(request("nav-1", BrowserMethod.NAVIGATE, mapOf("url" to SECOND_URL)))
            if (!started.ok) {
                fail("navigation", started.error?.message ?: "navigation did not start")
            } else {
                val second = harness.awaitReplacement(first.viewId)
                if (second.viewId == first.viewId) {
                    fail("navigation", "same URL replacement reused the old view")
                } else {
                    val oldAfterNav = harness.driver.execute(request("click-after-nav", BrowserMethod.CLICK, mapOf("ref" to beforeNav)))
                    if (oldAfterNav.ok) fail("navigation", "old view ref survived replacement") else pass("navigation", "fresh view ${second.viewId}")
                }
            }
            val denied = harness.driver.execute(request("nav-denied", BrowserMethod.NAVIGATE, mapOf("url" to "http://127.0.0.1/")))
            if (denied.ok) fail("policy", "loopback navigation was allowed") else pass("policy", "loopback refused")
            checkUnknownPost(harness)
        } finally {
            harness.close()
        }
    }

    private suspend fun checkUnknownPost(harness: PageHarness) {
        val page = harness.open(FIRST_URL)
        if (!page.committed) {
            fail("unknownPost", "fixture never became ready, so POST recovery was not observed")
            return
        }
        page.view.postUrl(FIRST_URL, ByteArray(0))
        withTimeout(8_000) {
            while (!page.unknownNavigation) delay(50)
        }
        page.view.webViewClient.onPageFinished(page.view, FIRST_URL)
        val reply = harness.driver.execute(request("post-snap", BrowserMethod.SNAPSHOT))
        if (page.unknownNavigation && !reply.ok) {
            pass("unknownPost", "POST stayed unknown after finished")
        } else {
            fail("unknownPost", "production did not keep an unknown POST refused after finished; committed=${page.committed} unknown=${page.unknownNavigation} reply=${reply.error?.message}")
        }
    }

    private class PageHarness(
        private val activity: MainActivity,
        private val host: FrameLayout,
        private val policy: BrowserUrlPolicy,
        private val scope: CoroutineScope,
    ) {
        var generation = 0L
        var current: BrowserSession? = null
        private val opened = mutableListOf<BrowserSession>()
        val driver = BrowserPageDriver(
            session = { current },
            policy = { policy },
            generation = { generation },
            navigateControlled = { url -> openControlled(url) },
        )

        suspend fun open(url: String): BrowserSession {
            val ready = CompletableDeferred<BrowserSession>()
            openOnMain(url, ready)
            val opened = withTimeout(8_000) { ready.await() }
            withTimeout(8_000) {
                while (!opened.committed && !opened.unknownNavigation) delay(50)
            }
            check(opened.committed) { "controlled page did not become ready" }
            return opened
        }

        suspend fun awaitReplacement(previousViewId: String): BrowserSession = withTimeout(8_000) {
            while (current?.viewId == previousViewId || current?.committed != true) delay(50)
            current ?: error("replacement missing")
        }

        private fun openOnMain(url: String, ready: CompletableDeferred<BrowserSession>) {
            val created = BrowserSession.tryCreate(activity) { policy } ?: run {
                ready.completeExceptionally(IllegalStateException("tryCreate returned null"))
                return
            }
            created.beginControlledGet()
            if (!created.installWitness()) {
                created.destroy()
                ready.completeExceptionally(IllegalStateException("DOCUMENT_START_SCRIPT witness was not installed"))
                return
            }
            created.onProbe = { viewId, ticket -> probe(created, viewId, ticket) }
            host.addView(created.view, FrameLayout.LayoutParams(VIEW_PX, VIEW_PX))
            installFixtureIntercept(created)
            opened += created
            current = created
            created.load(url, created.epoch())
            if (!ready.isCompleted) ready.complete(created)
        }

        private fun openControlled(url: String): Boolean {
            val decision = policy.checkSyntax(url)
            if (decision !is UrlDecision.Allow) return false
            val ready = CompletableDeferred<BrowserSession>()
            openOnMain(url, ready)
            return true
        }

        private fun probe(page: BrowserSession, viewId: String, ticket: Long) {
            scope.launch {
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

        private fun installFixtureIntercept(page: BrowserSession) {
            val production = page.view.webViewClient
            page.view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    production.shouldOverrideUrlLoading(view, request)

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val blocked = production.shouldInterceptRequest(view, request)
                    if (blocked != null) return blocked
                    val url = request.url?.toString()
                    val safeGet = request.method.equals("GET", ignoreCase = true) && !request.isRedirect
                    if (request.isForMainFrame && safeGet && (url == FIRST_URL || url == SECOND_URL)) {
                        val marker = if (url == SECOND_URL) "second" else "first"
                        return WebResourceResponse(
                            "text/html",
                            "utf-8",
                            200,
                            "OK",
                            mapOf("Content-Type" to "text/html; charset=utf-8"),
                            ByteArrayInputStream(fixture(marker).toByteArray(Charsets.UTF_8)),
                        )
                    }
                    return production.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    production.onPageStarted(view, url, favicon)
                }

                override fun onPageCommitVisible(view: WebView, url: String?) {
                    production.onPageCommitVisible(view, url)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    production.onPageFinished(view, url)
                }

                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean =
                    production.onRenderProcessGone(view, detail)
            }
        }

        suspend fun close() {
            withContext(Dispatchers.Main.immediate) {
                opened.forEach { page ->
                    host.removeView(page.view)
                    page.destroy()
                }
                opened.clear()
                current = null
            }
        }
    }

    private suspend fun snapshot(driver: BrowserDriver, id: String): JSONObject {
        val reply = withTimeout(8_000) {
            var last = driver.execute(request(id, BrowserMethod.SNAPSHOT))
            val started = SystemClock.uptimeMillis()
            while (!last.ok && SystemClock.uptimeMillis() - started < 8_000) {
                delay(50)
                last = driver.execute(request("$id-retry", BrowserMethod.SNAPSHOT))
            }
            last
        }
        check(reply.ok) { reply.error?.message ?: "snapshot failed" }
        return JSONObject(reply.result.getValue("snapshot"))
    }

    private fun request(id: String, method: BrowserMethod, params: Map<String, String> = emptyMap()): BrowserRequest =
        BrowserRequest(id, method, params, 5_000)

    private fun smokePolicy(): BrowserUrlPolicy = BrowserUrlPolicy(resolver = DnsResolver { host ->
        check(host == "example.com")
        listOf(InetAddress.getByName("93.184.216.34"))
    })

    private suspend fun awaitLayout(view: WebView) {
        withTimeout(8_000) {
            while (view.width <= 1 || view.height <= 1 || !view.isShown) delay(50)
        }
    }

    private suspend fun awaitDocument(view: WebView, marker: String) {
        awaitEval(view, "document.getElementById('marker') && document.getElementById('marker').textContent") {
            it == JSONObject.quote(marker)
        }
    }

    private suspend fun awaitValue(view: WebView, id: String, expected: String) {
        awaitEval(view, "document.getElementById('$id').value") { it == JSONObject.quote(expected) }
    }

    private suspend fun awaitEval(view: WebView, script: String, ready: (String) -> Boolean) {
        withTimeout(8_000) {
            val started = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - started < 8_000) {
                if (ready(eval(view, script))) return@withTimeout
                delay(50)
            }
            error("timed out waiting for $script")
        }
    }

    private suspend fun eval(view: WebView, script: String): String = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { value ->
                if (continuation.isActive) continuation.resume(value ?: "null")
            }
        }
    }

    private fun element(snapshot: JSONObject, role: String, name: String): JSONObject {
        val elements = snapshot.getJSONArray("elements")
        for (index in 0 until elements.length()) {
            val item = elements.getJSONObject(index)
            if (item.optString("role") == role && item.optString("name") == name) return item
        }
        error("missing $role named $name")
    }

    private fun names(snapshot: JSONObject): List<String> {
        val elements = snapshot.getJSONArray("elements")
        return List(elements.length()) { elements.getJSONObject(it).optString("name") }
    }

    private fun boundWebView(context: android.content.Context, profile: String): WebView {
        val view = WebView(context)
        WebViewCompat.setProfile(view, profile)
        check(WebViewCompat.getProfile(view).name == profile)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        return view
    }

    private fun cookieValue(header: String?, name: String): String? =
        header?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private fun decodePng(data: String): Bitmap {
        val bytes = Base64.decode(data, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("capture was not a PNG")
    }

    private fun pass(name: String, detail: String) {
        report(name, "PASS $detail", 0)
    }

    private fun fail(name: String, detail: String) {
        report(name, "FAIL $detail", 1)
    }

    private fun report(name: String, detail: String, code: Int) {
        results[name] = detail
        val bundle = Bundle().apply {
            putString("check", name)
            putString("result", detail)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread({ sendStatus(code, bundle) }, "browser-smoke-status").start()
        } else {
            sendStatus(code, bundle)
        }
    }

    private fun failed(): Boolean = results.values.any { it.startsWith("FAIL") }

    private companion object {
        const val PROFILE_NAME = "andchamber_browser_v1"
        const val OTHER_PROFILE = "andchamber_browser_smoke_other"
        const val ORIGIN = "https://example.com/"
        const val FIRST_URL = "https://example.com/smoke-first"
        const val SECOND_URL = "https://example.com/smoke-second"
        const val COOKIE_KEY = "andchamber_smoke_cookie"
        const val STORAGE_KEY = "andchamber_smoke_storage"
        const val BROWSER_VALUE = "browser-fixture"
        const val OTHER_VALUE = "other-fixture"
        const val VIEW_PX = 320

        fun fixture(marker: String): String = """
            <!doctype html>
            <html><head><title>smoke</title></head>
            <body>
              <span id="marker">$marker</span>
              <button id="go" onclick="document.getElementById('clicked').textContent='clicked'">Go</button>
              <span id="clicked"></span>
              <input id="note" type="text" aria-label="note" value="">
              <input id="secret" type="password" aria-label="secret" value="do-not-read">
              <input id="file" type="file" aria-label="file">
            </body></html>
        """.trimIndent()
    }
}

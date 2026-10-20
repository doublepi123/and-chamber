package io.andchamber.app.browser

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * androidTest-only deterministic HTTPS fixture. It answers exact URLs on the reserved example.com
 * domain from memory, so no network or certificate change is needed. Every other request goes to the
 * production client unchanged. Response headers forbid framing; the page must still show as a
 * top-level document.
 */
internal object OpenChamberProviderFixtures {
    const val ORIGIN = "https://example.com/openchamber-provider"
    const val ORIGIN2 = "https://example.com/openchamber-provider-second"
    const val LOGIN_URL = "https://example.com/openchamber-provider-login"
    const val LOGIN_SUBMIT_URL = "https://example.com/openchamber-provider-login/submit"
    const val WELCOME_URL = "https://example.com/openchamber-provider-welcome"

    /**
     * Human sign-in form. WebView cannot return a 3xx or apply Set-Cookie from an intercepted
     * response, so the submit answer sets the cookie from script and redirects client-side.
     */
    private val loginHtml = """
        <!doctype html>
        <html><head><title>provider-login</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{font:18px sans-serif;margin:12px} input,button{font-size:20px;padding:10px;width:90%}</style>
        </head><body>
          <form method="post" action="$LOGIN_SUBMIT_URL">
            <input name="user" aria-label="login-user" value="">
            <button type="submit" aria-label="Sign in">Sign in</button>
          </form>
        </body></html>
    """.trimIndent()

    private val submitHtml = """
        <!doctype html><html><head><title>provider-submitted</title></head><body>
        <script>
          document.cookie = 'oc_fixture_login=signed-in-42; path=/; max-age=3600; secure; samesite=lax';
          location.replace('$WELCOME_URL');
        </script></body></html>
    """.trimIndent()

    private val welcomeHtml = """
        <!doctype html><html><head><title>provider-welcome</title></head><body>
        <span id="cookie">cookie:<script>document.write(document.cookie||'none')</script></span>
        </body></html>
    """.trimIndent()

    private fun page(url: String, method: String): String? = when {
        method.equals("GET", true) && url == ORIGIN -> html("provider")
        method.equals("GET", true) && url == ORIGIN2 -> html("second")
        method.equals("GET", true) && url == LOGIN_URL -> loginHtml
        method.equals("POST", true) && url == LOGIN_SUBMIT_URL -> submitHtml
        method.equals("GET", true) && url == WELCOME_URL -> welcomeHtml
        else -> null
    }

    fun html(marker: String): String = """
        <!doctype html>
        <html><head><title>provider-fixture</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{font:16px sans-serif;margin:12px} #tall{height:3000px;background:linear-gradient(#eef,#fee)}</style>
        </head>
        <body>
          <span id="marker">$marker</span>
          <p><input id="note" type="text" aria-label="note" value=""></p>
          <p><button id="apply" type="button" aria-label="Apply"
               onclick="var c=document.getElementById('count');c.textContent='click-count:'+(++window.clicks)">Apply</button></p>
          <span id="count">click-count:0</span>
          <span id="scroll">scroll-y:0</span>
          <div id="framed">framed:<script>document.write(window.top===window.self?'top':'nested')</script></div>
          <div id="tall"></div>
          <span id="bottom">bottom</span>
          <script>window.clicks=0;addEventListener('scroll',function(){document.getElementById('scroll').textContent='scroll-y:'+Math.round(window.scrollY)})</script>
        </body></html>
    """.trimIndent()

    fun attach(view: WebView, production: WebViewClient) {
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(target: WebView, request: WebResourceRequest): Boolean =
                production.shouldOverrideUrlLoading(target, request)

            override fun shouldInterceptRequest(target: WebView, request: WebResourceRequest): WebResourceResponse? {
                val blocked = production.shouldInterceptRequest(target, request)
                if (blocked != null) return blocked
                val url = request.url?.toString().orEmpty()
                if (!request.isForMainFrame) return null
                val body = page(url, request.method.orEmpty()) ?: return null
                return WebResourceResponse(
                    "text/html",
                    "utf-8",
                    200,
                    "OK",
                    mapOf(
                        "Content-Type" to "text/html; charset=utf-8",
                        "X-Frame-Options" to "SAMEORIGIN",
                        "Content-Security-Policy" to "frame-ancestors 'self'",
                    ),
                    ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
                )
            }

            override fun onPageStarted(target: WebView, url: String?, favicon: Bitmap?) =
                production.onPageStarted(target, url, favicon)

            override fun onPageCommitVisible(target: WebView, url: String?) =
                production.onPageCommitVisible(target, url)

            override fun onPageFinished(target: WebView, url: String?) =
                production.onPageFinished(target, url)

            @Suppress("DEPRECATION")
            override fun onReceivedError(target: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) =
                production.onReceivedError(target, request, error)

            override fun onReceivedSslError(target: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) =
                production.onReceivedSslError(target, handler, error)

            override fun onRenderProcessGone(target: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean =
                production.onRenderProcessGone(target, detail)
        }
    }
}

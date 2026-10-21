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
    const val MOUSE_URL = "https://example.com/openchamber-provider-mouse"
    const val RETIRE_URL = "https://example.com/openchamber-provider-retire"
    const val FOCUSED_URL = "https://example.com/openchamber-provider-focused"

    /**
     * Desktop/mouse fixture. Reports the CSS viewport and records which real pointer events the page
     * received (type, pointerType, button, buttons, clientX/Y), plus wheel and drag results.
     */
    /**
     * B4 retirement fixture. It sets no cookie itself: the test writes its one unique value through pageEval
     * on the old page only. It records real pointer/click/wheel events and reports real page values (timeOrigin,
     * per-document load count, scroll, client width, UA). Nothing here is a constant that a case could pass with.
     */
    private val retireHtml = """
        <!doctype html><html><head><title>provider-retire</title><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{margin:0;font:16px sans-serif} #tall{height:2400px;background:#eef}
        #box{position:absolute;left:400px;top:150px;width:120px;height:80px;background:#f99}
        #next{position:fixed;left:8px;top:8px;width:48px;height:32px}</style></head><body>
        <div id="box">box</div>
        <button id="next">next</button>
        <div id="tall"></div>
        <script>
          window.__log = [];
          window.__clicks = [];
          // Monotonic counters, kept apart from the bounded 200-entry log so they never drop with it.
          window.__counts = {wheel:0, mouseReleases:0, mouseButtons:0};
          window.__buttons = 0;
          function note(e){
            window.__log.push({t:e.type, p:e.pointerType||'', b:e.button, bs:e.buttons, x:Math.round(e.clientX), y:Math.round(e.clientY)});
            if (window.__log.length>200) window.__log.shift();
            if (e.pointerType === 'mouse') {
              window.__buttons = e.buttons;
              if (e.type === 'pointerup') window.__counts.mouseReleases += 1;
            }
          }
          ['pointermove','pointerdown','pointerup','pointercancel','click','mousedown','mouseup','mousemove'].forEach(function(n){ document.addEventListener(n, note, true); });
          document.addEventListener('click', function(e){ window.__clicks.push(e.target && e.target.id || e.target.tagName); }, true);
          window.addEventListener('wheel', function(e){ window.__counts.wheel += 1; window.__log.push({t:'wheel', dy:Math.round(e.deltaY)}); if (window.__log.length>200) window.__log.shift(); }, {passive:true});
          var box = document.getElementById('box');
          // The DOM pointer id is whatever the first pointerdown carries, not a fixed 0. Capture uses that id.
          window.__pointerId = null;
          box.addEventListener('pointerdown', function(e){ window.__pointerId = e.pointerId; try { box.setPointerCapture(e.pointerId); } catch (x) {} });
          window.__loads = (Number(sessionStorage.getItem('loads')||0)) + 1; sessionStorage.setItem('loads', String(window.__loads));
          window.__report = function(){
            return JSON.stringify({
              ua: navigator.userAgent.indexOf('Mobile') >= 0 ? 'mobile' : 'desktop',
              clientWidth: document.documentElement.clientWidth,
              media1000: matchMedia('(min-width:1000px)').matches,
              sy: Math.round(window.scrollY),
              loads: window.__loads,
              timeOrigin: performance.timeOrigin,
              pointerId: window.__pointerId,
              boxCaptured: (box.hasPointerCapture && window.__pointerId != null) ? box.hasPointerCapture(window.__pointerId) : null,
              buttons: window.__buttons,
              clicks: window.__clicks.length,
              wheel: window.__counts.wheel,
              releases: window.__counts.mouseReleases,
              logTail: window.__log.slice(-30)
            });
          };
        </script></body></html>
    """.trimIndent()

    private val mouseHtml = """
        <!doctype html><html><head><title>provider-mouse</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{margin:0;font:16px sans-serif} #pad{position:absolute;left:0;top:0;width:1024px;height:2400px;background:#eef}
        #box{position:absolute;left:400px;top:150px;width:120px;height:80px;background:#f99}</style></head><body>
        <div id="pad"></div><div id="box">box</div>
        <script>
          window.__log = [];
          window.__drag = null;
          function note(e){ window.__log.push({t:e.type, p:e.pointerType||'', b:e.button, bs:e.buttons, x:Math.round(e.clientX), y:Math.round(e.clientY)}); if (window.__log.length>200) window.__log.shift(); }
          ['pointermove','pointerdown','pointerup','click','mousedown','mouseup','mousemove'].forEach(function(n){ document.addEventListener(n, note, true); });
          window.addEventListener('wheel', function(e){ window.__log.push({t:'wheel', dy:Math.round(e.deltaY)}); }, {passive:true});
          var box=document.getElementById('box');
          box.addEventListener('pointerdown', function(e){ window.__drag={sx:e.clientX, sy:e.clientY, l:box.offsetLeft, t:box.offsetTop}; box.setPointerCapture&&box.setPointerCapture(e.pointerId); });
          box.addEventListener('pointermove', function(e){ if(!window.__drag||!(e.buttons&1)) return; box.style.left=(window.__drag.l+e.clientX-window.__drag.sx)+'px'; box.style.top=(window.__drag.t+e.clientY-window.__drag.sy)+'px'; });
          box.addEventListener('pointerup', function(){ window.__drag=null; });
          window.__geometry = function(){ var v=window.visualViewport||{}; var root=document.documentElement; return {innerWidth:innerWidth, innerHeight:innerHeight, clientWidth:root.clientWidth, clientHeight:root.clientHeight, media1000:matchMedia('(min-width:1000px)').matches, viewport:[].map.call(document.querySelectorAll('meta[name="viewport"]'), function(m){return m.content}).slice(0,4), visualViewport:{width:v.width||0, height:v.height||0, offsetLeft:v.offsetLeft||0, offsetTop:v.offsetTop||0, scale:v.scale||1}, devicePixelRatio:devicePixelRatio, scrollX:scrollX, scrollY:scrollY}; };
          window.__report = function(){ var g=window.__geometry(); return JSON.stringify({w:g.innerWidth, clientWidth:g.clientWidth, media1000:g.media1000, vw:Math.round(g.visualViewport.width), dpr:g.devicePixelRatio, ua:navigator.userAgent.indexOf('Mobile')>=0?'mobile':'desktop', sy:Math.round(g.scrollY), box:[box.offsetLeft,box.offsetTop], geometry:g, log:window.__log.slice(-40)}); };
          window.__loads = (Number(sessionStorage.getItem('loads')||0))+1; sessionStorage.setItem('loads', window.__loads);
          window.__posts = Number(sessionStorage.getItem('posts')||0);
        </script></body></html>
    """.trimIndent()
    const val LOGIN_SUBMIT_URL = "https://example.com/openchamber-provider-login/submit"
    const val WELCOME_URL = "https://example.com/openchamber-provider-welcome"
    @Volatile var postArrivals: Int = 0

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
          <script>
            window.__loads = (Number(sessionStorage.getItem('loads')||0)) + 1;
            sessionStorage.setItem('loads', String(window.__loads));
            if (sessionStorage.getItem('posts') === null) sessionStorage.setItem('posts', '0');
            window.__posts = Number(sessionStorage.getItem('posts'));
          </script>
          <form method="post" action="$LOGIN_SUBMIT_URL" onsubmit="window.__submits=(window.__submits||0)+1" onclick="window.__clicks=(window.__clicks||0)+1">
            <input name="user" aria-label="login-user" value="">
            <button type="submit" aria-label="Sign in">Sign in</button>
          </form>
        </body></html>
    """.trimIndent()

    private val submitHtml = """
        <!doctype html><html><head><title>provider-submitted</title></head><body>
        <script>
          document.cookie = 'oc_fixture_login=signed-in-42; path=/; max-age=3600; secure; samesite=lax';
          sessionStorage.setItem('posts', String(Number(sessionStorage.getItem('posts')||0)+1));
          location.replace('$WELCOME_URL');
        </script></body></html>
    """.trimIndent()

    private val welcomeHtml = """
        <!doctype html><html><head><title>provider-welcome</title></head><body>
        <script>
          window.__posts = Number(sessionStorage.getItem('posts')||0);
          window.__loads = Number(sessionStorage.getItem('loads')||0)+1;
          sessionStorage.setItem('loads', String(window.__loads));
        </script>
        <span id="cookie">cookie:<script>document.write(document.cookie||'none')</script></span>
        <span id="posts">posts:<script>document.write(sessionStorage.getItem('posts')||'0')</script></span>
        </body></html>
    """.trimIndent()

    private val focusedHtml = """
        <!doctype html><html><head><title>provider-focused</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body{margin:0;font:16px sans-serif}
          #tall{height:6000px;background:linear-gradient(#eef,#fee)}
          #noteBar{position:fixed;left:0;right:0;bottom:0;display:flex;gap:8px;padding:8px;
            background:#fff;border-top:1px solid #ccc}
          #note{flex:1;min-width:0;font-size:18px;padding:8px}
          #apply{font-size:18px;padding:8px 14px}
        </style></head><body>
        <span id="marker">focused</span>
        <div id="tall"></div>
        <div id="bottom">bottom</div>
        <div id="noteBar">
          <input id="note" type="text" aria-label="note" value="">
          <button id="apply" type="button" aria-label="Apply">Apply</button>
        </div>
        <script>
          window.__log = [];
          window.__clicks = [];
          window.__keys = 0;
          window.__applies = 0;
          window.__wheel = 0;
          window.__releases = 0;
          window.__buttons = 0;
          window.__scrollEvents = 0;
          window.__pointerId = null;
          function note(e){
            if (window.__log.length > 200) window.__log.shift();
            window.__log.push({t:e.type, p:e.pointerType||'', b:e.button||0, bs:e.buttons||0,
              x:Math.round(e.clientX||0), y:Math.round(e.clientY||0)});
            if (e.pointerType === 'mouse') {
              window.__buttons = e.buttons || 0;
              if (e.type === 'pointerup') window.__releases += 1;
            }
          }
          ['pointermove','pointerdown','pointerup','pointercancel','click','mousedown','mouseup','mousemove']
            .forEach(function(name){ document.addEventListener(name, note, true); });
          document.addEventListener('click', function(e){
            window.__clicks.push(e.target && e.target.id || e.target && e.target.tagName || '');
          }, true);
          document.addEventListener('keydown', function(){ window.__keys += 1; }, true);
          document.addEventListener('wheel', function(e){
            window.__wheel += 1;
            window.__log.push({t:'wheel', dy:Math.round(e.deltaY)});
            if (window.__log.length > 200) window.__log.shift();
          }, {passive:true});
          window.addEventListener('scroll', function(){ window.__scrollEvents += 1; }, {passive:true});
          var apply = document.getElementById('apply');
          apply.addEventListener('pointerdown', function(e){
            window.__pointerId = e.pointerId;
            try { apply.setPointerCapture(e.pointerId); } catch (x) {}
          });
          apply.addEventListener('click', function(){ window.__applies += 1; });
          window.__loads = (Number(sessionStorage.getItem('loads') || 0)) + 1;
          sessionStorage.setItem('loads', String(window.__loads));
          window.__report = function(){
            var input = document.getElementById('note');
            return JSON.stringify({
              timeOrigin: performance.timeOrigin,
              loads: window.__loads,
              ua: navigator.userAgent.indexOf('Mobile') >= 0 ? 'mobile' : 'desktop',
              clientWidth: document.documentElement.clientWidth,
              media1000: matchMedia('(min-width:1000px)').matches,
              scrollX: Math.round(window.scrollX),
              scrollY: Math.round(window.scrollY),
              sy: Math.round(window.scrollY),
              scrollEvents: window.__scrollEvents,
              clicks: window.__clicks.length,
              applies: window.__applies,
              keys: window.__keys,
              buttons: window.__buttons,
              wheel: window.__wheel,
              releases: window.__releases,
              boxCaptured: null,
              pointerId: window.__pointerId,
              logTail: window.__log.slice(-40),
              noteValue: input ? String(input.value) : null
            });
          };
        </script></body></html>
    """.trimIndent()

    private fun page(url: String, method: String): String? = when {
        method.equals("GET", true) && url == ORIGIN -> html("provider")
        method.equals("GET", true) && url == ORIGIN2 -> html("second")
        method.equals("GET", true) && url == MOUSE_URL -> mouseHtml
        method.equals("GET", true) && url == RETIRE_URL -> retireHtml
        method.equals("GET", true) && url == FOCUSED_URL -> focusedHtml
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
          <script>
            window.__loads = (Number(sessionStorage.getItem('loads')||0)) + 1;
            sessionStorage.setItem('loads', String(window.__loads));
            if (sessionStorage.getItem('posts') === null) sessionStorage.setItem('posts', '0');
            window.__posts = Number(sessionStorage.getItem('posts'));
            window.clicks = 0;
            addEventListener('scroll',function(){document.getElementById('scroll').textContent='scroll-y:'+Math.round(window.scrollY)});
          </script>
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
                if (request.method.equals("POST", true) && url == LOGIN_SUBMIT_URL) postArrivals += 1
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

            override fun onScaleChanged(target: WebView, oldScale: Float, newScale: Float) =
                production.onScaleChanged(target, oldScale, newScale)

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

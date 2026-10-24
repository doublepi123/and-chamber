package io.andchamber.app.data

import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionIdentity
import io.andchamber.app.testhost.WebViewRegressionActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Real Chromium proof for the removal gate: localStorage and IndexedDB written at the removal
 * origin through one production-bound named profile are gone after [WebSessionProfiles.removeProfile],
 * while a second fixture profile keeps its own storage. The read-back uses a new
 * [WebSessionProfiles] instance and a new WebView bound to the same profile name, so it never reuses
 * the destroyed page or a cached in-memory value.
 *
 * Only random fixture profiles are created. The Default profile is never touched.
 */
@RunWith(AndroidJUnit4::class)
class WebProfileRemovalWebStorageDeviceTest {
    private val origin = "https://profile-removal.invalid"
    private val target = ServerTarget.Remote(
        id = "storage-removal-${UUID.randomUUID()}",
        displayName = "Storage removal fixture",
        baseUrl = origin,
        uiPassword = null,
    )
    private val sentinelTarget = ServerTarget.Remote(
        id = "storage-sentinel-${UUID.randomUUID()}",
        displayName = "Storage sentinel fixture",
        baseUrl = origin,
        uiPassword = null,
    )
    private val profileName = profileNameFor(WebSessionIdentity(target.id, canonicalWebOrigin(origin)!!))
    private val sentinelProfileName = profileNameFor(WebSessionIdentity(sentinelTarget.id, canonicalWebOrigin(origin)!!))

    private var scenario: ActivityScenario<WebViewRegressionActivity>? = null
    private var activity: WebViewRegressionActivity? = null
    private val views = mutableListOf<WebView>()

    @After
    fun tearDown() {
        runBlocking {
            runCatching {
                scenario?.onActivity { activity ->
                    views.forEach { view ->
                        (view.parent as? ViewGroup)?.removeView(view)
                        runCatching { view.destroy() }
                    }
                    activity.container.removeAllViews()
                }
            }
            runCatching { scenario?.close() }
            withContext(Dispatchers.Main) {
                runCatching { ProfileStore.getInstance().deleteProfile(profileName) }
                runCatching { ProfileStore.getInstance().deleteProfile(sentinelProfileName) }
            }
        }
    }

    @Test
    fun removalClearsLocalStorageAndIndexedDbForOnlyThatProfile() = runBlocking {
        val supported =
            withContext(Dispatchers.Main) {
                WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
            }
        // Unsupported MULTI_PROFILE is a failed proof, not a skipped test: the app cannot isolate a
        // named profile at all on such a device.
        assertTrue("MULTI_PROFILE required for the storage removal proof", supported)

        val scenario = ActivityScenario.launch(WebViewRegressionActivity::class.java)
        this@WebProfileRemovalWebStorageDeviceTest.scenario = scenario
        scenario.onActivity { launched -> this@WebProfileRemovalWebStorageDeviceTest.activity = launched }
        val activity = checkNotNull(this@WebProfileRemovalWebStorageDeviceTest.activity)

        val profiles = WebSessionProfiles()
        val targetBinding = withContext(Dispatchers.Main) { profiles.acquire(target) }
        val sentinelBinding = withContext(Dispatchers.Main) { profiles.acquire(sentinelTarget) }
        assertEquals(profileName, targetBinding.profileName)
        assertEquals(sentinelProfileName, sentinelBinding.profileName)

        val targetView =
            bindAndShow(targetBinding, activity, WRITE_TARGET_PAGE)
        val sentinelView =
            bindAndShow(sentinelBinding, activity, WRITE_SENTINEL_PAGE)

        awaitTitle(targetView, "probe-ready")
        awaitTitle(sentinelView, "sentinel-ready")

        // The data really exists before the removal, read from the live page and, more importantly,
        // from a second page load in the same profile (not just from the first renderer's memory).
        assertEquals("\"local-value\"", evaluate(targetView, "localStorage.getItem('andchamber_local')"))
        assertEquals("\"idb-value\"", evaluate(targetView, "window.__probeIdb"))
        assertEquals("\"sentinel-value\"", evaluate(sentinelView, "localStorage.getItem('andchamber_sentinel')"))

        val preRemovalView = bindAndShow(targetBinding, activity, READ_TARGET_PAGE)
        awaitTitle(preRemovalView, "probe-ready")
        assertEquals("\"local-value\"", evaluate(preRemovalView, "window.__probeLocal"))
        assertEquals("\"idb-value\"", evaluate(preRemovalView, "window.__probeIdb"))

        var destroyedInBeforeClear = 0
        val result =
            withContext(Dispatchers.Main) {
                profiles.removeProfile(target) {
                    // Production order: the old pages are gone before cookies and storage are cleared.
                    listOf(targetView, preRemovalView).forEach { view ->
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.destroy()
                        destroyedInBeforeClear++
                    }
                }
            }
        assertEquals(WebProfileRemovalResult.Removed, result)
        assertEquals("both target views are destroyed in beforeClear", 2, destroyedInBeforeClear)

        // A fresh instance (fresh barrier) and a fresh WebView read the same named profile back from
        // disk; nothing is asserted from the destroyed page's memory.
        val fresh = WebSessionProfiles()
        val readBackBinding = withContext(Dispatchers.Main) { fresh.acquire(target) }
        val sentinelReadBackBinding = withContext(Dispatchers.Main) { fresh.acquire(sentinelTarget) }
        val readBackView = bindAndShow(readBackBinding, activity, READ_TARGET_PAGE)
        val sentinelReadBackView = bindAndShow(sentinelReadBackBinding, activity, READ_SENTINEL_PAGE)
        awaitTitle(readBackView, "probe-ready")
        awaitTitle(sentinelReadBackView, "sentinel-ready")

        assertEquals("localStorage must be cleared", "null", evaluate(readBackView, "window.__probeLocal"))
        assertEquals("IndexedDB must be cleared", "null", evaluate(readBackView, "window.__probeIdb"))
        // A different fixture profile at the same origin keeps its storage.
        assertEquals(
            "another profile's localStorage must survive",
            "\"sentinel-value\"",
            evaluate(sentinelReadBackView, "window.__probeLocal"),
        )
    }

    private suspend fun bindAndShow(
        binding: WebProfileBinding,
        activity: WebViewRegressionActivity,
        html: String,
    ): WebView =
        withContext(Dispatchers.Main) {
            val view = WebView(activity)
            // Production binding first: setProfile must happen before settings, JS or navigation.
            binding.bindFreshWebView(view)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            activity.container.addView(view)
            views += view
            view.loadDataWithBaseURL("$origin/", html, "text/html", "UTF-8", null)
            view
        }

    private suspend fun awaitTitle(view: WebView, expected: String) {
        withTimeout(20_000) {
            while (evaluate(view, "document.title") != "\"$expected\"") delay(50)
        }
    }

    private suspend fun evaluate(view: WebView, script: String): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                view.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value) }
            }
        }

    private companion object {
        const val WRITE_TARGET_PAGE = """
            <!doctype html><meta charset="utf-8"><script>
            (function () {
              localStorage.setItem('andchamber_local', 'local-value');
              var request = indexedDB.open('andchamber_probe_db', 1);
              request.onupgradeneeded = function () { request.result.createObjectStore('values'); };
              request.onerror = function () { document.title = 'probe-error'; };
              request.onsuccess = function () {
                var db = request.result;
                var tx = db.transaction('values', 'readwrite');
                tx.objectStore('values').put('idb-value', 'probe');
                tx.onerror = function () { document.title = 'probe-error'; };
                tx.oncomplete = function () {
                  var read = db.transaction('values', 'readonly').objectStore('values').get('probe');
                  read.onerror = function () { document.title = 'probe-error'; };
                  read.onsuccess = function () {
                    window.__probeIdb = read.result;
                    db.close();
                    document.title = 'probe-ready';
                  };
                };
              };
            })();
            </script>
        """

        const val WRITE_SENTINEL_PAGE = """
            <!doctype html><script>
            localStorage.setItem('andchamber_sentinel', 'sentinel-value');
            document.title = 'sentinel-ready';
            </script>
        """

        const val READ_TARGET_PAGE = """
            <!doctype html><script>
            (function () {
              window.__probeLocal = localStorage.getItem('andchamber_local');
              window.__probeIdb = 'pending';
              var request = indexedDB.open('andchamber_probe_db', 1);
              request.onupgradeneeded = function () { request.result.createObjectStore('values'); };
              request.onerror = function () { document.title = 'probe-error'; };
              request.onsuccess = function () {
                var db = request.result;
                var read = db.transaction('values', 'readonly').objectStore('values').get('probe');
                read.onerror = function () { document.title = 'probe-error'; };
                read.onsuccess = function () {
                  window.__probeIdb = (read.result === undefined) ? null : read.result;
                  db.close();
                  document.title = 'probe-ready';
                };
              };
            })();
            </script>
        """

        const val READ_SENTINEL_PAGE = """
            <!doctype html><script>
            window.__probeLocal = localStorage.getItem('andchamber_sentinel');
            document.title = 'sentinel-ready';
            </script>
        """
    }
}

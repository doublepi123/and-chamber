package io.andchamber.app.ui.web

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Process
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import io.andchamber.app.data.HttpDownloadRequest
import io.andchamber.app.data.HttpDownloadResult
import io.andchamber.app.data.HttpDownloadStarter
import io.andchamber.app.data.WebProfileBinding
import io.andchamber.app.data.WebSessionProfiles
import io.andchamber.app.data.profileNameFor
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionCookies
import io.andchamber.app.runtime.WebSessionIdentity
import io.andchamber.app.testhost.WebViewRegressionActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Real Chromium: the pool keeps the local page and the remote page in independent slots. Opening
 * remote servers must not stop, reload or destroy the local page; a remote server replaces only the
 * remote slot, and discarding one target leaves the other slot running.
 *
 * Fixtures are a loopback [MockWebServer] per origin and random UUID remote ids, so the production
 * `local:3000` profile and any user data are never addressed. MULTI_PROFILE is required and fails
 * the test instead of skipping: the app may not fall back to a shared profile.
 */
@RunWith(AndroidJUnit4::class)
class WebViewPoolSlotInstrumentationTest {
    private val localServer = MockWebServer()
    private val firstRemoteServer = MockWebServer()
    private val secondRemoteServer = MockWebServer()
    private var scenario: ActivityScenario<WebViewRegressionActivity>? = null
    private lateinit var activity: WebViewRegressionActivity
    private val pool = WebViewPool(
        object : HttpDownloadStarter {
            override fun start(
                cookies: WebSessionCookies,
                request: HttpDownloadRequest,
                onFinished: (HttpDownloadResult) -> Unit,
            ) = error("Downloads are not part of this fixture")
        },
    )
    private val profiles = WebSessionProfiles()
    private val profileNames = linkedSetOf<String>()

    @After
    fun tearDown() =
        runBlocking {
            scenario?.onActivity { host ->
                pool.destroyAll()
                host.container.removeAllViews()
            }
            scenario?.close()
            withContext(Dispatchers.Main) {
                // Chromium keeps a profile referenced for the life of this process, so this usually
                // fails here. The record stays queued for WebViewPoolFixtureCleanupTest, which runs
                // deleteProfile in a fresh process.
                for (name in profileNames) {
                    val deleted = runCatching { ProfileStore.getInstance().deleteProfile(name) }.getOrDefault(false)
                    if (deleted) WebViewPoolFixtureProfiles.forget(name)
                }
            }
            for (server in listOf(localServer, firstRemoteServer, secondRemoteServer)) {
                runCatching { server.shutdown() }
            }
        }

    @Test
    fun localPageSurvivesOpeningTwoRemoteTargets() = runBlocking {
        start()
        val localBase = baseOf(localServer)
        val localBinding = main { acquire(ServerTarget.Local(localBase)) }
        val local = obtain(localBinding)
        load(local, localBase, title = "local-page", sessionKey = "local-session")
        val localView = checkNotNull(local.view) { "the local fixture WebView must be bound" }
        val localUrl = local.currentUrl
        val localKey = local.sessionKey
        assertEquals("$localBase/", localUrl)
        assertEquals("local-session", localKey)
        assertEquals("\"local-ok\"", js(local, "window.poolMarker = 'local-ok'"))

        val targetA = remoteTarget(firstRemoteServer)
        val remoteA = obtain(main { acquire(targetA) })
        load(remoteA, baseOf(firstRemoteServer), title = "remote-a-page", sessionKey = "remote-a-session")

        assertSame("the remote target reuses the remote slot", remoteA, pool.peek(targetA.id))
        assertSame("opening a remote must keep the local WebView object", local, pool.peek(ServerTarget.LOCAL_ID))
        assertSame("the local WebView must not be replaced", localView, local.view)
        assertEquals("the local page must not reload", localUrl, local.currentUrl)
        assertEquals("the local session must not be dropped", localKey, local.sessionKey)
        assertSame("the local WebView must stay attached", activity.container, parentOf(local))
        assertEquals("the local page must stay live", "\"local-ok\"", js(local, "window.poolMarker"))
        assertEquals("the local page must stay on its own origin", "$localBase/", shownUrl(local))

        val targetB = remoteTarget(secondRemoteServer)
        val remoteB = obtain(main { acquire(targetB) })
        load(remoteB, baseOf(secondRemoteServer), title = "remote-b-page", sessionKey = "remote-b-session")

        assertNotSame("remote B must be a fresh page", remoteA, remoteB)
        assertSame("remote B replaces the remote slot", remoteB, pool.peek(targetB.id))
        assertNull("remote A must no longer be pooled", pool.peek(targetA.id))
        assertNull("the replaced remote WebView must be detached", parentOf(remoteA))
        assertSame("opening remote B must still keep the local WebView object", local, pool.peek(ServerTarget.LOCAL_ID))
        assertSame(localView, local.view)
        assertEquals(localUrl, local.currentUrl)
        assertEquals(localKey, local.sessionKey)
        assertSame("the local WebView must still be attached", activity.container, parentOf(local))
        assertEquals("\"local-ok\"", js(local, "window.poolMarker"))
    }

    @Test
    fun sameTargetNewOriginDiscardsOnlyThatSlotAndDestroyAllClearsBoth() = runBlocking {
        start()
        val localBase = baseOf(localServer)
        val local = obtain(main { acquire(ServerTarget.Local(localBase)) })
        load(local, localBase, title = "local-page", sessionKey = "local-session")
        val localView = checkNotNull(local.view) { "the local fixture WebView must be bound" }
        val localUrl = local.currentUrl
        val localKey = local.sessionKey

        val targetId = "webview-pool-${UUID.randomUUID()}"
        val originA = baseOf(firstRemoteServer)
        val first = obtain(main { acquire(ServerTarget.Remote(targetId, "Pool fixture A", originA, null)) })
        load(first, originA, title = "remote-a-page", sessionKey = "remote-a-session")

        // Same target id, new canonical origin: a new identity, so only the remote slot rebinds.
        val originB = baseOf(secondRemoteServer)
        val second = obtain(main { acquire(ServerTarget.Remote(targetId, "Pool fixture A", originB, null)) })
        load(second, originB, title = "remote-b-page", sessionKey = "remote-b-session")

        assertNotSame("a new origin must not reuse the old page", first, second)
        assertSame(second, pool.peek(targetId))
        assertNull("the previous origin's page must be detached and destroyed", parentOf(first))
        assertSame("the local slot must not be touched", local, pool.peek(ServerTarget.LOCAL_ID))
        assertSame(localView, local.view)
        assertEquals(localUrl, local.currentUrl)
        assertEquals(localKey, local.sessionKey)

        main { pool.discard(ServerTarget.LOCAL_ID) }
        assertNull(pool.peek(ServerTarget.LOCAL_ID))
        assertSame("discarding the local page must not discard the current remote page", second, pool.peek(targetId))
        assertSame("the current remote WebView must stay attached", activity.container, parentOf(second))
        assertEquals(originB + "/", second.currentUrl)

        // Re-create the local slot, so destroyAll has both slots to clear.
        val localAgain = obtain(main { acquire(ServerTarget.Local(localBase)) })
        assertNotSame(local, localAgain)
        assertSame(localAgain, pool.peek(ServerTarget.LOCAL_ID))
        assertSame(second, pool.peek(targetId))

        main { pool.destroyAll() }
        assertNull(pool.peek(ServerTarget.LOCAL_ID))
        assertNull(pool.peek(targetId))
        assertNull("destroyAll must detach the local page", parentOf(localAgain))
        assertNull("destroyAll must detach the remote page", parentOf(second))
    }


    /**
     * A RuntimeException from the ChamberWebView constructor must not leave a destroyed page in the
     * slot. A WebProfileBinding failure is converted to `isolationFailure` by design (it does not
     * throw), so the constructor failure is injected through an invalid Context whose application
     * context access throws. This needs no production test seam.
     */
    @Test
    fun constructorFailureEmptiesOnlyTheFailingSlotAndTheNextObtainRecovers() = runBlocking {
        start()
        val localBase = baseOf(localServer)
        val local = obtain(main { acquire(ServerTarget.Local(localBase)) })
        load(local, localBase, title = "local-page", sessionKey = "local-session")
        val localView = checkNotNull(local.view) { "the local fixture WebView must be bound" }
        val localUrl = local.currentUrl
        val localKey = local.sessionKey
        js(local, "window.poolMarker = 'local-ok'")

        val shownTarget = remoteTarget(firstRemoteServer)
        val shownOrigin = baseOf(firstRemoteServer)
        val shown = obtain(main { acquire(shownTarget) })
        load(shown, shownOrigin, title = "remote-a-page", sessionKey = "remote-a-session")
        assertSame(shown, pool.peek(shownTarget.id))

        // A different remote identity forces the pool to replace the shown page. The context is the
        // injected failure: application-context access throws before the new WebView is bound.
        val replacedTarget = remoteTarget(secondRemoteServer)
        val replacedOrigin = baseOf(secondRemoteServer)
        val replacedBinding = main { acquire(replacedTarget) }
        val failingContext = object : ContextWrapper(activity) {
            override fun getApplicationContext(): Context = throw IllegalStateException(INJECTED_FAILURE)
        }
        val failure = runCatching { main { pool.obtain(failingContext, replacedBinding, false, Color.WHITE) } }
        val thrown = failure.exceptionOrNull()
        assertNotNull("the injected constructor failure must throw", thrown)
        assertTrue("the injected failure must be a RuntimeException: $thrown", thrown is RuntimeException)
        assertEquals(INJECTED_FAILURE, thrown?.message)

        assertNull("the failing slot must not retain the replaced page", pool.peek(shownTarget.id))
        assertNull("the failing slot must not retain a half-built page", pool.peek(replacedTarget.id))
        assertNull("the replaced page must have been destroyed", parentOf(shown))
        assertSame("the local slot must be untouched", local, pool.peek(ServerTarget.LOCAL_ID))
        assertSame(localView, local.view)
        assertEquals(localUrl, local.currentUrl)
        assertEquals(localKey, local.sessionKey)
        assertSame("the local page must stay attached", activity.container, parentOf(local))
        assertEquals("\"local-ok\"", js(local, "window.poolMarker"))

        // The next obtain with a valid context must create a fresh page, never reuse a destroyed one.
        val recovered = obtain(replacedBinding)
        assertNotSame("a destroyed page must never be reused", shown, recovered)
        assertSame(recovered, pool.peek(replacedTarget.id))
        load(recovered, replacedOrigin, title = "remote-b-page", sessionKey = "remote-b-session")
        assertEquals("$replacedOrigin/", recovered.currentUrl)
        assertSame("the local slot must stay untouched after recovery", local, pool.peek(ServerTarget.LOCAL_ID))
        assertEquals(localUrl, local.currentUrl)
        assertEquals("\"local-ok\"", js(local, "window.poolMarker"))
    }

    private fun start() {
        for (server in listOf(localServer, firstRemoteServer, secondRemoteServer)) {
            val title = when (server) {
                localServer -> "local-page"
                firstRemoteServer -> "remote-a-page"
                else -> "remote-b-page"
            }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse()
                        .addHeader("Content-Type", "text/html")
                        .setBody("<!doctype html><title>$title</title><p>$title</p>")
            }
            server.start()
            // Ephemeral loopback ports are in the high range, far from the production local server.
            assertTrue("fixture server must use a high loopback port: ${server.port}", server.port in 1024..65535)
        }
        assertNotEquals(
            "the fixture local target must not resolve to the production local:3000 profile",
            profileNameFor(WebSessionIdentity(ServerTarget.LOCAL_ID, "http://localhost:3000")),
            profileNameFor(WebSessionIdentity(ServerTarget.LOCAL_ID, baseOf(localServer))),
        )
        scenario = ActivityScenario.launch(WebViewRegressionActivity::class.java).also { launched ->
            launched.onActivity { host ->
                assertTrue(
                    "MULTI_PROFILE is required; the two-slot pool must fail closed rather than skip",
                    WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE),
                )
                activity = host
            }
        }
    }

    private fun acquire(target: ServerTarget): WebProfileBinding =
        profiles.acquire(target).also {
            profileNames += it.profileName
            WebViewPoolFixtureProfiles.record(it.profileName)
        }

    private suspend fun obtain(binding: WebProfileBinding): ChamberWebView {
        val web = main { pool.obtain(activity, binding, false, Color.WHITE) }
        val view = checkNotNull(web.attachableView()) { "fixture WebView must bind to its own profile" }
        main { activity.container.addView(view) }
        return web
    }

    private suspend fun load(
        web: ChamberWebView,
        base: String,
        title: String,
        sessionKey: String,
    ) {
        main { web.load("$base/", sessionKey) }
        until { js(web, "document.title") == "\"$title\"" }
    }

    private fun baseOf(server: MockWebServer): String =
        server.url("/").toString().trimEnd('/').also { require(it.startsWith("http://localhost:")) }

    private fun remoteTarget(server: MockWebServer): ServerTarget.Remote =
        ServerTarget.Remote(
            id = "webview-pool-${UUID.randomUUID()}",
            displayName = "Pool fixture",
            baseUrl = baseOf(server),
            uiPassword = null,
        )

    private suspend fun <T> main(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun parentOf(web: ChamberWebView): android.view.ViewParent? = main { web.view?.parent }

    private suspend fun shownUrl(web: ChamberWebView): String? = main { web.view?.url }

    private suspend fun js(
        web: ChamberWebView,
        script: String,
    ): String = main {
        suspendCancellableCoroutine { continuation ->
            val view = checkNotNull(web.view) { "fixture WebView is gone" }
            view.evaluateJavascript(script) { continuation.resume(it) }
        }
    }

    private suspend fun until(condition: suspend () -> Boolean) {
        withTimeout(20_000) { while (!condition()) delay(20) }
    }

    private companion object {
        const val TAG = "WebViewPoolSlot"
        const val INJECTED_FAILURE = "injected constructor failure"
    }
}

/**
 * Deletes this fixture's profiles in a fresh process, where Chromium no longer holds them:
 * `adb shell am instrument -w -e class io.andchamber.app.ui.web.WebViewPoolFixtureCleanupTest ...`.
 * Records are this fixture's own preference entries; nothing else is enumerated or deleted.
 */
@RunWith(AndroidJUnit4::class)
class WebViewPoolFixtureCleanupTest {
    @Test
    fun deleteProfilesFromAnEarlierFixtureProcess() {
        val pending = WebViewPoolFixtureProfiles.records()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val store = ProfileStore.getInstance()
            for ((name, pid) in pending) {
                if (pid == Process.myPid()) {
                    // A same-process run (connectedDebugAndroidTest) cannot delete a held profile.
                    Log.i(TAG, "deferred fixture profile $name held by pid $pid")
                    continue
                }
                assertFalse("fixture process $pid must have exited before cleanup", File("/proc/$pid").exists())
                assertTrue("only this fixture's hashed profile names", name.matches(Regex("andchamber_v1_[0-9a-f]{64}")))
                val deleted = store.deleteProfile(name)
                assertTrue(
                    "fixture profile must be deletable in a fresh process: $name",
                    deleted || name !in store.allProfileNames,
                )
                WebViewPoolFixtureProfiles.forget(name)
                Log.i(TAG, "deleted fixture profile $name from pid $pid")
            }
        }
        val fromEarlierProcess = WebViewPoolFixtureProfiles.records().filterValues { it != Process.myPid() }
        assertTrue("all fixture profiles from earlier processes were deleted: $fromEarlierProcess", fromEarlierProcess.isEmpty())
    }

    private companion object {
        const val TAG = "WebViewPoolSlot"
    }
}

/** This fixture's own pending names, so cleanup needs no shared registry or enumeration. */
internal object WebViewPoolFixtureProfiles {
    private val preferences get() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("webview_pool_fixture_profiles", Context.MODE_PRIVATE)

    private fun key(name: String) = "profile:$name"

    fun record(name: String) {
        check(preferences.edit().putInt(key(name), Process.myPid()).commit())
    }

    fun records(): Map<String, Int> =
        preferences.all.entries
            .filter { it.key.startsWith("profile:") && it.value is Int }
            .associate { it.key.removePrefix("profile:") to it.value as Int }

    fun forget(name: String) {
        check(preferences.edit().remove(key(name)).commit())
    }
}

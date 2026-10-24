package io.andchamber.app.data

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real provider: removing a remote server clears only that target's named profile, and a frozen
 * identity refuses to hand out a new binding. The clear's read-back for WebStorage is the caller's
 * job; this checks the cookie store and the profile names that are touched.
 */
@RunWith(AndroidJUnit4::class)
class WebProfileRemovalDeviceTest {
    private val target = ServerTarget.Remote(
        id = "removal-test-${UUID.randomUUID()}",
        displayName = "Removal fixture",
        baseUrl = "https://profile-removal.invalid",
        uiPassword = null,
    )
    private val origin = canonicalWebOrigin(target.baseUrl)!!
    private val url = "$origin/"
    private val profileName = profileNameFor(WebSessionIdentity(target.id, origin))
    private val otherProfileName = profileNameFor(WebSessionIdentity("other-${UUID.randomUUID()}", origin))
    private var supported = false

    @After
    fun tearDown() {
        runBlocking {
            if (supported) {
                withContext(Dispatchers.Main) {
                    runCatching { ProfileStore.getInstance().deleteProfile(profileName) }
                    runCatching { ProfileStore.getInstance().deleteProfile(otherProfileName) }
                }
            }
            Unit
        }
    }

    @Test
    fun removalClearsOnlyThatTargetProfileCookies() = runBlocking {
        supported = withContext(Dispatchers.Main) {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        }
        assumeTrue("MULTI_PROFILE required", supported)

        withContext(Dispatchers.Main) {
            val mine = ProfileStore.getInstance().getOrCreateProfile(profileName)
            val other = ProfileStore.getInstance().getOrCreateProfile(otherProfileName)
            setCookie(mine.cookieManager, url, "andchamber_probe=1")
            setCookie(other.cookieManager, url, "andchamber_probe=1")
            mine.cookieManager.flush()
            other.cookieManager.flush()
            assertTrue(cookieFor(profileName).orEmpty().contains("andchamber_probe=1"))
            assertTrue(cookieFor(otherProfileName).orEmpty().contains("andchamber_probe=1"))
        }

        val result = withContext(Dispatchers.Main) { WebSessionProfiles().removeProfile(target) }
        assertEquals(WebProfileRemovalResult.Removed, result)

        withContext(Dispatchers.Main) {
            assertNull(cookieFor(profileName))
            assertTrue(cookieFor(otherProfileName).orEmpty().contains("andchamber_probe=1"))
        }
    }

    @Test
    fun aProfileThatWasNeverCreatedIsNothingStoredAndStaysNotCreated() = runBlocking {
        supported = withContext(Dispatchers.Main) {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        }
        assumeTrue("MULTI_PROFILE required", supported)

        val result = withContext(Dispatchers.Main) { WebSessionProfiles().removeProfile(target) }
        assertEquals(WebProfileRemovalResult.NothingStored, result)
        withContext(Dispatchers.Main) {
            assertFalse(
                "a missing profile must not be created",
                profileName in ProfileStore.getInstance().allProfileNames,
            )
        }
    }

    @Test
    fun aRemovedIdentityRefusesToHandOutANewBinding() = runBlocking {
        supported = withContext(Dispatchers.Main) {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        }
        assumeTrue("MULTI_PROFILE required", supported)

        val profiles = WebSessionProfiles()
        withContext(Dispatchers.Main) { profiles.acquire(target) }
        val result = withContext(Dispatchers.Main) { profiles.removeProfile(target) }
        // A failed removal would reopen the gate; assert the success this test depends on instead of
        // letting the late acquire failure hide a different problem.
        assertEquals(WebProfileRemovalResult.NothingStored, result)

        val late = runCatching { withContext(Dispatchers.Main) { profiles.acquire(target) } }
        assertTrue(late.exceptionOrNull() is WebIsolationException)
        assertEquals(
            WebIsolationFailure.ProfileBindingFailed,
            (late.exceptionOrNull() as WebIsolationException).failure,
        )
    }

    private suspend fun setCookie(manager: CookieManager, url: String, value: String) =
        suspendCancellableCoroutine<Unit> { continuation ->
            manager.setCookie(url, value) { accepted ->
                if (accepted) continuation.resume(Unit) else continuation.resumeWithException(IllegalStateException("cookie rejected"))
            }
        }

    /** Test-only read-back through the same named profile; null means the profile has no cookie. */
    private fun cookieFor(name: String): String? = ProfileStore.getInstance().getProfile(name)?.cookieManager?.getCookie(url)
}

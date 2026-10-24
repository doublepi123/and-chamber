package io.andchamber.app.data

import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM rules for clearing one named profile when its remote server is removed: the freeze/write
 * barrier, the clear order, and that only a successful clear lets the caller delete the server.
 * The Android provider itself is not covered here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebProfileRemovalCoordinatorTest {
    private val identity = WebSessionIdentity("target-1", "https://example.com:443")
    private val otherIdentity = WebSessionIdentity("target-2", "https://example.com:443")
    private val profileName = profileNameFor(identity)
    private val otherProfileName = profileNameFor(otherIdentity)

    private class FakeProfile(
        override val name: String,
        private val log: MutableList<String>,
        private val cookieDelayMs: Long = 0,
        private val cookieCompletes: Boolean = true,
        private val storageFails: Boolean = false,
    ) : RemovableWebProfile {
        override suspend fun removeAllCookies(): Boolean {
            log += "cookies-start:$name"
            if (cookieDelayMs > 0) delay(cookieDelayMs)
            log += "cookies-end:$name"
            return cookieCompletes
        }

        override fun flushCookies() {
            log += "flush:$name"
        }

        override fun deleteAllStorage() {
            log += "storage:$name"
            if (storageFails) error("storage")
        }
    }

    private class FakeStore(
        var supported: Boolean = true,
        val profiles: MutableMap<String, RemovableWebProfile> = mutableMapOf(),
        val requested: MutableList<String> = mutableListOf(),
    ) : RemovableWebProfileStore {
        override fun profilesSupported(): Boolean = supported

        override fun existingProfile(name: String): RemovableWebProfile? {
            requested += name
            return profiles[name]
        }
    }

    @Test
    fun `a started cookie batch finishes before the page is discarded and cookies are cleared`() = runTest {
        val log = mutableListOf<String>()
        val store = FakeStore(profiles = mutableMapOf(profileName to FakeProfile(profileName, log)))
        val coordinator = WebProfileRemovalCoordinator(store)
        val gate = coordinator.gateFor(identity)
        val writer = launch {
            gate.write {
                delay(1_000)
                log += "write"
            }
        }
        runCurrent()
        assertEquals(listOf<String>(), log)

        val removal = async { coordinator.remove(identity) { log += "discard" } }
        runCurrent()
        assertTrue(gate.isRetired)
        assertFalse("discard" in log)
        assertFalse(log.any { it.startsWith("cookies-") })

        advanceUntilIdle()
        assertEquals(WebProfileRemovalResult.Removed, removal.await())
        assertEquals(
            listOf("write", "discard", "cookies-start:$profileName", "cookies-end:$profileName", "flush:$profileName", "storage:$profileName"),
            log,
        )
        writer.join()
    }

    @Test
    fun `a write started after the freeze is refused and never reaches the profile`() = runTest {
        val log = mutableListOf<String>()
        val store = FakeStore(profiles = mutableMapOf(profileName to FakeProfile(profileName, log, cookieDelayMs = 500)))
        val coordinator = WebProfileRemovalCoordinator(store)
        val gate = coordinator.gateFor(identity)
        val removal = async { coordinator.remove(identity) { log += "discard" } }
        runCurrent()
        assertTrue(gate.isRetired)

        val late = runCatching { gate.write { log += "late" } }
        assertTrue(late.exceptionOrNull() is IllegalStateException)
        assertFalse("late" in log)

        advanceUntilIdle()
        assertEquals(WebProfileRemovalResult.Removed, removal.await())
        assertFalse(coordinator.gateFor(otherIdentity).isRetired)
    }

    @Test
    fun `a late cookie write after a successful removal is refused for good`() = runTest {
        val log = mutableListOf<String>()
        val store = FakeStore(profiles = mutableMapOf(profileName to FakeProfile(profileName, log)))
        val coordinator = WebProfileRemovalCoordinator(store)
        val gate = coordinator.gateFor(identity)

        assertEquals(WebProfileRemovalResult.Removed, coordinator.remove(identity))
        assertTrue(coordinator.isUnavailable(identity))

        val late = runCatching { gate.write { log += "late" } }
        assertTrue(late.exceptionOrNull() is IllegalStateException)
        assertFalse("late" in log)
    }

    @Test
    fun `a missing profile counts as nothing stored and never creates one`() = runTest {
        val log = mutableListOf<String>()
        val store = FakeStore()
        val coordinator = WebProfileRemovalCoordinator(store)
        val gate = coordinator.gateFor(identity)

        val result = coordinator.remove(identity) { log += "discard" }

        assertEquals(WebProfileRemovalResult.NothingStored, result)
        assertEquals(listOf(profileName), store.requested)
        assertEquals(listOf("discard"), log)
        // Kept closed so a stale page of the removed server cannot recreate the profile.
        assertTrue(gate.isRetired)
    }

    @Test
    fun `without MULTI_PROFILE nothing is looked up or cleared and it is not reported as no data`() = runTest {
        val log = mutableListOf<String>()
        val store = FakeStore(supported = false)
        val coordinator = WebProfileRemovalCoordinator(store)
        val gate = coordinator.gateFor(identity)

        assertEquals(
            WebProfileRemovalResult.Failed(WebProfileRemovalFailure.UnsupportedWebView),
            coordinator.remove(identity) { log += "discard" },
        )
        assertTrue(store.requested.isEmpty())
        assertTrue(log.isEmpty())
        assertFalse(gate.isRetired)
    }

    @Test
    fun `an unfinished cookie removal fails the removal and reopens the gate`() = runTest {
        val log = mutableListOf<String>()
        val store = FakeStore(
            profiles = mutableMapOf(profileName to FakeProfile(profileName, log, cookieCompletes = false)),
        )
        val coordinator = WebProfileRemovalCoordinator(store)

        assertEquals(
            WebProfileRemovalResult.Failed(WebProfileRemovalFailure.CookiesNotCleared),
            coordinator.remove(identity),
        )
        assertEquals(listOf("cookies-start:$profileName", "cookies-end:$profileName"), log)
        assertFalse(coordinator.gateFor(identity).isRetired)
    }

    @Test
    fun `a cookie callback that never arrives times out without touching storage and reopens the gate`() = runTest {
        val log = mutableListOf<String>()
        val store = FakeStore(
            profiles = mutableMapOf(profileName to FakeProfile(profileName, log, cookieDelayMs = 10_000)),
        )
        val coordinator = WebProfileRemovalCoordinator(store, timeoutMillis = 1_000)

        assertEquals(
            WebProfileRemovalResult.Failed(WebProfileRemovalFailure.CookiesNotCleared),
            coordinator.remove(identity),
        )
        assertFalse(log.any { it.startsWith("storage:") })
        assertFalse(coordinator.gateFor(identity).isRetired)
    }

    @Test
    fun `a storage failure fails the removal and reopens the gate`() = runTest {
        val log = mutableListOf<String>()
        val store = FakeStore(
            profiles = mutableMapOf(profileName to FakeProfile(profileName, log, storageFails = true)),
        )
        val coordinator = WebProfileRemovalCoordinator(store)

        assertEquals(
            WebProfileRemovalResult.Failed(WebProfileRemovalFailure.StorageNotCleared),
            coordinator.remove(identity),
        )
        assertFalse(coordinator.gateFor(identity).isRetired)
    }

    @Test
    fun `a different profile returned by the lookup is never cleared`() = runTest {
        val log = mutableListOf<String>()
        val store = FakeStore(
            profiles = mutableMapOf(profileName to FakeProfile(otherProfileName, log)),
        )
        val coordinator = WebProfileRemovalCoordinator(store)

        assertEquals(
            WebProfileRemovalResult.Failed(WebProfileRemovalFailure.Unexpected),
            coordinator.remove(identity),
        )
        assertEquals(listOf<String>(), log)
        assertFalse(coordinator.gateFor(identity).isRetired)
    }

    @Test
    fun `a second removal of the same identity is refused while the first runs`() = runTest {
        val log = mutableListOf<String>()
        val store = FakeStore(
            profiles = mutableMapOf(profileName to FakeProfile(profileName, log, cookieDelayMs = 100)),
        )
        val coordinator = WebProfileRemovalCoordinator(store)
        val first = async { coordinator.remove(identity) }
        runCurrent()

        assertEquals(
            WebProfileRemovalResult.Failed(WebProfileRemovalFailure.AlreadyRemoving),
            coordinator.remove(identity),
        )
        advanceUntilIdle()
        assertEquals(WebProfileRemovalResult.Removed, first.await())
        assertEquals(listOf("cookies-start:$profileName", "cookies-end:$profileName", "flush:$profileName", "storage:$profileName"), log)
    }

    @Test
    fun `a cancelled removal reopens the gate and does not commit`() = runTest {
        val log = mutableListOf<String>()
        val store = FakeStore(
            profiles = mutableMapOf(profileName to FakeProfile(profileName, log, cookieDelayMs = 1_000)),
        )
        val coordinator = WebProfileRemovalCoordinator(store)
        val gate = coordinator.gateFor(identity)
        val removal = launch {
            try {
                coordinator.remove(identity)
            } catch (e: CancellationException) {
                throw e
            }
        }
        runCurrent()
        assertTrue(gate.isRetired)

        removal.cancel()
        removal.join()
        assertFalse(gate.isRetired)
        assertFalse(log.any { it.startsWith("storage:") })

        // The same identity can be removed again after the cancellation.
        advanceUntilIdle()
        assertEquals(WebProfileRemovalResult.Removed, coordinator.remove(identity))
    }

    @Test
    fun `removing one identity does not freeze or clear another identity`() = runTest {
        val log = mutableListOf<String>()
        val store = FakeStore(
            profiles = mutableMapOf(
                profileName to FakeProfile(profileName, log, cookieDelayMs = 100),
                otherProfileName to FakeProfile(otherProfileName, log),
            ),
        )
        val coordinator = WebProfileRemovalCoordinator(store)
        val removal = async { coordinator.remove(identity) { log += "discard" } }
        runCurrent()
        assertTrue(coordinator.isUnavailable(identity))
        assertFalse(coordinator.isUnavailable(otherIdentity))

        val otherWrite = async {
            coordinator.gateFor(otherIdentity).write { log += "write:$otherProfileName" }
        }
        advanceUntilIdle()
        otherWrite.await()
        assertEquals(WebProfileRemovalResult.Removed, removal.await())

        assertEquals(listOf(profileName), store.requested)
        assertTrue("write:$otherProfileName" in log)
        assertTrue(coordinator.isUnavailable(identity))
        assertFalse(coordinator.isUnavailable(otherIdentity))
        assertFalse(log.any { it.endsWith(":$otherProfileName") && !it.startsWith("write:") })
    }

    @Test
    fun `removal names the current canonical origin and refuses an address it cannot name`() {
        val target = ServerTarget.Remote(
            id = "target-1",
            displayName = "Example",
            baseUrl = "https://Example.com:8443/some/path?q=1",
            uiPassword = null,
        )
        assertEquals(WebSessionIdentity("target-1", "https://example.com:8443"), profileRemovalIdentity(target))

        assertNull(
            profileRemovalIdentity(
                ServerTarget.Remote("target-1", "Example", "http://user:pw@example.com:3000", null),
            ),
        )
        assertNull(profileRemovalIdentity(ServerTarget.Remote("target-1", "Example", "ftp://example.com", null)))
    }
}

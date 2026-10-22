package io.andchamber.app.ui.browser

import io.andchamber.app.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSiteDataClearerTest {
    private val consoleProfile = "andchamber_v1_" + "0".repeat(64)

    private class FakeProfile(
        override val name: String,
        private val log: MutableList<String>,
        private val cookiesDone: Boolean = true,
        private val storageFails: Boolean = false,
    ) : BrowserProfileData {
        override suspend fun removeAllCookies(): Boolean { log += "cookies:$name"; return cookiesDone }
        override fun flushCookies() { log += "flush:$name" }
        override fun deleteAllStorage() {
            log += "storage:$name"
            if (storageFails) throw IllegalStateException("storage")
        }
    }

    private class FakeOps(
        val log: MutableList<String> = mutableListOf(),
        var supported: Boolean = true,
        var pageShown: Boolean = true,
        var pageClearFails: Boolean = false,
        var reloads: Boolean = true,
        val profiles: MutableMap<String, BrowserProfileData> = mutableMapOf(),
    ) : BrowserSiteDataOps {
        val requested = mutableListOf<String>()
        override fun revokeAgent(reason: String) { log += "revoke:$reason" }
        override fun profilesSupported(): Boolean { log += "supported"; return supported }
        override fun existingProfile(name: String): BrowserProfileData? { requested += name; return profiles[name] }
        override fun clearCurrentPage(): PageCacheClear {
            log += "page"
            if (!pageShown) return PageCacheClear.NoPage
            return if (pageClearFails) PageCacheClear.Failed else PageCacheClear.Cleared
        }
        override fun reloadByHand(): Boolean { log += "reload"; return reloads }
    }

    /** Both the browser profile and a console-style profile exist; only the first may be touched. */
    private fun ops(): FakeOps {
        val ops = FakeOps()
        ops.profiles[BrowserDriverLogic.PROFILE_NAME] = FakeProfile(BrowserDriverLogic.PROFILE_NAME, ops.log)
        ops.profiles[consoleProfile] = FakeProfile(consoleProfile, ops.log)
        return ops
    }

    @Test fun `revokes first, then clears only the browser profile, then the page, then reloads by hand`() = runBlocking {
        val ops = ops()
        val result = BrowserSiteDataClearer(ops).clear()
        assertEquals(SiteDataClearResult.Cleared(reloaded = true), result)
        val p = BrowserDriverLogic.PROFILE_NAME
        assertEquals(
            listOf("revoke:${BrowserSiteDataClearer.REVOKE_REASON}", "supported", "cookies:$p", "flush:$p", "storage:$p", "page", "reload"),
            ops.log,
        )
        assertEquals(listOf(p), ops.requested)
        assertTrue(ops.log.none { it.endsWith(consoleProfile) })
    }

    @Test fun `without separate profiles the grant is still revoked and nothing is cleared`() = runBlocking {
        val ops = ops().apply { supported = false }
        assertEquals(SiteDataClearResult.Failed, BrowserSiteDataClearer(ops).clear())
        assertEquals(listOf("revoke:${BrowserSiteDataClearer.REVOKE_REASON}", "supported"), ops.log)
        assertTrue(ops.requested.isEmpty())
    }

    @Test fun `a missing browser profile is never created and the console profile is not used instead`() = runBlocking {
        val ops = ops()
        ops.profiles.remove(BrowserDriverLogic.PROFILE_NAME)
        assertEquals(SiteDataClearResult.NothingStored, BrowserSiteDataClearer(ops).clear())
        assertEquals(listOf(BrowserDriverLogic.PROFILE_NAME), ops.requested)
        assertTrue(ops.log.none { it.endsWith(consoleProfile) })
        assertTrue("page" !in ops.log && "reload" !in ops.log)
    }

    @Test fun `a profile with another name is refused`() = runBlocking {
        val ops = ops()
        ops.profiles[BrowserDriverLogic.PROFILE_NAME] = FakeProfile(consoleProfile, ops.log)
        assertEquals(SiteDataClearResult.Failed, BrowserSiteDataClearer(ops).clear())
        assertTrue(ops.log.none { it.startsWith("cookies") || it.startsWith("storage") })
    }

    @Test fun `cookies that did not finish stop before storage and reload`() = runBlocking {
        val ops = ops()
        ops.profiles[BrowserDriverLogic.PROFILE_NAME] =
            FakeProfile(BrowserDriverLogic.PROFILE_NAME, ops.log, cookiesDone = false)
        assertEquals(SiteDataClearResult.Failed, BrowserSiteDataClearer(ops).clear())
        assertTrue(ops.log.none { it.startsWith("flush") || it.startsWith("storage") || it == "reload" })
    }

    @Test fun `a storage failure is reported and nothing is reloaded`() = runBlocking {
        val ops = ops()
        ops.profiles[BrowserDriverLogic.PROFILE_NAME] =
            FakeProfile(BrowserDriverLogic.PROFILE_NAME, ops.log, storageFails = true)
        assertEquals(SiteDataClearResult.Failed, BrowserSiteDataClearer(ops).clear())
        assertTrue("reload" !in ops.log)
    }

    @Test fun `a page cache failure after cookies and storage is partial and nothing is reloaded`() = runBlocking {
        val ops = ops().apply { pageClearFails = true }
        assertEquals(SiteDataClearResult.PartiallyCleared, BrowserSiteDataClearer(ops).clear())
        val p = BrowserDriverLogic.PROFILE_NAME
        assertEquals(
            listOf("revoke:${BrowserSiteDataClearer.REVOKE_REASON}", "supported", "cookies:$p", "flush:$p", "storage:$p", "page"),
            ops.log,
        )
        assertTrue("reload" !in ops.log)
    }

    @Test fun `no shown page clears data and says the page was not reloaded`() = runBlocking {
        val ops = ops().apply { pageShown = false }
        assertEquals(SiteDataClearResult.Cleared(reloaded = false), BrowserSiteDataClearer(ops).clear())
        assertTrue("reload" !in ops.log)
    }

    @Test fun `result messages`() {
        assertEquals(R.string.browser_site_data_cleared, browserSiteDataResultResource(SiteDataClearResult.Cleared(true)))
        assertEquals(R.string.browser_site_data_cleared_no_reload, browserSiteDataResultResource(SiteDataClearResult.Cleared(false)))
        assertEquals(R.string.browser_site_data_partial, browserSiteDataResultResource(SiteDataClearResult.PartiallyCleared))
        assertEquals(R.string.browser_site_data_cleared, browserSiteDataResultResource(SiteDataClearResult.NothingStored))
        assertEquals(R.string.browser_site_data_failed, browserSiteDataResultResource(SiteDataClearResult.Failed))
    }
}

package io.andchamber.app.ui.web

import io.andchamber.app.data.canonicalWebOrigin
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure slot-selection rules of [WebViewPool]: the local server keeps its own slot, every remote
 * target shares the remote slot, and a new origin is a new identity (so `obtain` rebinds).
 */
class WebViewSlotTest {
    @Test
    fun `the local target owns the local slot`() {
        assertEquals(WebViewSlot.Local, WebViewSlot.of(ServerTarget.LOCAL_ID))
        assertEquals(WebViewSlot.Local, WebViewSlot.of(ServerTarget.Local("http://localhost:49152").id))
    }

    @Test
    fun `every remote target shares the single remote slot`() {
        val first = ServerTarget.Remote(
            id = "webview-pool-6f1c2b1e-0000-4000-8000-000000000001",
            displayName = "Remote A",
            baseUrl = "http://localhost:49153",
            uiPassword = null,
        )
        val second = ServerTarget.Remote(
            id = "webview-pool-6f1c2b1e-0000-4000-8000-000000000002",
            displayName = "Remote B",
            baseUrl = "http://localhost:49154",
            uiPassword = null,
        )
        assertEquals(WebViewSlot.Remote, WebViewSlot.of(first.id))
        // Same slot, different identities: opening B replaces A without touching the local slot.
        assertEquals(WebViewSlot.of(first.id), WebViewSlot.of(second.id))
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `the same target at another origin is another identity`() {
        val targetId = "webview-pool-6f1c2b1e-0000-4000-8000-000000000001"
        val first = WebSessionIdentity(targetId, canonicalWebOrigin("http://localhost:49153")!!)
        val second = WebSessionIdentity(targetId, canonicalWebOrigin("http://localhost:49154")!!)
        assertEquals("both stay in the remote slot", WebViewSlot.of(first.targetId), WebViewSlot.of(second.targetId))
        // `obtain` reuses only on full identity equality, so this pair forces a rebind of that slot.
        assertNotEquals(first, second)
    }
}

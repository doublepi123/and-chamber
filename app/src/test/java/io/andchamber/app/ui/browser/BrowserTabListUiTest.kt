package io.andchamber.app.ui.browser

import io.andchamber.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure projection rules for the page counter, the page list and the New page failure text. */
class BrowserTabListUiTest {
    private fun tab(id: String, title: String = "", url: String = "https://example.com/$id") =
        NativeBrowserTab(id = id, title = title, url = url, owner = "session-a", active = false)

    @Test
    fun `one page shows no counter, two or more show the current position`() {
        val one = browserTabEntries(listOf(tab("a")), "a", null, "Browser")
        assertNull(browserTabPosition(one))

        val three = browserTabEntries(listOf(tab("a"), tab("b"), tab("c")), "b", null, "Browser")
        assertEquals(BrowserTabPosition(2, 3), browserTabPosition(three))
        assertEquals("2/3", browserTabPositionText(BrowserTabPosition(2, 3)))
        assertEquals(listOf(false, true, false), three.map { it.current })
    }

    @Test
    fun `no current page means no counter`() {
        val entries = browserTabEntries(listOf(tab("a"), tab("b")), "gone", null, "Browser")
        assertNull(browserTabPosition(entries))
    }

    @Test
    fun `large counts keep a compact chip`() {
        assertEquals("7/99+", browserTabPositionText(BrowserTabPosition(7, 140)))
        assertEquals("12/40", browserTabPositionText(BrowserTabPosition(12, 40)))
    }

    @Test
    fun `title prefers the tab title, then the shown view title for the current page, then the host`() {
        val entries = browserTabEntries(
            listOf(
                tab("a", title = "  Docs \n home  "),
                tab("b", title = "", url = "https://www.example.org/x?y"),
                tab("c", title = ""),
            ),
            selectedTabId = "c",
            shownTitle = "Shown view title",
            fallbackTitle = "Browser",
        )
        assertEquals("Docs home", entries[0].title)
        // A background page never borrows the shown WebView's title.
        assertEquals("example.org", entries[1].title)
        assertEquals("example.org", entries[1].host)
        assertEquals("Shown view title", entries[2].title)
    }

    @Test
    fun `no title and no host fall back to the generic name`() {
        assertEquals("Browser", browserTabTitle(" ", null, "", "Browser"))
        assertEquals("", browserTabHost(null))
        assertEquals("", browserTabHost("  "))
        assertEquals("a.example.com", browserTabHost("https://user@A.Example.com/p"))
    }

    @Test
    fun `new page failure names a refused address, otherwise the generic new page failure`() {
        assertEquals(R.string.browser_open_page_refused, browserNewTabFailureResource("url refused"))
        assertEquals(R.string.browser_new_tab_failed, browserNewTabFailureResource("no owner"))
        assertEquals(R.string.browser_new_tab_failed, browserNewTabFailureResource(null))
    }

    @Test
    fun `item and close tags are stable per position`() {
        assertEquals("BrowserTabItem0", browserTabItemTag(0))
        assertEquals("BrowserTabClose2", browserTabCloseTag(2))
    }
}

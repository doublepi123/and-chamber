package io.andchamber.app.browser

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.andchamber.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The floating browser's several-page UI, on the real production [io.andchamber.app.ui.browser.BrowserFloatPage],
 * presenter, WebView and in-memory example.com fixture ([BrowserAuthorityHarness]):
 *
 *  - two pages show a compact "2/2" counter in the address area, whose accessible name is the
 *    position and whose click opens the page list; the address keeps the real URL as its name;
 *  - the list marks the shown page as selected, names each page by title or host, switches on a
 *    click (revoking the grant, never restoring it), and closes a background page in place;
 *  - More > New page opens an empty address editor; Cancel leaves everything as it was; Go opens
 *    one new page through the presenter and the counter follows it; closing the shown page from
 *    the list selects its neighbour.
 *
 * Nodes are found by their test tags (exposed as resource ids) or exact localized labels; every
 * action is one real ACTION_CLICK / ACTION_SET_TEXT. No skip path, no network, no runtime.
 */
@RunWith(AndroidJUnit4::class)
class BrowserTabsUiInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun counterListSwitchCloseAndNewPage() {
        val harness = BrowserAuthorityHarness(instrumentation, "tabs-ui")
        try {
            enableWindowRetrieval()
            val ui = TabsUi(harness)
            val first = harness.openDefault(OpenChamberProviderFixtures.ORIGIN, "tabs-open-1")
            assertTrue("first open failed: ${first.errorCode} ${first.message}", first.ok)
            harness.awaitPageReady()
            val firstTab = harness.state.selectedTabId
            assertNotNull(firstTab)
            val second = harness.openDefault(OpenChamberProviderFixtures.ORIGIN2, "tabs-open-2")
            assertTrue("second open failed: ${second.errorCode} ${second.message}", second.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            val secondTab = harness.state.selectedTabId
            assertNotEquals("the second open must be a new page", firstTab, secondTab)
            assertEquals(2, harness.state.tabs.size)

            harness.showProductionBrowserUi()
            val activity = harness.activity

            // ---- Counter: compact, its own name, opens the list. ----
            val counter = ui.awaitTag("BrowserTabCounter")
            assertEquals(activity.getString(R.string.browser_tabs_position, 2, 2), counter.contentDescription?.toString())
            assertTrue("the counter must be a 48dp target", ui.heightDp(counter) >= 47.5f && ui.widthDp(counter) >= 47.5f)

            // ---- Address keeps the real address as its name, plus the edit action. ----
            val editLabel = activity.getString(R.string.browser_address_edit)
            val address = ui.awaitTag("BrowserAddress")
            assertEquals(OpenChamberProviderFixtures.ORIGIN2, address.contentDescription?.toString())
            assertTrue(
                "the address must offer the edit action",
                address.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == editLabel },
            )
            assertNull("the fixed word must not be the address name", ui.findLabel("Web address"))

            // ---- List: current item selected, meaningful titles, 48dp close. ----
            harness.await("current tab title", 15_000L) {
                harness.state.tabs.firstOrNull { it.id == secondTab }?.title?.isNotBlank() == true
            }
            assertTrue("the counter must accept one click", ui.clickTag("BrowserTabCounter"))
            ui.awaitTag("BrowserTabList")
            val row0 = ui.awaitTag("BrowserTabItem0")
            val row1 = ui.awaitTag("BrowserTabItem1")
            val currentLabel = activity.getString(R.string.browser_tab_current)
            assertTrue("the shown page must read as selected", ui.isCurrent(row1, currentLabel))
            assertFalse("a background page must not read as selected", ui.isCurrent(row0, currentLabel))
            val meaningful = setOf("provider-fixture", "example.com")
            for (row in listOf(row0, row1)) {
                val texts = ui.texts(row)
                assertTrue("row must show a title or host, got $texts", texts.any { it in meaningful })
            }
            val close0 = ui.awaitTag("BrowserTabClose0")
            assertTrue("close must be a 48dp target", ui.heightDp(close0) >= 47.5f && ui.widthDp(close0) >= 47.5f)

            // ---- Switch: one click selects, revokes, and the counter follows. ----
            assertTrue(ui.clickTag("BrowserTabItem0"))
            harness.await("first page selected") { harness.state.selectedTabId == firstTab }
            assertNull("selecting another page revokes the grant", harness.presenter.registry.currentGrant())
            assertFalse("selecting never restores control by itself", harness.state.agentOn)
            ui.awaitDescription("BrowserTabCounter", activity.getString(R.string.browser_tabs_position, 1, 2))
            ui.awaitGone("BrowserTabList")

            // ---- Close a background page from the list: selection stays. ----
            assertTrue(ui.clickTag("BrowserTabCounter"))
            ui.awaitTag("BrowserTabList")
            assertTrue(ui.clickTag("BrowserTabClose1"))
            harness.await("background page closed") { harness.state.tabs.size == 1 }
            assertEquals("closing a background page keeps the shown one", firstTab, harness.state.selectedTabId)
            ui.awaitGone("BrowserTabCounter")
            ui.awaitGone("BrowserTabList")

            // ---- New page: empty editor, Cancel changes nothing. ----
            val more = activity.getString(R.string.browser_more)
            val newPage = activity.getString(R.string.browser_new_tab)
            assertTrue(ui.clickLabel(more))
            assertTrue("More must offer New page", ui.clickLabel(newPage))
            val field = ui.awaitTag("BrowserNewTabField")
            assertTrue("the new page editor must start empty, got '${field.text}'", field.text.isNullOrEmpty())
            assertTrue("the new page address must be editable", field.isEditable)
            assertTrue(ui.clickTag("BrowserAddressCancel"))
            ui.awaitGone("BrowserNewTabField")
            ui.awaitTag("BrowserAddress")
            assertEquals(1, harness.state.tabs.size)
            assertEquals(firstTab, harness.state.selectedTabId)

            // ---- New page: Go opens one new page; the counter shows 2/2. ----
            assertTrue(ui.clickLabel(more))
            ui.awaitLabel(newPage)
            assertTrue(ui.clickLabel(newPage))
            assertTrue("the field must accept text", ui.setText("BrowserNewTabField", OpenChamberProviderFixtures.ORIGIN2))
            assertTrue(ui.clickLabel(activity.getString(R.string.browser_address_go)))
            harness.await("new page opened") { harness.state.tabs.size == 2 && harness.state.selectedTabId != firstTab }
            val newTab = harness.state.selectedTabId
            harness.awaitPageReady()
            ui.awaitDescription("BrowserTabCounter", activity.getString(R.string.browser_tabs_position, 2, 2))

            // ---- Close the shown page from the list: the presenter picks the neighbour. ----
            assertTrue(ui.clickTag("BrowserTabCounter"))
            ui.awaitTag("BrowserTabList")
            assertTrue(ui.isCurrent(ui.awaitTag("BrowserTabItem1"), currentLabel))
            assertTrue(ui.clickTag("BrowserTabClose1"))
            harness.await("shown page closed") { harness.state.tabs.none { it.id == newTab } }
            assertEquals("closing the last shown page falls back to its neighbour", firstTab, harness.state.selectedTabId)
            ui.awaitGone("BrowserTabCounter")
        } finally {
            harness.dispose()
        }
    }

    /** Popups (the page list, the menu) are their own windows; read every interactive window. */
    private fun enableWindowRetrieval() {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
    }

    private inner class TabsUi(private val harness: BrowserAuthorityHarness) {
        private val pkg = harness.activity.packageName
        private val density = harness.activity.resources.displayMetrics.density

        private fun roots(): List<AccessibilityNodeInfo> {
            if (Build.VERSION.SDK_INT >= 34) runCatching { instrumentation.uiAutomation.clearCache() }
            val list = mutableListOf<AccessibilityNodeInfo>()
            runCatching { instrumentation.uiAutomation.windows }.getOrNull()?.forEach { w -> w.root?.let { list += it } }
            runCatching { instrumentation.uiAutomation.rootInActiveWindow }.getOrNull()?.let { list += it }
            return list
        }

        private fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            val seen = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
            val queue = ArrayDeque(roots())
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (!seen.add(node)) continue
                if (node.packageName?.toString() == pkg && node.isVisibleToUser && match(node)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
            }
            return null
        }

        fun findTag(tag: String) = find { it.viewIdResourceName == tag }
        fun findLabel(label: String) = find {
            it.text?.toString() == label || it.contentDescription?.toString() == label
        }

        private fun <T> await(what: String, timeoutMs: Long = 15_000L, read: () -> T?): T {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                read()?.let { return it }
                SystemClock.sleep(100L)
            }
            throw AssertionError("timed out waiting for $what; state=${harness.state}")
        }

        fun awaitTag(tag: String) = await("tag $tag") { findTag(tag) }
        fun awaitLabel(label: String) = await("label $label") { findLabel(label) }
        fun awaitGone(tag: String) = await("tag $tag gone") { if (findTag(tag) == null) true else null }
        fun awaitDescription(tag: String, description: String) = await("tag $tag = $description") {
            findTag(tag)?.takeIf { it.contentDescription?.toString() == description }
        }

        private fun click(node: AccessibilityNodeInfo?): Boolean {
            var current = node
            while (current != null) {
                if (current.isClickable) return current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current = current.parent
            }
            return false
        }

        fun clickTag(tag: String) = click(awaitTag(tag))
        fun clickLabel(label: String) = click(awaitLabel(label))

        fun setText(tag: String, value: String): Boolean {
            val node = awaitTag(tag)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        fun isCurrent(node: AccessibilityNodeInfo, currentLabel: String): Boolean =
            node.isSelected || (Build.VERSION.SDK_INT >= 30 && node.stateDescription?.toString() == currentLabel)

        fun texts(node: AccessibilityNodeInfo): List<String> {
            val out = mutableListOf<String>()
            node.text?.let { out += it.toString() }
            node.contentDescription?.let { out += it.toString() }
            for (i in 0 until node.childCount) node.getChild(i)?.let { out += texts(it) }
            return out.flatMap { it.split(", ", "\n") + it }
        }

        private fun bounds(node: AccessibilityNodeInfo) = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        fun heightDp(node: AccessibilityNodeInfo) = bounds(node).height() / density
        fun widthDp(node: AccessibilityNodeInfo) = bounds(node).width() / density
    }
}

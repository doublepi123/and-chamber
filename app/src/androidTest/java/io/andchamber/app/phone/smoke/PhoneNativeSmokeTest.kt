package io.andchamber.app.phone.smoke

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.andchamber.app.MainActivity
import io.andchamber.app.R
import io.andchamber.app.phone.android.PhoneControlService
import io.andchamber.app.phone.android.phoneIntegrationStore
import io.andchamber.app.service.RuntimeService
import io.andchamber.app.ui.UiPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/**
 * Native-UI smoke for the experimental phone-control entry. It uses only the platform
 * UiAutomation/AccessibilityNodeInfo tree and ActivityScenario; no Compose test library or
 * UiAutomator dependency is used.
 *
 * Safety:
 * - refuses the production package; run a debug build with a dedicated
 *   `-Pandchamber.testApplicationId=...`;
 * - requires the phone store to be off (a fresh test installation), never calls the controller,
 *   never grants, pairs, connects, reads another app's screen or starts the runtime;
 * - the auto-open UI preference is turned off before launch so the fresh app lands on Home;
 * - screen/font changes run only on an emulator, save the original values and restore them;
 * - every activity is closed at the end.
 *
 * This is UI/entry/accessibility smoke only. A green run says nothing about TLS, ADB pairing,
 * the OpenCode tool loop or real phone actions.
 */
@RunWith(AndroidJUnit4::class)
class PhoneNativeSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        val expected = InstrumentationRegistry.getArguments().getString(ARG_EXPECTED_PACKAGE)
        if (expected != null) assertEquals(expected, context.packageName)
        if (context.packageName == PRODUCTION_PACKAGE) {
            throw AssertionError(
                "phone smoke refuses the production package; build a debug test app with " +
                    "-Pandchamber.testApplicationId=<id>",
            )
        }
        // This is launch preference only. The phone switch itself is never touched by the test.
        context.getSharedPreferences(UiPrefs.FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(UiPrefs.KEY_AUTO_OPEN_LOCAL, false)
            .remove(UiPrefs.KEY_LAST_TARGET_ID)
            .commit()
        assertFalse(
            "phone control must start off; install a fresh test package",
            phoneIntegrationStore(context).enabled(),
        )
    }

    @After
    fun tearDown() {
        closeActivity()
    }

    @Test
    fun homeEntryOpensOffScreenAndRiskKeepsOff() {
        launchApp()

        val entry = scrollToText(string(R.string.phone_entry_body))
            ?: throw AssertionError("home phone entry is missing")
        assertTrue("home phone entry is not clickable", click(entry))

        val enableTitle = scrollToText(string(R.string.phone_enable_title))
        assertNotNull("phone screen did not open", enableTitle)
        assertVisibleText(R.string.phone_experimental)
        assertVisibleText(R.string.phone_risk_title)
        assertVisibleText(R.string.phone_enable_limits)
        assertVisibleText(R.string.phone_risk_ack)

        assertNotChecked(string(R.string.phone_risk_ack))
        val disabledEnable = assertDisabled(string(R.string.phone_enable))
        assertFalse("disabled enable action was performed", disabledEnable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertFalse("phone control turned on without confirmation", phoneIntegrationStore(context).enabled())
        assertNoRuntimeOrPhoneService()

        assertTrue("phone screen back control is not clickable", clickDescription(string(R.string.back)))
        assertNotNull(
            "back did not return to Home",
            scrollToText(string(R.string.phone_entry_body)),
        )
        assertFalse("phone control changed after back", phoneIntegrationStore(context).enabled())
    }

    @Test
    fun phoneStringsHaveEnglishAndChineseResources() {
        val base = context.resources.configuration
        val english = context.createConfigurationContext(
            Configuration(base).apply { setLocale(Locale.ENGLISH) },
        )
        val chinese = context.createConfigurationContext(
            Configuration(base).apply { setLocale(Locale.SIMPLIFIED_CHINESE) },
        )
        val ids = listOf(
            R.string.phone_title,
            R.string.phone_experimental,
            R.string.phone_entry_body,
            R.string.phone_enable_title,
            R.string.phone_risk_title,
            R.string.phone_risk_ack,
            R.string.phone_enable,
            R.string.phone_enable_limits,
            R.string.back,
        )
        for (id in ids) {
            val en = english.getString(id)
            val zh = chinese.getString(id)
            assertTrue("missing English string ${context.resources.getResourceEntryName(id)}", en.isNotBlank())
            assertTrue("missing Chinese string ${context.resources.getResourceEntryName(id)}", zh.isNotBlank())
        }
        // A few phone strings must really be translated, not only present as an English fallback.
        for (id in listOf(R.string.phone_title, R.string.phone_risk_ack, R.string.phone_enable)) {
            assertTrue(
                "${context.resources.getResourceEntryName(id)} is not localized",
                english.getString(id) != chinese.getString(id),
            )
        }
    }

    @Test
    fun largeFontNarrowScreenKeepsEntryAndBackReachable() {
        Assume.assumeTrue("screen/font changes are emulator-only", isEmulator())
        val original = readDisplaySettings()
        try {
            shell("settings put system font_scale 1.5")
            shell("wm size 720x1280")
            shell("wm density 320")
            SystemClock.sleep(600)

            launchApp()
            val entry = scrollToText(string(R.string.phone_entry_body))
                ?: throw AssertionError("phone entry is not reachable at large font/narrow width")
            assertTrue("phone entry is not clickable at large font/narrow width", click(entry))

            val backLabel = waitForDescription(string(R.string.back))
                ?: throw AssertionError("back control is missing at large font/narrow width")
            val back = clickableAncestor(backLabel)
                ?: throw AssertionError("back label has no clickable control")
            assertTrue("back control is not visible", back.isVisibleToUser)
            assertTrue("back control is not clickable", back.isClickable && back.isEnabled)
            assertTrue("back control has empty bounds", back.hasArea())

            assertNotNull(
                "enable card is not reachable at large font/narrow width",
                scrollToText(string(R.string.phone_enable_title)),
            )
            val enable = scrollToText(string(R.string.phone_enable))
                ?: throw AssertionError("enable button is not reachable at large font/narrow width")
            assertTrue("enable button is not visible", enable.isVisibleToUser)
            assertTrue("enable button has empty bounds", enable.hasArea())
            assertTrue("risk acknowledgement is not reachable", scrollToText(string(R.string.phone_risk_ack)) != null)

            assertTrue("back did not leave the phone screen", clickDescription(string(R.string.back)))
            assertNotNull(
                "Home entry is not reachable after back at large font/narrow width",
                scrollToText(string(R.string.phone_entry_body)),
            )
            assertFalse("phone control changed in large font test", phoneIntegrationStore(context).enabled())
        } finally {
            closeActivity()
            restoreDisplaySettings(original)
        }
    }

    private fun launchApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            // At large font sizes the phone entry starts below the fold. Wait for the app window,
            // then let scrollToText establish that the real entry is reachable.
            if (findNode { it.packageName?.toString() == context.packageName && it.isVisibleToUser } != null) return
            SystemClock.sleep(100)
        }
        throw AssertionError("App window did not become reachable")
    }

    private fun closeActivity() {
        scenario?.close()
        scenario = null
    }

    private fun string(id: Int): String = context.getString(id)

    private fun assertVisibleText(id: Int) {
        assertNotNull(
            "missing text ${context.resources.getResourceEntryName(id)}",
            scrollToText(string(id)),
        )
    }

    private fun assertDisabled(label: String): AccessibilityNodeInfo {
        val node = scrollToText(label) ?: throw AssertionError("disabled control is missing: $label")
        val target = clickableAncestor(node) ?: node
        assertFalse("control should be disabled: $label", target.isEnabled)
        return target
    }

    private fun assertNotChecked(label: String) {
        val node = scrollToText(label) ?: throw AssertionError("risk acknowledgement is missing")
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            if (current?.isCheckable == true) {
                assertFalse("risk acknowledgement must start unchecked", current!!.isChecked)
                return
            }
            current = current?.parent
        }
        throw AssertionError("risk acknowledgement is not exposed as a checkable node")
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        val target = clickableAncestor(node) ?: return false
        if (!target.isEnabled || !target.isVisibleToUser) return false
        if (Build.VERSION.SDK_INT >= 34) runCatching { instrumentation.uiAutomation.clearCache() }
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickDescription(description: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val node = findNode { it.contentDescription?.toString() == description && it.isVisibleToUser }
            if (node != null && click(node)) return true
            scrollBackward()
            SystemClock.sleep(100)
        }
        return false
    }

    private fun scrollToText(label: String): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            findNode { textMatches(it, label) && it.isVisibleToUser }?.let { return it }
            if (!scrollForward()) {
                SystemClock.sleep(100)
            } else {
                SystemClock.sleep(250)
            }
        }
        return findNode { textMatches(it, label) && it.isVisibleToUser }
    }

    private fun waitForDescription(description: String): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            findNode { it.contentDescription?.toString() == description && it.isVisibleToUser }?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }

    private fun textMatches(node: AccessibilityNodeInfo, label: String): Boolean =
        node.text?.toString()?.contains(label) == true ||
            node.contentDescription?.toString() == label

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            if (current == null) return null
            if (current!!.isClickable) return current
            current = current!!.parent
        }
        return null
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = try {
            if (Build.VERSION.SDK_INT >= 34) runCatching { instrumentation.uiAutomation.clearCache() }
            instrumentation.uiAutomation.rootInActiveWindow
        } catch (_: Throwable) {
            null
        } ?: return null
        if (root.packageName?.toString() != context.packageName) return null
        val visited = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        visited.add(root)
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                if (visited.add(child)) queue.add(child)
            }
        }
        return null
    }

    private fun scrollForward(): Boolean =
        findNode {
            it.isVisibleToUser &&
                (it.isScrollable || it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
        }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true

    private fun scrollBackward(): Boolean =
        findNode {
            it.isVisibleToUser &&
                (it.isScrollable || it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD })
        }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true

    private fun AccessibilityNodeInfo.hasArea(): Boolean {
        val bounds = android.graphics.Rect()
        getBoundsInScreen(bounds)
        return bounds.width() > 0 && bounds.height() > 0
    }

    private fun assertNoRuntimeOrPhoneService() {
        @Suppress("DEPRECATION")
        val running = context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .filter { it.service.packageName == context.packageName }
            .map { it.service.className }
        assertFalse("RuntimeService started during phone smoke", running.contains(RuntimeService::class.java.name))
        assertFalse("PhoneControlService started during phone smoke", running.contains(PhoneControlService::class.java.name))
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun isEmulator(): Boolean = runCatching { shell("getprop ro.kernel.qemu").trim() == "1" }
        .getOrDefault(false)

    private fun readDisplaySettings(): DisplaySettings =
        DisplaySettings(
            sizeOverride = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1),
            densityOverride = Regex("Override density: (\\d+)").find(shell("wm density"))?.groupValues?.get(1),
            fontScale = shell("settings get system font_scale").trim().takeUnless { it == "null" },
        )

    private fun restoreDisplaySettings(original: DisplaySettings) {
        if (original.sizeOverride != null) shell("wm size ${original.sizeOverride}") else shell("wm size reset")
        if (original.densityOverride != null) {
            shell("wm density ${original.densityOverride}")
        } else {
            shell("wm density reset")
        }
        if (original.fontScale != null) {
            shell("settings put system font_scale ${original.fontScale}")
        } else {
            shell("settings delete system font_scale")
        }
    }

    private data class DisplaySettings(
        val sizeOverride: String?,
        val densityOverride: String?,
        val fontScale: String?,
    )

    private companion object {
        const val PRODUCTION_PACKAGE = "io.andchamber.app"
        const val ARG_EXPECTED_PACKAGE = "expectedPackage"
        const val WAIT_MS = 15_000L
    }
}

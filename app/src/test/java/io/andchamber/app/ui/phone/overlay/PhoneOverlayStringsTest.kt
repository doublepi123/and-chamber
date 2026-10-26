package io.andchamber.app.ui.phone.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The overlay strings have their own files; same parity rules as the other string tests. */
class PhoneOverlayStringsTest {
    private fun load(path: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(ROOT, path))
        val nodes = document.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i) as org.w3c.dom.Element
            node.getAttribute("name") to node.textContent
        }
    }

    private val english by lazy { load("values/phone_overlay_strings.xml") }
    private val chinese by lazy { load("values-zh/phone_overlay_strings.xml") }

    @Test
    fun `same keys in both languages, all prefixed`() {
        assertEquals(english.keys, chinese.keys)
        assertTrue(english.keys.all { it.startsWith("phone_overlay_") })
    }

    @Test
    fun `same placeholders in the same order`() {
        val bad = english.keys.filter { placeholders(english.getValue(it)) != placeholders(chinese.getValue(it)) }
        assertTrue("placeholder mismatch: $bad", bad.isEmpty())
    }

    @Test
    fun `no empty text`() {
        assertTrue(english.values.none { it.isBlank() } && chinese.values.none { it.isBlank() })
    }

    @Test
    fun `no key shadows another string file`() {
        val others =
            listOf("values/strings.xml", "values/phone_strings.xml", "values/phone_integration_strings.xml")
                .filter { File(ROOT, it).exists() }
                .flatMap { load(it).keys }
                .toSet()
        assertTrue((english.keys intersect others).isEmpty())
    }

    @Test
    fun `copy makes no false claims`() {
        val all = (english.values + chinese.values).joinToString("\n")
        // No fake progress, no automatic detection of every sensitive page, no auto-pause claim,
        // no retry, no "proves a person".
        listOf("%%", "已自动暂停", "automatically paused", "检测到密码", "detects every", "一键重试", "proves").forEach {
            assertTrue("found '$it'", !all.contains(it, ignoreCase = true))
        }
        // Unreachable is neutral: it doesn't claim the notification took over or was seen.
        assertTrue(!english.getValue("phone_overlay_reason_unreachable").contains("notification", ignoreCase = true))
        assertTrue(!chinese.getValue("phone_overlay_reason_unreachable").contains("通知"))
        assertTrue(chinese.getValue("phone_overlay_settings_moved_to_notification").contains("请检查通知栏"))
        // The take-over result says what is off, not that everything stopped or was undone.
        assertTrue(chinese.getValue("phone_overlay_taken_notice").contains("新观察和操作已禁用；上一操作仍需核对"))
    }

    private fun placeholders(text: String) = PLACEHOLDER.findAll(text).map { it.value }.toList()

    private companion object {
        val ROOT = File("src/main/res")
        val PLACEHOLDER = Regex("%\\d\\$[sd]")
    }
}

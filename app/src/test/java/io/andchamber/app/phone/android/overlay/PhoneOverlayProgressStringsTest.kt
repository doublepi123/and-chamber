package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.overlay.progress.PhoneProgressKind
import io.andchamber.app.phone.overlay.progress.PhoneProgressReason
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native typed reason/kind labels live in their own string files. Every enum value must have
 * exactly one label in both languages, and no label may describe page text, input or a fake result.
 */
class PhoneOverlayProgressStringsTest {
    private fun load(path: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(ROOT, path))
        val nodes = document.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i) as org.w3c.dom.Element
            node.getAttribute("name") to node.textContent
        }
    }

    private val english by lazy { load("values/phone_overlay_progress_strings.xml") }
    private val chinese by lazy { load("values-zh/phone_overlay_progress_strings.xml") }

    private fun kindKeys(): Set<String> =
        PhoneProgressKind.values().map { "phone_overlay_progress_kind_" + it.name.lowercase() }.toSet()

    private fun reasonKeys(): Set<String> =
        PhoneProgressReason.values().map { "phone_overlay_progress_reason_" + it.name.lowercase() }.toSet()

    @Test
    fun `every typed kind and reason has one label in both languages`() {
        val expected = kindKeys() + reasonKeys() + EVENT_KEY
        assertEquals(expected, english.keys)
        assertEquals(expected, chinese.keys)
    }

    @Test
    fun `the same placeholders appear in the same order`() {
        val bad =
            english.keys.filter {
                placeholders(english.getValue(it)) != placeholders(chinese.getValue(it))
            }
        assertTrue("placeholder mismatch: $bad", bad.isEmpty())
        assertEquals(listOf("%1\$s", "%2\$s"), placeholders(english.getValue(EVENT_KEY)))
    }

    @Test
    fun `no empty text and no false result or page claim`() {
        assertTrue(english.values.none { it.isBlank() } && chinese.values.none { it.isBlank() })
        val all = (english.values + chinese.values).joinToString("\n")
        listOf("%%", "screenshot", "succeed", "done!", "自动完成", "已成功", "页面内容", "密码").forEach {
            assertTrue("found '$it'", !all.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `no key shadows another string file`() {
        val others =
            listOf(
                "values/strings.xml",
                "values/phone_strings.xml",
                "values/phone_integration_strings.xml",
                "values/phone_overlay_strings.xml",
                "values/phone_overlay_integration_strings.xml",
            ).filter { File(ROOT, it).exists() }
                .flatMap { load(it).keys }
                .toSet()
        assertTrue(english.keys.intersect(others).isEmpty())
    }

    private fun placeholders(text: String) = PLACEHOLDER.findAll(text).map { it.value }.toList()

    private companion object {
        const val EVENT_KEY = "phone_overlay_progress_event"
        val ROOT = File("src/main/res")
        val PLACEHOLDER = Regex("%\\d\\$[sd]")
    }
}

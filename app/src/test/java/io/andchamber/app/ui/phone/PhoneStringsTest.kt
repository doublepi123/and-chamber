package io.andchamber.app.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The phone-control strings live in their own files; same parity rules as StringResourcesTest. */
class PhoneStringsTest {
    private fun load(path: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(ROOT, path))
        val nodes = document.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i) as org.w3c.dom.Element
            node.getAttribute("name") to node.textContent
        }
    }

    private val english by lazy { load("values/phone_strings.xml") }
    private val chinese by lazy { load("values-zh/phone_strings.xml") }

    @Test
    fun `same keys in both languages, all prefixed`() {
        assertEquals(english.keys, chinese.keys)
        assertTrue(english.keys.all { it.startsWith("phone_") })
    }

    @Test
    fun `same placeholders in the same order`() {
        val bad = english.keys.filter { key -> placeholders(english.getValue(key)) != placeholders(chinese.getValue(key)) }
        assertTrue("placeholder mismatch: $bad", bad.isEmpty())
    }

    @Test
    fun `no empty text`() {
        assertTrue(english.values.none { it.isBlank() } && chinese.values.none { it.isBlank() })
    }

    @Test
    fun `no phone key shadows a main string`() {
        val main = load("values/strings.xml").keys
        assertTrue((english.keys intersect main).isEmpty())
    }

    @Test
    fun `a failed wake-up while running says the chat wasn't told and nothing was re-sent`() {
        val zh = chinese.getValue("phone_continue_failed_running_body")
        assertTrue(zh.contains("没能通知聊天继续") && zh.contains("没有重发任何操作"))
        val en = english.getValue("phone_continue_failed_running_body")
        // Raw XML keeps Android's \' escape, so match words without the apostrophe.
        assertTrue(en.contains("told to continue") && en.contains("nothing was re-sent"))
        assertTrue(chinese.getValue("phone_continue_failed_title").contains("没能联系到聊天"))
    }

    @Test
    fun `copy never claims a confirmation proves a person`() {
        val all = (english.values + chinese.values).joinToString("\n").lowercase()
        listOf("proves you", "verified human", "证明是你", "确认是本人").forEach { assertTrue("found '$it'", it !in all) }
    }

    private fun placeholders(text: String) = PLACEHOLDER.findAll(text).map { it.value }.toList()

    private companion object {
        val ROOT = File("src/main/res")
        val PLACEHOLDER = Regex("%\\d\\$[sd]")
    }
}

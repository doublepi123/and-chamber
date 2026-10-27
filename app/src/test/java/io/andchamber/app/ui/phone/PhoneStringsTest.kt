package io.andchamber.app.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val englishIntegration by lazy { load("values/phone_integration_strings.xml") }
    private val chineseIntegration by lazy { load("values-zh/phone_integration_strings.xml") }

    @Test
    fun `setup and ready copy do not promise only short finite tasks`() {
        for (strings in listOf(english, chinese)) {
            listOf("phone_entry_body", "phone_enable_intro", "phone_grant_title", "phone_standing_ready_body").forEach { key ->
                val text = strings.getValue(key).lowercase()
                assertFalse("$key still promises short tasks", "short" in text || "短时" in text || "很短" in text)
            }
        }
        assertTrue(english.getValue("phone_enable_intro").contains("Permanent"))
        assertTrue(chinese.getValue("phone_enable_intro").contains("永久"))
        assertTrue(english.getValue("phone_standing_ready_body").contains("other stop rules"))
        assertTrue(chinese.getValue("phone_standing_ready_body").contains("其他停手规则不变"))
    }

    @Test
    fun `integration strings have the same keys and placeholders`() {
        assertEquals(englishIntegration.keys, chineseIntegration.keys)
        val bad = englishIntegration.keys.filter { placeholders(englishIntegration.getValue(it)) != placeholders(chineseIntegration.getValue(it)) }
        assertTrue("placeholder mismatch: $bad", bad.isEmpty())
    }

    @Test
    fun `the main pairing path asks only for the code, not a port`() {
        for (strings in listOf(english, chinese)) {
            listOf("phone_pair_how", "phone_pair_waiting_body", "phone_pair_searching_body").forEach { key ->
                val text = strings.getValue(key).lowercase()
                assertTrue("$key mentions a port", "port" !in text && "端口" !in text)
            }
        }
        for (strings in listOf(englishIntegration, chineseIntegration)) {
            // Paired/not-found bodies may name typing a port as the fallback; these main-path ones may not.
            listOf("phone_notif_channel_pairing_desc", "phone_pair_notif_searching_body").forEach { key ->
                val text = strings.getValue(key).lowercase()
                assertTrue("$key asks to type a port", "type the port" !in text && "输入端口" !in text && "输入无线调试" !in text)
            }
        }
        // The notification no longer tells the person to fill in a port first.
        assertFalse(englishIntegration.getValue("phone_pair_notif_port_body").startsWith("Android"))
    }

    @Test
    fun `the long-press tip names Xiaomi only, not every phone`() {
        assertTrue(english.getValue("phone_pair_waiting_xiaomi").contains("Xiaomi"))
        assertTrue(chinese.getValue("phone_pair_waiting_xiaomi").contains("小米"))
        assertTrue(englishIntegration.getValue("phone_pair_notif_code_body").contains("Some Xiaomi", ignoreCase = true))
        assertTrue(chineseIntegration.getValue("phone_pair_notif_code_body").contains("部分小米"))
    }

    @Test
    fun `search copy keeps the dialog open and never promises it always works`() {
        assertTrue(english.getValue("phone_pair_searching_body").contains("keep the dialog open"))
        assertTrue(chinese.getValue("phone_pair_searching_body").contains("保持打开"))
        val all = (english.values + chinese.values + englishIntegration.values + chineseIntegration.values).joinToString("\n").lowercase()
        listOf("always finds", "always find", "always connects", "guaranteed", "永远", "一定能找到", "总能找到", "保证").forEach {
            assertTrue("found '$it'", it !in all)
        }
        // Each failed search offers the way out in words.
        assertEquals("Search again", english.getValue("phone_discovery_retry"))
        assertEquals("重新查找", chinese.getValue("phone_discovery_retry"))
        assertTrue(chinese.getValue("phone_discovery_manual").contains("输入端口"))
    }

    @Test
    fun `connecting is never described as letting the agent act`() {
        assertTrue(english.getValue("phone_connect_auto_body").contains("doesn\\'t let the agent do anything"))
        assertTrue(chinese.getValue("phone_connect_auto_body").contains("不会让代理做任何操作"))
    }

    private fun placeholders(text: String) = PLACEHOLDER.findAll(text).map { it.value }.toList()

    private companion object {
        val ROOT = File("src/main/res")
        val PLACEHOLDER = Regex("%\\d\\$[sd]")
    }
}

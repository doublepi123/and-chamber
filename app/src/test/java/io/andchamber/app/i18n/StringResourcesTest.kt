package io.andchamber.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks the English and Simplified Chinese resources against each other, straight from the XML files:
 * the same keys, the same placeholders in the same order for every key, and no empty Chinese text.
 */
class StringResourcesTest {
    private data class Entry(
        /** For plurals: one entry per quantity, keyed by quantity. */
        val texts: Map<String, String>,
    )

    private fun load(relativePath: String): Map<String, Entry> {
        val file = File(ROOT, relativePath)
        val document =
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
                .newDocumentBuilder().parse(file)
        val result = linkedMapOf<String, Entry>()
        val children = document.documentElement.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index) as? org.w3c.dom.Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> result[name] = Entry(mapOf("" to node.textContent))
                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    result[name] =
                        Entry(
                            (0 until items.length).associate { i ->
                                val item = items.item(i) as org.w3c.dom.Element
                                item.getAttribute("quantity") to item.textContent
                            },
                        )
                }
            }
        }
        return result
    }

    /** The printf placeholders in order, e.g. `%1$s`, `%2$d`. */
    private fun placeholders(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.value }.toList()

    private val english by lazy { load("values/strings.xml") }
    private val chinese by lazy { load("values-zh/strings.xml") }

    @Test
    fun `english and chinese define exactly the same keys`() {
        assertEquals(english.keys, chinese.keys)
    }

    @Test
    fun `every key has the same placeholders in the same order in both languages`() {
        // Plurals differ by quantity (English has one/other, Chinese only other), so compare the
        // "other" form, which every language has, and require it on both sides.
        val mismatches =
            english.keys.filter { key ->
                val en = english.getValue(key).texts
                val zh = chinese[key]?.texts ?: return@filter false
                val enOther = en[""] ?: en["other"]
                val zhOther = zh[""] ?: zh["other"]
                enOther == null || zhOther == null || placeholders(enOther) != placeholders(zhOther)
            }
        assertTrue("placeholder mismatch in: $mismatches", mismatches.isEmpty())
    }

    @Test
    fun `every plural has an other form in both languages`() {
        val plurals = english.filter { it.value.texts.keys.any { q -> q.isNotEmpty() } }.keys
        plurals.forEach { key ->
            assertTrue("$key lacks an 'other' form in English", english.getValue(key).texts.containsKey("other"))
            assertTrue("$key lacks an 'other' form in Chinese", chinese[key]?.texts?.containsKey("other") == true)
        }
    }

    @Test
    fun `no chinese string is empty`() {
        val empty = chinese.filter { (_, entry) -> entry.texts.values.any { it.isBlank() } }.keys
        assertTrue("empty Chinese text in: $empty", empty.isEmpty())
    }

    @Test
    fun `no english string is empty either`() {
        val empty = english.filter { (_, entry) -> entry.texts.values.any { it.isBlank() } }.keys
        assertTrue("empty English text in: $empty", empty.isEmpty())
    }

    @Test
    fun `the number of translated strings is what the brief committed to`() {
        // 210 entries: plain strings plus one plural (log_lines). Changing this is a deliberate decision.
        assertEquals(210, english.size)
    }

    private companion object {
        /** Gradle runs unit tests in the module directory. */
        val ROOT = File("src/main/res")
        val PLACEHOLDER = Regex("%\\d\\$[sd]")
    }
}

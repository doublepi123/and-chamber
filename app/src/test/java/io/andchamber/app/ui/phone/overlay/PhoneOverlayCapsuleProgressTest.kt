package io.andchamber.app.ui.phone.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The collapsed capsule's live progress line: newest event only, live phases only. */
class PhoneOverlayCapsuleProgressTest {
    private val observed = OverlayEvent("Screen read · observed", OverlaySource.NativeObserved, atMs = 1_000)
    private val sent = OverlayEvent("Action sent · sent", OverlaySource.Sent, atMs = 2_000)
    private val note = OverlayEvent("Opening the compose screen", OverlaySource.AgentNote, atMs = 3_000)

    private fun running(events: List<OverlayEvent> = emptyList()) =
        PhoneOverlayState(phase = OverlayPhase.Running, targetLabel = "Notes", summary = "secret summary", events = events)

    @Test
    fun `live phases show the newest event`() {
        listOf(OverlayPhase.Running, OverlayPhase.Pausing, OverlayPhase.Paused).forEach { phase ->
            assertEquals(
                phase.name,
                CapsuleProgress.Latest("Action sent · sent"),
                capsuleProgress(running(listOf(observed, sent)).copy(phase = phase)),
            )
        }
        // Order of the list doesn't matter; time does.
        assertEquals(CapsuleProgress.Latest("Action sent · sent"), capsuleProgress(running(listOf(sent, observed))))
    }

    @Test
    fun `the agent's own note is shown directly, with no label`() {
        assertEquals(
            CapsuleProgress.Latest("Opening the compose screen"),
            capsuleProgress(running(listOf(observed, sent, note))),
        )
        // A newer native fact replaces an older note.
        val later = sent.copy(atMs = 4_000)
        assertEquals(CapsuleProgress.Latest("Action sent · sent"), capsuleProgress(running(listOf(note, later))))
    }

    @Test
    fun `no events yet reserves a waiting line`() {
        assertEquals(CapsuleProgress.Waiting, capsuleProgress(running()))
        assertEquals(CapsuleProgress.Waiting, capsuleProgress(running(listOf(note.copy(text = "  \n ")))))
    }

    @Test
    fun `stopped, waiting, unknown and ended phases keep their wording`() {
        OverlayPhase.entries
            .filter { it !in setOf(OverlayPhase.Running, OverlayPhase.Pausing, OverlayPhase.Paused) }
            .forEach { phase -> assertEquals(phase.name, CapsuleProgress.None, capsuleProgress(running(listOf(sent)).copy(phase = phase))) }
    }

    @Test
    fun `demo and an open question show no progress line`() {
        assertEquals(CapsuleProgress.None, capsuleProgress(running(listOf(sent)).copy(demo = true)))
        val question = OverlayPrompt(formId = "f", generation = 1, choices = listOf(OverlayPromptChoice("a", "A")), delivery = OverlayPromptDelivery.Ready)
        assertEquals(CapsuleProgress.None, capsuleProgress(running(listOf(sent)).copy(prompt = question)))
        // A question that can't be answered here doesn't hide progress.
        assertTrue(capsuleProgress(running(listOf(sent)).copy(prompt = question.copy(delivery = OverlayPromptDelivery.Accepted))) is CapsuleProgress.Latest)
    }

    @Test
    fun `long agent notes become one bounded line`() {
        val long = "Step one\nthen   step two " + "x".repeat(200)
        val line = (capsuleProgress(running(listOf(note.copy(text = long)))) as CapsuleProgress.Latest).text
        assertTrue(line.length <= CAPSULE_PROGRESS_MAX_CHARS)
        assertTrue(line.endsWith("…"))
        assertTrue(line.startsWith("Step one then step two"))
        assertTrue('\n' !in line)
        // TalkBack still gets the whole (flattened) note.
        val full = (capsuleProgress(running(listOf(note.copy(text = long)))) as CapsuleProgress.Latest).full
        assertEquals(long.replace(Regex("\\s+"), " ").trim(), full)
        assertEquals("short", capsuleOneLine("  short  "))
        assertNull(capsuleOneLine(" \t\n"))
        // A surrogate pair is never split.
        val emoji = "a".repeat(CAPSULE_PROGRESS_MAX_CHARS - 2) + "\uD83D\uDE00" + "tail"
        val cut = capsuleOneLine(emoji)!!
        assertTrue(!cut.dropLast(1).last().isHighSurrogate())
    }

    @Test
    fun `the person's summary is never the progress line`() {
        val p = capsuleProgress(running(listOf(sent)))
        assertTrue(p is CapsuleProgress.Latest && !p.text.contains("secret summary"))
        assertEquals(CapsuleProgress.Waiting, capsuleProgress(running()))
    }

    @Test
    fun `live capsule facts drop the time text for a permanent lease`() {
        val finite = running().copy(sentCount = 3, leaseEndsAtMs = 10 * 60_000L)
        assertEquals(CapsuleMeta(sentCount = 3, minutesLeft = 10), capsuleMeta(finite, nowMs = 0))
        assertEquals(CapsuleMeta(sentCount = 3, minutesLeft = null), capsuleMeta(finite.copy(leasePermanent = true), nowMs = 0))
        assertEquals(CapsuleMeta(sentCount = null, minutesLeft = null), capsuleMeta(running(), nowMs = 0))
    }

    @Test
    fun `live capsule title is a short word`() {
        assertEquals(io.andchamber.app.R.string.phone_overlay_capsule_running, capsuleLiveTitle(OverlayPhase.Running))
        assertEquals(io.andchamber.app.R.string.phone_overlay_pausing, capsuleLiveTitle(OverlayPhase.Pausing))
        assertEquals(io.andchamber.app.R.string.phone_overlay_capsule_paused, capsuleLiveTitle(OverlayPhase.Paused))
        val en = load("values/phone_overlay_strings.xml")
        val zh = load("values-zh/phone_overlay_strings.xml")
        assertEquals("Running", en["phone_overlay_capsule_running"])
        assertEquals("代理操作中", zh["phone_overlay_capsule_running"])
        listOf("phone_overlay_capsule_running", "phone_overlay_capsule_paused").forEach {
            assertTrue(en.getValue(it).length <= 12 && zh.getValue(it).length <= 6)
        }
    }

    @Test
    fun `overlay copy carries no not-verified label`() {
        val en = load("values/phone_overlay_strings.xml")
        val zh = load("values-zh/phone_overlay_strings.xml")
        assertTrue(en["phone_overlay_capsule_progress_none"].orEmpty().isNotBlank())
        assertTrue(zh["phone_overlay_capsule_progress_none"].orEmpty().isNotBlank())
        // The removed wrappers are gone; the agent's words need no wrapper.
        listOf("phone_overlay_capsule_progress_agent", "phone_overlay_capsule_progress_native").forEach {
            assertTrue(it !in en && it !in zh)
        }
        val enProgress = load("values/phone_overlay_progress_strings.xml")
        val zhProgress = load("values-zh/phone_overlay_progress_strings.xml")
        val all = (en.values + zh.values + enProgress.values + zhProgress.values).joinToString("\n")
        listOf("未核验", "未核实", "未经核实", "not verified", "unverified", "isn't verified", "isn\\'t verified").forEach {
            assertTrue("found '$it'", !all.contains(it, ignoreCase = true))
        }
        assertEquals("代理说明", zh.getValue("phone_overlay_src_agent"))
        assertEquals("Agent note", en.getValue("phone_overlay_src_agent"))
    }

    private fun load(path: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(ROOT, path))
        val nodes = document.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i) as org.w3c.dom.Element
            node.getAttribute("name") to node.textContent
        }
    }

    private companion object {
        val ROOT = File("src/main/res")
    }
}

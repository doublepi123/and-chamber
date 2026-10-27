package io.andchamber.app.ui.phone.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The chat's question in the overlay: pick, then one explicit send; Unknown never re-sends. */
class PhoneOverlayPromptTest {
    private val choices = listOf(OverlayPromptChoice("a", "Yes"), OverlayPromptChoice("b", "No"))
    private val ready =
        OverlayPrompt(
            formId = "form-1",
            generation = 3,
            noticeTitle = "OpenChamber",
            noticeBody = "Notice body",
            questionTitle = "Next step",
            questionDescription = "Continue with the order?",
            choices = choices,
            delivery = OverlayPromptDelivery.Ready,
        )

    @Test
    fun `old states have no prompt and no notice`() {
        val s = PhoneOverlayState()
        assertNull(s.prompt)
        assertNull(s.sessionNotice)
    }

    @Test
    fun `question text prefers the form, then falls back to the notification`() {
        assertEquals("Continue with the order?", promptQuestion(ready))
        assertEquals("Next step", promptLabel(ready))
        val bare = ready.copy(questionDescription = " ", questionTitle = null)
        assertEquals("Notice body", promptQuestion(bare))
        assertEquals("OpenChamber", promptLabel(bare))
        assertNull(promptQuestion(bare.copy(noticeBody = null)))
    }

    @Test
    fun `only an open question with choices can be sent`() {
        assertTrue(promptCanSubmit(ready))
        assertFalse(promptCanSubmit(ready.copy(choices = emptyList())))
        OverlayPromptDelivery.entries.filter { it != OverlayPromptDelivery.Ready }.forEach {
            assertFalse("$it must not submit", promptCanSubmit(ready.copy(delivery = it)))
        }
    }

    @Test
    fun `send needs an explicit pick among the shown choices and happens once per question`() {
        // Picking alone sends nothing: no pick, no answer.
        assertNull(promptSubmission(ready, picked = null, sentFor = null))
        assertEquals("a", promptSubmission(ready, picked = "a", sentFor = null))
        // A value not among the shown choices is never sent.
        assertNull(promptSubmission(ready, picked = "zzz", sentFor = null))
        // A second tap for the same form + generation is ignored, even before native says Sending.
        assertNull(promptSubmission(ready, picked = "b", sentFor = PromptKey("form-1", 3)))
        // A newer generation of the same form is a new question.
        assertEquals("b", promptSubmission(ready.copy(generation = 4), picked = "b", sentFor = PromptKey("form-1", 3)))
        // While sending, nothing more goes out.
        assertNull(promptSubmission(ready.copy(delivery = OverlayPromptDelivery.Sending), picked = "a", sentFor = null))
    }

    @Test
    fun `unknown offers only check again and the chat, never another send`() {
        val unknown = ready.copy(delivery = OverlayPromptDelivery.Unknown)
        assertEquals(listOf(PromptAction.CheckAgain, PromptAction.OpenChat), promptActions(unknown))
        assertNull(promptSubmission(unknown, picked = "a", sentFor = null))
    }

    @Test
    fun `every state has an honest way forward and no dead button`() {
        assertEquals(listOf(PromptAction.Send, PromptAction.OpenChat), promptActions(ready))
        assertEquals(listOf(PromptAction.OpenChat), promptActions(ready.copy(choices = emptyList())))
        val blocked = ready.copy(delivery = OverlayPromptDelivery.Blocked)
        assertEquals(listOf(PromptAction.CheckAgain, PromptAction.OpenChat), promptActions(blocked))
        assertNull(promptSubmission(blocked, picked = "a", sentFor = null))
        listOf(OverlayPromptDelivery.Refused, OverlayPromptDelivery.Unsupported).forEach {
            assertEquals(listOf(PromptAction.OpenChat), promptActions(ready.copy(delivery = it)))
        }
        listOf(OverlayPromptDelivery.Checking, OverlayPromptDelivery.Sending, OverlayPromptDelivery.Accepted, OverlayPromptDelivery.Superseded).forEach {
            assertTrue(promptActions(ready.copy(delivery = it)).isEmpty())
        }
    }

    @Test
    fun `a plain notice sits beside the question and never changes its identity`() {
        val notice = OverlaySessionNotice(OverlaySessionNoticeKind.Error, "Error", "Build failed")
        val both = PhoneOverlayState(prompt = ready, sessionNotice = notice)
        assertEquals(ready.key(), both.prompt!!.key())
        assertEquals(notice, both.sessionNotice)
        // A notice alone is still shown; it carries no form, choice or answer.
        val onlyNotice = PhoneOverlayState(sessionNotice = notice)
        assertNull(onlyNotice.prompt)
    }

    @Test
    fun `the send callback carries the frozen identity`() {
        val calls = mutableListOf<Triple<String, Long, String>>()
        val actions = PhoneOverlayActions(onAnswerPrompt = { f, g, v -> calls += Triple(f, g, v) })
        promptSubmission(ready, "b", null)?.let { actions.onAnswerPrompt(ready.formId, ready.generation, it) }
        assertEquals(listOf(Triple("form-1", 3L, "b")), calls)
    }

    @Test
    fun `copy is honest about delivery`() {
        val en = load("values/phone_overlay_strings.xml")
        val zh = load("values-zh/phone_overlay_strings.xml")
        assertTrue(en.getValue("phone_overlay_prompt_unknown").contains("won\\'t be sent again"))
        assertTrue(zh.getValue("phone_overlay_prompt_unknown").contains("不会再次发送"))
        assertTrue(zh.getValue("phone_overlay_prompt_accepted").contains("不代表代理已经照做"))
        assertTrue(zh.getValue("phone_overlay_prompt_pick_hint").contains("点之前不会发送"))
        // Not presented as reading other apps' notifications.
        val all = (en.values + zh.values).joinToString("\n")
        listOf("other apps\\' notifications", "其他应用的通知").forEach { assertFalse("found '$it'", all.contains(it)) }
    }

    private fun load(path: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res", path))
        val nodes = document.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i) as org.w3c.dom.Element
            node.getAttribute("name") to node.textContent
        }
    }
}

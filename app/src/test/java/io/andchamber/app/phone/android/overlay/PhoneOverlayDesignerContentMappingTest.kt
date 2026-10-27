package io.andchamber.app.phone.android.overlay

import io.andchamber.app.ui.phone.overlay.OverlayPromptDelivery
import io.andchamber.app.ui.phone.overlay.OverlaySessionNoticeKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The native adapter only maps data; these pin every field and both exhaustive enums. */
class PhoneOverlayDesignerContentMappingTest {
    @Test
    fun `a prompt maps every frozen field and choice without changing the answer identity`() {
        val view =
            PhoneOverlayPromptView(
                formId = "frm_1",
                generation = 42L,
                noticeTitle = "Questions",
                noticeBody = "Which fixture action should I run next?",
                questionTitle = "Next step",
                questionDescription = "Which fixture action should I run next?",
                choices =
                    listOf(
                        PhoneOverlayPromptChoice("a", "Option A"),
                        PhoneOverlayPromptChoice("b", "Option B"),
                    ),
                delivery = PhoneOverlayPromptDelivery.Ready,
            )

        val mapped = view.toOverlayPrompt()

        assertEquals("frm_1", mapped.formId)
        assertEquals(42L, mapped.generation)
        assertEquals("Questions", mapped.noticeTitle)
        assertEquals("Which fixture action should I run next?", mapped.noticeBody)
        assertEquals("Next step", mapped.questionTitle)
        assertEquals("Which fixture action should I run next?", mapped.questionDescription)
        assertEquals(listOf("a", "b"), mapped.choices.map { it.value })
        assertEquals(listOf("Option A", "Option B"), mapped.choices.map { it.label })
        assertEquals(OverlayPromptDelivery.Ready, mapped.delivery)
    }

    @Test
    fun `all nine prompt deliveries map one to one`() {
        val pairs =
            listOf(
                PhoneOverlayPromptDelivery.Checking to OverlayPromptDelivery.Checking,
                PhoneOverlayPromptDelivery.Ready to OverlayPromptDelivery.Ready,
                PhoneOverlayPromptDelivery.Sending to OverlayPromptDelivery.Sending,
                PhoneOverlayPromptDelivery.Accepted to OverlayPromptDelivery.Accepted,
                PhoneOverlayPromptDelivery.Unknown to OverlayPromptDelivery.Unknown,
                PhoneOverlayPromptDelivery.Blocked to OverlayPromptDelivery.Blocked,
                PhoneOverlayPromptDelivery.Refused to OverlayPromptDelivery.Refused,
                PhoneOverlayPromptDelivery.Unsupported to OverlayPromptDelivery.Unsupported,
                PhoneOverlayPromptDelivery.Superseded to OverlayPromptDelivery.Superseded,
            )
        pairs.forEach { (native, ui) ->
            assertEquals("$native must map to $ui", ui, native.toOverlayPromptDelivery())
        }
    }

    @Test
    fun `a notice maps every field and all four kinds`() {
        val pairs =
            listOf(
                PhoneOverlayNoticeKind.Ready to OverlaySessionNoticeKind.Ready,
                PhoneOverlayNoticeKind.Error to OverlaySessionNoticeKind.Error,
                PhoneOverlayNoticeKind.Goal to OverlaySessionNoticeKind.Goal,
                PhoneOverlayNoticeKind.RestartInterrupted to OverlaySessionNoticeKind.RestartInterrupted,
            )
        pairs.forEach { (native, ui) ->
            val mapped =
                PhoneOverlayNoticeView(
                    kind = native,
                    title = "Title",
                    body = "Body",
                    generation = 9L,
                ).toOverlaySessionNotice()
            assertEquals(ui, mapped.kind)
            assertEquals("Title", mapped.title)
            assertEquals("Body", mapped.body)
        }
    }
}

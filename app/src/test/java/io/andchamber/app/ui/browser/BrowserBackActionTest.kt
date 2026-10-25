package io.andchamber.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which action the system Back key takes while the browser window is composed. */
class BrowserBackActionTest {
    private fun action(
        tabListShown: Boolean = false,
        editorShown: Boolean = false,
        presentation: BrowserPresentation,
        fullRequested: Boolean = false,
        floatFits: Boolean = true,
    ) = browserBackAction(tabListShown, editorShown, presentation, fullRequested, floatFits)

    @Test
    fun `editing the address comes first in every presentation`() {
        for (presentation in BrowserPresentation.values()) {
            assertEquals(
                BrowserBackAction.EndEditing,
                action(editorShown = true, presentation = presentation, fullRequested = true),
            )
        }
    }

    @Test
    fun `an open page list is dismissed before editing or window navigation`() {
        assertEquals(
            BrowserBackAction.DismissTabs,
            action(
                tabListShown = true,
                editorShown = true,
                presentation = BrowserPresentation.Full,
                fullRequested = true,
            ),
        )
    }

    @Test
    fun `a small window leaves Back to the chat`() {
        assertEquals(BrowserBackAction.PassThrough, action(presentation = BrowserPresentation.Float))
    }

    @Test
    fun `a full screen the person chose goes back to the small window`() {
        assertEquals(
            BrowserBackAction.ExitFull,
            action(presentation = BrowserPresentation.Full, fullRequested = true, floatFits = true),
        )
    }

    @Test
    fun `an automatic full screen hides the browser instead of reaching the chat`() {
        assertEquals(
            BrowserBackAction.Hide,
            action(presentation = BrowserPresentation.Full, fullRequested = false, floatFits = false),
        )
        // Even if a small window would fit with the keyboard closed (the keyboard forced it).
        assertEquals(
            BrowserBackAction.Hide,
            action(presentation = BrowserPresentation.Full, fullRequested = false, floatFits = true),
        )
    }

    @Test
    fun `a requested full screen with no small window to return to hides`() {
        assertEquals(
            BrowserBackAction.Hide,
            action(presentation = BrowserPresentation.Full, fullRequested = true, floatFits = false),
        )
    }

    @Test
    fun `the folded bar hides`() {
        assertEquals(BrowserBackAction.Hide, action(presentation = BrowserPresentation.Collapsed))
        assertEquals(
            BrowserBackAction.Hide,
            action(presentation = BrowserPresentation.Collapsed, fullRequested = true),
        )
    }

    @Test
    fun `the layout decides which presentation a landscape screen gets`() {
        // Portrait phone: a small window fits, so a requested full screen can go back to it.
        val portrait = browserWindowLayout(360f, 760f, 0f, 136f, 360f, 664f, fullRequested = false)
        assertEquals(BrowserPresentation.Float, portrait.presentation)
        val portraitFull = browserWindowLayout(360f, 760f, 0f, 136f, 360f, 664f, fullRequested = true)
        assertEquals(
            BrowserBackAction.ExitFull,
            browserBackAction(false, false, portraitFull.presentation, true, portrait.presentation == BrowserPresentation.Float),
        )

        // Landscape phone: no small window fits; the automatic full screen hides on Back.
        val landscape = browserWindowLayout(780f, 360f, 0f, 72f, 780f, 264f, fullRequested = false)
        assertEquals(BrowserPresentation.Full, landscape.presentation)
        assertEquals(
            BrowserBackAction.Hide,
            browserBackAction(false, false, landscape.presentation, false, false),
        )
    }
}

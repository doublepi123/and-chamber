package io.andchamber.app.ui.browser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCaptureGeometryTest {

    @Test
    fun `an unscaled fully visible page copies exactly its own rectangle`() {
        val presentation = presentation(pageLeft = 40f, pageTop = 100f, width = 390, height = 844)
        val frame = BrowserCaptureGeometry.captureFrame(presentation, 1080, 2400)
        assertNotNull(frame)
        assertEquals(CaptureRect(40, 100, 430, 944), frame!!.rect)
        assertEquals(1080, frame.windowWidth)
        assertEquals(2400, frame.windowHeight)
    }

    @Test
    fun `a scaled letterboxed page rounds inward inside the drawn page`() {
        val presentation = presentation(
            pageLeft = 100.25f,
            pageTop = 0f,
            width = 390,
            height = 844,
            scale = 0.5f,
        )
        val frame = BrowserCaptureGeometry.captureFrame(presentation, 1024, 768)
        assertNotNull(frame)
        // Drawn page is 195 x 422 at (100.25, 0); only whole pixels inside it may be copied.
        assertEquals(CaptureRect(101, 0, 295, 422), frame!!.rect)
        assertEquals(194, frame.rect.width)
        assertEquals(422, frame.rect.height)
    }

    @Test
    fun `a half pixel at the page edge is dropped rather than copying neighbors`() {
        val presentation = presentation(pageLeft = 10.5f, pageTop = 20.5f, width = 390, height = 844)
        val frame = BrowserCaptureGeometry.captureFrame(presentation, 1080, 2400)
        assertNotNull(frame)
        assertEquals(CaptureRect(11, 21, 400, 864), frame!!.rect)
        assertTrue(frame.rect.left >= 10.5f)
        assertTrue(frame.rect.top >= 20.5f)
        assertTrue(frame.rect.right <= 10.5f + 390f)
        assertTrue(frame.rect.bottom <= 20.5f + 844f)
    }

    @Test
    fun `a clipped page is refused instead of copying the bounding box`() {
        val presentation = presentation(
            pageLeft = 100f,
            pageTop = 0f,
            width = 390,
            height = 844,
            visible = CaptureRect(100, 0, 300, 844),
        )
        assertNull(BrowserCaptureGeometry.captureFrame(presentation, 1024, 768))
    }

    @Test
    fun `a partially clipped page is refused`() {
        val presentation = presentation(
            pageLeft = 100f,
            pageTop = 0f,
            width = 390,
            height = 844,
            visible = CaptureRect(100, 0, 490, 843),
        )
        assertNull(BrowserCaptureGeometry.captureFrame(presentation, 1024, 768))
    }

    @Test
    fun `a page outside the window is refused`() {
        val outside = presentation(pageLeft = -5f, pageTop = 0f, width = 390, height = 844)
        assertNull(BrowserCaptureGeometry.captureFrame(outside, 1024, 768))
        val tooTall = presentation(pageLeft = 0f, pageTop = 0f, width = 390, height = 900)
        assertNull(BrowserCaptureGeometry.captureFrame(tooTall, 1024, 768))
    }

    @Test
    fun `a missing visible area is refused`() {
        val presentation = presentation(pageLeft = 0f, pageTop = 0f, width = 390, height = 844, visible = null)
        assertNull(BrowserCaptureGeometry.captureFrame(presentation, 1024, 768))
    }

    @Test
    fun `an invalid scale or window is refused`() {
        assertNull(BrowserCaptureGeometry.captureFrame(presentation(scale = 0f), 1024, 768))
        assertNull(BrowserCaptureGeometry.captureFrame(presentation(scale = -1f), 1024, 768))
        assertNull(BrowserCaptureGeometry.captureFrame(presentation(scale = Float.NaN), 1024, 768))
        assertNull(BrowserCaptureGeometry.captureFrame(presentation(scale = Float.POSITIVE_INFINITY), 1024, 768))
        assertNull(BrowserCaptureGeometry.captureFrame(presentation(), 0, 768))
        assertNull(BrowserCaptureGeometry.captureFrame(presentation(), 1024, 0))
        assertNull(BrowserCaptureGeometry.captureFrame(null, 1024, 768))
    }

    @Test
    fun `a page size that does not match the fixed viewport is refused`() {
        val mismatch = presentation(pageLeft = 0f, pageTop = 0f, width = 390, height = 844)
            .copy(pageWidth = 300f)
        assertNull(BrowserCaptureGeometry.captureFrame(mismatch, 1024, 768))
    }

    @Test
    fun `a sub pixel page leaves no whole pixel to copy`() {
        val tiny = presentation(pageLeft = 0f, pageTop = 0f, width = 390, height = 844, scale = 0.001f)
            .copy(visible = CaptureRect(0, 0, 1024, 768))
        assertNull(BrowserCaptureGeometry.captureFrame(tiny, 1024, 768))
    }

    @Test
    fun `capture accepts only a live presentation equal to a committed one`() {
        val live = presentation(pageLeft = 40f, pageTop = 100f)
        // No frame commit yet: fail closed instead of guessing the window buffer.
        assertNull(BrowserCaptureGeometry.captureCommittedFrame(null, live, 1080, 2400))
        // The committed tuple is exactly the live one: the rect may be copied.
        assertEquals(
            CaptureRect(40, 100, 430, 944),
            BrowserCaptureGeometry.captureCommittedFrame(live, live, 1080, 2400)?.rect,
        )
    }

    @Test
    fun `a newer revision alone is not a committed frame`() {
        val committed = presentation(pageLeft = 40f, pageTop = 100f)
        val live = committed.copy(revision = committed.revision + 1)
        assertNull(BrowserCaptureGeometry.captureCommittedFrame(committed, live, 1080, 2400))
    }

    @Test
    fun `a position that moved before revision updated is refused`() {
        val committed = presentation(pageLeft = 40f, pageTop = 100f)
        val live = committed.copy(pageLeft = 41f)
        assertEquals(committed.revision, live.revision)
        assertNull(BrowserCaptureGeometry.captureCommittedFrame(committed, live, 1080, 2400))
    }

    @Test
    fun `a scale change before revision updated is refused`() {
        val committed = presentation(pageLeft = 40f, pageTop = 100f)
        val live = committed.copy(
            scale = 0.5f,
            pageWidth = 195f,
            pageHeight = 422f,
            visible = CaptureRect(40, 100, 235, 522),
        )
        assertEquals(committed.revision, live.revision)
        assertNull(BrowserCaptureGeometry.captureCommittedFrame(committed, live, 1080, 2400))
    }

    @Test
    fun `a stable capture returns the copied bytes`() = runBlocking {
        val stamp = stamp()
        val outcome = guardedCapture(
            sample = { CaptureStampResult.Ready(stamp) },
            current = { observation(stamp) },
            copy = { CaptureBytes(byteArrayOf(7), 9, 8) },
        )
        val captured = outcome as? CaptureOutcome.Captured
        assertNotNull(captured)
        assertEquals(stamp, captured?.attempt?.stamp)
        assertEquals(9, captured?.attempt?.image?.width)
        assertEquals(7, captured?.attempt?.image?.payload?.single()?.toInt())
    }

    @Test
    fun `a presentation move while sampling is retryable and copies nothing`() = runBlocking {
        var copies = 0
        val outcome = guardedCapture(
            sample = { CaptureStampResult.Moved },
            current = { observation(stamp()) },
            copy = { copies += 1; CaptureBytes(byteArrayOf(1), 1, 1) },
        )
        assertEquals(CaptureOutcome.Refused(CaptureRefusal.PRESENTATION_CHANGED), outcome)
        assertEquals(0, copies)
        assertTrue((outcome as CaptureOutcome.Refused).reason.retryable)
    }

    @Test
    fun `a presentation move after the copy discards this attempt`() = runBlocking {
        val stable = stamp()
        val moved = stable.presentation.copy(
            presentation = stable.presentation.presentation.copy(pageTop = 9f),
        )
        var reads = 0
        var copies = 0
        val outcome = guardedCapture(
            sample = { CaptureStampResult.Ready(stable) },
            current = {
                reads += 1
                observation(stable, if (reads <= 1) stable.presentation else moved)
            },
            copy = { copies += 1; CaptureBytes(byteArrayOf(1), 1, 1) },
        )
        assertEquals(CaptureOutcome.Refused(CaptureRefusal.PRESENTATION_CHANGED), outcome)
        assertEquals(1, copies)
    }

    @Test
    fun `a copy refused by a presentation move is retried`() = runBlocking {
        val stable = stamp()
        val moved = stable.presentation.copy(
            presentation = stable.presentation.presentation.copy(pageLeft = 11f),
        )
        var reads = 0
        val outcome = guardedCapture(
            sample = { CaptureStampResult.Ready(stable) },
            current = {
                reads += 1
                observation(stable, if (reads <= 1) stable.presentation else moved)
            },
            copy = { null },
        )
        assertEquals(CaptureOutcome.Refused(CaptureRefusal.PRESENTATION_CHANGED), outcome)
        assertTrue(CaptureRetry.shouldRetry(outcome, 1))
    }

    @Test
    fun `a new document is an identity refusal and not retried`() = runBlocking {
        val stable = stamp()
        val outcome = guardedCapture(
            sample = { CaptureStampResult.Ready(stable) },
            current = { observation(stable).copy(documentId = "other") },
            copy = { CaptureBytes(byteArrayOf(1), 1, 1) },
        )
        assertEquals(CaptureOutcome.Refused(CaptureRefusal.IDENTITY_CHANGED), outcome)
        assertFalse(CaptureRetry.shouldRetry(outcome, 1))
    }

    @Test
    fun `an unavailable page is a stable refusal`() = runBlocking {
        val outcome = guardedCapture(
            sample = { CaptureStampResult.Unavailable },
            current = { null },
            copy = { CaptureBytes(byteArrayOf(1), 1, 1) },
        )
        assertEquals(CaptureOutcome.Refused(CaptureRefusal.UNAVAILABLE), outcome)
        assertFalse(CaptureRetry.shouldRetry(outcome, 1))
    }

    @Test
    fun `a failed pixel copy is not retried`() = runBlocking {
        val stable = stamp()
        val outcome = guardedCapture(
            sample = { CaptureStampResult.Ready(stable) },
            current = { observation(stable) },
            copy = { null },
        )
        assertEquals(CaptureOutcome.Refused(CaptureRefusal.COPY_FAILED), outcome)
        assertFalse(CaptureRetry.shouldRetry(outcome, 1))
    }

    @Test
    fun `a changed css fingerprint after the copy is retried`() = runBlocking {
        val stable = stamp()
        val changed = stable.copy(probe = stable.probe.copy(scrollY = 120f))
        var samples = 0
        val outcome = guardedCapture(
            sample = {
                samples += 1
                CaptureStampResult.Ready(if (samples == 1) stable else changed)
            },
            current = { observation(stable) },
            copy = { CaptureBytes(byteArrayOf(1), 1, 1) },
        )
        assertEquals(CaptureOutcome.Refused(CaptureRefusal.PRESENTATION_CHANGED), outcome)
        assertTrue(CaptureRetry.shouldRetry(outcome, 1))
    }

    @Test
    fun `capture retries a presentation move up to the bound and then fails`() {
        val moved = CaptureOutcome.Refused(CaptureRefusal.PRESENTATION_CHANGED)
        assertTrue(CaptureRetry.shouldRetry(moved, 1))
        assertTrue(CaptureRetry.shouldRetry(moved, CaptureRetry.MAX_ATTEMPTS - 1))
        assertFalse(CaptureRetry.shouldRetry(moved, CaptureRetry.MAX_ATTEMPTS))
        assertEquals(3, CaptureRetry.MAX_ATTEMPTS)
    }

    private fun presentation(
        pageLeft: Float = 0f,
        pageTop: Float = 0f,
        width: Int = 390,
        height: Int = 844,
        scale: Float = 1f,
        visible: CaptureRect? = null,
    ) = BrowserPresentationGeometry(
        revision = 7L,
        viewportWidthPx = width,
        viewportHeightPx = height,
        scale = scale,
        pageLeft = pageLeft,
        pageTop = pageTop,
        pageWidth = width * scale,
        pageHeight = height * scale,
        visible = visible ?: CaptureRect(
            kotlin.math.floor(pageLeft.toDouble()).toInt(),
            kotlin.math.floor(pageTop.toDouble()).toInt(),
            kotlin.math.ceil((pageLeft + width * scale).toDouble()).toInt(),
            kotlin.math.ceil((pageTop + height * scale).toDouble()).toInt(),
        ),
    )

    private fun stamp(): CaptureStamp = CaptureStamp(
        viewId = "view",
        documentId = "doc",
        ticket = 1L,
        refEpoch = 2L,
        presentation = CaptureFrame(
            presentation = presentation(pageLeft = 10f, pageTop = 20f),
            rect = CaptureRect(10, 20, 400, 864),
            windowWidth = 1080,
            windowHeight = 2400,
        ),
        probe = GeometryProbe("doc", 390, 844, 390, 844, 0f, 0f, 2f, 390f, 844f, 0f, 0f, 0f, 0f, 1f),
    )

    private fun observation(stamp: CaptureStamp, presentation: CaptureFrame = stamp.presentation) =
        CaptureObservation(stamp.viewId, stamp.documentId, stamp.ticket, stamp.refEpoch, presentation)
}

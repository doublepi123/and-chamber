package io.andchamber.app.phone.overlay.safety

import io.andchamber.app.phone.core.PhoneGeometry
import io.andchamber.app.phone.core.PhonePoint
import io.andchamber.app.phone.core.PhoneRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySafetyContractTest {
    private val display = PhoneGeometry(displayWidth = 1080, displayHeight = 2400, rotation = 0)

    // Real API 35 geometry: the requested (x,y) is in the app window space, so the real frame is
    // shifted by the status-bar inset; the parser must use the real frame, never the request.
    private val overlayBounds = PhoneRect(left = 100, top = 536, right = 260, bottom = 696)

    private val expectation = OverlayExpectation(
        owner = "owner-a",
        generation = 3,
        revision = 7,
        phase = OverlayPhase.VISIBLE,
        windowToken = "593486a",
        freshnessDeadlineMs = 1_000,
    )
    private val observation = OverlayObservation(
        owner = "owner-a",
        generation = 3,
        revision = 7,
        display = display,
    )

    private val detailDump = """
        WINDOW MANAGER WINDOWS (dumpsys window windows)
          Window #6 Window{593486a u0 Sys2038:io.example.overlay/com.example.overlay.Overlay}:
            mDisplayId=0 rootTaskId=1 mSession=Session{d2ea5cd 4853:u0a10210}
            mOwnerUid=10210 showForAllUsers=false package=io.example.overlay appop=SYSTEM_ALERT_WINDOW
            mAttrs={(100,400)(160x160) gr=TOP START CENTER sim={adjust=pan} ty=APPLICATION_OVERLAY fmt=TRANSLUCENT
              fl=NOT_FOCUSABLE NOT_TOUCH_MODAL HARDWARE_ACCELERATED}
            Requested w=160 h=160 mLayoutSeq=235
            mBaseLayer=111000 mSubLayer=0    mToken=WindowToken{3a68655 type=2038 android.os.BinderProxy@3e7ad0c}
            mViewVisibility=0x0 mHaveFrame=true mObscured=false
            Frames: parent=[0,136][1080,2337] display=[0,136][1080,2337] frame=[100,536][260,696] last=[100,536][260,696]
            mHasSurface=true isReadyForDisplay()=true mWindowRemovalAllowed=false
            WindowStateAnimator{ef0337a Sys2038:io.example.overlay/com.example.overlay.Overlay}:
              mSurface=Surface(name=Sys2038:io.example.overlay/com.example.overlay.Overlay)/@0x651472b
              Surface: shown=true      mDrawState=HAS_DRAWN       mLastHidden=false
            isOnScreen=true
            isVisible=true
    """.trimIndent()

    /** Full `dumpsys window` on API 35: window sections, no frames. */
    private val fullDump = """
        WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
          mCurrentFocus=Window{593486a u0 Sys2038:io.example.overlay/com.example.overlay.Overlay}
          mFocusedApp=ActivityRecord{fff u0 io.example.phonefixture/.MainActivity t2}
          Window #6 Window{593486a u0 Sys2038:io.example.overlay/com.example.overlay.Overlay}:
            mDisplayId=0 rootTaskId=1
            mOwnerUid=10210 showForAllUsers=false package=io.example.overlay appop=SYSTEM_ALERT_WINDOW
            mAttrs={(100,400)(160x160) gr=TOP START ty=APPLICATION_OVERLAY fmt=TRANSLUCENT}
            mHasSurface=true isReadyForDisplay()=true mWindowRemovalAllowed=false
            mViewVisibility=0x0 mHaveFrame=true mObscured=false
            isOnScreen=true
            isVisible=true
          Window #9 Window{def456 u0 io.example.phonefixture/.MainActivity}:
            mDisplayId=0
            mAttrs={(0,0)(1080x2400) gr=TOP START ty=APPLICATION fmt=TRANSLUCENT}
            mHasSurface=true
            isVisible=true
    """.trimIndent()

    @Test
    fun parserFindsOnlyTheApplicationOverlayWindowInTheRealDetailDump() {
        val scan = OverlayWindowStateParser.parse(detailDump)
        assertEquals(1, scan.windows.size)
        val window = scan.windows.single()
        assertEquals("593486a", window.windowHash)
        assertEquals("io.example.overlay", window.packageName)
        assertEquals(overlayBounds, window.bounds)
        assertTrue(window.hasSurface)
        assertTrue(window.visible)
        assertEquals(0, window.displayId)
    }

    @Test
    fun stableManagerTitleIsTheWindowKeyAndFocusUsesTheBinderHash() {
        val key = "andchamber-phone-overlay:owner-a:3"
        val titled = detailDump.replace(
            "Sys2038:io.example.overlay/com.example.overlay.Overlay",
            "Sys2038:$key",
        )
        val parsed = OverlayWindowStateParser.parse(titled)
        assertEquals(key, parsed.windows.single().titleKey)
        assertEquals("io.example.overlay", parsed.windows.single().packageName)

        val witness = OverlayWitnessReader.read(
            expectation.copy(windowToken = key),
            parsed.copy(currentFocusToken = null),
            display,
            nowMs = 500,
        )
        assertEquals(OverlayPhase.VISIBLE, witness.phase)
        assertEquals(listOf(overlayBounds), witness.actualBounds)
        assertEquals(key, witness.windowKey)
        assertFalse(witness.focused)

        val focused = OverlayWitnessReader.read(
            expectation.copy(windowToken = key),
            parsed.copy(currentFocusToken = "593486a"),
            display,
            nowMs = 500,
        )
        assertTrue(focused.focused)
        assertEquals(OverlayRefusal.OVERLAY_FOCUSED, OverlaySafetyPolicy.backRefusal(focused, 500))

        // The manager never had a binder token; a wrong key is simply unavailable.
        val wrong = OverlayWitnessReader.read(
            expectation.copy(windowToken = "andchamber-phone-overlay:owner-a:4"),
            parsed.copy(currentFocusToken = null),
            display,
            nowMs = 500,
        )
        assertEquals(OverlayPhase.UNAVAILABLE, wrong.phase)
    }

    @Test
    fun fullListHasVisibilityButNoFrameAndTheLegacyNumericTypeStillParses() {
        val full = OverlayWindowStateParser.parse(fullDump)
        assertEquals(1, full.windows.size)
        val window = full.windows.single()
        assertEquals("io.example.overlay", window.packageName)
        assertTrue(window.hasSurface)
        assertTrue(window.visible)
        assertNull("the full list must not invent a frame", window.bounds)
        assertEquals("593486a", full.currentFocusToken)
        assertFalse(window.isSelfVisible)

        val legacy = OverlayWindowStateParser.parse(
            detailDump
                .replace("ty=APPLICATION_OVERLAY", "ty=2038")
                .replace("Sys2038:", ""),
        )
        assertEquals(overlayBounds, legacy.windows.single().bounds)
        assertEquals("io.example.overlay", legacy.windows.single().packageName)
    }

    @Test
    fun visibleWitnessNeedsTheRealFrameFromTheTargetedRead() {
        val scan = OverlayWindowStateParser.parse(detailDump)
        // The targeted window dump has no `mCurrentFocus`; the driver copies focus from the full read.
        assertNull(scan.currentFocusToken)
        val witness = OverlayWitnessReader.read(
            expectation,
            scan.copy(currentFocusToken = "593486a"),
            display,
            nowMs = 500,
        )
        assertEquals(OverlayPhase.VISIBLE, witness.phase)
        assertEquals(listOf(overlayBounds), witness.actualBounds)
        assertTrue(witness.hasSurface)
        assertTrue(witness.visible)
        assertTrue(witness.focused)
        assertNull(OverlaySafetyPolicy.visibleRefusal(witness, nowMs = 500))
        assertNull(OverlaySafetyPolicy.identityRefusal(witness, observation, nowMs = 500))

        val noFrame = OverlayWitnessReader.read(
            expectation,
            OverlayWindowStateParser.parse(fullDump),
            display,
            nowMs = 500,
        )
        assertEquals(OverlayPhase.UNAVAILABLE, noFrame.phase)
        assertEquals(OverlayRefusal.UNAVAILABLE, OverlaySafetyPolicy.visibleRefusal(noFrame, 500))

        val stale = OverlayWitnessReader.read(expectation, scan, display, nowMs = 1_001)
        assertEquals(OverlayPhase.UNAVAILABLE, stale.phase)
        assertEquals(OverlayRefusal.STALE, OverlaySafetyPolicy.visibleRefusal(stale, 1_001))

        val wrongToken = OverlayWitnessReader.read(
            expectation.copy(windowToken = "999999"),
            scan,
            display,
            500,
        )
        assertEquals(OverlayPhase.UNAVAILABLE, wrongToken.phase)
    }

    @Test
    fun forcedHiddenRequiresTheRealSurfaceToBeGone() {
        val stillVisible = OverlayWitnessReader.read(
            expectation,
            OverlayWindowStateParser.parse(fullDump),
            display,
            nowMs = 500,
            forcedHiddenPhase = OverlayPhase.CAPTURE_HIDDEN,
        )
        assertEquals(OverlayPhase.UNAVAILABLE, stillVisible.phase)

        val hidden = OverlayWitnessReader.read(
            expectation,
            OverlayWindowScan(emptyList(), null),
            display,
            nowMs = 500,
            forcedHiddenPhase = OverlayPhase.COLLECTION_HIDDEN,
        )
        assertEquals(OverlayPhase.COLLECTION_HIDDEN, hidden.phase)
        assertTrue(hidden.actualBounds.isEmpty())
        assertFalse(hidden.hasSurface)
        assertFalse(hidden.visible)
        assertEquals(OverlayRefusal.UNAVAILABLE, OverlaySafetyPolicy.visibleRefusal(hidden, 500))
    }

    @Test
    fun boundsOutsideTheDisplayAreUnavailable() {
        val moved = detailDump.replace(
            "frame=[100,536][260,696] last=[100,536][260,696]",
            "frame=[1000,400][1400,800] last=[1000,400][1400,800]",
        )
        val witness = OverlayWitnessReader.read(
            expectation,
            OverlayWindowStateParser.parse(moved),
            display,
            nowMs = 500,
        )
        assertEquals(OverlayPhase.UNAVAILABLE, witness.phase)
    }

    @Test
    fun identityRefusalCoversOwnerGenerationRevisionDisplayAndFreshness() {
        val witness = OverlayWitnessReader.read(
            expectation,
            OverlayWindowStateParser.parse(detailDump),
            display,
            nowMs = 500,
        )
        assertEquals(
            OverlayRefusal.OWNER_CHANGED,
            OverlaySafetyPolicy.identityRefusal(witness, observation.copy(owner = "other"), 500),
        )
        assertEquals(
            OverlayRefusal.GENERATION_CHANGED,
            OverlaySafetyPolicy.identityRefusal(witness, observation.copy(generation = 4), 500),
        )
        // A cosmetic display revision (chat notice, expand, drag) is not an identity change: each
        // action re-reads the real frame and checks the point against it.
        assertEquals(
            null,
            OverlaySafetyPolicy.identityRefusal(witness, observation.copy(revision = 8), 500),
        )
        assertTrue(OverlaySafetyPolicy.isObstruction(OverlayRefusal.POINT_IN_OVERLAY))
        assertTrue(OverlaySafetyPolicy.isObstruction(OverlayRefusal.SWIPE_CROSSES_OVERLAY))
        assertTrue(OverlaySafetyPolicy.isObstruction(OverlayRefusal.OVERLAY_FOCUSED))
        assertFalse(OverlaySafetyPolicy.isObstruction(OverlayRefusal.UNAVAILABLE))
        assertFalse(OverlaySafetyPolicy.isObstruction(OverlayRefusal.STALE))
        assertEquals(
            OverlayRefusal.GEOMETRY_CHANGED,
            OverlaySafetyPolicy.identityRefusal(
                witness,
                observation.copy(display = display.copy(rotation = 1)),
                500,
            ),
        )
        assertEquals(
            OverlayRefusal.STALE,
            OverlaySafetyPolicy.identityRefusal(witness, observation, 1_001),
        )
    }

    @Test
    fun tapAndInputFocusCannotLandInsideTheOverlay() {
        val witness = OverlayWitnessReader.read(
            expectation,
            OverlayWindowStateParser.parse(detailDump),
            display,
            nowMs = 500,
        )
        assertNull(OverlaySafetyPolicy.tapRefusal(witness, PhonePoint(50, 50), 500))
        // The requested y=400 is outside the real frame; only the real frame protects the window.
        assertNull(OverlaySafetyPolicy.tapRefusal(witness, PhonePoint(150, 420), 500))
        assertEquals(
            OverlayRefusal.POINT_IN_OVERLAY,
            OverlaySafetyPolicy.tapRefusal(witness, PhonePoint(150, 600), 500),
        )
        assertEquals(
            OverlayRefusal.POINT_IN_OVERLAY,
            OverlaySafetyPolicy.inputFocusRefusal(witness, PhonePoint(259, 695), 500),
        )
        assertNull(OverlaySafetyPolicy.inputFocusRefusal(witness, null, 500))
        assertFalse(OverlaySafetyPolicy.pointInRect(overlayBounds, PhonePoint(260, 696)))
        assertTrue(OverlaySafetyPolicy.pointInRect(overlayBounds, PhonePoint(259, 695)))
    }

    @Test
    fun swipeCrossingTheMiddleIsRefusedEvenWhenBothEndpointsAreOutside() {
        val witness = OverlayWitnessReader.read(
            expectation,
            OverlayWindowStateParser.parse(detailDump),
            display,
            nowMs = 500,
        )
        assertTrue(
            OverlaySafetyPolicy.segmentIntersectsRect(
                PhonePoint(0, 600),
                PhonePoint(500, 600),
                overlayBounds,
            ),
        )
        assertTrue(
            OverlaySafetyPolicy.segmentIntersectsRect(
                PhonePoint(180, 0),
                PhonePoint(180, 1000),
                overlayBounds,
            ),
        )
        assertTrue(
            OverlaySafetyPolicy.segmentIntersectsRect(
                PhonePoint(0, 700),
                PhonePoint(600, 500),
                overlayBounds,
            ),
        )
        assertTrue(
            OverlaySafetyPolicy.segmentIntersectsRect(
                PhonePoint(150, 600),
                PhonePoint(0, 0),
                overlayBounds,
            ),
        )
        assertFalse(
            OverlaySafetyPolicy.segmentIntersectsRect(
                PhonePoint(0, 0),
                PhonePoint(50, 1000),
                overlayBounds,
            ),
        )
        assertFalse(
            OverlaySafetyPolicy.segmentIntersectsRect(
                PhonePoint(300, 0),
                PhonePoint(300, 1000),
                overlayBounds,
            ),
        )
        assertEquals(
            OverlayRefusal.SWIPE_CROSSES_OVERLAY,
            OverlaySafetyPolicy.swipeRefusal(
                witness,
                PhonePoint(0, 600),
                PhonePoint(500, 600),
                500,
            ),
        )
        assertNull(
            OverlaySafetyPolicy.swipeRefusal(
                witness,
                PhonePoint(0, 0),
                PhonePoint(50, 1000),
                500,
            ),
        )
    }

    @Test
    fun backIsRefusedWhenTheOverlayOwnsTheCurrentFocus() {
        val scan = OverlayWindowStateParser.parse(fullDump)
        val focused = OverlayWitnessReader.read(
            expectation,
            scan,
            display,
            nowMs = 500,
        )
        // Focus alone is not visibility, so this witness is unavailable and refuses everything.
        assertEquals(OverlayPhase.UNAVAILABLE, focused.phase)

        val detailFocused = OverlayWitnessReader.read(
            expectation,
            OverlayWindowStateParser.parse(detailDump).copy(
                currentFocusToken = "593486a",
            ),
            display,
            nowMs = 500,
        )
        assertEquals(
            OverlayRefusal.OVERLAY_FOCUSED,
            OverlaySafetyPolicy.backRefusal(detailFocused, 500),
        )

        val notFocused = OverlayWitnessReader.read(
            expectation,
            OverlayWindowStateParser.parse(detailDump).copy(
                currentFocusToken = "def456",
            ),
            display,
            nowMs = 500,
        )
        assertNull(OverlaySafetyPolicy.backRefusal(notFocused, 500))
    }

    @Test
    fun absentOrUnparsableWindowSectionsAreNeverVisible() {
        val absent = OverlayWindowStateParser.parse(
            "Bad window command, or no windows match: deadbeef\nUse -h for help.",
        )
        assertTrue(absent.windows.isEmpty())
        val witness = OverlayWitnessReader.read(expectation, absent, display, nowMs = 500)
        assertEquals(OverlayPhase.UNAVAILABLE, witness.phase)

        val nonOverlay = OverlayWindowStateParser.parse(
            """
            Window #9 Window{def456 u0 io.example.phonefixture/.MainActivity}:
              mDisplayId=0
              mAttrs={(0,0)(1080x2400) gr=TOP START ty=APPLICATION fmt=TRANSLUCENT}
              mHasSurface=true
              isVisible=true
            """.trimIndent(),
        )
        assertTrue(nonOverlay.windows.isEmpty())
    }

    @Test
    fun missingSurfaceOrVisibilityFailsTheWindowVisibleState() {
        val hidden = detailDump
            .replace("mHasSurface=true", "mHasSurface=false")
            .replace("isVisible=true", "isVisible=false")
        val window = OverlayWindowStateParser.parse(hidden).windows.single()
        assertFalse(window.hasSurface)
        assertFalse(window.visible)

        val offScreen = OverlayWindowStateParser.parse(
            detailDump.replace("isOnScreen=true", "isOnScreen=false"),
        ).windows.single()
        assertFalse(offScreen.visible)
    }
}

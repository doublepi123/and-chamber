// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.overlay.safety

import io.andchamber.app.phone.core.PhoneGeometry
import io.andchamber.app.phone.core.PhoneRect

/**
 * One `TYPE_APPLICATION_OVERLAY` window parsed from `dumpsys window`.
 *
 * Two identities are deliberately separate:
 * - [windowHash] is the WindowManager binder hash from `Window{<hash> u0 ...}`. It only exists in
 *   `dumpsys`; the manager cannot reflect it and must not be asked to.
 * - [title] / [titleKey] is the manager's stable `LayoutParams.title`
 *   (`andchamber-phone-overlay:<owner>:<generation>`). This is the only key a manager supplies.
 */
data class OverlayWindowState(
    /** Binder hash from `Window{<hash> u0 ...}`; internal to the driver, never manager-supplied. */
    val windowHash: String,
    /** Full window name after `uN`, e.g. `Sys2038:andchamber-phone-overlay:owner:1`. */
    val title: String,
    val packageName: String,
    /** Real `Frames: ... frame=[l,t][r,b]` when the dump has it; null otherwise. */
    val bounds: PhoneRect?,
    val hasSurface: Boolean,
    val visible: Boolean,
    val displayId: Int,
    /**
     * Real `mForceHideNonSystemOverlayWindow=true`: the focused app asked WindowManager to hide
     * every non-system overlay. It explains a policy-hidden window (our surface is still attached
     * but the system does not show it); it is not visibility proof by itself.
     */
    val forceHiddenBySystem: Boolean = false,
) {
    /**
     * The manager-owned window key: the full name with the framework `Sys<type>:` prefix removed.
     * For an App overlay this is exactly `LayoutParams.title`.
     */
    val titleKey: String get() = title.replaceFirst(SYS_PREFIX, "")

    val isSelfVisible: Boolean
        get() = hasSurface && visible && bounds != null

    private companion object {
        private val SYS_PREFIX = Regex("""^Sys\d+:""")
    }
}

data class OverlayWindowScan(
    val windows: List<OverlayWindowState>,
    val currentFocusToken: String?,
)

/**
 * Android-free parser for the bounded real window-manager state on API 31..35.
 *
 * Two real `dumpsys window` shapes are accepted:
 * - the full `dumpsys window` list: window sections with `mHasSurface`, `isVisible`, `mAttrs`
 *   (`ty=APPLICATION_OVERLAY` or legacy `ty=2038`) and the `package=` line, but **no frame**;
 * - the targeted `dumpsys window <token>` dump: one window section that also carries the real
 *   `Frames: parent=... frame=[l,t][r,b]` line.
 *
 * Only `TYPE_APPLICATION_OVERLAY` windows are returned. A window is `visible` only when a real
 * surface exists, the window view and policy are visible and the window is on screen; missing
 * fields are never guessed. Bounds stay null unless the dump really prints a frame, so a visible
 * witness needs the targeted read. The window `title` carries the manager's stable
 * `LayoutParams.title`; the framework may prefix it with `Sys<type>:`, and
 * [OverlayWindowState.titleKey] removes that prefix. `expectation.windowToken` is that stable title
 * (a WM hash is also accepted for tests/legacy): use [findWindow] to resolve it to the real window
 * and its [OverlayWindowState.windowHash]. The parser is deliberately format-tolerant about the
 * fields the driver relies on and the driver fails closed when the expected key is absent.
 */
object OverlayWindowStateParser {
    const val TYPE_APPLICATION_OVERLAY: Int = 2038

    // `Window{593486a u0 Sys2038:andchamber-phone-overlay:o:1}` and
    // `Window{593486a u0 io.example.overlay/com.example.overlay.Overlay}`.
    private val WINDOW_TITLE = Regex(
        """Window #\d+ Window\{([0-9a-fA-F]+)\s+u(\d+)\s+([^\s}]+)\}""",
    )
    private val CURRENT_FOCUS = Regex(
        """mCurrentFocus=Window\{([0-9a-fA-F]+)\s+u(\d+)\s+(?:Sys\d+:)?[^\s}]+\}""",
    )
    private val PACKAGE_LINE = Regex("""(?:^|\s)package=([A-Za-z0-9_.]+)""")
    private val TYPE = Regex("""\bty=(\d+|[A-Z_]+)\b""")
    private val FRAME = Regex(
        """Frames:[^\n]*?\bframe=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""",
    )
    private val LEGACY_FRAME = Regex("""mFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
    private val HAS_SURFACE = Regex("""mHasSurface=(true|false)""")
    private val IS_VISIBLE = Regex("""isVisible=(true|false)""")
    private val IS_ON_SCREEN = Regex("""isOnScreen=(true|false)""")
    private val VIEW_VISIBILITY = Regex("""mViewVisibility=0x([0-9a-fA-F]+)""")
    private val POLICY_VISIBILITY = Regex("""mPolicyVisibility=(true|false)""")
    private val FORCE_HIDDEN = Regex("""mForceHideNonSystemOverlayWindow=(true|false)""")
    private val DISPLAY_ID = Regex("""mDisplayId=(\d+)""")
    private val SYS_PREFIX = Regex("""^Sys\d+:""")

    /**
     * Resolves the manager key (`expectation.windowToken`: the stable `LayoutParams.title`) to the
     * real window. A binder hash is also accepted so tests/legacy callers keep working. The manager
     * never sees the resolved hash; the driver uses it only for the targeted frame read.
     */
    fun findWindow(scan: OverlayWindowScan, windowKey: String): OverlayWindowState? =
        scan.windows.firstOrNull { it.windowHash == windowKey || it.titleKey == windowKey }

    fun parse(text: String): OverlayWindowScan {
        val currentFocus = CURRENT_FOCUS.find(text)?.groupValues?.get(1)
        val windows = ArrayList<OverlayWindowState>()
        var sectionToken: String? = null
        var sectionTitle: String? = null
        var lines = ArrayList<String>()

        fun finishSection() {
            val token = sectionToken ?: return
            val title = sectionTitle ?: return
            val body = lines.joinToString("\n")
            val typeRaw = TYPE.find(body)?.groupValues?.get(1) ?: return
            val type = when (typeRaw) {
                "APPLICATION_OVERLAY" -> TYPE_APPLICATION_OVERLAY
                else -> typeRaw.toIntOrNull() ?: return
            }
            if (type != TYPE_APPLICATION_OVERLAY) return
            val titleKey = title.replaceFirst(SYS_PREFIX, "")
            val packageName = PACKAGE_LINE.find(body)?.groupValues?.get(1)
                ?: titleKey.substringBefore('/', missingDelimiterValue = "")
            val bounds = FRAME.find(body)?.let(::rectOf) ?: LEGACY_FRAME.find(body)?.let(::rectOf)
            val hasSurface = HAS_SURFACE.find(body)?.groupValues?.get(1) == "true"
            val isVisible = IS_VISIBLE.find(body)?.groupValues?.get(1) == "true"
            val onScreen = IS_ON_SCREEN.find(body)?.groupValues?.get(1) != "false"
            val viewVisible = VIEW_VISIBILITY.find(body)?.groupValues?.get(1)
                ?.toIntOrNull(16)
                ?.let { it == 0 }
                ?: true
            val policyVisible = POLICY_VISIBILITY.find(body)?.groupValues?.get(1) != "false"
            val forceHiddenBySystem = FORCE_HIDDEN.find(body)?.groupValues?.get(1) == "true"
            val displayId = DISPLAY_ID.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            windows += OverlayWindowState(
                windowHash = token,
                title = title,
                packageName = packageName,
                bounds = bounds,
                hasSurface = hasSurface,
                visible = hasSurface && isVisible && onScreen && viewVisible && policyVisible,
                displayId = displayId,
                forceHiddenBySystem = forceHiddenBySystem,
            )
        }

        for (line in text.lineSequence()) {
            val match = WINDOW_TITLE.find(line)
            if (match != null) {
                finishSection()
                sectionToken = match.groupValues[1]
                sectionTitle = match.groupValues[3]
                lines = ArrayList()
            } else if (sectionToken != null) {
                lines += line
            }
        }
        finishSection()
        return OverlayWindowScan(windows = windows, currentFocusToken = currentFocus)
    }

    private fun rectOf(match: MatchResult): PhoneRect = PhoneRect(
        left = match.groupValues[1].toInt(),
        top = match.groupValues[2].toInt(),
        right = match.groupValues[3].toInt(),
        bottom = match.groupValues[4].toInt(),
    )
}

/**
 * Combines the manager expectation with the parsed real WindowManager state. Visible requires the
 * expected key to have a real surface, a real frame inside the display and a visible View. Forced
 * hidden phases require that same window to have no visible surface; otherwise the result is
 * UNAVAILABLE, never a pretend hide.
 *
 * The manager key is matched against the binder token (legacy/tests) or the stable window title
 * (`LayoutParams.title`). Input focus is compared with the matched window's binder token, so a
 * focusable overlay is still attributed correctly even though the manager never supplies a binder.
 */
object OverlayWitnessReader {
    fun read(
        expectation: OverlayExpectation,
        scan: OverlayWindowScan,
        display: PhoneGeometry,
        nowMs: Long,
        forcedHiddenPhase: OverlayPhase? = null,
    ): OverlayWitness {
        val key = expectation.windowToken
        val window = key?.let { OverlayWindowStateParser.findWindow(scan, it) }
        val focused = window != null && scan.currentFocusToken == window.windowHash
        val forceHiddenBySystem = window?.forceHiddenBySystem == true
        if (nowMs > expectation.freshnessDeadlineMs) {
            return unavailable(expectation, display, focused, key, window)
        }
        if (forcedHiddenPhase != null) {
            if (forcedHiddenPhase != OverlayPhase.CAPTURE_HIDDEN &&
                forcedHiddenPhase != OverlayPhase.COLLECTION_HIDDEN
            ) {
                return unavailable(expectation, display, focused, key, window)
            }
            if (key == null) {
                return unavailable(expectation, display, focused, null, null)
            }
            if (window != null && window.hasSurface && window.visible) {
                // The manager claimed a hide but the real window tree still shows a visible surface.
                return unavailable(expectation, display, focused, key, window)
            }
            return OverlayWitness(
                owner = expectation.owner,
                generation = expectation.generation,
                revision = expectation.revision,
                phase = forcedHiddenPhase,
                windowToken = key,
                actualBounds = emptyList(),
                hasSurface = false,
                visible = false,
                focused = focused,
                display = display,
                freshnessDeadlineMs = expectation.freshnessDeadlineMs,
                forceHiddenBySystem = forceHiddenBySystem,
            )
        }
        if (expectation.phase != OverlayPhase.VISIBLE || key == null) {
            return unavailable(expectation, display, focused, key, window)
        }
        val bounds = window?.bounds
        if (window == null ||
            !window.hasSurface ||
            !window.visible ||
            window.displayId != 0 ||
            bounds == null ||
            !bounds.isWithin(display)
        ) {
            return unavailable(expectation, display, focused, key, window)
        }
        return OverlayWitness(
            owner = expectation.owner,
            generation = expectation.generation,
            revision = expectation.revision,
            phase = OverlayPhase.VISIBLE,
            windowToken = key,
            actualBounds = listOf(bounds),
            hasSurface = true,
            visible = true,
            focused = focused,
            display = display,
            freshnessDeadlineMs = expectation.freshnessDeadlineMs,
            forceHiddenBySystem = forceHiddenBySystem,
        )
    }

    /**
     * An UNAVAILABLE witness keeps the parsed real facts about the matched window (surface present,
     * system-force-hidden) so the driver can tell "our window exists but the target app hides it"
     * apart from "the window is gone / unknown". Neither field ever makes the witness VISIBLE.
     */
    private fun unavailable(
        expectation: OverlayExpectation,
        display: PhoneGeometry,
        focused: Boolean,
        key: String?,
        window: OverlayWindowState?,
    ): OverlayWitness = OverlayWitness(
        owner = expectation.owner,
        generation = expectation.generation,
        revision = expectation.revision,
        phase = OverlayPhase.UNAVAILABLE,
        windowToken = key,
        actualBounds = emptyList(),
        hasSurface = window?.hasSurface == true,
        visible = false,
        focused = focused,
        display = display,
        freshnessDeadlineMs = expectation.freshnessDeadlineMs,
        forceHiddenBySystem = window?.forceHiddenBySystem == true,
    )
}

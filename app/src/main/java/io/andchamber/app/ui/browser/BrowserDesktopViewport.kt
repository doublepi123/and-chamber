package io.andchamber.app.ui.browser

/**
 * The public desktop viewport contract for the native browser page. All values are CSS pixels; the
 * page is configured natively and the presenter never claims the DOM was measured.
 *
 * Width and height must be supplied as a pair and stay inside the inclusive ranges below. A value
 * outside them is refused with an unsupported-parameter result; it is never clamped and never
 * loaded. Mobile pages use the real phone viewport and never accept these custom values.
 */
object BrowserDesktopViewport {
    const val DEFAULT_WIDTH = 1920
    const val DEFAULT_HEIGHT = 1080
    const val MIN_WIDTH = 640
    const val MAX_WIDTH = 3840
    const val MIN_HEIGHT = 360
    const val MAX_HEIGHT = 2160

    data class Size(val width: Int, val height: Int) {
        init {
            require(isValid(width, height)) { "desktop viewport out of range" }
        }
    }

    val default: Size = Size(DEFAULT_WIDTH, DEFAULT_HEIGHT)

    fun isValid(width: Int, height: Int): Boolean =
        width in MIN_WIDTH..MAX_WIDTH && height in MIN_HEIGHT..MAX_HEIGHT

    /**
     * Parses an optional RPC pair. Neither value means "unspecified"; exactly one is invalid; a
     * non-integer or out-of-range pair is invalid. No value is ever clamped.
     */
    fun parse(width: String?, height: String?): Parse {
        val w = width?.takeIf { it.isNotBlank() }
        val h = height?.takeIf { it.isNotBlank() }
        if (w == null && h == null) return Parse.Unspecified
        if (w == null || h == null) return Parse.Invalid("width and height must be paired")
        val parsedWidth = w.toIntOrNull() ?: return Parse.Invalid("width must be an integer")
        val parsedHeight = h.toIntOrNull() ?: return Parse.Invalid("height must be an integer")
        if (!isValid(parsedWidth, parsedHeight)) {
            return Parse.Invalid("width $MIN_WIDTH..$MAX_WIDTH, height $MIN_HEIGHT..$MAX_HEIGHT")
        }
        return Parse.Valid(Size(parsedWidth, parsedHeight))
    }

    sealed interface Parse {
        /** No pair supplied. The caller decides: default for a new desktop page, keep for a resize. */
        data object Unspecified : Parse
        data class Valid(val size: Size) : Parse
        data class Invalid(val reason: String) : Parse
    }
}

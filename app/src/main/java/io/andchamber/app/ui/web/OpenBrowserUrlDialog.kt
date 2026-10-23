package io.andchamber.app.ui.web

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.andchamber.app.R
import io.andchamber.app.browser.BrowserUrlPolicy
import io.andchamber.app.browser.UrlDecision
import io.andchamber.app.ui.theme.ChamberTextStyles
import kotlinx.coroutines.delay
import java.net.URI
import java.util.Locale

/** How long a message waits for typing to pause, so a half-typed address doesn't flash red. */
private const val MESSAGE_DELAY_MS = 700L

/**
 * Asks for a web address to open in this chat's browser window. Open stays off until
 * [checkBrowserUrl] finds an address the window will open; the field then previews it exactly, so
 * an added `https://` is visible. Go on the keyboard does the same as Open. [onOpen] gets the
 * address once per edit, and the caller closes the dialog. Opening is not a grant by itself: the
 * presenter decides control once the page has loaded, and Take over in the window stops it.
 */
@Composable
internal fun OpenBrowserUrlDialog(
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Not saveable on purpose: a pasted link can carry a sign-in token, and the caller doesn't
    // bring the dialog back after a restart either.
    var text by remember { mutableStateOf("") }
    // Go on text that can't be opened shows why at once; the next edit hides it again.
    var attempted by remember { mutableStateOf(false) }
    // A second tap before the dialog closes must not open the page twice.
    var sent by remember { mutableStateOf(false) }
    // The text typing last paused on. Compared with the current text, so a new keystroke hides a
    // message in the same frame instead of one frame late.
    var settled by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(text) {
        delay(MESSAGE_DELAY_MS)
        settled = text
    }
    val policy = remember { BrowserUrlPolicy() }
    val check = remember(text) { checkBrowserUrl(text, policy) }
    val ready = (check as? BrowserUrlCheck.Ready)?.url
    val problem =
        when (check) {
            is BrowserUrlCheck.Refused -> R.string.browser_open_page_refused
            BrowserUrlCheck.Invalid -> R.string.browser_open_page_invalid
            BrowserUrlCheck.Unfinished -> if (attempted) R.string.browser_open_page_invalid else null
            is BrowserUrlCheck.Ready -> null
        }
    val message = problem?.takeIf { attempted || settled == text }?.let { stringResource(it) }
    val focus = remember { FocusRequester() }

    fun submit() {
        if (sent) return
        val url = ready
        if (url == null) {
            attempted = true
            return
        }
        sent = true
        onOpen(url)
    }

    // Drawn in the Activity window, not a separate dialog window. Compose 1.7's Dialog sizes its
    // own window to the content and, on API 31+, sets no soft-input mode, so the keyboard insets
    // that window receives are not reliable and the keyboard covered Open. The caller (WebScreen)
    // places this inside its content box, which already stops above the system bars and the
    // keyboard (imeAnimationTarget, the same inset the chat composer uses). The safeDrawing padding
    // below only applies to insets nobody above consumed, so it never counts the keyboard twice.
    // The address field and the Open/Cancel row are outside the scrolling part; the title shrinks
    // and the hint scrolls, or is left out, when space is short.
    BackHandler(onBack = onDismiss)
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        // Same window as the page: it already has window focus, so focus the field right away.
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            // Outside the card: a tap dismisses, as with any dialog, and nothing reaches the page.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val title = stringResource(R.string.browser_open_page)
            val fontScale = LocalDensity.current.fontScale
            val compact = openUrlDialogCompact(maxHeight.value, fontScale)
            // Very little room (landscape with the keyboard, large fonts): the hint is left out and
            // the whole card scrolls, so the field and the buttons are never pushed off.
            val minimal = openUrlDialogMinimal(maxHeight.value, fontScale)
            val inner = if (compact) 16.dp else 24.dp
            Surface(
                // Taps inside the card stay in the card.
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .semantics { paneTitle = title },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(
                    Modifier
                        .then(if (minimal) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(start = inner, end = inner, top = inner, bottom = 8.dp),
                ) {
                    Text(
                        title,
                        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!minimal) {
                        // The only part that gives way: it takes what is left and scrolls.
                        Column(
                            modifier = Modifier.weight(1f, fill = false)
                                .padding(top = if (compact) 4.dp else 12.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                stringResource(R.string.browser_open_page_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            attempted = false
                            sent = false
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        label = { Text(stringResource(R.string.field_address), maxLines = 1) },
                        placeholder = {
                            Text(stringResource(R.string.browser_open_page_placeholder), style = ChamberTextStyles.code, maxLines = 1)
                        },
                        textStyle = ChamberTextStyles.code.copy(color = MaterialTheme.colorScheme.onSurface),
                        singleLine = true,
                        isError = message != null,
                        supportingText = {
                            when {
                                message != null -> Text(message, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                                // One line: a long address is cut at the end and its host stays readable.
                                ready != null ->
                                    Text(
                                        stringResource(R.string.browser_open_page_preview, ready),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                // Keeps the line, so the buttons don't jump when a message comes or goes.
                                else -> Text("")
                            }
                        },
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                                autoCorrectEnabled = false,
                                capitalization = KeyboardCapitalization.None,
                            ),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        ) {
                            Text(stringResource(R.string.cancel), maxLines = 1)
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = { submit() },
                            enabled = ready != null && !sent,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.open), fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Whether the dialog uses its compact form (one-line smaller title, tighter padding) in
 * [availableHeightDp] of keyboard-free space. Larger fonts need more room before the regular form
 * fits, so the threshold grows with [fontScale].
 */
internal fun openUrlDialogCompact(availableHeightDp: Float, fontScale: Float): Boolean =
    availableHeightDp < OPEN_URL_DIALOG_REGULAR_MIN_HEIGHT * fontScale.coerceAtLeast(1f)

/** Height (dp at font scale 1) below which the dialog switches to its compact form. */
internal const val OPEN_URL_DIALOG_REGULAR_MIN_HEIGHT = 300f

/**
 * Whether there is too little room for even a line of hint next to the title, field and buttons
 * (about 200dp at font scale 1). The dialog then leaves the hint out and scrolls as a whole.
 */
internal fun openUrlDialogMinimal(availableHeightDp: Float, fontScale: Float): Boolean =
    availableHeightDp < OPEN_URL_DIALOG_MINIMAL_HEIGHT * fontScale.coerceAtLeast(1f)

internal const val OPEN_URL_DIALOG_MINIMAL_HEIGHT = 220f

/** What the dialog makes of the typed text. */
internal sealed interface BrowserUrlCheck {
    /** Empty, or still on its way to an address ("https:/", "localhost:", "www."): Open is off, no message yet. */
    data object Unfinished : BrowserUrlCheck

    /** Can't become a web address: Open is off. */
    data object Invalid : BrowserUrlCheck

    /** A web address the browser window won't open, such as plain http, localhost or a home-network address. */
    data class Refused(val url: String) : BrowserUrlCheck

    /** Opens [url]. */
    data class Ready(val url: String) : BrowserUrlCheck
}

/**
 * Normalizes the typed text ([normalizeBrowserUrl]) and asks the browser window's own address check
 * ([BrowserUrlPolicy.checkSyntax], which the window runs again before it opens anything) whether
 * it would open it. Without this, Open on a refused address would close the dialog with nothing
 * opened.
 */
internal fun checkBrowserUrl(
    input: String,
    policy: BrowserUrlPolicy = BrowserUrlPolicy(),
): BrowserUrlCheck {
    val text = trimAddress(input).lowercase(Locale.ROOT)
    // A start of "https://" is no host to open, even though "ht" would parse as one.
    if (text.isEmpty() || "https://".startsWith(text) || "http://".startsWith(text)) return BrowserUrlCheck.Unfinished
    val url = normalizeBrowserUrl(input)
    if (url == null) {
        // "localhost:" before its port is on its way; "javascript:" never becomes a web address.
        val beforePort = text.endsWith(':') && text.dropLast(1) !in NON_WEB_SCHEMES
        return if (beforePort) BrowserUrlCheck.Unfinished else BrowserUrlCheck.Invalid
    }
    // "www." on the way to "www.example.com".
    if (runCatching { URI(url).host }.getOrNull()?.endsWith('.') == true) return BrowserUrlCheck.Unfinished
    val allowed = runCatching { policy.checkSyntax(url) is UrlDecision.Allow }.getOrDefault(false)
    return if (allowed) BrowserUrlCheck.Ready(url) else BrowserUrlCheck.Refused(url)
}

/** Text before the first ':' shaped like a URL scheme, and everything after that colon. */
private val SCHEME_AND_REST = Regex("([A-Za-z][A-Za-z0-9+.-]*):(.*)")

/** What follows "host:" when that colon starts a port rather than a scheme: "3000", "8443/docs". */
private val PORT_AND_REST = Regex("[0-9]+([/?#].*)?")

/** Never read as a host name, so "tel:123" or "javascript:1" is not taken for a host and port. */
private val NON_WEB_SCHEMES =
    setOf(
        "about", "blob", "chrome", "content", "data", "file", "filesystem", "intent", "jar",
        "javascript", "mailto", "market", "sms", "tel", "vbscript", "view-source", "ws", "wss",
    )

/** Kept as typed after the host: RFC 3986 path, query and fragment characters besides letters and digits. */
private const val TAIL_SAFE = "-._~!$&'()*+,;=:@/?"
private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * The address for what the person typed, or null when it isn't a web address.
 *
 * Trims, adds `https://` when no scheme is given (`localhost:3000` is a host and a port), and
 * accepts only http and https with an ASCII host: no spaces, no user name or password, port 1 to
 * 65535. Every other scheme is refused (javascript:, data:, file:, intent:, content:, about: and so
 * on). Scheme and host are lowercased. In the path, query and fragment, other text is escaped as
 * UTF-8 the way a browser would and existing escapes are kept, so the result is plain ASCII, parses
 * as a URI, starts with `http://` or `https://`, and comes back unchanged when normalized again.
 * Whether the browser window opens it is a separate check ([checkBrowserUrl]).
 */
internal fun normalizeBrowserUrl(input: String): String? {
    val text = trimAddress(input)
    if (text.isEmpty() || text.any { it.isWhitespace() || it.isISOControl() } || hasLoneSurrogate(text)) return null
    val typed = typedScheme(text)
    val scheme = typed?.first ?: "https"
    val rest = typed?.second ?: if (text.startsWith("//")) text else "//$text"
    if ((scheme != "http" && scheme != "https") || !rest.startsWith("//")) return null
    val hierarchical = rest.substring(2)
    val authorityEnd =
        hierarchical.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) hierarchical.length else it }
    val authority = hierarchical.substring(0, authorityEnd)
    // International host names are refused by the window in any form, and look-alike letters
    // (a fullwidth "：" or "ｅ") must not be folded quietly into a different host.
    if (authority.any { it.code >= 0x80 }) return null
    val tail = escapeTail(hierarchical.substring(authorityEnd))
    val uri = runCatching { URI("$scheme://$authority$tail") }.getOrNull() ?: return null
    if (uri.isOpaque || uri.rawUserInfo != null) return null
    val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    // "https//example.com" is missing its colon: no real site is called "http" or "https".
    if (typed == null && (host == "http" || host == "https")) return null
    val port = uri.port
    if (port == 0 || port > 65_535) return null
    return buildString {
        append(scheme).append("://").append(host)
        if (port != -1) append(':').append(port)
        append(uri.rawPath.orEmpty())
        uri.rawQuery?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }
}

/** Trims spaces, line breaks and invisible format characters (a pasted zero-width space or BOM). */
private fun trimAddress(input: String): String =
    input.trim { it.isWhitespace() || it.category == CharCategory.FORMAT }

/** Half of an emoji or other paired character without its other half: broken pasted text. */
private fun hasLoneSurrogate(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> i += 2
            c.isSurrogate() -> return true
            else -> i++
        }
    }
    return false
}

/** The scheme the person typed and what follows its colon, or null when the text starts with a host. */
private fun typedScheme(text: String): Pair<String, String>? {
    val match = SCHEME_AND_REST.matchEntire(text) ?: return null
    val scheme = match.groupValues[1].lowercase(Locale.ROOT)
    val rest = match.groupValues[2]
    // "localhost:3000" and "example.com:8443/docs" are a host and a port, not a scheme.
    val hostAndPort = scheme !in NON_WEB_SCHEMES && scheme != "http" && scheme != "https" && PORT_AND_REST.matches(rest)
    return if (hostAndPort) null else scheme to rest
}

/**
 * Escapes what java.net.URI refuses after the host (`|`, `^`, a lone `%`, a second `#`, any
 * non-ASCII text) as UTF-8 `%XX`, and keeps everything else, existing escapes included, as typed.
 */
private fun escapeTail(tail: String): String {
    val out = StringBuilder(tail.length)
    var inFragment = false
    var i = 0
    while (i < tail.length) {
        val c = tail[i]
        when {
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in TAIL_SAFE -> out.append(c)
            c == '#' && !inFragment -> {
                inFragment = true
                out.append(c)
            }
            c == '%' && i + 2 < tail.length && tail[i + 1].isAsciiHex() && tail[i + 2].isAsciiHex() -> out.append(c)
            else -> {
                // One code point: an emoji is a surrogate pair, two chars.
                val end = if (c.isHighSurrogate() && i + 1 < tail.length) i + 2 else i + 1
                for (byte in tail.substring(i, end).toByteArray(Charsets.UTF_8)) {
                    val b = byte.toInt() and 0xFF
                    out.append('%').append(HEX_DIGITS[b shr 4]).append(HEX_DIGITS[b and 0xF])
                }
                i = end
                continue
            }
        }
        i++
    }
    return out.toString()
}

private fun Char.isAsciiHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

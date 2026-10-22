package io.andchamber.app.ui.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.flow.first
import java.net.URI
import java.util.Locale

/** How long a message waits for typing to pause, so a half-typed address doesn't flash red. */
private const val MESSAGE_DELAY_MS = 700L

/**
 * Asks for a web address to open in this chat's browser window. Open stays off until
 * [checkBrowserUrl] finds an address the window will open; the field then previews it exactly, so
 * an added `https://` is visible. Go on the keyboard does the same as Open. [onOpen] gets the
 * address once per edit, and the caller closes the dialog. Opening is not a grant: the person
 * still taps "Allow agent" in the window before the agent can use the page.
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.browser_open_page), style = MaterialTheme.typography.titleLarge) },
        text = {
            // Read here, inside the dialog: its window has its own focus and keyboard controller.
            val window = LocalWindowInfo.current
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(window) {
                // Focus once the dialog window has it, so the keyboard comes up with the field.
                snapshotFlow { window.isWindowFocused }.first { it }
                runCatching { focus.requestFocus() }
                keyboard?.show()
            }
            // Scrolls when the keyboard leaves little room, as in landscape.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.browser_open_page_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        attempted = false
                        sent = false
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    label = { Text(stringResource(R.string.field_address)) },
                    placeholder = {
                        Text(stringResource(R.string.browser_open_page_placeholder), style = ChamberTextStyles.code)
                    },
                    textStyle = ChamberTextStyles.code.copy(color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true,
                    isError = message != null,
                    supportingText = {
                        when {
                            message != null -> Text(message, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = ready != null && !sent) {
                Text(stringResource(R.string.open), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

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

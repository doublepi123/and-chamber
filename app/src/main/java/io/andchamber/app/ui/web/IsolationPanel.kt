package io.andchamber.app.ui.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.SystemSecurityUpdateWarning
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.andchamber.app.R
import io.andchamber.app.data.WebIsolationFailure
import io.andchamber.app.data.canonicalWebOrigin
import io.andchamber.app.ui.theme.ChamberTheme
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The page could not get its own WebView profile, so nothing was loaded and no password was sent.
 * Data is kept as it is. A local server keeps running; a remote one can be opened in the browser
 * (address only). [onRetry] is offered only when a new attempt can help.
 */
@Composable
internal fun IsolationPanel(
    failure: WebIsolationFailure,
    isLocal: Boolean,
    onRetry: () -> Unit,
    onHome: () -> Unit,
    onOpenInBrowser: (() -> Unit)?,
) {
    val home = PanelAction(stringResource(R.string.home), onHome)
    val browser = onOpenInBrowser?.let { PanelAction(stringResource(R.string.open_in_browser), it) }
    val (title, body) =
        when (failure) {
            WebIsolationFailure.UnsupportedWebView -> R.string.web_update_webview_title to R.string.web_update_webview_body
            WebIsolationFailure.ProfileBindingFailed -> R.string.web_isolation_failed_title to R.string.web_isolation_failed_body
            WebIsolationFailure.InvalidServerAddress -> R.string.web_bad_address_title to R.string.web_bad_address_body
        }
    val bodyText = stringResource(body)
    val shownBody = if (isLocal) bodyText + "\n\n" + stringResource(R.string.web_local_keeps_running) else bodyText
    // A bad address won't get better by trying again; it is fixed on Home.
    val retry = if (failure == WebIsolationFailure.InvalidServerAddress) null else PanelAction(stringResource(R.string.try_again), onRetry)
    StatusPanel(
        icon = if (failure == WebIsolationFailure.InvalidServerAddress) Icons.Rounded.LinkOff else Icons.Rounded.SystemSecurityUpdateWarning,
        iconTint = ChamberTheme.colors.warning,
        title = stringResource(title),
        body = shownBody,
        primary = retry ?: home,
        secondary = if (retry != null) home else null,
        tertiary = browser,
    )
}

/**
 * The address to hand to a browser for a remote server: the saved address, normalized, only when it
 * is a plain http(s) URL the profile could be keyed on (same check as the profile). Never one with a
 * user name or password; query and fragment are dropped. Pure, so it is unit-testable.
 */
internal fun browserAddressFor(baseUrl: String): String? {
    if (canonicalWebOrigin(baseUrl) == null) return null
    val url = baseUrl.trim().toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return url.newBuilder().query(null).fragment(null).build().toString()
}

/** Opens [url] in the user's browser. Nothing else (cookies, passwords, tokens) is passed along. */
internal fun openAddressInBrowser(
    context: Context,
    url: String,
) {
    val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.no_app_for_link), Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, context.getString(R.string.no_app_for_link), Toast.LENGTH_SHORT).show()
    }
}

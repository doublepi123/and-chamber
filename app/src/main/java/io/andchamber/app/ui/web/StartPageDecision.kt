package io.andchamber.app.ui.web

import io.andchamber.app.ui.withRememberedPagePath
import io.andchamber.app.ui.withSessionPath

/** Where a pending start page came from, because the two are joined onto the server differently. */
internal enum class StartPageSource {
    /** A notification link (`/?session=…`): joined onto the instance's base URL. */
    Notification,

    /** The instance's saved page (`/openchamber/?session=…`): restored on the origin, path intact. */
    Remembered,
}

/**
 * A notification path wins; gateway preparation must not consume either pending path. [source] keeps
 * the join rule with the path, so a saved page can't be mistaken for a notification link.
 */
internal data class StartPageDecision(
    val path: String?,
    val source: StartPageSource?,
    val consume: Boolean,
)

internal fun startPageDecision(
    deepLinkPath: String?,
    resumePath: String?,
    needsGatewayLogin: Boolean,
): StartPageDecision {
    val source =
        when {
            deepLinkPath != null -> StartPageSource.Notification
            resumePath != null -> StartPageSource.Remembered
            else -> null
        }
    val path = deepLinkPath ?: resumePath
    return StartPageDecision(path, source, consume = path != null && !needsGatewayLogin)
}

/** The URL to load for a first page: [startUrl] itself when nothing is pending. */
internal data class StartPage(val url: String, val consume: Boolean)

/**
 * Resolves the first page of an instance. A notification path is a route relative to the base URL
 * ([withSessionPath]); a remembered page is already an absolute same-origin path and is restored on
 * the origin alone ([withRememberedPagePath]), so a base path such as `/openchamber` is not repeated.
 */
internal fun startPageUrl(
    startUrl: String,
    baseUrl: String,
    deepLinkPath: String?,
    resumePath: String?,
    needsGatewayLogin: Boolean,
): StartPage {
    val decision = startPageDecision(deepLinkPath, resumePath, needsGatewayLogin)
    val page =
        when (decision.source) {
            StartPageSource.Notification -> decision.path?.let { withSessionPath(startUrl, it) }
            StartPageSource.Remembered -> decision.path?.let { withRememberedPagePath(baseUrl, it) }
            null -> null
        }
    return StartPage(page ?: startUrl, decision.consume)
}

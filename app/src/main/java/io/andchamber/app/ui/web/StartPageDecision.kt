package io.andchamber.app.ui.web

/** A notification path wins; gateway preparation must not consume either pending path. */
internal data class StartPageDecision(val path: String?, val consume: Boolean)

internal fun startPageDecision(
    deepLinkPath: String?,
    resumePath: String?,
    needsGatewayLogin: Boolean,
): StartPageDecision {
    val path = deepLinkPath ?: resumePath
    return StartPageDecision(path, consume = path != null && !needsGatewayLogin)
}

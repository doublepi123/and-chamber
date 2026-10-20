package io.andchamber.app.ui.web

import io.andchamber.app.runtime.ServerTarget

/**
 * Hides OpenChamber's own update prompt on the on-device server. OpenChamber polls
 * `GET /api/openchamber/update-check` and offers `openchamber update`, which runs `npm i -g` in place and
 * would break the PRoot layout. AndChamber owns updates (the Home card), so this one request is answered
 * locally with "no update". There is no server switch for it.
 *
 * Only the local target is affected. Remote servers keep their own behaviour.
 */
internal object LocalUpdateGate {
    /** The path OpenChamber checks for a new release. Matched exactly; a query string is allowed. */
    const val UPDATE_CHECK_PATH = "/api/openchamber/update-check"

    /**
     * Answer for the intercepted request. `available:false` is what the web client expects to mean "no
     * update". The client accepts this shape, and a 200 avoids the error toast of its manual check.
     */
    const val UPDATE_CHECK_BODY = "{\"available\":false,\"currentVersion\":\"unknown\"}"

    /**
     * True only for a GET to exactly [UPDATE_CHECK_PATH] on the binding's own [origin], made by the local
     * target. [url] is the full request URL; its query string is ignored. Path prefixes, other origins,
     * other methods and remote targets all return false.
     */
    fun interceptsLocalUpdateCheck(
        targetId: String,
        origin: String,
        method: String,
        url: String,
    ): Boolean {
        if (targetId != ServerTarget.LOCAL_ID) return false
        if (method != "GET") return false
        if (origin.isEmpty() || originOf(url) != origin) return false
        return pathOf(url) == UPDATE_CHECK_PATH
    }

    /** The path of [url] without its query or fragment; "" when it is not an absolute URL. */
    internal fun pathOf(url: String): String {
        if (!url.contains("://")) return ""
        val afterScheme = url.substringAfter("://")
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return ""
        return afterScheme.substring(slash).substringBefore('?').substringBefore('#')
    }
}

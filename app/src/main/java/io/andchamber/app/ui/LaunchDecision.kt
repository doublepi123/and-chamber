package io.andchamber.app.ui

import io.andchamber.app.data.canonicalWebOrigin
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.ServerTarget

/*
 * Pure launch rules, kept free of Android and Compose so they are unit-tested directly.
 */

/** Where a launch takes the user, once the runtime has looked at the disk. */
internal enum class LaunchTarget {
    /** The runtime is still detecting; decide later. */
    Wait,

    /** Show Home: nothing to open yet, or something needs the user's attention there. */
    Home,

    /** The local server is up or coming up: open it. */
    OpenLocal,

    /** Installed but stopped: start it, then open it. */
    StartAndOpenLocal,
}

internal fun launchTarget(state: RuntimeState): LaunchTarget =
    when (state) {
        RuntimeState.Detecting -> LaunchTarget.Wait
        is RuntimeState.Running, is RuntimeState.Starting -> LaunchTarget.OpenLocal
        RuntimeState.Stopped -> LaunchTarget.StartAndOpenLocal
        // Setup, failures and unsupported devices belong on Home, where their actions are; an
        // in-flight stop was asked for by the user (or the notification), so it isn't undone.
        RuntimeState.NotInstalled,
        RuntimeState.UnsupportedAbi,
        is RuntimeState.Installing,
        is RuntimeState.Failed,
        is RuntimeState.Stopping,
        -> LaunchTarget.Home
    }

/** How the app's first frames look on a launch. */
internal enum class LaunchStage {
    /** Launch cover up, waiting for detection; the screens aren't composed yet. */
    Waiting,

    /** Launch cover up while the app switches to the local server underneath it. */
    Opening,

    /** No cover; screens as usual. */
    Done,
}

/**
 * The stage for the first frame. A launch that will end on Home starts without the cover, so
 * nothing extra is ever shown; one that may open the local server covers Home from frame one.
 */
internal fun initialLaunchStage(
    launchPending: Boolean,
    autoOpenLocal: Boolean,
    state: RuntimeState,
    remoteResume: Boolean = false,
): LaunchStage {
    if (!launchPending || !autoOpenLocal) return LaunchStage.Done
    // A remote server doesn't depend on the runtime, so its resume is decided already.
    if (remoteResume) return LaunchStage.Opening
    return when (launchTarget(state)) {
        LaunchTarget.Wait -> LaunchStage.Waiting
        LaunchTarget.Home -> LaunchStage.Done
        LaunchTarget.OpenLocal, LaunchTarget.StartAndOpenLocal -> LaunchStage.Opening
    }
}

/** Where a launch that remembers the last used instance goes. */
internal sealed interface ResumeTarget {
    /** The local OpenChamber: the existing local launch logic (with its state rules) applies. */
    data object Local : ResumeTarget

    /** This remote server, opened directly; the local runtime is not started for it. */
    data class Remote(val targetId: String) : ResumeTarget
}

/**
 * The instance a launch reopens. A pending notification link always means the local server.
 * [rememberedId] is what the user last had open; a remote id that is no longer saved falls back
 * to [ResumeTarget.Local] (the caller clears the stale record on its way).
 */
internal fun resumeTarget(
    rememberedId: String?,
    remoteIds: Set<String>,
    linkPending: Boolean,
): ResumeTarget =
    when {
        linkPending -> ResumeTarget.Local
        rememberedId == null -> ResumeTarget.Local
        rememberedId == ServerTarget.LOCAL_ID -> ResumeTarget.Local
        rememberedId in remoteIds -> ResumeTarget.Remote(rememberedId)
        else -> ResumeTarget.Local
    }

/** Longest page address the app remembers; longer ones are dropped rather than truncated. */
internal const val MAX_PAGE_PATH_CHARS = 512

/** Cloudflare Access' own URLs are login flow, never a page to come back to. */
private const val GATEWAY_PATH_PREFIX = "/cdn-cgi/"

/**
 * The path+query of [pageUrl] (`/?session=…`), or null when it must not be remembered: different
 * origin than [baseUrl] (the same canonical origin check profiles and the WebView use), anything
 * under `/cdn-cgi/`, a fragment (dropped, so tokens in one never reach disk), or over the length
 * cap. The result always starts with `/`.
 */
internal fun sanitizedPagePath(
    pageUrl: String,
    baseUrl: String,
): String? {
    val pageOrigin = canonicalWebOrigin(pageUrl) ?: return null
    val baseOrigin = canonicalWebOrigin(baseUrl) ?: return null
    if (pageOrigin != baseOrigin) return null
    // canonicalWebOrigin rejected userinfo and non-http(s) URLs, so what follows "://" is the
    // plain authority.
    val schemeEnd = pageUrl.indexOf("://")
    var rest = pageUrl.substring(schemeEnd + 3).substringAfter('/', "")
    val fragment = rest.indexOf('#')
    if (fragment >= 0) rest = rest.substring(0, fragment)
    val path = "/$rest"
    if (path.startsWith(GATEWAY_PATH_PREFIX, ignoreCase = true)) return null
    if (path.length > MAX_PAGE_PATH_CHARS) return null
    return path
}

/** [path] (`/?session=…`) on the server at [baseUrl], e.g. `http://127.0.0.1:3000/?session=…`. */
internal fun withSessionPath(
    baseUrl: String,
    path: String,
): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

/**
 * The origin (`scheme://authority`) of [baseUrl] exactly as written, or null when it isn't a plain,
 * credential-free http(s) address. Validated by the same canonical origin profiles and the WebView
 * use, so a userinfo address can never become a remembered-page target.
 */
internal fun originAddress(baseUrl: String): String? {
    if (canonicalWebOrigin(baseUrl) == null) return null
    val schemeEnd = baseUrl.indexOf("://")
    if (schemeEnd <= 0) return null
    val authority = baseUrl.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' }
    if (authority.isEmpty()) return null
    return baseUrl.substring(0, schemeEnd + 3) + authority
}

/**
 * A remembered page [path] (`/openchamber/?session=…`, already a same-origin path+query) restored on
 * the origin of [baseUrl]. The saved path is absolute, so it already contains the instance's base
 * path: joining it to the full base URL (as a notification link is joined) would repeat that path.
 * The path is appended untouched, so the characters saved on disk keep their original escaping.
 * Null when [baseUrl] has no usable origin or [path] isn't absolute.
 */
internal fun withRememberedPagePath(
    baseUrl: String,
    path: String,
): String? {
    if (!path.startsWith("/")) return null
    val origin = originAddress(baseUrl) ?: return null
    return origin + path
}

/**
 * A start that was just requested reads as Starting until the runtime reports back, so screens
 * don't show "Stopped" (or the old failure) for the moment the service takes to pick it up.
 * [pendingStartFrom] is the state the start was requested from, or null when none is pending.
 */
internal fun shownLocalState(
    state: RuntimeState,
    pendingStartFrom: RuntimeState?,
): RuntimeState {
    val waitingForRuntime =
        pendingStartFrom != null &&
            state == pendingStartFrom &&
            (state is RuntimeState.Stopped || state is RuntimeState.Failed)
    return if (waitingForRuntime) RuntimeState.Starting(detail = null) else state
}

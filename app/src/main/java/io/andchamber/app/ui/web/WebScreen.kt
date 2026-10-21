package io.andchamber.app.ui.web

import io.andchamber.app.i18n.UserMessages
import io.andchamber.app.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.andchamber.app.data.WebIsolationFailure
import io.andchamber.app.data.WebSessionProfiles
import io.andchamber.app.runtime.FailurePhase
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionAuthenticator
import io.andchamber.app.runtime.WebSessionResult
import io.andchamber.app.ui.displayAddress
import io.andchamber.app.ui.findActivity
import io.andchamber.app.ui.rememberLastNonNull
import io.andchamber.app.ui.withSessionPath
import kotlinx.coroutines.CancellationException
import java.util.Locale

private sealed interface SessionPhase {
    data object Connecting : SessionPhase

    data object Loaded : SessionPhase

    data class Unreachable(val reason: String) : SessionPhase
}

/** What covers the page, if anything. */
private sealed interface WebOverlay {
    data object Missing : WebOverlay

    data class Local(val state: RuntimeState) : WebOverlay

    data class Unreachable(val reason: String) : WebOverlay

    data object Connecting : WebOverlay

    data class PageError(val message: String) : WebOverlay

    /** The page couldn't get its own WebView profile: nothing is loaded and no password is sent. */
    data class Isolation(val failure: WebIsolationFailure) : WebOverlay
}

/** Overlays of the same kind share a key, so live progress updates don't restart the crossfade. */
private fun WebOverlay?.kind(): Any? =
    when (this) {
        is WebOverlay.Local -> "local:" + state::class.simpleName
        null -> null
        else -> this::class
    }

/**
 * Full-screen OpenChamber for one server.
 *
 * [target] is null while the server can't be shown: the local runtime isn't running, or a saved
 * server was removed. [sessionKey] identifies one server session; when it changes (e.g. the local
 * runtime restarted) the session is prepared again and the page reloaded.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WebScreen(
    targetId: String,
    target: ServerTarget?,
    localState: RuntimeState?,
    checking: Boolean,
    sessionKey: String?,
    auth: WebSessionAuthenticator,
    /** Per-server WebView profiles; null only in previews, where no WebView is created. */
    profiles: WebSessionProfiles?,
    pool: WebViewPool,
    onExit: () -> Unit,
    onStartLocal: () -> Unit,
    onSystemBarColor: (Color?) -> Unit,
    /** A path such as `/?session=…` to show instead of the start page; reported back once used. */
    deepLinkPath: String? = null,
    onDeepLinkUsed: () -> Unit = {},
    /** The remembered page of this instance, for the first load only; a deep link wins over it. */
    resumePath: String? = null,
    /** Trusted main-frame locations of this instance's page, so the app can remember the page. */
    onPageLocation: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val darkTheme = isSystemInDarkTheme()
    val themeBackground = MaterialTheme.colorScheme.background
    val isLocal = targetId == ServerTarget.LOCAL_ID
    val shownTarget = rememberLastNonNull(target)

    // The page gets its own WebView profile before anything else: no profile, no sign-in, no load.
    // Keyed on the last known address, so a stopped local server keeps its page under the panel.
    var bindAttempt by remember(targetId) { mutableIntStateOf(0) }
    val bound =
        remember(targetId, shownTarget?.baseUrl, bindAttempt) {
            bindWeb(if (inspection) null else profiles, shownTarget) { binding ->
                pool.obtain(context, binding, darkTheme, themeBackground.toArgb())
            }
        }
    val web: ChamberWebView? = bound.web
    // The page outlives theme flips (uiMode is handled in place), so restyle it when the theme changes.
    val themeBackgroundArgb = themeBackground.toArgb()
    LaunchedEffect(web, darkTheme, themeBackgroundArgb) { web?.applyTheme(darkTheme, themeBackgroundArgb) }
    // Read as state, so a renderer that can't be bound again after a crash shows up here as well.
    val isolation: WebIsolationFailure? = if (web != null) web.isolationFailure else bound.failure

    var phase by remember(web) {
        mutableStateOf<SessionPhase>(
            if (web != null && sessionKey != null && web.sessionKey == sessionKey) {
                SessionPhase.Loaded
            } else {
                SessionPhase.Connecting
            },
        )
    }
    var attempt by remember(web) { mutableIntStateOf(0) }
    var gatewayPreparations by remember(web) { mutableIntStateOf(0) }
    val gatewayBudget = remember(web, sessionKey) { GatewayHandoffBudget() }
    var handoffTicket by remember(web, sessionKey) { mutableLongStateOf(0L) }
    var gatewayPending by remember(web, sessionKey) { mutableStateOf(web?.gatewayLoginMode == true) }
    DisposableEffect(web, sessionKey, target?.id, target?.baseUrl) {
        val complete: (Long) -> Unit = complete@ { ticket ->
            if (web?.isGatewayHandoffCurrent(ticket) != true) return@complete
            when (gatewayBudget.claim(ticket)) {
                GatewayHandoffBudget.Claim.Start -> {
                    gatewayPreparations = gatewayBudget.used
                    handoffTicket = ticket
                    gatewayPending = true
                    web?.invalidateSession()
                    attempt++
                }
                GatewayHandoffBudget.Claim.Duplicate -> Unit
                GatewayHandoffBudget.Claim.Rejected -> {
                    web?.cancelGatewayHandoff(ticket)
                    phase = SessionPhase.Unreachable(context.getString(R.string.gateway_handoff_failed))
                }
            }
        }
        web?.onGatewayCompleted = complete
        if (web != null && web.gatewayHandoffTicket > 0) {
            if (web.isGatewayHandoffCurrent(web.gatewayHandoffTicket)) complete(web.gatewayHandoffTicket)
            else web.beginGatewayHandoff()
        }
        onDispose {
            if (web?.onGatewayCompleted === complete) {
                web.onGatewayCompleted = null
                web.cancelGatewayHandoff(web.gatewayHandoffTicket)
            }
        }
    }
    // Read when the page is actually loaded, which can be long after this screen appeared
    // (e.g. the local server was still starting).
    val latestDeepLink by rememberUpdatedState(deepLinkPath)
    val latestResumePath by rememberUpdatedState(resumePath)
    val latestDeepLinkUsed by rememberUpdatedState(onDeepLinkUsed)
    val latestOnPageLocation by rememberUpdatedState(onPageLocation)
    DisposableEffect(web) {
        val report = { url: String -> latestOnPageLocation(url) }
        web?.onPageLocation = report
        onDispose {
            if (web?.onPageLocation === report) web?.onPageLocation = null
        }
    }

    LaunchedEffect(web, sessionKey, attempt) {
        val view = web ?: return@LaunchedEffect
        val current = target ?: return@LaunchedEffect
        val key = sessionKey ?: return@LaunchedEffect
        // Only a WebView bound to this server's own profile may sign in; otherwise nothing is sent
        // and the isolation panel stays up.
        if (view.isolationFailure != null || view.view == null) return@LaunchedEffect
        val ticket = handoffTicket
        // The newly attached callback will claim an existing/quarantined pooled-view ticket first.
        if (ticket == 0L && view.gatewayHandoffTicket > 0) return@LaunchedEffect
        if (ticket == 0L && view.sessionKey == key && view.gatewayHandoffTicket == 0L) {
            phase = SessionPhase.Loaded
            return@LaunchedEffect
        }
        phase = SessionPhase.Connecting
        if (ticket > 0 && !view.awaitGatewayHandoff(ticket)) {
            phase = SessionPhase.Unreachable(context.getString(R.string.gateway_handoff_failed))
            return@LaunchedEffect
        }
        // A replacement view may have a new cookie capability: capture it only after quiescence.
        val cookies = view.sessionCookies ?: return@LaunchedEffect
        val result =
            try {
                auth.prepare(current, cookies)
            } catch (error: CancellationException) {
                if (ticket > 0) view.cancelGatewayHandoff(ticket)
                throw error
            } catch (error: Exception) {
                WebSessionResult.Unreachable(error.message ?: context.getString(R.string.connection_failed))
            }
        if (ticket > 0 && (!view.isGatewayHandoffCurrent(ticket) || view.sessionCookies !== cookies)) {
            view.cancelGatewayHandoff(ticket)
            phase = SessionPhase.Unreachable(context.getString(R.string.gateway_handoff_failed))
            return@LaunchedEffect
        }
        phase =
            when (result) {
                // NeedsLogin: OpenChamber shows its own sign-in page at the same address.
                is WebSessionResult.Ready, is WebSessionResult.NeedsLogin, is WebSessionResult.NeedsGatewayLogin -> {
                    val gateway = result is WebSessionResult.NeedsGatewayLogin
                    gatewayPending = gateway
                    val start =
                        when (result) {
                            is WebSessionResult.Ready -> result.url
                            is WebSessionResult.NeedsLogin -> result.url
                            is WebSessionResult.NeedsGatewayLogin -> result.url
                            is WebSessionResult.Unreachable -> error("unreachable")
                        }
                    // A completed gateway round re-arms the bounded re-prepare for a later expiry.
                    if (!gateway) {
                        gatewayPreparations = 0
                        gatewayBudget.reset()
                    }
                    // A session picked from a notification, or the remembered page of this instance,
                    // is the first page, not a second load. The notification link wins over the
                    // remembered page. Keep it pending through gateway retries so the final
                    // authenticated prepare still loads the requested page.
                    val startPage = startPageDecision(latestDeepLink, latestResumePath, gateway)
                    view.load(
                        startPage.path?.let { withSessionPath(start, it) } ?: start,
                        key,
                        gatewayLogin = gateway && gatewayPreparations < 2,
                    )
                    if (ticket > 0) handoffTicket = 0L
                    if (startPage.consume) latestDeepLinkUsed()
                    SessionPhase.Loaded
                }
                is WebSessionResult.Unreachable -> {
                    if (ticket > 0) view.cancelGatewayHandoff(ticket)
                    SessionPhase.Unreachable(result.reason)
                }
            }
    }

    // A session picked while this server's page is already loaded: open it in place. The
    // remembered page is not applied here — the pooled page already is that page.
    LaunchedEffect(web, deepLinkPath, phase, sessionKey, gatewayPending) {
        val view = web ?: return@LaunchedEffect
        val link = deepLinkPath ?: return@LaunchedEffect
        val base = target?.baseUrl ?: return@LaunchedEffect
        // A retained notification path must not be consumed by the already-loaded shortcut either.
        if (gatewayPending || view.gatewayHandoffTicket != 0L) return@LaunchedEffect
        if (phase != SessionPhase.Loaded || sessionKey == null || view.sessionKey != sessionKey) return@LaunchedEffect
        if (view.navigateWithinSession(withSessionPath(base, link))) latestDeepLinkUsed()
    }

    val retry: () -> Unit = {
        gatewayBudget.manualRetry()
        gatewayPreparations = 0
        if (web != null && web.gatewayHandoffTicket > 0) {
            web.cancelGatewayHandoff(web.gatewayHandoffTicket)
            web.beginGatewayHandoff()
        } else {
            web?.invalidateSession()
            attempt += 1
        }
    }
    val reload: () -> Unit = {
        if (web != null && web.gatewayHandoffTicket > 0) retry() else web?.reload()
    }
    // A WebView whose profile failed is never bound again: drop it and bind a fresh one.
    val retryIsolation = {
        if (web != null) pool.discard(targetId)
        bindAttempt += 1
    }
    // Remote only: the saved address alone (no password, cookies or token), and only a valid one.
    val browserAddress = (shownTarget as? ServerTarget.Remote)?.baseUrl?.let(::browserAddressFor)

    WebLifecycleEffect(web)
    WebHostEffect(web)

    val pageError = web?.pageError
    val overlay: WebOverlay? =
        when {
            !isLocal && target == null -> WebOverlay.Missing
            isLocal && checking && localState == RuntimeState.NotInstalled -> WebOverlay.Connecting
            isLocal && localState !is RuntimeState.Running -> WebOverlay.Local(localState ?: RuntimeState.NotInstalled)
            isolation != null -> WebOverlay.Isolation(isolation)
            phase is SessionPhase.Unreachable -> WebOverlay.Unreachable((phase as SessionPhase.Unreachable).reason)
            phase == SessionPhase.Connecting && web?.gatewayLoginMode != true -> WebOverlay.Connecting
            pageError != null -> WebOverlay.PageError(pageError)
            web != null && !web.committed -> WebOverlay.Connecting
            inspection -> WebOverlay.Connecting
            else -> null
        }
    // OpenChamber's own notifications are left to the page only while the local page is on screen.
    LocalPageVisibilityEffect(isLocal = isLocal && !inspection, overlayShown = overlay != null)

    BackHandler {
        when {
            overlay == null && web != null && web.canGoBack() -> web.goBack()
            // The local OpenChamber is the app's main screen: leaving it with Back behaves like
            // leaving any app (to the launcher), not a step back into the control room. The edge
            // handle still offers Home. Panels with an error or progress keep the old way out.
            isLocal && overlay == null -> context.findActivity()?.moveTaskToBack(true) ?: onExit()
            else -> onExit()
        }
    }

    val pageColor = web?.pageColor?.let { Color(OPAQUE or it) } ?: themeBackground
    val barColor = if (overlay == null) pageColor else themeBackground
    val background by animateColorAsState(barColor, animationSpec = tween(250), label = "webBackground")
    // Status bar icons follow the page's own background, which can differ from the app theme.
    LaunchedEffect(barColor) { onSystemBarColor(barColor) }
    DisposableEffect(Unit) { onDispose { onSystemBarColor(null) } }

    Box(modifier = Modifier.fillMaxSize().background(background)) {
        if (web != null) {
            // The page is kept clear of the status bar, navigation bar, cutouts and keyboard; those
            // areas are painted in the page's own background color. The IME target (not the
            // animated value) is used so the page is resized once per keyboard show/hide.
            val pageInsets =
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .union(WindowInsets.imeAnimationTarget)
            Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(pageInsets)) {
                key(web, web.generation) {
                    // Only a WebView bound to this server's profile is ever attached; after a failed
                    // rebind there is none, and the isolation panel covers the screen instead.
                    if (web.view != null) {
                        AndroidView(
                            // Detached from any earlier holder only here, never during recomposition.
                            factory = { viewContext -> web.attachableView() ?: View(viewContext) },
                            modifier = Modifier.fillMaxSize().clipToBounds(),
                            // The WebView outlives this screen in the pool; never destroy it here.
                            onRelease = {},
                        )
                    }
                }

                AnimatedVisibility(
                    visible = overlay == null && web.progress < 100,
                    enter = fadeIn(),
                    exit = fadeOut(tween(400)),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    LinearProgressIndicator(
                        progress = { web.progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        trackColor = Color.Transparent,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }

                if (overlay == null && shownTarget != null) {
                    EdgeHandle(
                        fraction = pool.handleFraction,
                        onFractionChange = { pool.handleFraction = it },
                        title = shownTarget.displayName,
                        address = displayAddress(shownTarget.baseUrl),
                        onHome = onExit,
                        onReload = reload,
                        // The local server's password is internal, so a browser couldn't sign in.
                        onOpenInBrowser = if (shownTarget is ServerTarget.Remote) web::openInBrowser else null,
                    )
                }
            }
        }

        AnimatedContent(
            targetState = overlay,
            contentKey = { it.kind() },
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
            label = "webOverlay",
        ) { shown ->
            if (shown != null) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .blockTouches(),
                ) {
                    OverlayContent(
                        overlay = shown,
                        target = shownTarget,
                        isLocal = isLocal,
                        onExit = onExit,
                        onRetry = retry,
                        onReload = reload,
                        onStartLocal = onStartLocal,
                        onRetryIsolation = retryIsolation,
                        onOpenInBrowser = browserAddress?.let { address -> { openAddressInBrowser(context, address) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayContent(
    overlay: WebOverlay,
    target: ServerTarget?,
    isLocal: Boolean,
    onExit: () -> Unit,
    onRetry: () -> Unit,
    onReload: () -> Unit,
    onStartLocal: () -> Unit,
    onRetryIsolation: () -> Unit,
    /** Remote only: opens the saved address in the browser, without any credentials. */
    onOpenInBrowser: (() -> Unit)?,
) {
    val home = PanelAction(stringResource(R.string.home), onExit)
    when (overlay) {
        is WebOverlay.Isolation ->
            IsolationPanel(
                failure = overlay.failure,
                isLocal = isLocal,
                onRetry = onRetryIsolation,
                onHome = onExit,
                onOpenInBrowser = if (isLocal) null else onOpenInBrowser,
            )
        WebOverlay.Missing ->
            StatusPanel(
                icon = Icons.Rounded.LinkOff,
                title = stringResource(R.string.server_removed_title),
                primary = home,
            )
        is WebOverlay.Local -> LocalServerPanel(state = overlay.state, onStart = onStartLocal, onHome = onExit)
        is WebOverlay.Unreachable ->
            StatusPanel(
                icon = Icons.Rounded.CloudOff,
                title = if (isLocal) stringResource(R.string.cant_reach_local) else stringResource(R.string.cant_reach, target?.displayName ?: stringResource(R.string.server_fallback_name)),
                body = if (isLocal) stringResource(R.string.unreachable_hint_local) else stringResource(R.string.unreachable_hint_remote),
                detail = overlay.reason,
                primary = PanelAction(stringResource(R.string.try_again), onRetry),
                secondary = PanelAction(stringResource(R.string.back), onExit),
            )
        WebOverlay.Connecting ->
            ConnectingPanel(
                name = if (isLocal) null else target?.displayName,
                address = target?.baseUrl?.let(::displayAddress),
                onBack = onExit,
            )
        is WebOverlay.PageError ->
            StatusPanel(
                icon = Icons.Rounded.ErrorOutline,
                title = stringResource(R.string.page_failed_title),
                detail = overlay.message,
                primary = PanelAction(stringResource(R.string.reload), onReload),
                secondary = home,
            )
    }
}

/** Covers the page when the local runtime isn't running. */
@Composable
internal fun LocalServerPanel(
    state: RuntimeState,
    onStart: () -> Unit,
    onHome: () -> Unit,
) {
    val home = PanelAction(stringResource(R.string.home), onHome)
    when (state) {
        is RuntimeState.Starting ->
            StatusPanel(
                title = stringResource(R.string.starting_title),
                detail = state.detail,
                progress = Float.NaN,
                breathingMark = true,
                tertiary = home,
            )
        is RuntimeState.Stopping ->
            StatusPanel(
                title = stringResource(R.string.stopping_title),
                detail = state.detail,
                progress = Float.NaN,
                tertiary = home,
            )
        RuntimeState.Stopped ->
            StatusPanel(
                icon = Icons.Rounded.PowerSettingsNew,
                title = stringResource(R.string.local_stopped_title),
                body = stringResource(R.string.local_stopped_body),
                primary = PanelAction(stringResource(R.string.start_again), onStart),
                secondary = home,
            )
        is RuntimeState.Failed -> {
            // Say what actually went wrong: a start that never came up reads differently from a crash.
            val title =
                when (state.phase) {
                    FailurePhase.Install -> stringResource(R.string.local_setup_failed_title)
                    FailurePhase.Start -> stringResource(R.string.local_start_failed_title)
                    FailurePhase.Runtime -> stringResource(R.string.local_failed_title)
                }
            StatusPanel(
                icon = Icons.Rounded.ErrorOutline,
                iconTint = MaterialTheme.colorScheme.error,
                title = title,
                detail =
                    state.reason.takeUnless {
                        it.equals(title, ignoreCase = true) || it.equals(stringResource(R.string.local_failed_title), ignoreCase = true)
                    },
                body = if (state.recoverable) null else stringResource(R.string.repair_from_home),
                primary = if (state.recoverable) PanelAction(stringResource(R.string.try_again), onStart) else home,
                secondary = if (state.recoverable) home else null,
            )
        }
        is RuntimeState.Installing ->
            StatusPanel(
                title = stringResource(if (state.updating) R.string.updating_title else R.string.setting_up_title),
                body = stringResource(UserMessages.stepLabel(state.step)),
                progress = state.progress ?: Float.NaN,
                tertiary = home,
            )
        RuntimeState.Detecting ->
            StatusPanel(
                title = stringResource(R.string.starting_title),
                progress = Float.NaN,
                breathingMark = true,
                tertiary = home,
            )
        RuntimeState.NotInstalled, RuntimeState.UnsupportedAbi ->
            StatusPanel(
                icon = Icons.Rounded.PowerSettingsNew,
                title = stringResource(R.string.local_not_set_up_title),
                body = stringResource(R.string.local_not_set_up_body),
                primary = home,
            )
        is RuntimeState.Running -> Unit
    }
}

/** Resumes and pauses the WebView with the screen. */
@Composable
private fun WebLifecycleEffect(web: ChamberWebView?) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(web, lifecycleOwner) {
        if (web == null) return@DisposableEffect onDispose { }
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> web.onResume()
                    Lifecycle.Event.ON_PAUSE -> web.onPause()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private class PendingRequests {
    var file: ValueCallback<Array<Uri>>? = null
    var microphone: ((Boolean) -> Unit)? = null
}

/** Lends the WebView the file picker and the microphone permission while this screen is shown. */
@Composable
private fun WebHostEffect(web: ChamberWebView?) {
    if (web == null) return
    val context = LocalContext.current
    val pending = remember { PendingRequests() }
    val fileLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pending.file
            pending.file = null
            callback?.onReceiveValue(parseChooserResult(result.resultCode, result.data))
        }
    val micLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val callback = pending.microphone
            pending.microphone = null
            callback?.invoke(granted)
        }

    DisposableEffect(web, context) {
        val host =
            object : WebHost {
                override fun showFileChooser(
                    callback: ValueCallback<Array<Uri>>,
                    params: WebChromeClient.FileChooserParams,
                ): Boolean {
                    pending.file?.onReceiveValue(null)
                    pending.file = callback
                    return try {
                        fileLauncher.launch(chooserIntent(params))
                        true
                    } catch (_: ActivityNotFoundException) {
                        pending.file = null
                        false
                    }
                }

                override fun requestMicrophone(onResult: (Boolean) -> Unit) {
                    when {
                        // Without the permission in the manifest there is nothing to ask for.
                        !context.declaresPermission(Manifest.permission.RECORD_AUDIO) -> onResult(false)
                        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED -> onResult(true)
                        else -> {
                            pending.microphone?.invoke(false)
                            pending.microphone = onResult
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            }
        web.host = host
        onDispose {
            if (web.host === host) web.host = null
            // A file chooser callback must always be answered, or the page can't open another one.
            pending.file?.onReceiveValue(null)
            pending.file = null
            pending.microphone?.invoke(false)
            pending.microphone = null
        }
    }
}

private fun chooserIntent(params: WebChromeClient.FileChooserParams): Intent {
    val mimeTypes =
        params.acceptTypes.orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .map { type ->
                if (type.startsWith('.')) {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.drop(1)) ?: "*/*"
                } else {
                    type
                }
            }
            .distinct()
    val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
    when {
        mimeTypes.isEmpty() || "*/*" in mimeTypes -> intent.type = "*/*"
        mimeTypes.size == 1 -> intent.type = mimeTypes.first()
        else -> {
            intent.type = "*/*"
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        }
    }
    if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }
    return intent
}

/** Handles single and multiple selection; the platform parser only reads a single result. */
private fun parseChooserResult(
    resultCode: Int,
    data: Intent?,
): Array<Uri>? {
    if (resultCode != Activity.RESULT_OK || data == null) return null
    val clip = data.clipData
    if (clip != null && clip.itemCount > 0) {
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (uris.isNotEmpty()) return uris.toTypedArray()
    }
    return data.data?.let { arrayOf(it) }
}

private fun Context.declaresPermission(permission: String): Boolean =
    runCatching {
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }
        info.requestedPermissions?.contains(permission) == true
    }.getOrDefault(false)

/** Stops touches from reaching the page underneath an overlay. */
private fun Modifier.blockTouches(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent()
        }
    }

private const val OPAQUE = -0x1000000

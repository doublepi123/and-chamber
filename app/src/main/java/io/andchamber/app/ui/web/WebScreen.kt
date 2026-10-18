package io.andchamber.app.ui.web

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import io.andchamber.app.runtime.FailurePhase
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionAuthenticator
import io.andchamber.app.runtime.WebSessionResult
import io.andchamber.app.ui.Strings
import io.andchamber.app.ui.displayAddress
import io.andchamber.app.ui.findActivity
import io.andchamber.app.ui.label
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
    pool: WebViewPool,
    onExit: () -> Unit,
    onStartLocal: () -> Unit,
    onSystemBarColor: (Color?) -> Unit,
    /** A path such as `/?session=…` to show instead of the start page; reported back once used. */
    deepLinkPath: String? = null,
    onDeepLinkUsed: () -> Unit = {},
) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val darkTheme = isSystemInDarkTheme()
    val themeBackground = MaterialTheme.colorScheme.background
    val isLocal = targetId == ServerTarget.LOCAL_ID
    val shownTarget = rememberLastNonNull(target)

    val web: ChamberWebView? =
        remember(targetId) {
            if (inspection) null else pool.obtain(context, targetId, darkTheme, themeBackground.toArgb())
        }

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
    // Read when the page is actually loaded, which can be long after this screen appeared
    // (e.g. the local server was still starting).
    val latestDeepLink by rememberUpdatedState(deepLinkPath)
    val latestDeepLinkUsed by rememberUpdatedState(onDeepLinkUsed)

    LaunchedEffect(web, sessionKey, attempt) {
        val view = web ?: return@LaunchedEffect
        val current = target ?: return@LaunchedEffect
        val key = sessionKey ?: return@LaunchedEffect
        if (view.sessionKey == key) {
            phase = SessionPhase.Loaded
            return@LaunchedEffect
        }
        phase = SessionPhase.Connecting
        val result =
            try {
                auth.prepare(current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                WebSessionResult.Unreachable(error.message ?: Strings.connectionFailed)
            }
        phase =
            when (result) {
                // NeedsLogin: OpenChamber shows its own sign-in page at the same address.
                is WebSessionResult.Ready, is WebSessionResult.NeedsLogin -> {
                    val start = if (result is WebSessionResult.Ready) result.url else (result as WebSessionResult.NeedsLogin).url
                    // A session picked from a notification is the first page, not a second load.
                    val link = latestDeepLink
                    view.load(link?.let { withSessionPath(start, it) } ?: start, key)
                    if (link != null) latestDeepLinkUsed()
                    SessionPhase.Loaded
                }
                is WebSessionResult.Unreachable -> SessionPhase.Unreachable(result.reason)
            }
    }

    // A session picked while this server's page is already loaded: open it in place.
    LaunchedEffect(web, deepLinkPath, phase, sessionKey) {
        val view = web ?: return@LaunchedEffect
        val link = deepLinkPath ?: return@LaunchedEffect
        val base = target?.baseUrl ?: return@LaunchedEffect
        if (phase != SessionPhase.Loaded || sessionKey == null || view.sessionKey != sessionKey) return@LaunchedEffect
        if (view.navigateWithinSession(withSessionPath(base, link))) latestDeepLinkUsed()
    }

    val retry = {
        web?.invalidateSession()
        attempt += 1
    }

    WebLifecycleEffect(web)
    WebHostEffect(web)

    val pageError = web?.pageError
    val overlay: WebOverlay? =
        when {
            !isLocal && target == null -> WebOverlay.Missing
            isLocal && checking && localState == RuntimeState.NotInstalled -> WebOverlay.Connecting
            isLocal && localState !is RuntimeState.Running -> WebOverlay.Local(localState ?: RuntimeState.NotInstalled)
            phase is SessionPhase.Unreachable -> WebOverlay.Unreachable((phase as SessionPhase.Unreachable).reason)
            phase == SessionPhase.Connecting -> WebOverlay.Connecting
            pageError != null -> WebOverlay.PageError(pageError)
            web != null && !web.committed -> WebOverlay.Connecting
            inspection -> WebOverlay.Connecting
            else -> null
        }

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
                    AndroidView(
                        factory = { web.attachableView() },
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                        // The WebView outlives this screen in the pool; never destroy it here.
                        onRelease = {},
                    )
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
                        onReload = { web.reload() },
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
                        onReload = { web?.reload() },
                        onStartLocal = onStartLocal,
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
) {
    val home = PanelAction(Strings.home, onExit)
    when (overlay) {
        WebOverlay.Missing ->
            StatusPanel(
                icon = Icons.Rounded.LinkOff,
                title = Strings.serverRemovedTitle,
                primary = home,
            )
        is WebOverlay.Local -> LocalServerPanel(state = overlay.state, onStart = onStartLocal, onHome = onExit)
        is WebOverlay.Unreachable ->
            StatusPanel(
                icon = Icons.Rounded.CloudOff,
                title = if (isLocal) Strings.cantReachLocal else Strings.cantReach(target?.displayName ?: Strings.serverFallbackName),
                body = if (isLocal) Strings.unreachableHintLocal else Strings.unreachableHintRemote,
                detail = overlay.reason,
                primary = PanelAction(Strings.tryAgain, onRetry),
                secondary = PanelAction(Strings.back, onExit),
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
                title = Strings.pageFailedTitle,
                detail = overlay.message,
                primary = PanelAction(Strings.reload, onReload),
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
    val home = PanelAction(Strings.home, onHome)
    when (state) {
        is RuntimeState.Starting ->
            StatusPanel(
                title = Strings.startingTitle,
                detail = state.detail,
                progress = Float.NaN,
                breathingMark = true,
                tertiary = home,
            )
        is RuntimeState.Stopping ->
            StatusPanel(
                title = Strings.stoppingTitle,
                detail = state.detail,
                progress = Float.NaN,
                tertiary = home,
            )
        RuntimeState.Stopped ->
            StatusPanel(
                icon = Icons.Rounded.PowerSettingsNew,
                title = Strings.localStoppedTitle,
                body = Strings.localStoppedBody,
                primary = PanelAction(Strings.startAgain, onStart),
                secondary = home,
            )
        is RuntimeState.Failed -> {
            // Say what actually went wrong: a start that never came up reads differently from a crash.
            val title =
                when (state.phase) {
                    FailurePhase.Install -> Strings.localSetupFailedTitle
                    FailurePhase.Start -> Strings.localStartFailedTitle
                    FailurePhase.Runtime -> Strings.localFailedTitle
                }
            StatusPanel(
                icon = Icons.Rounded.ErrorOutline,
                iconTint = MaterialTheme.colorScheme.error,
                title = title,
                detail =
                    state.reason.takeUnless {
                        it.equals(title, ignoreCase = true) || it.equals(Strings.localFailedTitle, ignoreCase = true)
                    },
                body = if (state.recoverable) null else Strings.repairFromHome,
                primary = if (state.recoverable) PanelAction(Strings.tryAgain, onStart) else home,
                secondary = if (state.recoverable) home else null,
            )
        }
        is RuntimeState.Installing ->
            StatusPanel(
                title = Strings.settingUpTitle,
                body = state.step.label(),
                progress = state.progress ?: Float.NaN,
                tertiary = home,
            )
        RuntimeState.Detecting ->
            StatusPanel(
                title = Strings.startingTitle,
                progress = Float.NaN,
                breathingMark = true,
                tertiary = home,
            )
        RuntimeState.NotInstalled, RuntimeState.UnsupportedAbi ->
            StatusPanel(
                icon = Icons.Rounded.PowerSettingsNew,
                title = Strings.localNotSetUpTitle,
                body = Strings.localNotSetUpBody,
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

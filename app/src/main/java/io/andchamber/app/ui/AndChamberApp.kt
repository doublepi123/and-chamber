package io.andchamber.app.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.andchamber.app.runtime.FailurePhase
import io.andchamber.app.runtime.LocalRuntimeController
import io.andchamber.app.runtime.RemoteServerStore
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.SharedStorageController
import io.andchamber.app.runtime.WebSessionAuthenticator
import io.andchamber.app.ui.home.HomeActions
import io.andchamber.app.ui.home.HomeScreen
import io.andchamber.app.ui.theme.AndChamberTheme
import io.andchamber.app.ui.web.WebScreen
import io.andchamber.app.ui.web.WebViewPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

internal object Routes {
    const val HOME = "home"
    const val ARG_TARGET = "targetId"
    const val WEB = "web/{$ARG_TARGET}"

    fun web(targetId: String) = "web/${Uri.encode(targetId)}"
}

/**
 * Local runtime actions as the UI uses them. Asks for the notification permission on Set up and
 * Start (never blocking), and makes "Try again" repeat whatever failed: setup or start.
 */
private class LocalActions(
    private val runtime: LocalRuntimeController,
    private val askForNotifications: () -> Unit,
    private val pool: WebViewPool,
) {
    /** True while the most recent work was setup, so a failure there retries the setup. */
    private var inSetup = false

    fun observe(state: RuntimeState) {
        when (state) {
            is RuntimeState.Installing -> inSetup = true
            is RuntimeState.Failed, RuntimeState.NotInstalled -> Unit
            // Setup finished (the controller starts the server right after it).
            else -> inSetup = false
        }
    }

    fun install() {
        askForNotifications()
        inSetup = true
        runtime.install()
    }

    fun start() {
        askForNotifications()
        inSetup = false
        runtime.start()
    }

    /** Retry redoes exactly what failed: setup failures reinstall, server failures start again. */
    fun retry() {
        val failed = runtime.state.value as? RuntimeState.Failed
        if (failed?.phase == FailurePhase.Install) install() else start()
    }

    /** Start from Stopped, or retry after a failure. */
    fun startOrRetry() {
        if (runtime.state.value is RuntimeState.Failed) retry() else start()
    }

    fun stop() = runtime.stop()

    /**
     * Stop, then start, so new folder settings apply. One request, made right away while the app is
     * in the foreground; the controller runs stop and start as a single queued operation.
     */
    fun restart() {
        if (runtime.state.value !is RuntimeState.Running) return
        askForNotifications()
        runtime.restart()
    }

    fun repair() {
        askForNotifications()
        inSetup = true
        pool.discard(ServerTarget.LOCAL_ID)
        runtime.repair()
    }

    fun uninstall() {
        pool.discard(ServerTarget.LOCAL_ID)
        runtime.uninstall()
    }
}

/**
 * Root of the UI: a control-room home screen and a full-screen OpenChamber web screen per server.
 *
 * Runtime and server flows are collected inside each destination rather than here, so the
 * navigation graph isn't rebuilt every time a log line arrives.
 */
@Composable
fun AndChamberApp(
    runtime: LocalRuntimeController,
    remotes: RemoteServerStore,
    auth: WebSessionAuthenticator,
    storage: SharedStorageController,
) {
    AndChamberTheme {
        val nav = rememberNavController()
        val pool = remember { WebViewPool() }
        DisposableEffect(pool) { onDispose { pool.destroyAll() } }

        val askForNotifications = rememberNotificationPermissionRequest()
        val local = remember(runtime, askForNotifications, pool) { LocalActions(runtime, askForNotifications, pool) }
        LaunchedEffect(local) { runtime.state.collect(local::observe) }

        // Shared-storage calls touch the disk: run them in order, off the main thread.
        val storageJobs = remember { Channel<() -> Unit>(Channel.UNLIMITED) }
        LaunchedEffect(storageJobs) {
            withContext(Dispatchers.IO) {
                for (job in storageJobs) runCatching { job() }
            }
        }
        val refreshStorage: () -> Unit =
            remember(storage) {
                { storageJobs.trySend(storage::refreshPermission) }
            }
        // Picks up access granted (or revoked) in system settings when the user comes back.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshStorage() }
        val requestStorageAccess = rememberStorageAccessRequest(onResult = refreshStorage)

        // Until the runtime has looked at the disk it reports Detecting; no "Set up" offer before then.
        val checking = runtime.state.collectAsState().value == RuntimeState.Detecting

        var webBarColor by remember { mutableStateOf<Color?>(null) }
        val background = MaterialTheme.colorScheme.background
        val currentEntry by nav.currentBackStackEntryAsState()
        val onWeb = currentEntry?.destination?.route == Routes.WEB
        WindowBackgroundEffect(background)
        SystemBarsEffect(darkIcons = ((if (onWeb) webBarColor else null) ?: background).luminance() > 0.5f)

        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize().background(background),
            enterTransition = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.985f) },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.985f) },
        ) {
            composable(Routes.HOME) { entry ->
                val state by runtime.state.collectAsStateWithLifecycle()
                val info by runtime.info.collectAsStateWithLifecycle()
                val logLines by runtime.logTail.collectAsStateWithLifecycle()
                val servers by remotes.servers.collectAsStateWithLifecycle()
                val storageState by storage.state.collectAsStateWithLifecycle()
                val actions =
                    remember(local, remotes, pool, nav, entry, storage, refreshStorage, requestStorageAccess) {
                        HomeActions(
                            onSetUp = local::install,
                            onStart = local::start,
                            onRetry = local::retry,
                            onStop = local::stop,
                            onRepair = local::repair,
                            onUninstall = local::uninstall,
                            onOpenLocal = { nav.openIfResumed(entry, Routes.web(ServerTarget.LOCAL_ID)) },
                            onOpenRemote = { server -> nav.openIfResumed(entry, Routes.web(server.id)) },
                            onSaveRemote = remotes::upsert,
                            onRemoveRemote = { server ->
                                pool.discard(server.id)
                                remotes.remove(server.id)
                            },
                            onSharedStorageChange = { on -> storageJobs.trySend { storage.setEnabled(on) } },
                            onAllowStorageAccess = requestStorageAccess,
                            onCheckStorageAgain = refreshStorage,
                            onRestartLocal = local::restart,
                        )
                    }
                HomeScreen(
                    state = state,
                    checking = checking,
                    info = info,
                    logLines = logLines,
                    servers = servers,
                    actions = actions,
                    storage = storageState,
                )
            }

            composable(
                route = Routes.WEB,
                arguments = listOf(navArgument(Routes.ARG_TARGET) { type = NavType.StringType }),
            ) { entry ->
                val state by runtime.state.collectAsStateWithLifecycle()
                val servers by remotes.servers.collectAsStateWithLifecycle()
                val targetId = entry.arguments?.getString(Routes.ARG_TARGET).orEmpty()
                val isLocal = targetId == ServerTarget.LOCAL_ID
                val running = state as? RuntimeState.Running
                val target: ServerTarget? =
                    if (isLocal) {
                        running?.let { ServerTarget.Local(it.url) }
                    } else {
                        servers.firstOrNull { it.id == targetId }
                    }
                // A new key means a new server session: sign in again and reload the page.
                val sessionKey =
                    when (target) {
                        is ServerTarget.Local -> "local|${running?.sinceEpochMs}|${target.baseUrl}"
                        is ServerTarget.Remote ->
                            "remote|${target.id}|${target.baseUrl}|${target.uiPassword.orEmpty().hashCode()}"
                        null -> null
                    }
                WebScreen(
                    targetId = targetId,
                    target = target,
                    localState = if (isLocal) state else null,
                    checking = checking,
                    sessionKey = sessionKey,
                    auth = auth,
                    pool = pool,
                    onExit = { nav.closeIfResumed(entry) },
                    onStartLocal = local::startOrRetry,
                    onSystemBarColor = { webBarColor = it },
                )
            }
        }
    }
}

/** Ignores repeated taps while a transition is already running. */
private fun NavHostController.openIfResumed(
    from: NavBackStackEntry,
    route: String,
) {
    if (from.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        navigate(route) { launchSingleTop = true }
    }
}

private fun NavHostController.closeIfResumed(entry: NavBackStackEntry) {
    if (!entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    if (!popBackStack(Routes.HOME, inclusive = false)) {
        navigate(Routes.HOME) {
            popUpTo(0)
            launchSingleTop = true
        }
    }
}

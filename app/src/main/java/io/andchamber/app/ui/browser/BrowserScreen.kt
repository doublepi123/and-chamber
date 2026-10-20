package io.andchamber.app.ui.browser

import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.andchamber.app.R
import io.andchamber.app.browser.BrowserController
import io.andchamber.app.browser.BrowserPhase
import io.andchamber.app.browser.BrowserUrlPolicy
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.ui.components.StatusDot
import io.andchamber.app.ui.components.StatusTone
import io.andchamber.app.ui.theme.ChamberTextStyles
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * The independent browser. [controller] is the app's single controller, passed in. This screen does
 * not construct another one.
 *
 * Agent control stays off until the user turns it on, and only while the local OpenChamber is
 * running on this device. Manual browsing does not need that. Leaving the screen, a pause, a
 * stopped renderer, or a changed runtime start time drops control. Coming back does not turn it on.
 *
 * Address checks are a navigation allow-list, not a complete block on everything the page may load.
 */
@Composable
internal fun BrowserScreen(
    controller: BrowserController,
    localState: RuntimeState,
    launchId: Long?,
    remotes: List<ServerTarget.Remote>,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val scope = rememberCoroutineScope()
    val control = controller.state.collectAsStateWithLifecycle().value
    val agentReady = localState is RuntimeState.Running && launchId != null
    val latestLaunch by rememberUpdatedState(launchId)
    val seenLaunch = remember { mutableStateOf(launchId) }

    val forbidden = remember(localState, remotes) {
        val localUrl = (localState as? RuntimeState.Running)?.url
        BrowserDriverLogic.forbiddenOrigins(localUrl, remotes)
    }
    val policy = remember(forbidden) { BrowserUrlPolicy(forbiddenOrigins = forbidden) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var holder by remember { mutableStateOf<BrowserSession?>(null) }
    var failedClosed by remember { mutableStateOf(false) }
    var rendererStopped by remember { mutableStateOf(false) }
    val navTicket = remember { AtomicLong(0) }
    val pageShown = holder != null && !failedClosed && !rendererStopped

    fun takeOverNow() {
        controller.takeOverFromUi()
        holder?.invalidateRefs()
    }

    /** Drops the lease only. Visibility stays with the page that is actually on screen. */
    fun revokeLease() {
        holder?.invalidateRefs()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (controller.state.value.phase != BrowserPhase.DISABLED) controller.disable()
        }
    }

    var openNav by remember { mutableStateOf<suspend (String, Boolean) -> Boolean>({ _, _ -> false }) }
    var probeNav by remember { mutableStateOf<(BrowserSession, String, Long) -> Unit>({ _, _, _ -> }) }

    fun bindSession(created: BrowserSession, replaceDriver: Boolean) {
        created.onFatal = fatal@{
            if (holder !== created) return@fatal
            rendererStopped = true
            holder = null
            controller.setVisible(false)
            controller.detach()
            created.destroy()
        }
        created.onProbe = { viewId, ticket -> probeNav(created, viewId, ticket) }
        created.onMainGet = main@{ url ->
            if (holder !== created) return@main
            val job = scope.launch { openNav(url, true) }
            created.onCancelNavigation = { job.cancel() }
        }
        created.onUnknown = unknown@{
            if (holder !== created) return@unknown
            created.markUnknownNavigation()
            created.onCancelNavigation?.invoke()
            controller.takeOverFromUi()
        }
        val previous = holder
        holder = created
        if (previous != null && previous !== created) previous.destroy()
        if (replaceDriver || !controller.state.value.attached) {
            controller.attach(
                BrowserPageDriver(
                    session = { holder },
                    policy = { policy },
                    generation = { controller.state.value.generation },
                    navigateControlled = { url -> openNav(url, false) },
                ),
            )
        }
        controller.setVisible(lifecycleResumed && holder != null && !failedClosed)
    }

    suspend fun openControlled(raw: String, fromPage: Boolean): Boolean {
        val source = holder
        if (fromPage && source != null && holder !== source) return false
        if (fromPage) takeOverNow()
        val generationAtStart = controller.state.value.generation
        holder?.beginDocument()
        val ticket = navTicket.incrementAndGet()
        val decision = withContext(Dispatchers.IO) { policy.preflight(raw) }
        if (ticket != navTicket.get() || controller.state.value.generation != generationAtStart) return false
        if (fromPage && holder !== source) return false
        if (decision !is io.andchamber.app.browser.UrlDecision.Allow) {
            holder?.fail(context.getString(R.string.browser_address_refused))
            return false
        }
        val created = BrowserSession.tryCreate(context) { policy } ?: run {
            failedClosed = true
            holder?.destroy()
            holder = null
            controller.setVisible(false)
            return false
        }
        if (ticket != navTicket.get() || controller.state.value.generation != generationAtStart) {
            created.destroy()
            return false
        }
        created.beginControlledGet()
        if (!created.installWitness()) {
            created.destroy()
            failedClosed = true
            controller.setVisible(false)
            return false
        }
        bindSession(created, replaceDriver = false)
        created.load(raw, created.epoch())
        return true
    }

    fun probe(page: BrowserSession, viewId: String, ticket: Long) {
        scope.launch {
            if (holder !== page || !page.owns(viewId, ticket)) return@launch
            val raw = page.eval(BrowserDriverLogic.probeScript(), page.epoch()) ?: return@launch
            if (holder !== page || !page.owns(viewId, ticket)) return@launch
            val allowed = page.displayUrl?.let { policy.checkSyntax(it) is io.andchamber.app.browser.UrlDecision.Allow } == true
            when (val accepted = BrowserDriverLogic.acceptProbe(page.acceptedDocumentId, raw, allowed)) {
                is ProbeAcceptance.Accept -> page.acceptReady(ticket, accepted.documentId)
                is ProbeAcceptance.Pending -> Unit
                is ProbeAcceptance.Reject -> page.markUnknownNavigation()
            }
        }
    }

    openNav = { raw, fromPage -> openControlled(raw, fromPage) }
    probeNav = { page, viewId, ticket -> probe(page, viewId, ticket) }

    DisposableEffect(controller, inspection) {
        if (inspection) return@DisposableEffect onDispose {}
        val created = BrowserSession.tryCreate(context) { policy }
        if (created == null) {
            failedClosed = true
            holder = null
        } else {
            bindSession(created, replaceDriver = true)
        }
        onDispose {
            controller.setVisible(false)
            controller.detach()
            holder?.destroy()
            holder = null
        }
    }

    val lifecycle = lifecycleOwner.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    lifecycleResumed = false
                    holder?.view?.onPause()
                    controller.setVisible(false)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { controller.disable() }
                }
                Lifecycle.Event.ON_RESUME -> {
                    lifecycleResumed = true
                    holder?.view?.onResume()
                    // Visible again does not turn control back on.
                    if (holder != null && !failedClosed && !rendererStopped) controller.setVisible(true)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(launchId, localState is RuntimeState.Running) {
        val previous = seenLaunch.value
        seenLaunch.value = launchId
        val running = localState is RuntimeState.Running
        val identityChanged = previous != null && previous != launchId
        val readyDropped = previous != null && launchId == null
        if (identityChanged || readyDropped || !running) revokeLease()
    }

    LaunchedEffect(control.viewInvalidated) {
        if (!control.viewInvalidated || inspection) return@LaunchedEffect
        val dead = holder
        holder = null
        BrowserPageDriver.abandon(dead)
        val replacement = BrowserSession.tryCreate(context) { policy }
        if (replacement == null) {
            failedClosed = true
            controller.detach()
        } else {
            bindSession(replacement, replaceDriver = true)
        }
    }

    BackHandler {
        scope.launch {
            controller.disable()
            onExit()
        }
    }

    val page = holder
    val tone = when {
        failedClosed || page?.pageError != null -> StatusTone.Error
        control.phase == BrowserPhase.ENABLED -> StatusTone.Live
        control.phase == BrowserPhase.TAKEN_OVER -> StatusTone.Busy
        else -> StatusTone.Idle
    }
    val status = when {
        failedClosed -> stringResource(R.string.browser_unavailable)
        page?.pageError != null -> page.pageError.orEmpty()
        control.phase == BrowserPhase.ENABLED -> stringResource(R.string.browser_status_on)
        control.phase == BrowserPhase.TAKEN_OVER -> stringResource(R.string.browser_status_taken)
        else -> stringResource(R.string.browser_status_off)
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            BrowserTopBar(
                url = page?.displayUrl.orEmpty(),
                loading = (page?.progress ?: 100) in 1..99,
                progress = page?.progress ?: 0,
                tone = tone,
                status = status,
                onBack = {
                    scope.launch {
                        controller.disable()
                        onExit()
                    }
                },
                onGo = { raw ->
                    takeOverNow()
                    scope.launch { openControlled(raw, fromPage = false) }
                },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    inspection -> BrowserEmpty(stringResource(R.string.browser_hint))
                    failedClosed || rendererStopped -> BrowserEmpty(stringResource(R.string.browser_unavailable_body))
                    page == null -> BrowserEmpty(stringResource(R.string.browser_hint))
                    else -> {
                        key(page) {
                            BrowserWeb(page = page, onTouchDown = { takeOverNow() })
                        }
                        if (page.displayUrl.isNullOrBlank() && page.pageError == null && !page.committed && page.progress == 0) {
                            BrowserEmpty(stringResource(R.string.browser_hint))
                        }
                    }
                }
            }
            BrowserControlBar(
                phase = control.phase,
                agentReady = agentReady,
                canEnable = agentReady && pageShown && control.visible && page?.unknownNavigation != true,
                busy = control.busy,
                onEnable = {
                    scope.launch {
                        if (latestLaunch == null || localState !is RuntimeState.Running) return@launch
                        if (!pageShown || !lifecycleResumed || holder?.unknownNavigation == true) return@launch
                        controller.setVisible(true)
                        controller.enableFromUi()
                    }
                },
                onTakeOver = { takeOverNow() },
                onClose = { scope.launch { controller.disable() } },
            )
        }
    }
}

@Composable
private fun BrowserTopBar(
    url: String,
    loading: Boolean,
    progress: Int,
    tone: StatusTone,
    status: String,
    onBack: () -> Unit,
    onGo: (String) -> Unit,
) {
    var draft by remember(url) { mutableStateOf(url) }
    var editing by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = if (editing) draft else url,
                    onValueChange = {
                        editing = true
                        draft = it
                    },
                    singleLine = true,
                    textStyle = ChamberTextStyles.code.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        editing = false
                        onGo(normalizeAddress(draft))
                    }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if ((if (editing) draft else url).isBlank()) {
                            Text(
                                stringResource(R.string.browser_address_hint),
                                style = ChamberTextStyles.code,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(tone = tone, size = 6.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(visible = loading, enter = fadeIn(), exit = fadeOut()) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun BrowserWeb(
    page: BrowserSession,
    onTouchDown: () -> Unit,
) {
    val background = MaterialTheme.colorScheme.background.toArgb()
    AndroidView(
        factory = { _ ->
            page.view.apply {
                (parent as? ViewGroup)?.removeView(this)
                setBackgroundColor(background)
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) onTouchDown()
                    false
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            view.setBackgroundColor(background)
            view.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onTouchDown()
                false
            }
        },
        onRelease = { view ->
            view.setOnTouchListener(null)
            (view.parent as? ViewGroup)?.removeView(view)
        },
    )
}

@Composable
private fun BrowserEmpty(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.Language,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BrowserControlBar(
    phase: BrowserPhase,
    agentReady: Boolean,
    canEnable: Boolean,
    busy: Boolean,
    onEnable: () -> Unit,
    onTakeOver: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = if (agentReady) stringResource(R.string.browser_note) else stringResource(R.string.browser_agent_needs_local),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            when (phase) {
                BrowserPhase.ENABLED -> {
                    TextButton(onClick = onTakeOver) {
                        Text(stringResource(R.string.browser_take_over))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.browser_turn_off))
                    }
                }
                else -> {
                    TextButton(onClick = onEnable, enabled = canEnable && !busy) {
                        Text(stringResource(R.string.browser_turn_on))
                    }
                    Spacer(Modifier.weight(1f))
                    if (phase == BrowserPhase.TAKEN_OVER) {
                        TextButton(onClick = onClose) { Text(stringResource(R.string.browser_turn_off)) }
                    }
                }
            }
        }
    }
}

/** Shown when the app graph has not provided the single controller yet. Manual browsing waits. */
@Composable
internal fun BrowserUnavailable(onExit: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.browser_unavailable),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.browser_controller_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onExit) { Text(stringResource(R.string.home)) }
        }
    }
}

internal fun normalizeAddress(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.startsWith("https://")) return trimmed
    if (trimmed.startsWith("http://")) return trimmed
    return "https://$trimmed"
}

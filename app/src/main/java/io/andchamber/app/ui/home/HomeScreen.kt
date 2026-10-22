package io.andchamber.app.ui.home

import androidx.compose.ui.res.stringResource
import io.andchamber.app.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import io.andchamber.app.runtime.RuntimeInfo
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.RuntimeUpdateState
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.SharedStorageState
import io.andchamber.app.runtime.SharedStorageStatus
import io.andchamber.app.ui.components.ChamberCard
import io.andchamber.app.ui.components.ChamberMark
import io.andchamber.app.ui.components.IconTile
import io.andchamber.app.ui.components.SectionHeader
import io.andchamber.app.ui.rememberLastNonNull
import io.andchamber.app.ui.revealOnEnter

/** Everything the home screen can do, so previews and the app share one surface. */
internal class HomeActions(
    val onSetUp: () -> Unit,
    val onStart: () -> Unit,
    val onRetry: () -> Unit,
    val onStop: () -> Unit,
    val onRepair: () -> Unit,
    val onUninstall: () -> Unit,
    val onOpenLocal: () -> Unit,
    val onOpenRemote: (ServerTarget.Remote) -> Unit,
    val onSaveRemote: (ServerTarget.Remote) -> Unit,
    val onRemoveRemote: (ServerTarget.Remote) -> Unit,
    val onSharedStorageChange: (Boolean) -> Unit = {},
    val onAllowStorageAccess: () -> Unit = {},
    val onCheckStorageAgain: () -> Unit = {},
    /** Stops the local server and starts it again, so new folder settings apply. */
    val onRestartLocal: () -> Unit = {},
    val onAutoOpenChange: (Boolean) -> Unit = {},
    /** Installs the available OpenChamber / OpenCode update (after the card has asked). */
    val onUpdate: () -> Unit = {},
    val onCheckForUpdates: () -> Unit = {},
    val onDismissUpdateFailure: () -> Unit = {},
)

/** The storage card only makes sense where the local server can exist at all. */
private fun RuntimeState.allowsSharedStorage(): Boolean =
    this != RuntimeState.Detecting && this != RuntimeState.UnsupportedAbi && this != RuntimeState.NotInstalled

/** Editor dialog target for a new server; existing servers use their id so it survives rotation. */
private const val NEW_SERVER = "__new__"

/**
 * The control room: the local runtime card and the saved servers. A short list of cards, so a
 * plain scrolling column is used rather than a lazy list.
 */
@Composable
internal fun HomeScreen(
    state: RuntimeState,
    info: RuntimeInfo?,
    logLines: List<String>,
    servers: List<ServerTarget.Remote>,
    actions: HomeActions,
    checking: Boolean = false,
    storage: SharedStorageState? = null,
    /** "Open OpenChamber when the app starts"; null hides the setting. */
    autoOpenLocal: Boolean? = null,
    /** Update check and the outcome of the last update of the local server. */
    updates: RuntimeUpdateState = RuntimeUpdateState(),
) {
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    // Play the entrance once per process, not every time the user comes back from a server.
    val animate = !LocalInspectionMode.current && remember { HomeEntrance.consume() }
    val glow = MaterialTheme.colorScheme.primary

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        // A faint amber light from the top corner, behind the header.
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(glow.copy(alpha = 0.07f), Color.Transparent),
                                center = Offset(size.width * 0.15f, 0f),
                                radius = size.width * 0.9f,
                            ),
                        )
                    },
        ) {
            Column(
                // Content scrolls under the transparent system bars; spacers keep the ends clear.
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.safeDrawing))
                Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    HomeHeader(modifier = Modifier.revealOnEnter(0, animate))

                    SectionHeader(
                        text = stringResource(R.string.section_this_device),
                        modifier = Modifier.padding(top = 28.dp, bottom = 10.dp).revealOnEnter(1, animate),
                    )
                    LocalRuntimeCard(
                        state = state,
                        checking = checking,
                        info = info,
                        logLines = logLines,
                        onSetUp = actions.onSetUp,
                        onStart = actions.onStart,
                        onStop = actions.onStop,
                        onOpen = actions.onOpenLocal,
                        onRetry = actions.onRetry,
                        onRepair = actions.onRepair,
                        onUninstall = actions.onUninstall,
                        sharedFolderInUse = storage?.status == SharedStorageStatus.Active,
                        autoOpenLocal = autoOpenLocal,
                        onAutoOpenChange = actions.onAutoOpenChange,
                        updates = updates,
                        onUpdate = actions.onUpdate,
                        onCheckForUpdates = actions.onCheckForUpdates,
                        onDismissUpdateFailure = actions.onDismissUpdateFailure,
                        modifier = Modifier.revealOnEnter(1, animate),
                    )
                    AnimatedVisibility(
                        visible = storage != null && !checking && state.allowsSharedStorage(),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                        modifier = Modifier.revealOnEnter(2, animate),
                    ) {
                        rememberLastNonNull(storage)?.let { shown ->
                            SharedStorageCard(
                                state = shown,
                                serverRunning = state is RuntimeState.Running,
                                onEnabledChange = actions.onSharedStorageChange,
                                onAllowAccess = actions.onAllowStorageAccess,
                                onCheckAgain = actions.onCheckStorageAgain,
                                onRestart = actions.onRestartLocal,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }

                    SectionHeader(
                        text = stringResource(R.string.section_servers),
                        modifier =
                            Modifier
                                .padding(top = 28.dp, bottom = if (servers.isEmpty()) 10.dp else 2.dp)
                                .revealOnEnter(3, animate),
                        trailing =
                            if (servers.isNotEmpty()) {
                                {
                                    TextButton(onClick = { editingId = NEW_SERVER }) {
                                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.add))
                                    }
                                }
                            } else {
                                null
                            },
                    )
                    RemoteServersSection(
                        servers = servers,
                        onOpen = actions.onOpenRemote,
                        onAdd = { editingId = NEW_SERVER },
                        onEdit = { editingId = it.id },
                        onRemove = { removingId = it.id },
                        modifier = Modifier.revealOnEnter(3, animate),
                    )

                    Spacer(Modifier.height(32.dp))
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
            }
        }
    }

    editingId?.let { id ->
        val existing = servers.firstOrNull { it.id == id }
        if (id == NEW_SERVER || existing != null) {
            ServerEditorDialog(
                initial = existing,
                onSave = {
                    actions.onSaveRemote(it)
                    editingId = null
                },
                onDismiss = { editingId = null },
            )
        } else {
            // The server was removed while its editor was open.
            LaunchedEffect(id) { editingId = null }
        }
    }
    removingId?.let { id ->
        val server = servers.firstOrNull { it.id == id }
        if (server != null) {
            RemoveServerDialog(
                server = server,
                onConfirm = { actions.onRemoveRemote(server) },
                onDismiss = { removingId = null },
            )
        } else {
            LaunchedEffect(id) { removingId = null }
        }
    }
}

@Composable
private fun HomeHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 28.dp, start = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChamberMark(size = 36.dp)
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One-shot flag for the home entrance animation. */
private object HomeEntrance {
    private var played = false

    fun consume(): Boolean {
        val first = !played
        played = true
        return first
    }
}

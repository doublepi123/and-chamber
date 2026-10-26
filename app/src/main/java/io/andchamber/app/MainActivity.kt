package io.andchamber.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.andchamber.app.runtime.LocalRuntimeController
import io.andchamber.app.runtime.RemoteServerStore
import io.andchamber.app.runtime.SharedStorageController
import io.andchamber.app.runtime.WebSessionAuthenticator
import io.andchamber.app.ui.AndChamberApp
import io.andchamber.app.ui.ScreenAwakeController
import io.andchamber.app.ui.keepScreenOnWhileLocalRuns
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity still carries the intent it was first launched with; that tap was
        // already handled (or is kept in the UI's saved state), so it is offered only once.
        if (savedInstanceState == null) {
            offerSessionFrom(intent)
            offerPhoneFrom(intent)
        }
        val runtime = get<LocalRuntimeController>()
        val screenAwake = get<ScreenAwakeController>()
        val phone = get<io.andchamber.app.phone.android.PhoneIntegration>()
        val overlay = get<io.andchamber.app.phone.android.overlay.PhoneOverlayCoordinator>()
        // Opt-in: holds this window's screen on only while the local server runs and the app is in
        // front. Released on stop, failure, background, a remote page, and when the activity goes.
        keepScreenOnWhileLocalRuns(
            window = window,
            enabled = screenAwake.enabled,
            runtime = runtime.state,
            viewedTargetId = screenAwake.viewedTargetId,
        )
        setContent {
            AndChamberApp(
                runtime = runtime,
                remotes = get<RemoteServerStore>(),
                auth = get<WebSessionAuthenticator>(),
                storage = get<SharedStorageController>(),
                phone = phone,
                overlay = overlay,
            )
        }
    }

    /**
     * Coming back to the app is not a phone-control stop while the person is reading the chat.
     * The native phone screen itself applies the host-return stop from `onScreenOpened`, exactly
     * when the person really opens it.
     */
    override fun onResume() {
        super.onResume()
        // The native screen is really in front now: the overlay may close. Its notification stays.
        get<io.andchamber.app.phone.android.overlay.PhoneOverlayCoordinator>().onHostVisible()
    }

    /** The native screen is no longer in front; the overlay may be shown again if the task needs it. */
    override fun onStop() {
        super.onStop()
        get<io.andchamber.app.phone.android.overlay.PhoneOverlayCoordinator>().onHostHidden()
    }

    /** singleTop: a notification tap while the app is open arrives here instead of onCreate. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        offerSessionFrom(intent)
        offerPhoneFrom(intent)
    }

    /** The phone session/paired notification's content intent asks for the phone screen. */
    private fun offerPhoneFrom(intent: android.content.Intent?) {
        if (intent?.action != io.andchamber.app.phone.android.PhoneControlService.ACTION_OPEN_PHONE) return
        io.andchamber.app.phone.android.PhoneOpenRequest.offer()
    }

    private fun offerSessionFrom(intent: android.content.Intent?) {
        if (intent?.action != io.andchamber.app.notify.OpenChamberNotifier.ACTION_OPEN_SESSION) return
        // Reopening from Recents replays the original notification intent; that isn't a new tap.
        if (intent.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        io.andchamber.app.notify.OpenSessionLink.offer(
            sessionId = intent.getStringExtra(io.andchamber.app.notify.OpenChamberNotifier.EXTRA_SESSION_ID),
            directory = intent.getStringExtra(io.andchamber.app.notify.OpenChamberNotifier.EXTRA_DIRECTORY),
        )
    }
}

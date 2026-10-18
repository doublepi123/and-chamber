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
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity still carries the intent it was first launched with; that tap was
        // already handled (or is kept in the UI's saved state), so it is offered only once.
        if (savedInstanceState == null) offerSessionFrom(intent)
        setContent {
            AndChamberApp(
                runtime = get<LocalRuntimeController>(),
                remotes = get<RemoteServerStore>(),
                auth = get<WebSessionAuthenticator>(),
                storage = get<SharedStorageController>(),
            )
        }
    }

    /** singleTop: a notification tap while the app is open arrives here instead of onCreate. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        offerSessionFrom(intent)
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

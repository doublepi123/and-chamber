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
        offerSessionFrom(intent)
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
        io.andchamber.app.notify.OpenSessionLink.offer(
            sessionId = intent.getStringExtra(io.andchamber.app.notify.OpenChamberNotifier.EXTRA_SESSION_ID),
            directory = intent.getStringExtra(io.andchamber.app.notify.OpenChamberNotifier.EXTRA_DIRECTORY),
        )
    }
}

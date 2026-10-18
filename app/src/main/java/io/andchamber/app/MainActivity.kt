package io.andchamber.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.andchamber.app.runtime.LocalRuntimeController
import io.andchamber.app.runtime.RemoteServerStore
import io.andchamber.app.runtime.WebSessionAuthenticator
import io.andchamber.app.ui.AndChamberApp
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AndChamberApp(
                runtime = get<LocalRuntimeController>(),
                remotes = get<RemoteServerStore>(),
                auth = get<WebSessionAuthenticator>(),
            )
        }
    }
}

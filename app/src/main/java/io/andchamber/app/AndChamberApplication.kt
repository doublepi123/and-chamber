package io.andchamber.app

import android.app.Application
import io.andchamber.app.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.android.ext.android.get
import org.koin.core.context.startKoin

class AndChamberApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@AndChamberApplication)
            modules(appModule)
        }
        // Process-wide: runtime error alerts and the one-time notification bootstrap.
        get<io.andchamber.app.notify.NotificationObserver>().start()
        // Notification replies and the phone-control foreground service are started by the system,
        // so they reach the integration through a process-wide handle. It carries no authority.
        val phone = get<io.andchamber.app.phone.android.PhoneIntegration>()
        io.andchamber.app.phone.android.PhoneIntegrationHolder.install(phone)
        // A locked screen drops any phone-control lease. Registered for the process lifetime; it only
        // forwards the system broadcast and never decides anything itself.
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    context: android.content.Context?,
                    intent: android.content.Intent?,
                ) {
                    if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) phone.onScreenOff()
                }
            },
            android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}

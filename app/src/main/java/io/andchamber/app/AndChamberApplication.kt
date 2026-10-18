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
    }
}

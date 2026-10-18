package io.andchamber.app

import android.app.Application
import io.andchamber.app.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class AndChamberApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@AndChamberApplication)
            modules(appModule)
        }
    }
}

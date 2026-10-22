package com.hardwareshop.pos

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class HardwareShopApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        FinalProductCatalog.install(this)
        // Also cancels any unfinished prototype work when the release gate is closed.
        com.hardwareshop.pos.service.CloudSyncScheduler.schedule(this)
        com.hardwareshop.pos.service.CloudSyncScheduler.requestNow(this)
        // Recover remote print jobs saved locally when Firebase was unavailable.
        com.hardwareshop.pos.service.CloudSyncScheduler.requestRemotePrintUpload(this)

        // Timber for structured debug logging — stripped from release by ProGuard
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    // The manifest removes WorkManager's default initializer. Its first getInstance()
    // uses this configuration so print and sync workers receive their Hilt dependencies.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(
                if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.ERROR
            )
            .build()
}

package com.hardwareshop.pos

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ProgressUpdater
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import com.hardwareshop.pos.service.CloudSyncWorker
import com.hardwareshop.pos.service.PrintJobWorker
import com.hardwareshop.pos.service.RemotePrintUploadWorker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.UUID

class WorkerInitializationTest {
    private val app: HardwareShopApp = ApplicationProvider.getApplicationContext()

    @Suppress("DEPRECATION")
    @Test fun installedManifestRemovesOnlyTheDefaultWorkerInitializer() {
        val provider = app.packageManager.getProviderInfo(
            ComponentName(app, "androidx.startup.InitializationProvider"), PackageManager.GET_META_DATA
        )
        assertFalse(provider.metaData?.containsKey("androidx.work.WorkManagerInitializer") == true)
    }

    @Test fun runningWorkManagerUsesTheApplicationsHiltFactory() {
        assertSame(app.workerFactory, WorkManager.getInstance(app).configuration.workerFactory)
    }

    @Test fun actualConfiguredFactoryCanCreatePrintAndSyncWorkers() {
        val manager = WorkManager.getInstance(app) as WorkManagerImpl
        val configuration = manager.configuration
        val factory = configuration.workerFactory
        val parameters = WorkerParameters(
            UUID.randomUUID(), Data.EMPTY, emptyList(), WorkerParameters.RuntimeExtras(), 0, 0,
            configuration.executor, manager.workTaskExecutor, factory,
            ProgressUpdater { _, _, _ -> error("This test creates workers without executing jobs") },
            ForegroundUpdater { _, _, _ -> error("This test creates workers without executing jobs") }
        )
        listOf(PrintJobWorker::class.java, RemotePrintUploadWorker::class.java, CloudSyncWorker::class.java)
            .forEach { type ->
                val worker = factory.createWorker(app, type.name, parameters)
                assertNotNull("Configured factory could not create ${type.simpleName}", worker)
                assertSame(type, worker!!.javaClass)
            }
    }
}

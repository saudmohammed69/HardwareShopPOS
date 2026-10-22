package com.hardwareshop.pos.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.BackoffPolicy
import com.hardwareshop.pos.data.sync.SyncReleaseGate
import java.util.concurrent.TimeUnit

object CloudSyncScheduler {
    private const val PERIODIC = "hardware_shop_cloud_sync_periodic"
    private const val IMMEDIATE = "hardware_shop_cloud_sync_now"
    private const val PRINT_PERIODIC = "hardware_shop_remote_print_upload_periodic"
    private const val PRINT_IMMEDIATE = "hardware_shop_remote_print_upload_now"

    fun schedule(context: Context) {
        // Remote printing is independent of catalogue-sync rollout.
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val printPeriodic = PeriodicWorkRequestBuilder<RemotePrintUploadWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PRINT_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, printPeriodic
        )
        if (!SyncReleaseGate.READY) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
    }

    fun requestNow(context: Context) {
        if (!SyncReleaseGate.READY) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CloudSyncWorker>().setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()
        )
    }

    fun requestRemotePrintUpload(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            PRINT_IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RemotePrintUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
        )
    }

    /** A successful partial pass continues immediately, without failure backoff. */
    fun continueNow(context: Context) {
        if (!SyncReleaseGate.READY) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<CloudSyncWorker>().setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()
        )
    }
}

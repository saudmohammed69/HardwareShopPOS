package com.hardwareshop.pos.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hardwareshop.pos.data.remote.ExcelSyncRepository
import com.hardwareshop.pos.domain.model.Result
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

@HiltWorker
class ExcelSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val excelSyncRepository: ExcelSyncRepository
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "excel_two_way_sync_work"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ExcelSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.i("ExcelSyncWorker scheduled (every 1 hour)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.i("ExcelSyncWorker cancelled")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            when (val result = excelSyncRepository.performTwoWaySync()) {
                is com.hardwareshop.pos.domain.model.Result.Success -> {
                    Timber.i("ExcelSyncWorker completed successfully: ${result.data}")
                    Result.success()
                }
                is com.hardwareshop.pos.domain.model.Result.Error -> {
                    Timber.w("ExcelSyncWorker failed: ${result.message}")
                    Result.retry()
                }
                else -> Result.failure()
            }
        } catch (e: Exception) {
            Timber.e(e, "ExcelSyncWorker unexpected error")
            Result.retry()
        }
    }
}

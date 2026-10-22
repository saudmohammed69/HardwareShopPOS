package com.hardwareshop.pos.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hardwareshop.pos.data.remote.CloudSyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import kotlinx.coroutines.CancellationException
import com.hardwareshop.pos.data.sync.SyncReleaseGate

@HiltWorker
class CloudSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sync: CloudSyncRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!SyncReleaseGate.READY) return Result.success()
        return try { when (val result = sync.runOnce()) {
        is CloudSyncRepository.SyncResult.Success -> {
            Timber.i("Cloud sync complete: uploaded=%d downloaded=%d review=%d", result.uploaded, result.downloaded, result.reviewRequired)
            if (result.moreWork) CloudSyncScheduler.continueNow(applicationContext)
            Result.success()
        }
        is CloudSyncRepository.SyncResult.NotLinked -> {
            Timber.i("Cloud sync skipped: phone is not linked to a shop")
            Result.success()
        }
        is CloudSyncRepository.SyncResult.Failed -> {
            Timber.w("Cloud sync deferred: %s", result.message)
            Result.retry()
        }
        } } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true)
                Result.retry()
            else throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No payload/customer/credential details in logs.
            Timber.w("Cloud sync could not complete (%s)", e.javaClass.simpleName)
            Result.retry()
        }
    }
}

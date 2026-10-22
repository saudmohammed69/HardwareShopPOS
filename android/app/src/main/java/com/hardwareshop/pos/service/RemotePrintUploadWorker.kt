package com.hardwareshop.pos.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hardwareshop.pos.data.remote.RemotePrintRepository
import com.hardwareshop.pos.domain.model.Result as DomainResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** Uploads owner-side remote print jobs that were saved while Firebase was unavailable. */
@HiltWorker
class RemotePrintUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val remotePrintRepository: RemotePrintRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            when (val result = remotePrintRepository.recoverAuthoredPrintJobs()) {
                is DomainResult.Success -> {
                    Timber.i("Remote print upload recovery complete: %d job(s)", result.data)
                    Result.success()
                }
                is DomainResult.Error -> {
                    Timber.w("Remote print upload recovery deferred: %s", result.message)
                    Result.retry()
                }
                else -> Result.retry()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true)
                Result.retry()
            else throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Remote print upload recovery failed")
            Result.retry()
        }
    }
}

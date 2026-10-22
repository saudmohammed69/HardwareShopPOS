package com.hardwareshop.pos.service

import com.hardwareshop.pos.util.printerDeviceId
import android.content.Context
import android.util.Base64
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.RemotePrintJobEntity
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import com.hardwareshop.pos.data.remote.FirebaseManager
import com.hardwareshop.pos.data.remote.PrintClaimPolicy
import com.hardwareshop.pos.domain.model.Result as PrintResult
import com.hardwareshop.pos.util.BluetoothPrintManager
import com.hardwareshop.pos.util.PrinterTransport
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

@HiltWorker
class PrintJobWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase,
    private val firebase: FirebaseManager,
    private val printer: BluetoothPrintManager
) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_SHOP_ID = "shop_id"
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val shopId = inputData.getString(KEY_SHOP_ID) ?: return Result.failure()
        val token = id.toString() // WorkManager retains this ID across process-death retries.
        val markerKey = "remote_print_completion_$jobId"
        var authorizedToWrite = false
        var bytesSent = false
        suspend fun persistCompletion(sent: Boolean, error: String?) {
            db.settingsDao().upsertSetting(SettingsEntity(markerKey,
                token + "\n" + (if (sent) "SENT" else "FAILED") + "\n" + error.orEmpty()))
            if (sent) db.remotePrintJobDao().markPrinted(jobId)
            else db.remotePrintJobDao().markFailed(jobId, error.orEmpty())
        }
        suspend fun publishCompletion(sent: Boolean, error: String?): Boolean {
            val updated = firebase.finishClaimedPrint(shopId, jobId, token, sent, error)
            if (updated) db.settingsDao().upsertSetting(SettingsEntity(markerKey, ""))
            return updated
        }
        suspend fun preserveInterruptedAttempt() {
            if (!authorizedToWrite) return
            withContext(NonCancellable) {
                try {
                    withTimeoutOrNull(5_000) {
                        persistCompletion(bytesSent, if (bytesSent) null else PrintClaimPolicy.UNCERTAIN)
                    }
                } catch (e: Exception) {
                    // The cloud PRINTING fence still requires review if local storage fails.
                    Timber.e(e, "Could not save interrupted print result")
                }
            }
        }
        try {
            val registered = db.shopDeviceDao().getRegisteredDevice()
            val thisDevice = printerDeviceId(applicationContext)
            if (registered == null || registered.deviceId != thisDevice || registered.shopId != shopId || !ShopPrintAgentService.isEnabled(applicationContext) ||
                !jobId.matches(Regex("[A-Za-z0-9-]{1,80}"))) return Result.failure()
            if (firebase.ensureSignedIn() is PrintResult.Error) return Result.retry()
            firebase.verifyRegisteredPrinter(shopId, registered.deviceId)
            // Reconcile a durable local completion after an interrupted cloud update.
            // Never send Bluetooth bytes again just to repair a status update.
            val marker = db.settingsDao().getSetting(markerKey)?.value.orEmpty().split('\n', limit = 3)
            if (marker.size >= 2) {
                val sent = marker[1] == "SENT"
                val error = marker.getOrNull(2)?.takeIf { it.isNotEmpty() }
                val updated = firebase.finishClaimedPrint(shopId, jobId, marker[0], sent, error)
                db.settingsDao().upsertSetting(SettingsEntity(markerKey, ""))
                if (updated) return if (sent) Result.success() else Result.failure()
                // A different token means a subsequent explicit operator retry superseded it.
            }
            val claim = firebase.atomicClaimJob(shopId, jobId, token, registered.deviceId)
            db.remotePrintJobDao().insertJob(RemotePrintJobEntity(jobId = jobId, shopId = shopId,
                billId = claim.billId, billNumber = claim.billNumber, receiptPayloadBase64 = claim.payload))
            when (claim.decision) {
                PrintClaimPolicy.Decision.BUSY -> return Result.retry()
                PrintClaimPolicy.Decision.ALREADY_SENT -> {
                    db.remotePrintJobDao().markPrinted(jobId)
                    return Result.success()
                }
                PrintClaimPolicy.Decision.REVIEW, PrintClaimPolicy.Decision.FAILED -> {
                    db.remotePrintJobDao().markFailed(jobId,
                        if (claim.decision == PrintClaimPolicy.Decision.REVIEW) PrintClaimPolicy.UNCERTAIN
                        else claim.error ?: "Print job failed. Check the paper before retrying.")
                    return Result.failure()
                }
                PrintClaimPolicy.Decision.MISSING -> return Result.failure(workDataOf("error" to "Remote job not found"))
                PrintClaimPolicy.Decision.ACQUIRE -> Unit
            }
            db.remotePrintJobDao().setClaimed(jobId)
            val bytes = try {
                require(claim.payload.length <= PrinterTransport.MAX_PAYLOAD_BYTES * 4 / 3 + 8)
                Base64.decode(claim.payload, Base64.NO_WRAP).also { require(it.isNotEmpty()) }
            } catch (_: IllegalArgumentException) {
                persistCompletion(false, "Invalid receipt payload")
                publishCompletion(false, "Invalid receipt payload")
                return Result.failure()
            }
            var fenceError: Exception? = null
            val result = printer.printRawBytes(bytes) {
                try {
                    // Acquired printer mutex and connected socket; only now start the write lease.
                    check((ShopPrintAgentService.isEnabled(applicationContext) && db.shopDeviceDao().getRegisteredDevice()?.shopId == shopId && firebase.beginClaimedPrint(shopId, jobId, token, registered.deviceId))) { "Print claim expired or changed" }
                    db.remotePrintJobDao().transitionToPrinting(jobId)
                    authorizedToWrite = true
                } catch (e: Exception) { fenceError = e; throw e }
            }
            if (fenceError != null) return Result.retry()
            if (result is PrintResult.Error && !authorizedToWrite) {
                val message = "Printer not ready; retrying automatically: ${result.message}"
                db.remotePrintJobDao().recordRetryError(jobId, message)
                firebase.notePrintRetry(shopId, jobId, token, message)
                return Result.retry()
            }
            bytesSent = result is PrintResult.Success
            val error = (result as? PrintResult.Error)?.message
            persistCompletion(bytesSent, error)
            publishCompletion(bytesSent, error)
            return if (bytesSent) Result.success() else Result.failure(workDataOf("error" to error.orEmpty()))
        } catch (e: CancellationException) {
            preserveInterruptedAttempt()
            // A request timeout is retryable; WorkManager cancellation must still propagate.
            if (e is kotlinx.coroutines.TimeoutCancellationException &&
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true)
                return Result.retry()
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Remote print interrupted; retrying state reconciliation")
            db.remotePrintJobDao().recordRetryError(jobId, "Print retry: ${e.message ?: e.javaClass.simpleName}")
            preserveInterruptedAttempt()
            return Result.retry()
        }
    }
}

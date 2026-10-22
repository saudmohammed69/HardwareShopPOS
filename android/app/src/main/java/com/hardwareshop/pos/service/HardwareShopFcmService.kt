package com.hardwareshop.pos.service

import com.hardwareshop.pos.util.printerDeviceId
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hardwareshop.pos.data.local.database.AppDatabase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Firebase Cloud Messaging service.
 *
 * Receives data messages from the owner device (via Cloud Functions trigger
 * or Firestore write that triggers FCM).  The message contains the jobId —
 * the shop device then enqueues a PrintJobWorker to process it.
 *
 * FCM is used as a WAKE-UP signal only.  The actual job data is always
 * fetched from Firestore, never trusted from the FCM payload alone.
 *
 * The foreground ShopPrintAgentService also listens via Firestore real-time
 * listener — FCM is a supplementary mechanism to wake the device when the
 * service is not running.
 *
 * Message format expected:
 *   data["type"]   = "PRINT_JOB"
 *   data["jobId"]  = "<uuid>"
 *   data["shopId"] = "<shopId>"
 */
@AndroidEntryPoint
class HardwareShopFcmService : FirebaseMessagingService() {

    @Inject lateinit var db: AppDatabase
    @Inject lateinit var firebase: com.hardwareshop.pos.data.remote.FirebaseManager

    companion object {
        const val FCM_TYPE_PRINT_JOB = "PRINT_JOB"
        const val NOTIFICATION_CHANNEL_PRINT = "remote_print"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Timber.d("FCM notification received")
        val type   = message.data["type"]   ?: return
        if(type=="WEB_ORDER_EVENT") {
            if(!WebsiteOrderHint.valid(message.data))return
            val work=OneTimeWorkRequestBuilder<WebsiteOrderHintWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,java.util.concurrent.TimeUnit.SECONDS).build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork("website_order_reconcile",ExistingWorkPolicy.KEEP,work)
            return
        }
        val jobId  = message.data["jobId"]  ?: return
        val shopId = message.data["shopId"] ?: return

        when (type) {
            FCM_TYPE_PRINT_JOB -> CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (db.shopDeviceDao().getRegisteredDevice()?.shopId == shopId && ShopPrintAgentService.isEnabled(applicationContext))
                        handlePrintJobMessage(jobId, shopId)
                } catch (e: Exception) { Timber.w(e, "Incoming print job could not be queued") }
            }
            else -> Timber.w("Unknown FCM message type: $type")
        }
    }

    override fun onNewToken(token: String) {
        Timber.i("FCM token refreshed")
        // Persist new token and update Firestore so owner can reach this device
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val device = db.shopDeviceDao().getRegisteredDevice() ?: return@launch
                val thisDevice = printerDeviceId(applicationContext)
                if (device.deviceId != thisDevice) return@launch
                db.shopDeviceDao().updateFcmToken(device.deviceId, token)
                firebase.updateFcmToken(device.shopId, token, device.deviceId)
                Timber.d("FCM token updated in Room for device ${device.deviceId}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to update FCM token")
            }
        }
    }

    private fun handlePrintJobMessage(jobId: String, shopId: String) {
        Timber.i("FCM: print job $jobId for shop $shopId")
        showIncomingJobNotification(jobId)

        // Enqueue PrintJobWorker — WorkManager handles retry and lifecycle
        val workRequest = OneTimeWorkRequestBuilder<PrintJobWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR,10,java.util.concurrent.TimeUnit.SECONDS)
            .setInputData(workDataOf(
                PrintJobWorker.KEY_JOB_ID  to jobId,
                PrintJobWorker.KEY_SHOP_ID to shopId
            ))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            // Use unique work per jobId — prevents duplicate WorkManager tasks
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                "print_job_$jobId",
                ExistingWorkPolicy.KEEP,  // KEEP = if already queued, don't re-add
                workRequest
            )
    }

    private fun showIncomingJobNotification(jobId: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_PRINT,
            "Remote Print Jobs",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_PRINT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Print Job Received")
            .setContentText("Processing bill print…")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        if (android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            nm.notify(jobId.hashCode(), notification)
    }
}

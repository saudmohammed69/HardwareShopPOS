package com.hardwareshop.pos.service

import android.app.*
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.*
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.remote.FirebaseManager
import com.hardwareshop.pos.data.remote.RemotePrintRepository
import com.hardwareshop.pos.util.BluetoothPrintManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import javax.inject.Inject

/**
 * Shop Print Agent — foreground service running on the shop's printer device.
 *
 * Responsibilities:
 *  1. Keep a Firestore real-time listener active for PENDING print jobs
 *  2. Process incoming jobs via PrintJobWorker (WorkManager — survives restart)
 *  3. Send heartbeat to Firestore every 30 s so owner sees device status
 *  4. Update Bluetooth printer status in heartbeat
 *
 * Lifecycle:
 *  - Started by the owner when they enable "Shop Printer Mode" in settings
 *  - Runs as a FOREGROUND service with an ongoing notification
 *  - Automatically restarted by Android after process kill (START_STICKY)
 *  - WorkManager tasks survive device restart independently
 *
 * This service + the FCM handler form a two-layer wake-up system:
 *  Layer 1 (primary):  Firestore real-time listener in this foreground service
 *  Layer 2 (backup):   FCM data message → PrintJobWorker
 *
 */
@AndroidEntryPoint
class ShopPrintAgentService : Service() {

    @Inject lateinit var db:                    AppDatabase
    @Inject lateinit var firebaseManager:       FirebaseManager
    @Inject lateinit var remotePrintRepo:       RemotePrintRepository
    @Inject lateinit var bluetoothPrintManager: BluetoothPrintManager

    /** Scope for all ongoing agent work — cancelled in onDestroy(). */
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val CHANNEL_ID              = "shop_print_agent"
        const val NOTIFICATION_ID         = 1001
        const val HEARTBEAT_INTERVAL_MS   = 30_000L

        fun hasBluetoothPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        fun isEnabled(context: Context): Boolean = context.getSharedPreferences("printer_mode", MODE_PRIVATE).getBoolean("enabled", false)

        fun start(context: Context) {
            check(hasBluetoothPermission(context)) {
                "Allow Nearby devices permission before starting the print agent."
            }
            val intent = Intent(context, ShopPrintAgentService::class.java)
            val preferences = context.getSharedPreferences("printer_mode", MODE_PRIVATE)
            preferences.edit { putBoolean("enabled", true) }
            try { context.startForegroundService(intent) }
            catch (e: Exception) { preferences.edit { putBoolean("enabled", false) }; throw e }
        }

        fun stop(context: Context) {
            context.getSharedPreferences("printer_mode", MODE_PRIVATE).edit { putBoolean("enabled", false); remove("last_error") }
            context.stopService(Intent(context, ShopPrintAgentService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!hasBluetoothPermission(this)) {
            stopSelf()
            return
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Listening for print jobs"))
        Timber.i("ShopPrintAgentService started")
    }

    private var agentJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasBluetoothPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (agentJob?.isActive != true) {
            agentJob = serviceScope.launch {
                while (isActive && isEnabled(this@ShopPrintAgentService)) {
                    try {
                        runAgent()
                        break
                    } catch (e: CancellationException) {
                        if (e is kotlinx.coroutines.TimeoutCancellationException && currentCoroutineContext().isActive) {
                            val message = "Cloud connection timed out; reconnecting automatically"
                            getSharedPreferences("printer_mode", MODE_PRIVATE).edit { putString("last_error", message) }
                            updateNotification(message)
                            delay(10_000)
                            continue
                        }
                        throw e
                    } catch (e: Exception) {
                        val message = e.message ?: "Printer connection unavailable"
                        getSharedPreferences("printer_mode", MODE_PRIVATE).edit { putString("last_error", message) }
                        updateNotification("Printer blocked: $message")
                        Timber.w(e, "Print agent will reconnect")
                        delay(10_000)
                    }
                }
            }
        }
        return START_STICKY  // Restart automatically if process is killed
    }

    override fun onDestroy() {
        serviceScope.cancel()
        // Best effort: independent of the cancelled service scope, bounded to five seconds.
        // Process death can still interrupt this; remote presence must expire stale heartbeats.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(5_000L) {
                    val device = db.shopDeviceDao().getRegisteredDevice()
                    val localId = com.hardwareshop.pos.util.printerDeviceId(this@ShopPrintAgentService)
                    if (device != null && localId == device.deviceId) firebaseManager.setDeviceOffline(device.shopId, device.deviceId)
                }
            } catch (e: Exception) {
                Timber.w(e, "Device offline update failed during shutdown")
            }
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun runAgent() = coroutineScope {
        val device = db.shopDeviceDao().getRegisteredDevice()
        if (device == null) {
            Timber.w("No registered device — ShopPrintAgent has nothing to do")
            stopSelf()
            return@coroutineScope
        }

        check(com.hardwareshop.pos.data.remote.RemotePrintRecoveryPolicy.isRegisteredPrinter(
            com.hardwareshop.pos.util.printerDeviceId(this@ShopPrintAgentService), device.deviceId)) {
            "This phone is a sender. Enable Printer Mode only on the worker's printer phone."
        }
        firebaseManager.verifyRegisteredPrinter(device.shopId, device.deviceId)
        getSharedPreferences("printer_mode", MODE_PRIVATE).edit { remove("last_error") }

        // Start heartbeat loop in parallel
        val heartbeatJob = launch { heartbeatLoop(device.shopId, device.deviceId) }

        // Listen for PENDING print jobs via Firestore real-time snapshot
        Timber.i("Listening for print jobs on shop ${device.shopId}")
        try {
            firebaseManager.observePendingJobs(device.shopId)
                .collectLatest { pendingJobs ->
                    pendingJobs.forEach { jobData ->
                        val jobId  = jobData["jobId"]  as? String ?: return@forEach
                        val shopId = jobData["shopId"] as? String ?: device.shopId
                        Timber.d("Firestore: new PENDING job $jobId")
                        enqueueJobWorker(jobId, shopId)
                    }
                }
        } finally {
            heartbeatJob.cancelAndJoin()
        }
    }

    /** Enqueue a PrintJobWorker for this jobId (KEEP = skip if already queued) */
    private fun enqueueJobWorker(jobId: String, shopId: String) {
        val request = OneTimeWorkRequestBuilder<PrintJobWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR,10,java.util.concurrent.TimeUnit.SECONDS)
            .setInputData(workDataOf(
                PrintJobWorker.KEY_JOB_ID  to jobId,
                PrintJobWorker.KEY_SHOP_ID to shopId
            ))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this)
            .enqueueUniqueWork(
                "print_job_$jobId",
                ExistingWorkPolicy.KEEP,
                request
            )
        Timber.d("PrintJobWorker enqueued: $jobId")
    }

    /** Send heartbeat + Bluetooth status to Firestore every 30 s */
    private suspend fun heartbeatLoop(shopId: String, deviceId: String) {
        while (currentCoroutineContext().isActive) {
            try {
                if (!isEnabled(this) || db.shopDeviceDao().getRegisteredDevice()?.deviceId != deviceId) {
                    stopSelf(); return
                }
                val btConnected  = bluetoothPrintManager.isConnected()
                val btName       = bluetoothPrintManager.getSavedPrinterAddress()
                val pendingCount = db.remotePrintJobDao().getActiveJobs().size
                firebaseManager.updateDeviceHeartbeat(
                    shopId             = shopId,
                    deviceId           = deviceId,
                    bluetoothConnected = btConnected,
                    printerName        = btName,
                    pendingJobCount    = pendingCount
                )
                val notifText = if (btConnected)
                    "Sending to printer | $pendingCount pending jobs"
                else
                    "Listening | Printer connects when printing"
                updateNotification(notifText)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.TimeoutCancellationException && currentCoroutineContext().isActive) {
                    // A child timeout is otherwise treated as normal cancellation, leaving
                    // the listener alive but its heartbeat permanently stopped.
                    throw IllegalStateException("Printer heartbeat timed out; reconnecting", e)
                }
                Timber.w(e, "Heartbeat failed; reconnecting")
                throw e // Restart the agent with server identity/permission verification.
            }
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Shop Print Agent",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Remote print service for Hardware Shop POS"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hardware Shop POS — Printer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}


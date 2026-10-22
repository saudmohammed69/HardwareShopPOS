package com.hardwareshop.pos.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import com.hardwareshop.pos.domain.model.RemotePrintStatus
import com.hardwareshop.pos.domain.model.ShopDevice
import com.hardwareshop.pos.domain.model.Result
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase abstraction layer.
 *
 * Firestore schema:
 *   /shops/{shopId}/
 *     device  (single doc — the registered shop printer device)
 *     printJobs/{jobId}
 *
 * Auth: Firebase Anonymous — gives each device a stable UID without accounts.
 *
 * Security: shop membership is provisioned in Firestore by a Firebase administrator.
 * Knowing a shop ID is not authorization. See firestore.rules and the installation guide.
 */
@Singleton
class FirebaseManager @Inject constructor() {
    private val auth: FirebaseAuth get() = Firebase.auth
    private val db:   FirebaseFirestore get() = Firebase.firestore

    // ── Constants ─────────────────────────────────────────────────────────────
    companion object {
        private const val COL_SHOPS      = "shops"
        private const val DOC_DEVICE     = "device"
        private const val COL_PRINT_JOBS = "printJobs"

        private const val FIELD_STATUS       = "status"
        private const val FIELD_PRINTED_AT   = "printedAt"
        private const val FIELD_LAST_ERROR   = "lastError"
        private const val FIELD_RETRY_COUNT  = "retryCount"
        private const val FIELD_FCM_TOKEN    = "fcmToken"
        private const val FIELD_IS_ONLINE    = "isOnline"
        private const val FIELD_BT_CONNECTED = "bluetoothPrinterConnected"
        private const val FIELD_BT_NAME      = "bluetoothPrinterName"
        private const val FIELD_HEARTBEAT    = "lastHeartbeat"
        private const val FIELD_DEVICE_NAME  = "deviceName"
        private const val FIELD_DEVICE_ID    = "deviceId"
        private const val FIELD_PENDING_JOBS = "pendingJobCount"
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    /** Sign in anonymously if not already authenticated. Returns UID on success. */
    suspend fun ensureSignedIn(): Result<String> {
        val current = auth.currentUser
        if (current != null) return Result.Success(current.uid)
        return try {
            val result = withTimeout(20_000) { auth.signInAnonymously().await() }
            val uid = result.user?.uid ?: return Result.Error("Auth failed — no UID returned")
            Timber.d("Firebase anonymous sign-in OK: $uid")
            Result.Success(uid)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.e(e, "Firebase sign-in failed")
            Result.Error("Firebase sign-in failed: ${e.message}", e)
        }
    }

    suspend fun updateFcmToken(shopId: String, token: String, deviceId: String) {
        if (ensureSignedIn() is Result.Error) return
        updateRegisteredDevice(shopId, deviceId, mapOf(FIELD_FCM_TOKEN to token))
    }

    val currentUid: String? get() = auth.currentUser?.uid

    /** Check live server authorization, never grant roles from the client. */
    suspend fun verifyPrintAccess(shopId: String, sending: Boolean = false) = withTimeout(20_000) {
        check(ensureSignedIn() is Result.Success) { "Firebase sign-in unavailable" }
        val uid = checkNotNull(currentUid)
        val membership = db.collection(COL_SHOPS).document(shopId).collection("members")
            .document(uid).get(Source.SERVER).await()
        val owner = membership.getString("role") == "OWNER"
        check(membership.getBoolean("enabled") == true &&
            (owner || (!sending && membership.getBoolean("printer") == true))) {
            if (sending) "This phone needs enabled OWNER cloud membership to send print jobs."
            else "Printer permission missing. An administrator must enable printer access for this phone's Firebase UID."
        }
    }

    suspend fun verifyRegisteredPrinter(shopId: String, deviceId: String) = withTimeout(20_000) {
        verifyPrintAccess(shopId)
        val registered = fetchRegisteredDevice(shopId)
        check(RemotePrintRecoveryPolicy.isRegisteredPrinter(deviceId, registered?.deviceId)) {
            "This is not the registered printer phone. Register the worker phone, then use Link Sender on the owner phone."
        }
    }

    // ── FCM token ─────────────────────────────────────────────────────────────

    suspend fun getFcmToken(): String? = try {
        withTimeout(10_000) { FirebaseMessaging.getInstance().token.await() }
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        Timber.e(e, "Failed to get FCM token")
        null
    }

    // ── Device management ─────────────────────────────────────────────────────

    /** Register this device as the shop's print agent */
    suspend fun registerDevice(
        shopId:     String,
        deviceId:   String,
        deviceName: String,
        fcmToken:   String
    ): Result<Unit> {
        return try {
            verifyPrintAccess(shopId)
            val data = mapOf(
                FIELD_DEVICE_ID   to deviceId,
                FIELD_DEVICE_NAME to deviceName,
                FIELD_FCM_TOKEN   to fcmToken,
                FIELD_IS_ONLINE   to false,
                FIELD_BT_CONNECTED to false,
                FIELD_HEARTBEAT   to FieldValue.serverTimestamp(),
                FIELD_PENDING_JOBS to 0
            )
            withTimeout(20_000) { db.collection(COL_SHOPS).document(shopId)
                .collection("config").document(DOC_DEVICE)
                .set(data, SetOptions.merge())
                .await() }
            Timber.i("Device registered: $deviceName ($deviceId) for shop $shopId")
            Result.Success(Unit)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.e(e, "Device registration failed")
            Result.Error("Registration failed: ${e.message}", e)
        }
    }

    suspend fun fetchRegisteredDevice(shopId: String): com.hardwareshop.pos.data.local.entity.ShopDeviceEntity? {
        val snap = withTimeout(20_000) {
            db.collection(COL_SHOPS).document(shopId).collection("config").document(DOC_DEVICE).get(Source.SERVER).await()
        }
        val id = snap.getString(FIELD_DEVICE_ID)?.takeIf { it.isNotBlank() } ?: return null
        return com.hardwareshop.pos.data.local.entity.ShopDeviceEntity(deviceId = id,
            deviceName = snap.getString(FIELD_DEVICE_NAME).orEmpty(), shopId = shopId, isRegistered = true)
    }

    /** Update device heartbeat + printer status (called every 30 s from foreground service) */
    suspend fun updateDeviceHeartbeat(
        shopId:           String,
        deviceId:         String,
        bluetoothConnected: Boolean,
        printerName:      String,
        pendingJobCount:  Int
    ) {
        try {
            updateRegisteredDevice(shopId, deviceId, mapOf(
                    FIELD_IS_ONLINE    to true,
                    FIELD_HEARTBEAT    to FieldValue.serverTimestamp(),
                    FIELD_BT_CONNECTED to bluetoothConnected,
                    FIELD_BT_NAME      to printerName,
                    FIELD_PENDING_JOBS to pendingJobCount
                ))
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.w(e, "Heartbeat update failed (non-fatal)")
            throw e
        }
    }

    /** Mark device offline (called when foreground service stops) */
    suspend fun setDeviceOffline(shopId: String, deviceId: String) {
        try {
            updateRegisteredDevice(shopId, deviceId, mapOf(FIELD_IS_ONLINE to false))
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.w(e, "setDeviceOffline failed (non-fatal)")
        }
    }

    private suspend fun updateRegisteredDevice(shopId: String, deviceId: String, fields: Map<String, Any>) = withTimeout(20_000) {
        val ref = db.collection(COL_SHOPS).document(shopId).collection("config").document(DOC_DEVICE)
        db.runTransaction { tx ->
            val registered = tx.get(ref).getString(FIELD_DEVICE_ID)
            check(RemotePrintRecoveryPolicy.isRegisteredPrinter(deviceId, registered)) { "Printer registration changed" }
            tx.update(ref, fields)
        }.await()
    }

    /** Owner observes real-time device + printer status */
    fun observeShopDevice(shopId: String): Flow<ShopDevice?> = callbackFlow {
        var registration: ListenerRegistration? = null
        try {
            registration = db.collection(COL_SHOPS).document(shopId)
                .collection("config").document(DOC_DEVICE)
                .addSnapshotListener { snap, err ->
                    if (err != null) { close(err); return@addSnapshotListener }
                    if (snap == null || !snap.exists()) { trySend(null); return@addSnapshotListener }
                    val device = ShopDevice(
                        deviceId                  = snap.getString(FIELD_DEVICE_ID)  ?: "",
                        deviceName                = snap.getString(FIELD_DEVICE_NAME) ?: "",
                        shopId                    = shopId,
                        isOnline                  = snap.getBoolean(FIELD_IS_ONLINE) ?: false,
                        bluetoothPrinterName      = snap.getString(FIELD_BT_NAME)    ?: "",
                        bluetoothPrinterConnected = snap.getBoolean(FIELD_BT_CONNECTED) ?: false,
                        lastHeartbeatMs           = snap.getTimestamp(FIELD_HEARTBEAT)?.toDate()?.time,
                        pendingJobCount           = (snap.getLong(FIELD_PENDING_JOBS) ?: 0L).toInt()
                    )
                    trySend(device)
                }
        } catch (e: Exception) { close(e) }
        awaitClose { registration?.remove() }
    }

    // ── Print jobs ────────────────────────────────────────────────────────────

    /** Owner uploads a new print job to Firestore */
    suspend fun uploadPrintJob(
        shopId:           String,
        jobId:            String,
        billId:           Long,
        billNumber:       String,
        receiptBase64:    String,
        deviceId:         String,
        createdByUserId:  String
    ): Result<Unit> {
        return try {
            val data = mapOf(
                "jobId"              to jobId,
                "billId"             to billId,
                "billNumber"         to billNumber,
                "receiptPayload"     to receiptBase64,
                FIELD_STATUS         to RemotePrintStatus.PENDING.name,
                "deviceId"           to deviceId,
                "createdByUserId"    to createdByUserId,
                "createdAt"          to FieldValue.serverTimestamp(),
                FIELD_PRINTED_AT     to null,
                FIELD_LAST_ERROR     to null,
                FIELD_RETRY_COUNT    to 0
            )
            withTimeout(20_000) {
                val ref = db.collection(COL_SHOPS).document(shopId).collection(COL_PRINT_JOBS).document(jobId)
                db.runTransaction { tx ->
                    if (!tx.get(ref).exists()) tx.set(ref, data)
                }.await()
            }
            Timber.i("Print job uploaded: $jobId for bill #$billNumber")
            Result.Success(Unit)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.e(e, "uploadPrintJob failed")
            Result.Error("Failed to send print job: ${e.message}", e)
        }
    }

    data class Claim(val decision: PrintClaimPolicy.Decision, val payload: String,
                     val billId: Long, val billNumber: String, val error: String?)

    /** Errors propagate; a failed network transaction is never reported as a processed job. */
    suspend fun atomicClaimJob(shopId: String, jobId: String, token: String, deviceId: String): Claim = withTimeout(20_000) {
        val ref = db.collection(COL_SHOPS).document(shopId).collection(COL_PRINT_JOBS).document(jobId)
        db.runTransaction { tx ->
            val device = tx.get(db.collection(COL_SHOPS).document(shopId).collection("config").document(DOC_DEVICE))
            check(RemotePrintRecoveryPolicy.isRegisteredPrinter(deviceId, device.getString(FIELD_DEVICE_ID))) { "Printer registration changed" }
            val snap = tx.get(ref)
            val now = System.currentTimeMillis()
            val decision = PrintClaimPolicy.decide(snap.getString(FIELD_STATUS), snap.getString("claimToken"),
                token, snap.getLong("leaseUntil") ?: 0L, now)
            when (decision) {
                PrintClaimPolicy.Decision.ACQUIRE -> tx.update(ref, mapOf(
                    FIELD_STATUS to "RECEIVED", "claimToken" to token,
                    "leaseUntil" to now + PrintClaimPolicy.RECEIVE_LEASE_MS, FIELD_LAST_ERROR to null))
                PrintClaimPolicy.Decision.REVIEW -> tx.update(ref, mapOf(
                    FIELD_STATUS to "FAILED", FIELD_LAST_ERROR to PrintClaimPolicy.UNCERTAIN,
                    "leaseUntil" to 0L))
                else -> Unit
            }
            Claim(decision, snap.getString("receiptPayload").orEmpty(), snap.getLong("billId") ?: 0L,
                snap.getString("billNumber").orEmpty(), snap.getString(FIELD_LAST_ERROR))
        }.await()
    }

    /** Every write is fenced by the current claim token before any Bluetooth bytes are sent. */
    suspend fun beginClaimedPrint(shopId: String, jobId: String, token: String, deviceId: String): Boolean = withTimeout(20_000) {
        val ref = db.collection(COL_SHOPS).document(shopId).collection(COL_PRINT_JOBS).document(jobId)
        db.runTransaction { tx ->
            val device = tx.get(db.collection(COL_SHOPS).document(shopId).collection("config").document(DOC_DEVICE))
            check(RemotePrintRecoveryPolicy.isRegisteredPrinter(deviceId, device.getString(FIELD_DEVICE_ID))) { "Printer registration changed" }
            val snap = tx.get(ref)
            val now = System.currentTimeMillis()
            if (snap.getString(FIELD_STATUS) == "RECEIVED" && snap.getString("claimToken") == token &&
                (snap.getLong("leaseUntil") ?: 0L) > now) {
                tx.update(ref, mapOf(FIELD_STATUS to "PRINTING", "leaseUntil" to now + PrintClaimPolicy.PRINT_LEASE_MS))
                true
            } else false
        }.await()
    }

    suspend fun finishClaimedPrint(shopId: String, jobId: String, token: String,
                                  sent: Boolean, error: String? = null): Boolean = withTimeout(20_000) {
        val ref = db.collection(COL_SHOPS).document(shopId).collection(COL_PRINT_JOBS).document(jobId)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            if (snap.getString("claimToken") != token || (snap.getString(FIELD_STATUS) == "PRINTED" && !sent)) false
            else {
                tx.update(ref, mapOf(FIELD_STATUS to if (sent) "PRINTED" else "FAILED",
                    FIELD_LAST_ERROR to error, "leaseUntil" to 0L,
                    FIELD_PRINTED_AT to if (sent) FieldValue.serverTimestamp() else null))
                true
            }
        }.await()
    }

    /** No bytes were authorized: keep the claim retryable and expose the reason. */
    suspend fun notePrintRetry(shopId: String, jobId: String, token: String, message: String) = withTimeout(20_000) {
        val ref = db.collection(COL_SHOPS).document(shopId).collection(COL_PRINT_JOBS).document(jobId)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            if (snap.getString(FIELD_STATUS) == "RECEIVED" && snap.getString("claimToken") == token)
                tx.update(ref, FIELD_LAST_ERROR, message.take(500))
        }.await()
    }

    /** Shop device listens for PENDING jobs in real-time (foreground service listener) */
    fun observePendingJobs(shopId: String): Flow<List<Map<String, Any?>>> = callbackFlow {
        var reg: ListenerRegistration? = null
        try {
            reg = db.collection(COL_SHOPS).document(shopId)
                .collection(COL_PRINT_JOBS)
                .whereIn(FIELD_STATUS, listOf("PENDING", "RECEIVED", "PRINTING"))
                .addSnapshotListener { snaps, err ->
                    if (err != null) { close(err); return@addSnapshotListener }
                    val jobs = snaps?.documents?.map { it.data.orEmpty() + ("jobId" to it.id) + ("shopId" to shopId) } ?: emptyList()
                    trySend(jobs)
                }
        } catch (e: Exception) { close(e) }
        awaitClose { reg?.remove() }
    }

    /** Owner observes a specific job's status (for the print-status UI) */
    fun observeJobStatus(shopId: String, jobId: String): Flow<Map<String, Any?>?> = callbackFlow {
        var reg: ListenerRegistration? = null
        try {
            reg = db.collection(COL_SHOPS).document(shopId)
                .collection(COL_PRINT_JOBS).document(jobId)
                .addSnapshotListener { snap, err ->
                    if (err != null) { close(err); return@addSnapshotListener }
                    trySend(snap?.data)
                }
        } catch (e: Exception) { close(e) }
        awaitClose { reg?.remove() }
    }

    /** Explicit operator-confirmed reprint; never reset an active lease or a completed job. */
    suspend fun resetJobToPending(shopId: String, jobId: String, receiptBase64: String): Result<Unit> {
        return try {
            withTimeout(20_000) {
                val ref = db.collection(COL_SHOPS).document(shopId).collection(COL_PRINT_JOBS).document(jobId)
                db.runTransaction { tx ->
                    val snap = tx.get(ref)
                    val status = snap.getString(FIELD_STATUS)
                    val expired = (snap.getLong("leaseUntil") ?: 0L) <= System.currentTimeMillis()
                    check(status == "FAILED" || (status in listOf("RECEIVED", "PRINTING") && expired)) {
                        "This job is active or already sent. Refresh its status before retrying."
                    }
                    tx.update(ref, mapOf(FIELD_STATUS to "PENDING", FIELD_LAST_ERROR to null,
                        "claimToken" to null, "leaseUntil" to 0L, "receiptPayload" to receiptBase64,
                        FIELD_RETRY_COUNT to FieldValue.increment(1)))
                }.await()
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Result.Error("Retry failed: ${e.message}", e)
        }
    }

    fun observeRecentJobUpdates(shopId: String): Flow<List<Map<String, Any?>>> = callbackFlow {
        val registration = db.collection(COL_SHOPS).document(shopId).collection(COL_PRINT_JOBS)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) close(error)
                else trySend(snapshot?.documents?.map { it.data.orEmpty() + ("jobId" to it.id) }.orEmpty())
            }
        awaitClose { registration.remove() }
    }
}

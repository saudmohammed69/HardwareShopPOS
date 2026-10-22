package com.hardwareshop.pos.data.remote

import com.hardwareshop.pos.util.printerDeviceId
import android.content.Context
import androidx.room.withTransaction
import com.hardwareshop.pos.data.local.database.AppDatabase
import android.util.Base64
import com.hardwareshop.pos.data.local.dao.RemotePrintJobDao
import com.hardwareshop.pos.data.local.dao.ShopDeviceDao
import com.hardwareshop.pos.data.local.entity.RemotePrintJobEntity
import com.hardwareshop.pos.data.local.entity.ShopDeviceEntity
import com.hardwareshop.pos.data.repository.AuditRepository
import com.hardwareshop.pos.data.repository.BillingRepository
import com.hardwareshop.pos.data.repository.SettingsRepository
import com.hardwareshop.pos.domain.model.RemotePrintJob
import com.hardwareshop.pos.domain.model.RemotePrintStatus
import com.hardwareshop.pos.domain.model.Result
import com.hardwareshop.pos.domain.model.ShopDevice
import com.hardwareshop.pos.util.PermissionChecker
import com.hardwareshop.pos.util.ReceiptBuilder
import com.hardwareshop.pos.util.SessionHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemotePrintRepository @Inject constructor(
    private val db: AppDatabase,
    private val remotePrintJobDao:  RemotePrintJobDao,
    private val shopDeviceDao:      ShopDeviceDao,
    private val firebaseManager:    FirebaseManager,
    private val billingRepository:  BillingRepository,
    private val settingsRepository: SettingsRepository,
    private val auditRepository:    AuditRepository,
    private val session:            SessionHolder,
    private val shopLink:           ShopLinkRepository,
    @param:ApplicationContext private val context: Context
) {
    // ─────────────────────────────────────────────────────────────────────────
    // OWNER — CREATE REMOTE PRINT JOB
    // Business-layer permission check: WorkerSession → SecurityException
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a remote print job for the given bill.
     *
     * Steps (all local-first, network second):
     *  1. Enforce REMOTE_PRINT permission at business layer
     *  2. Verify Firebase auth
     *  3. Build receipt bytes using existing ReceiptBuilder (same as local print)
     *  4. Encode to Base64
     *  5. Persist RemotePrintJobEntity in local Room (PENDING)
     *  6. Upload to Firestore (shop device will see it via real-time listener / FCM)
     *  7. Audit log
     *
     * If Firebase upload fails the job remains PENDING in Room and will be
     * retried when connectivity is restored.  The bill is never lost.
     */
    private val createMutex = Mutex()
    suspend fun createRemotePrintJob(billId: Long): Result<String> = createMutex.withLock {
        try { createJob(billId) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.Error("Cannot queue print job: ${e.message}", e) }
    }

    private suspend fun createJob(billId: Long): Result<String> {
        // ── 1. Business-layer permission check ───────────────────────────────
        PermissionChecker.enforceRemotePrint(session.current)

        // ── 2. Load bill ─────────────────────────────────────────────────────
        val bill = billingRepository.getBillById(billId)
            ?: return Result.Error("Bill not found: $billId")

        // ── 3. Load shop device + settings ───────────────────────────────────
        val device   = shopDeviceDao.getRegisteredDevice() ?: when(val refreshed=refreshRegisteredPrinter()) {
            is Result.Success -> refreshed.data
            is Result.Error -> return Result.Error(refreshed.message)
            else -> return Result.Error("Register the phone beside the shop printer first")
        }
        val shopId   = device.shopId
        val shopInfo = settingsRepository.getShopInfo()

        // ── 4. Ensure Firebase auth ───────────────────────────────────────────
        val authResult = firebaseManager.ensureSignedIn()
        if (authResult is Result.Error) {
            return Result.Error("Firebase auth failed: ${authResult.message}")
        }
        val uid = firebaseManager.currentUid ?: return Result.Error("Not authenticated")

        // ── 5. Build receipt payload (same bytes as local Bluetooth print) ────
        val receiptBytes = try {
            withContext(Dispatchers.Default) { ReceiptBuilder.buildReceipt(bill, shopInfo) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Timber.e(e, "buildReceipt failed")
            return Result.Error("Failed to build receipt: ${e.message}")
        }
        val receiptBase64 = Base64.encodeToString(receiptBytes, Base64.NO_WRAP)

        // ── 6. Idempotency: check if a non-failed job already exists ──────────
        val existingJob = remotePrintJobDao.getLatestJobForBill(billId)
        if (existingJob?.status == "PENDING") {
            com.hardwareshop.pos.service.CloudSyncScheduler.requestRemotePrintUpload(context)
            return Result.Success(existingJob.jobId)
        }
        if (existingJob != null) {
            return Result.Error(
                "Bill #${bill.billNumber} already has a ${existingJob.status} print job. " +
                "Check the print queue or retry from there."
            )
        }

        // ── 7. Generate stable job ID ─────────────────────────────────────────
        val jobId = UUID.randomUUID().toString()

        // ── 8. Persist locally first (bill is never lost even if network fails) ─
        remotePrintJobDao.insertJob(
            RemotePrintJobEntity(
                jobId                = jobId,
                shopId               = shopId,
                billId               = billId,
                billNumber           = bill.billNumber,
                receiptPayloadBase64 = receiptBase64,
                status               = RemotePrintStatus.PENDING.name,
                deviceId             = device.deviceId,
                createdByUserId      = uid
            )
        )

        // ── 9. Upload to Firestore ────────────────────────────────────────────
        val uploadResult = firebaseManager.uploadPrintJob(
            shopId          = shopId,
            jobId           = jobId,
            billId          = billId,
            billNumber      = bill.billNumber,
            receiptBase64   = receiptBase64,
            deviceId        = device.deviceId,
            createdByUserId = uid
        )
        if (uploadResult is Result.Error) {
            // Job remains PENDING in Room — ShopPrintAgentService will push it when online
            Timber.w("Firestore upload failed — job queued offline: ${uploadResult.message}")
            remotePrintJobDao.recordRetryError(jobId, uploadResult.message)
            com.hardwareshop.pos.service.CloudSyncScheduler.requestRemotePrintUpload(context)
        }

        // ── 10. Audit ─────────────────────────────────────────────────────────
        auditRepository.log(
            actionType = "REMOTE_PRINT_REQUESTED",
            entityType = "BILL",
            entityId   = billId,
            billId     = billId,
            afterValue = "Job $jobId → device ${device.deviceName}",
            reason     = "Remote print for bill #${bill.billNumber}"
        )

        Timber.i("Remote print job created: $jobId for bill #${bill.billNumber}")
        return Result.Success(jobId)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OWNER — RETRY FAILED JOB
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun retryJob(jobId: String): Result<Unit> {
        PermissionChecker.enforceRemotePrint(session.current)
        return try {
            val job = remotePrintJobDao.getJobById(jobId) ?: return Result.Error("Job not found")
            val bill = billingRepository.getBillById(job.billId) ?: return Result.Error("Bill not found")
            val shopInfo = settingsRepository.getShopInfo()
            val bytes = withContext(Dispatchers.Default) { ReceiptBuilder.buildReceipt(bill, shopInfo, copy = true) }
            val result = firebaseManager.resetJobToPending(job.shopId, jobId, Base64.encodeToString(bytes, Base64.NO_WRAP))
            if (result is Result.Error) return result
            remotePrintJobDao.retryJob(jobId)
            Result.Success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.Error("Cannot retry job: ${e.message}", e) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SHOP DEVICE — REGISTRATION
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun registerThisDevice(shopId: String, deviceName: String): Result<Unit> {
        PermissionChecker.enforceManageRemoteDevice(session.current)

        if (shopId.isBlank() || shopId.contains('/')) return Result.Error("Enter a valid Shop ID")
        val authResult = firebaseManager.ensureSignedIn()
        if (authResult is Result.Error) return authResult

        try { shopLink.link(shopId) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { return Result.Error("Cannot link shop data: ${e.message}", e) }

        // Stable device identifier — survives app reinstall on same phone
        val deviceId = printerDeviceId(context)?.takeIf { it.isNotBlank() }
            ?: return Result.Error("Cannot identify this phone. Restart it before registering the printer.")

        val fcmToken = firebaseManager.getFcmToken() ?: ""

        val result = firebaseManager.registerDevice(shopId, deviceId, deviceName, fcmToken)
        if (result is Result.Error) return result

        db.withTransaction {
        shopDeviceDao.unregisterAllDevices()
        shopDeviceDao.insertOrUpdateDevice(
            ShopDeviceEntity(
                deviceId     = deviceId,
                deviceName   = deviceName,
                fcmToken     = fcmToken,
                shopId       = shopId,
                isRegistered = true
            )
        )

        }
        auditRepository.log(
            actionType = "REMOTE_DEVICE_REGISTERED",
            entityType = "SYSTEM",
            afterValue = "Device: $deviceName ($deviceId) for shop $shopId"
        )
        return Result.Success(Unit)
    }

    suspend fun linkSender(shopId: String): Result<Unit> {
        PermissionChecker.enforceOwner(session.current)
        return try {
            require(shopId.isNotBlank() && !shopId.contains('/')) { "Enter a valid Shop ID" }
            val auth = firebaseManager.ensureSignedIn()
            if (auth is Result.Error) return auth
            val device = firebaseManager.fetchRegisteredDevice(shopId)
                ?: return Result.Error("Register the printer phone for this Shop ID first")
            shopLink.link(shopId)
            com.hardwareshop.pos.service.ShopPrintAgentService.stop(context)
            db.withTransaction {
                shopDeviceDao.unregisterAllDevices()
                shopDeviceDao.insertOrUpdateDevice(device)
            }
            Result.Success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.Error("Could not link printer: ${e.message}", e) }
    }

    /** Download the authoritative printer target; never register or replace a cloud device. */
    suspend fun refreshRegisteredPrinter(): Result<ShopDeviceEntity> {
        check(session.isLoggedIn) { "Sign in before checking the shop printer" }
        return try {
            val shopId=com.hardwareshop.pos.data.sync.SyncJournal(db).linkedStore()
                ?: shopDeviceDao.getRegisteredDevice()?.shopId
                ?: return Result.Error("Connect this phone to the shop account first")
            firebaseManager.verifyPrintAccess(shopId)
            val device=firebaseManager.fetchRegisteredDevice(shopId)
                ?: return Result.Error("Register the phone beside the shop printer first")
            db.withTransaction {
                shopDeviceDao.unregisterAllDevices()
                shopDeviceDao.insertOrUpdateDevice(device)
            }
            return Result.Success(device)
        } catch(e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.Error("Printer check timed out. Check internet and try Refresh shop printer.",e)
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { Result.Error("Cannot refresh shop printer: ${e.message}",e) }
    }

    suspend fun unregisterDevice(): Result<Unit> {
        PermissionChecker.enforceManageRemoteDevice(session.current)
        val device = shopDeviceDao.getRegisteredDevice()
        shopDeviceDao.unregisterAllDevices()
        auditRepository.log(
            actionType = "REMOTE_DEVICE_UNREGISTERED",
            entityType = "SYSTEM",
            afterValue = device?.deviceName ?: "unknown"
        )
        return Result.Success(Unit)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OBSERVE (owner UI)
    // ─────────────────────────────────────────────────────────────────────────

    fun observeRecentJobs(): Flow<List<RemotePrintJob>> = channelFlow {
        launch {
            remotePrintJobDao.observeRecentJobs().collect { list ->
                send(list.map { e -> RemotePrintJob(e.jobId, e.shopId, e.billId, e.billNumber,
                    RemotePrintStatus.from(e.status), e.retryCount, e.lastError, e.createdAt, e.printedAt) })
            }
        }
        launch {
            shopDeviceDao.observeRegisteredDevice().collectLatest { device ->
                if (device != null) firebaseManager.observeRecentJobUpdates(device.shopId)
                    .retryWhen { _, _ -> delay(3_000); true }
                    .collect { jobs ->
                        jobs.forEach { data ->
                            val id = data["jobId"] as? String ?: return@forEach
                            val status = data["status"] as? String ?: return@forEach
                            val printedAt = (data["printedAt"] as? com.google.firebase.Timestamp)?.toDate()?.time
                            remotePrintJobDao.syncStatus(id, status, printedAt, data["lastError"] as? String)
                        }
                    }
            }
        }
    }

    fun observeJobForBill(billId: Long): Flow<RemotePrintJob?> = channelFlow {
        launch {
            remotePrintJobDao.observeJobForBill(billId).collect { e ->
                send(e?.let { RemotePrintJob(it.jobId, it.shopId, it.billId, it.billNumber,
                    RemotePrintStatus.from(it.status), it.retryCount, it.lastError, it.createdAt, it.printedAt) })
            }
        }
        launch {
            remotePrintJobDao.observeJobForBill(billId).map { it?.let { e -> e.shopId to e.jobId } }
                .distinctUntilChanged().collectLatest { key ->
                    if (key != null) firebaseManager.observeJobStatus(key.first, key.second)
                        .retryWhen { _, _ -> delay(3_000); true }.collect { data ->
                            val status = data?.get("status") as? String ?: return@collect
                            val printedAt = (data["printedAt"] as? com.google.firebase.Timestamp)?.toDate()?.time
                            remotePrintJobDao.syncStatus(key.second, status, printedAt, data["lastError"] as? String)
                        }
                }
        }
    }

    fun observeRegisteredDevice(): Flow<ShopDeviceEntity?> =
        shopDeviceDao.observeRegisteredDevice()

    fun observeShopDeviceLive(shopId: String): Flow<ShopDevice?> =
        firebaseManager.observeShopDevice(shopId)

    fun observePendingJobCount(): Flow<Int> =
        remotePrintJobDao.observePendingJobCount()

    // ─────────────────────────────────────────────────────────────────────────
    // SYNC — push pending offline jobs to Firestore when internet returns
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun syncPendingJobsToFirestore(): Result<Int> {
        PermissionChecker.enforceOwner(session.current)
        return recoverAuthoredPrintJobs()
    }

    /** No in-memory UI login required. Server membership and job authorship are mandatory. */
    suspend fun recoverAuthoredPrintJobs(): Result<Int> { return try {
        val queued = remotePrintJobDao.getPendingJobs()
        if (queued.isEmpty()) return Result.Success(0)
        check(firebaseManager.ensureSignedIn() is Result.Success) { "Firebase sign-in is unavailable" }
        val pendingJobs = queued.filter {
            RemotePrintRecoveryPolicy.mayReplay(it.createdByUserId, firebaseManager.currentUid)
        }
        val verifiedShops = mutableSetOf<String>()
        var failed = 0
        for (job in pendingJobs) {
            if (job.shopId !in verifiedShops) {
                try {
                    firebaseManager.verifyPrintAccess(job.shopId, sending = true)
                    verifiedShops += job.shopId
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    remotePrintJobDao.recordRetryError(job.jobId, "Upload blocked: ${e.message}")
                    failed++
                    continue
                }
            }
            val result = firebaseManager.uploadPrintJob(
                shopId = job.shopId, jobId = job.jobId, billId = job.billId,
                billNumber = job.billNumber, receiptBase64 = job.receiptPayloadBase64,
                deviceId = job.deviceId, createdByUserId = job.createdByUserId)
            if (result is Result.Error) {
                failed++
                remotePrintJobDao.recordRetryError(job.jobId, result.message)
            } else remotePrintJobDao.recordRetryError(job.jobId, null)
        }
        if (failed == 0) Result.Success(pendingJobs.size)
        else Result.Error("$failed job(s) could not sync. They remain saved on this device; try again when online.")
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.Error("Sync failed: ${e.message}", e) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HOUSEKEEPING
    // ─────────────────────────────────────────────────────────────────────────

}

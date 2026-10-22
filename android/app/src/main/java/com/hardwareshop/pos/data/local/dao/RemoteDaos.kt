package com.hardwareshop.pos.data.local.dao

import androidx.room.*
import com.hardwareshop.pos.data.local.entity.*
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
// REMOTE PRINT JOB DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface RemotePrintJobDao {
    @Query("DELETE FROM remote_print_jobs")
    suspend fun deleteAllJobs()


    // ── Insert (IGNORE if same jobId already exists — idempotent) ────────────
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJob(job: RemotePrintJobEntity): Long

    // ── Fetch ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM remote_print_jobs WHERE jobId = :jobId LIMIT 1")
    suspend fun getJobById(jobId: String): RemotePrintJobEntity?

    @Query("SELECT * FROM remote_print_jobs WHERE billId = :billId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestJobForBill(billId: Long): RemotePrintJobEntity?

    /** Jobs the shop device should process */
    @Query("""
        SELECT * FROM remote_print_jobs
        WHERE status IN ('PENDING','RECEIVED','PRINTING')
        ORDER BY createdAt ASC
    """)
    suspend fun getActiveJobs(): List<RemotePrintJobEntity>

    @Query("SELECT * FROM remote_print_jobs WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingJobs(): List<RemotePrintJobEntity>

    /** Retry diagnostics do not change the claim or imply that paper was printed. */
    @Query("UPDATE remote_print_jobs SET lastError = :message WHERE jobId = :jobId AND status IN ('PENDING','RECEIVED','PRINTING')")
    suspend fun recordRetryError(jobId: String, message: String?)

    // ── Observe (owner UI) ───────────────────────────────────────────────────

    @Query("SELECT * FROM remote_print_jobs ORDER BY createdAt DESC LIMIT 50")
    fun observeRecentJobs(): Flow<List<RemotePrintJobEntity>>

    @Query("SELECT * FROM remote_print_jobs WHERE billId = :billId ORDER BY createdAt DESC LIMIT 1")
    fun observeJobForBill(billId: Long): Flow<RemotePrintJobEntity?>

    @Query("""
        SELECT COUNT(*) FROM remote_print_jobs WHERE status IN ('PENDING','RECEIVED','PRINTING')
    """)
    fun observePendingJobCount(): Flow<Int>

    @Query("UPDATE remote_print_jobs SET status = 'RECEIVED', lastError = NULL WHERE jobId = :jobId")
    suspend fun setClaimed(jobId: String)

    // ── Status transitions (idempotent — use conditional WHERE) ─────────────

    /** PENDING → RECEIVED: atomic on shop device, prevents double-processing */
    @Query("""
        UPDATE remote_print_jobs
        SET status = 'RECEIVED'
        WHERE jobId = :jobId AND status = 'PENDING'
    """)
    suspend fun transitionToReceived(jobId: String): Int  // Returns rows changed (0 = already processed)

    @Query("UPDATE remote_print_jobs SET status = 'PRINTING' WHERE jobId = :jobId AND status = 'RECEIVED'")
    suspend fun transitionToPrinting(jobId: String): Int

    @Query("""
        UPDATE remote_print_jobs
        SET status = 'PRINTED', printedAt = :now, lastError = NULL
        WHERE jobId = :jobId AND status != 'PRINTED'
    """)
    suspend fun markPrinted(jobId: String, now: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE remote_print_jobs
        SET status = 'FAILED', lastError = :error, retryCount = retryCount + 1
        WHERE jobId = :jobId AND status != 'PRINTED'
    """)
    suspend fun markFailed(jobId: String, error: String)

    /** Owner retries a FAILED job — resets to PENDING, increments retryCount */
    @Query("""
        UPDATE remote_print_jobs
        SET status = 'PENDING', lastError = NULL, retryCount = retryCount + 1
        WHERE jobId = :jobId AND status IN ('FAILED','RECEIVED','PRINTING')
    """)
    suspend fun retryJob(jobId: String)

    /** Update status from Firestore sync (owner-side polling) */
    @Query("UPDATE remote_print_jobs SET status = :status, printedAt = :printedAt, lastError = :error WHERE jobId = :jobId AND (status != 'PRINTED' OR :status = 'PRINTED') AND NOT (status = 'FAILED' AND :status IN ('PENDING','RECEIVED','PRINTING'))")
    suspend fun syncStatus(jobId: String, status: String, printedAt: Long?, error: String?)

    // ── Idempotency check (shop device) ──────────────────────────────────────

    /** Returns true if this job is already PRINTED — never print twice */
    @Query("SELECT COUNT(*) FROM remote_print_jobs WHERE jobId = :jobId AND status = 'PRINTED'")
    suspend fun isAlreadyPrinted(jobId: String): Int

    // ── Housekeeping ─────────────────────────────────────────────────────────

    /** Remove PRINTED jobs older than 7 days to keep the table lean */
    @Query("DELETE FROM remote_print_jobs WHERE status = 'PRINTED' AND createdAt < :before")
    suspend fun cleanOldPrintedJobs(before: Long)
}


// ─────────────────────────────────────────────────────────────────────────────
// SHOP DEVICE DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface ShopDeviceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateDevice(device: ShopDeviceEntity)

    @Query("SELECT * FROM shop_devices WHERE isRegistered = 1 LIMIT 1")
    fun observeRegisteredDevice(): Flow<ShopDeviceEntity?>

    @Query("SELECT * FROM shop_devices WHERE isRegistered = 1 LIMIT 1")
    suspend fun getRegisteredDevice(): ShopDeviceEntity?

    @Query("SELECT * FROM shop_devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getDeviceById(deviceId: String): ShopDeviceEntity?

    @Query("UPDATE shop_devices SET isRegistered = 0")
    suspend fun unregisterAllDevices()

    @Query("UPDATE shop_devices SET fcmToken = :token WHERE deviceId = :deviceId")
    suspend fun updateFcmToken(deviceId: String, token: String)

    @Query("UPDATE shop_devices SET lastHeartbeat = :ts WHERE deviceId = :deviceId")
    suspend fun updateHeartbeat(deviceId: String, ts: Long = System.currentTimeMillis())
}

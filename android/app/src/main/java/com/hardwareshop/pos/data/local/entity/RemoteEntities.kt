package com.hardwareshop.pos.data.local.entity

import androidx.room.*

// ─────────────────────────────────────────────────────────────────────────────
// REMOTE PRINT JOB ENTITY
//
// Persisted on BOTH the owner device and the shop print agent device.
//   Owner device:     tracks jobs it sent (status observed from Firestore)
//   Shop device:      tracks jobs it received (idempotency — never print twice)
//
// jobId is a UUID string — guaranteed unique across restarts/retries.
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "remote_print_jobs",
    indices   = [
        Index("status"),
        Index("billId"),
        Index("shopId"),
        Index("createdAt")
    ]
)
data class RemotePrintJobEntity(
    @PrimaryKey
    val jobId:              String,         // UUID — stable across retries
    val shopId:             String,
    val billId:             Long,
    val billNumber:         String,
    /** ESC/POS byte array encoded as Base64 — ready for direct Bluetooth write */
    val receiptPayloadBase64: String,
    /** RemotePrintStatus.name */
    val status:             String = "PENDING",
    val deviceId:           String = "",
    val createdByUserId:    String = "",
    val createdAt:          Long   = System.currentTimeMillis(),
    val printedAt:          Long?  = null,
    val lastError:          String? = null,
    val retryCount:         Int    = 0
)


// ─────────────────────────────────────────────────────────────────────────────
// SHOP DEVICE ENTITY
//
// Stored on both devices:
//   Owner device: knows which device is the registered shop printer
//   Shop device:  knows its own registration details / shopId
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "shop_devices")
data class ShopDeviceEntity(
    @PrimaryKey
    val deviceId:       String,
    val deviceName:     String,
    val fcmToken:       String  = "",
    val shopId:         String,
    val isRegistered:   Boolean = false,
    val registeredAt:   Long    = System.currentTimeMillis(),
    val lastHeartbeat:  Long?   = null
)

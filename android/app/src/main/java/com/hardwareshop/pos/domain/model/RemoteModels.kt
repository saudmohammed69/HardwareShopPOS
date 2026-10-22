package com.hardwareshop.pos.domain.model

// ─────────────────────────────────────────────────────────────────────────────
// REMOTE PRINT JOB STATUS  (one-way state machine)
//
//  PENDING → RECEIVED → PRINTING → PRINTED
//              ↘ FAILED (retryable back to PENDING)
// ─────────────────────────────────────────────────────────────────────────────

enum class RemotePrintStatus(val displayLabel: String, val emoji: String) {
    PENDING("Waiting for shop printer", "⏳"),
    RECEIVED("Received by shop device",  "📥"),
    PRINTING("Printing…",               "🖨️"),
    PRINTED("Sent to printer",                  "✅"),
    FAILED("Print failed",              "❌");

    companion object {
        fun from(name: String): RemotePrintStatus =
            entries.firstOrNull { it.name == name } ?: PENDING
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REMOTE PRINT JOB  (domain model)
// ─────────────────────────────────────────────────────────────────────────────

data class RemotePrintJob(
    val jobId:      String,
    val shopId:     String,
    val billId:     Long,
    val billNumber: String,
    val status:     RemotePrintStatus,
    val retryCount: Int,
    val lastError:  String?,
    val createdAt:  Long,
    val printedAt:  Long?
)

// ─────────────────────────────────────────────────────────────────────────────
// SHOP DEVICE  (domain model)
// ─────────────────────────────────────────────────────────────────────────────

data class ShopDevice(
    val deviceId:                String,
    val deviceName:              String,
    val shopId:                  String,
    val isOnline:                Boolean,
    val bluetoothPrinterName:    String,
    val bluetoothPrinterConnected: Boolean,
    val lastHeartbeatMs:         Long?,
    val pendingJobCount:         Int = 0
) {
    /** True if device reported a heartbeat within the last 90 seconds */
    val isRecentlyOnline: Boolean
        get() = isRecentlyOnlineAt(System.currentTimeMillis())

    fun isRecentlyOnlineAt(nowMs: Long): Boolean = lastHeartbeatMs?.let {
        it > 0L && nowMs - it < 90_000L
    } ?: false
}

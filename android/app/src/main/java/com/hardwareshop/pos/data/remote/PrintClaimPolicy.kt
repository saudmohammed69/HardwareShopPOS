package com.hardwareshop.pos.data.remote

/** Pure policy; Firestore evaluates this inside its transaction on every retry. */
object PrintClaimPolicy {
    const val RECEIVE_LEASE_MS = 45_000L
    const val PRINT_LEASE_MS = 180_000L
    const val UNCERTAIN = "Print outcome unknown. Check the paper before requesting a copy."
    enum class Decision { ACQUIRE, BUSY, ALREADY_SENT, FAILED, REVIEW, MISSING }

    fun decide(status: String?, holder: String?, token: String, leaseUntil: Long, now: Long): Decision =
        when (status) {
            "PENDING" -> Decision.ACQUIRE
            "RECEIVED" -> if (holder == token || leaseUntil <= now) Decision.ACQUIRE else Decision.BUSY
            // A restarted worker must not repeat a write whose outcome it cannot establish.
            "PRINTING" -> if (holder == token || leaseUntil <= now) Decision.REVIEW else Decision.BUSY
            "PRINTED" -> Decision.ALREADY_SENT
            "FAILED" -> Decision.FAILED
            else -> Decision.MISSING
        }
}

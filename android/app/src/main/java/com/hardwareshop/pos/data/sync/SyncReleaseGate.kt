package com.hardwareshop.pos.data.sync

/** Enabled 6 October 2026 by owner decision: full two-phone sync requested.
 * First-link catalogue/financial flows apply automatically; stock uses the
 * owner-phone baseline plus post-link deltas, parking genuine conflicts for review.
 */
object SyncReleaseGate {
    const val READY = true
    const val MESSAGE = "Cloud data sync is not ready yet. Your bills and stock remain saved on this phone."
}



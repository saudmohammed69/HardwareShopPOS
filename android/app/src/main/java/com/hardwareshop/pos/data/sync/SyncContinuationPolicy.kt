package com.hardwareshop.pos.data.sync

object SyncContinuationPolicy {
    /** Do not spin forever on unresolved dependencies or owner-review conflicts. */
    fun shouldContinue(uploaded: Int, received: Int, remainingLocal: Int, queued: Int,
                       incomingWaiting: Boolean, fullDownloadPage: Boolean): Boolean =
        fullDownloadPage || ((uploaded > 0 || received > 0) &&
            (remainingLocal > 0 || queued > 0 || incomingWaiting))
}

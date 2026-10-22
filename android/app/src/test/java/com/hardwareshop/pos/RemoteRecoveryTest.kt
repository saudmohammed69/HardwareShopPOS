package com.hardwareshop.pos

import com.hardwareshop.pos.data.remote.RemotePrintRecoveryPolicy
import com.hardwareshop.pos.data.remote.PrintClaimPolicy
import com.hardwareshop.pos.data.sync.SyncContinuationPolicy
import org.junit.Assert.*
import org.junit.Test

class RemoteRecoveryTest {
    @Test fun backgroundRecoveryRequiresOriginalCloudAuthorNotUiSession() {
        assertTrue(RemotePrintRecoveryPolicy.mayReplay("owner-uid", "owner-uid"))
        assertFalse(RemotePrintRecoveryPolicy.mayReplay("owner-uid", "worker-uid"))
        assertFalse(RemotePrintRecoveryPolicy.mayReplay("", "owner-uid"))
        assertFalse(RemotePrintRecoveryPolicy.mayReplay("owner-uid", null))
    }
    @Test fun onlyRegisteredPhoneMayClaimOrAdvertisePrinter() {
        assertTrue(RemotePrintRecoveryPolicy.isRegisteredPrinter("worker-phone", "worker-phone"))
        assertFalse(RemotePrintRecoveryPolicy.isRegisteredPrinter("owner-phone", "worker-phone"))
        assertFalse(RemotePrintRecoveryPolicy.isRegisteredPrinter(null, null))
        assertFalse(RemotePrintRecoveryPolicy.isRegisteredPrinter("", ""))
    }
    @Test fun backlogContinuesAfterProductTransfer() {
        assertTrue(SyncContinuationPolicy.shouldContinue(500, 775, 1503, 444, true, false))
        assertTrue(SyncContinuationPolicy.shouldContinue(0, 200, 0, 0, true, false))
        assertTrue(SyncContinuationPolicy.shouldContinue(0, 0, 0, 0, false, true))
    }
    @Test fun noBusyLoopWhenEverythingNeedsReviewOrIsFinished() {
        assertFalse(SyncContinuationPolicy.shouldContinue(0, 0, 200, 100, true, false))
        assertFalse(SyncContinuationPolicy.shouldContinue(100, 200, 0, 0, false, false))
    }
    @Test fun safeConnectionRetryDoesNotAuthorizeDuplicateReceipt() {
        assertEquals(PrintClaimPolicy.Decision.ACQUIRE, PrintClaimPolicy.decide("RECEIVED", "same", "same", 1000, 1))
        assertEquals(PrintClaimPolicy.Decision.BUSY, PrintClaimPolicy.decide("RECEIVED", "other", "same", 1000, 1))
        assertEquals(PrintClaimPolicy.Decision.REVIEW, PrintClaimPolicy.decide("PRINTING", "same", "same", 1000, 1))
        assertEquals(PrintClaimPolicy.Decision.ALREADY_SENT, PrintClaimPolicy.decide("PRINTED", "same", "same", 0, 10))
    }
}

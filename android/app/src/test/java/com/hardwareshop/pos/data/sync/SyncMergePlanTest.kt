package com.hardwareshop.pos.data.sync

import org.junit.Assert.*
import org.junit.Test

class SyncMergePlanTest {
    private val a=SyncMergePlan.Product("product_phone_a_01","phone-a",2,"Plumbing","PVC","Elbow","1/2 inch","Piece",100.0)
    private val b=a.copy(syncId="product_phone_b_01",deviceId="phone-b",stock=95.0)

    @Test fun identicalCatalogueIsProposedForReviewWithoutSummingStock() {
        val group=SyncMergePlan.candidates(listOf(a,b)).single()
        val decision=SyncMergePlan.StockDecision(mapOf(a.syncId to 100.0,b.syncId to 95.0),
            mapOf(a.syncId to 2L,b.syncId to 2L),92.0,mapOf(a.syncId to 0L,b.syncId to 0L))
        assertEquals(92.0,SyncMergePlan.approve(group,a.syncId,decision).stock,0.0)
        assertEquals(setOf(b.syncId),SyncMergePlan.approve(group,a.syncId,decision).aliases)
    }

    @Test fun differentMaterialSizeAndUnitStaySeparate() {
        for (other in listOf(b.copy(type="CPVC"),b.copy(size="3/4 inch"),b.copy(unit="Foot"))) {
            assertTrue(SyncMergePlan.candidates(listOf(a,other)).isEmpty())
        }
    }

    @Test fun spacingAndCaseDoNotHideExactCandidate() {
        assertEquals(1,SyncMergePlan.candidates(listOf(a,b.copy(name=" ELBOW  ",category="plumbing"))).size)
    }

    @Test(expected=IllegalArgumentException::class) fun saleDuringReviewRequiresRefresh() {
        val decision=SyncMergePlan.StockDecision(mapOf(a.syncId to 100.0,b.syncId to 95.0),
            mapOf(a.syncId to 2L,b.syncId to 2L),95.0,mapOf(a.syncId to 0L,b.syncId to 0L))
        SyncMergePlan.approve(SyncMergePlan.CandidateGroup(listOf(a,b.copy(revision=3,stock=94.0))),a.syncId,decision)
    }

    @Test(expected=IllegalArgumentException::class) fun incompleteReviewCannotBeApproved() {
        SyncMergePlan.approve(SyncMergePlan.CandidateGroup(listOf(a,b)),a.syncId,
            SyncMergePlan.StockDecision(mapOf(a.syncId to 100.0),mapOf(a.syncId to 2L),100.0,mapOf(a.syncId to 0L)))
    }

    @Test fun bothPhonesBillNumberOneSurvivesAndRepeatedDeliveryDoesNotDuplicate() {
        val first=SyncProtocol.Record("bill_phone_a_0001","bills",SyncProtocol.Scope.PUBLIC,1,false,"uid-a",
            mapOf("billNumber" to "000001","totalPaisa" to 1500L))
        val second=first.copy(recordId="bill_phone_b_0001",authorUid="uid-b")
        assertEquals(listOf(first,second),SyncMergePlan.combineHistory(listOf(first,second,first)))
    }

    @Test(expected=IllegalArgumentException::class) fun conflictingCopiesOfSameHistoryRequireReview() {
        val first=SyncProtocol.Record("bill_phone_a_0001","bills",SyncProtocol.Scope.PUBLIC,1,false,"uid-a",
            mapOf("billNumber" to "000001"))
        SyncMergePlan.combineHistory(listOf(first,first.copy(fields=mapOf("billNumber" to "changed"))))
    }

    @Test(expected=IllegalArgumentException::class) fun saleAndReturnToSameQuantityStillInvalidatesStockReview() {
        val decision=SyncMergePlan.StockDecision(mapOf(a.syncId to 100.0,b.syncId to 95.0),
            mapOf(a.syncId to 2L,b.syncId to 2L),95.0,mapOf(a.syncId to 0L,b.syncId to 0L))
        SyncMergePlan.approve(SyncMergePlan.CandidateGroup(listOf(a,b.copy(stockRevision=2))),a.syncId,decision)
    }
}

package com.hardwareshop.pos.data.sync

import org.junit.Assert.*
import org.junit.Test

class SyncProtocolTest {
    private val owner = SyncProtocol.Actor("owner-uid",SyncProtocol.Role.OWNER)
    private val worker = SyncProtocol.Actor("worker-uid",SyncProtocol.Role.WORKER)
    private fun mutation(entity: String = "products", actor: SyncProtocol.Actor = owner,
                         operation: String = "operation_00000001"): SyncProtocol.Mutation {
        val spec = SyncWireSchema.byName.getValue(entity)
        val fields = spec.required.associateWith { field ->
            if (field in spec.references) "global_reference_00001" else when (SyncWireSchema.kind(entity,field)) {
                "INTEGER" -> 0L
                "REAL" -> 0.0
                else -> "Value"
            }
        }.toMutableMap()
        if (entity == "stock_movements") { fields["kind"]="DELTA"; fields["delta"]=-3.0 }
        if (entity == "settings") fields["key"]="shop_name"
        if (entity == "customers") fields["isActive"]=1L
        return SyncProtocol.Mutation(operation,"record_00000000001",entity,spec.scope,actor.uid,0,false,fields)
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun retryAfterAnotherDeviceEditReturnsOriginalReceipt() {
        val m=mutation()
        val first=SyncProtocol.decide(owner,m,null,null)
        val second=m.copy(operationId="operation_00000002",baseVersion=1,fields=m.fields+("sellingPricePaisa" to 1800L))
        val newer=SyncProtocol.nextRecord(SyncProtocol.decide(owner,second,SyncProtocol.nextRecord(first),null))
        assertEquals(first,SyncProtocol.decide(owner,m,newer,first))
        assertEquals(2,newer.version)
    }

    @Test fun manualBillReferenceFitsExistingWorkerWireContract() {
        val original = mutation("bill_items", worker)
        val manual = original.copy(fields = original.fields + mapOf("productId" to SyncWireSchema.MANUAL_PRODUCT_REFERENCE,
            "quantity" to 1.0, "unitPricePaisa" to 11800L, "itemTotalPaisa" to 11800L,
            "gstRateBp" to 1800L, "gstAmountPaisa" to 1800L))
        SyncProtocol.decide(worker, manual, null, null)
        assertEquals(manual, SyncWireJson.decode(SyncWireJson.encode(manual)))
    }

    @Test fun sameOperationIdWithChangedPayloadRejected() {
        val m=mutation(); val receipt=SyncProtocol.decide(owner,m,null,null)
        rejected { SyncProtocol.decide(owner,m.copy(fields=m.fields+("sellingPricePaisa" to 1L)),null,receipt) }
    }

    @Test fun concurrentPriceChangesBecomeConflictRatherThanOverwrite() {
        val m=mutation(); val first=SyncProtocol.decide(owner,m,null,null)
        try {
            SyncProtocol.decide(owner,m.copy(operationId="operation_00000002"),SyncProtocol.nextRecord(first),null)
            fail("Stale write must be retained for owner review")
        } catch (conflict: SyncProtocol.Conflict) { assertEquals(1,conflict.remote.version) }
    }

    @Test fun phoneClockCannotOverrideServerVersion() {
        val m=mutation(); val first=SyncProtocol.decide(owner,m,null,null)
        try {
            SyncProtocol.decide(owner,m.copy(operationId="operation_00000002",fields=m.fields+("updatedAt" to Long.MAX_VALUE)),SyncProtocol.nextRecord(first),null)
            fail("Future phone timestamp must not bypass a version conflict")
        } catch (_: SyncProtocol.Conflict) { }
    }

    @Test fun absoluteStockNeverTravelsInProductSnapshot() {
        val m=mutation()
        rejected { SyncProtocol.validate(m.copy(fields=m.fields+("currentStock" to 100.0))) }
    }

    @Test fun buyingPriceNeverTravelsInPublicProduct() {
        val m=mutation()
        rejected { SyncProtocol.validate(m.copy(fields=m.fields+("purchasePricePaisa" to 1000L))) }
    }

    @Test fun credentialsCannotEnterWorkerProfile() {
        val m=mutation("workers")
        rejected { SyncProtocol.validate(m.copy(fields=m.fields+("passwordHash" to "secret"))) }
        rejected { SyncProtocol.validate(m.copy(fields=m.fields+("passwordSalt" to "secret"))) }
    }

    @Test fun workerCannotEditWorkerPermissionsOrCosts() {
        rejected { SyncProtocol.decide(worker,mutation("workers",worker),null,null) }
        rejected { SyncProtocol.decide(worker,mutation("product_costs",worker),null,null) }
    }

    @Test fun workerCannotClaimAnotherAuthenticatedAuthor() {
        rejected { SyncProtocol.decide(worker,mutation("bills",owner),null,null) }
    }

    @Test fun workerCanCreateAndRetryPendingProduct() {
        val m=mutation(actor=worker)
        val result=SyncProtocol.decide(worker,m,null,null)
        assertEquals(result,SyncProtocol.decide(worker,m,SyncProtocol.nextRecord(result),result))
        rejected { SyncProtocol.decide(worker,m.copy(fields=m.fields+("sellingPricePaisa" to 1500L)),null,null) }
    }

    @Test fun workerCannotIncreaseCreditLimit() {
        val m=mutation("customers",worker)
        rejected { SyncProtocol.decide(worker,m.copy(fields=m.fields+("creditLimitPaisa" to 10000L)),null,null) }
    }

    @Test fun workerCanDeductStockButCannotCreateOpeningBalance() {
        val m=mutation("stock_movements",worker)
        assertEquals(1,SyncProtocol.decide(worker,m,null,null).version)
        rejected { SyncProtocol.decide(worker,m.copy(fields=m.fields+("kind" to "OPENING")),null,null) }
        rejected { SyncProtocol.decide(worker,m.copy(fields=m.fields+("delta" to 3.0)),null,null) }
    }

    @Test fun immutablePaymentCannotBeChangedOrDeletedEvenByOwner() {
        val m=mutation("payments"); val first=SyncProtocol.decide(owner,m,null,null)
        rejected { SyncProtocol.decide(owner,m.copy(operationId="operation_00000002",baseVersion=1,
            fields=m.fields+("amountPaisa" to 999L)),SyncProtocol.nextRecord(first),null) }
        rejected { SyncProtocol.validate(m.copy(deleted=true,fields=emptyMap())) }
        rejected { SyncProtocol.validate(mutation("bills").copy(deleted=true,fields=emptyMap())) }
    }

    @Test fun aLocalNumericForeignKeyIsRejected() {
        val m=mutation()
        rejected { SyncProtocol.validate(m.copy(fields=m.fields+("categoryId" to 1L))) }
    }

    @Test fun requiredAndUnexpectedFieldsAreChecked() {
        val m=mutation()
        rejected { SyncProtocol.validate(m.copy(fields=m.fields-"name")) }
        rejected { SyncProtocol.validate(m.copy(fields=m.fields+("admin" to true))) }
        rejected { SyncProtocol.validate(m.copy(fields=m.fields+("sellingPricePaisa" to "1500"))) }
    }

    @Test fun tombstoneCannotBeResurrectedByLaterWrite() {
        val m=mutation("categories")
        val tombstone=SyncProtocol.nextRecord(SyncProtocol.decide(owner,m.copy(deleted=true,fields=emptyMap()),null,null))
        rejected { SyncProtocol.decide(owner,m.copy(baseVersion=1),tombstone,null) }
    }

    @Test fun paisaSurvivesJsonRoundTripAboveDoubleIntegerPrecision() {
        val m=mutation().let { it.copy(fields=it.fields+("sellingPricePaisa" to 9007199254740993L)) }
        assertEquals(m,SyncWireJson.decode(SyncWireJson.encode(m)))
    }

    @Test fun nonFiniteStockRejected() {
        val m=mutation("stock_movements")
        rejected { SyncProtocol.validate(m.copy(fields=m.fields+("delta" to Double.NaN))) }
    }

    @Test fun deviceSpecificSettingsRejected() {
        val m=mutation("settings")
        for (key in listOf("printer_mac","shop_id","bill_sequence","cart:1")) {
            rejected { SyncProtocol.validate(m.copy(fields=m.fields+("key" to key))) }
        }
    }

    @Test fun negativePriceAndInvalidBooleanRejected() {
        val m=mutation()
        rejected { SyncProtocol.validate(m.copy(fields=m.fields+("sellingPricePaisa" to -1L))) }
        rejected { SyncProtocol.validate(m.copy(fields=m.fields+("isActive" to 2L))) }
        rejected { SyncProtocol.validate(m.copy(fields=m.fields+("gstRateBp" to 10001L))) }
    }

    @Test fun jsonCannotCoerceStringIntoDeletionFlag() {
        val json=SyncWireJson.encode(mutation())
        rejected { SyncWireJson.decode(json.replace("\"deleted\":false","\"deleted\":\"false\"")) }
    }

    @Test fun realQuantitiesCanonicalizeAcrossFirebaseNumberRepresentations() {
        val m=mutation("stock_movements").let { it.copy(fields=it.fields+("delta" to -3L)) }
        assertEquals(-3.0,SyncWireJson.decode(SyncWireJson.encode(m)).fields["delta"])
    }

    @Test fun standaloneCreditRequiresSignedAmountAndExactPaisaArithmetic() {
        val m=mutation("credit_transactions").let { it.copy(fields=it.fields+mapOf(
            "type" to "PARTIAL_PAYMENT","amountPaisa" to -100L,
            "previousBalancePaisa" to 500L,"newBalancePaisa" to 400L)) }
        SyncProtocol.validate(m)
        rejected { SyncProtocol.validate(m.copy(fields=m.fields+("newBalancePaisa" to 401L))) }
        rejected { SyncProtocol.validate(m.copy(fields=m.fields+("type" to "CREDIT_CREATED"))) }
        rejected { SyncProtocol.validate(m.copy(fields=m.fields+mapOf("previousBalancePaisa" to Long.MIN_VALUE,
            "newBalancePaisa" to Long.MAX_VALUE))) }
    }

    @Test fun tombstoneCannotSmuggleBusinessValues() {
        rejected { SyncProtocol.validate(mutation("customers").copy(deleted=true)) }
    }
}

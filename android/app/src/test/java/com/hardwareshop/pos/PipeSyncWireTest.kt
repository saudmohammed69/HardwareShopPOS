package com.hardwareshop.pos
import com.hardwareshop.pos.data.sync.*
import org.junit.Test
import org.junit.Assert.*

class PipeSyncWireTest {
    private fun mutation(entity: String): SyncProtocol.Mutation {
        val spec=SyncWireSchema.byName.getValue(entity)
        val fields=spec.required.associateWith { f ->
            if (f in spec.references) "reference_global_001" else when(SyncWireSchema.kind(entity,f)) { "TEXT" -> "Value"; "REAL" -> 0.0; else -> 0L }
        }
        return SyncProtocol.Mutation("operation_global_001","record_global_00001",entity,spec.scope,"owner",0,false,fields)
    }
    @Test fun pipeSaleSnapshotsTravelAsTypedFields() {
        val m=mutation("bill_items").let { it.copy(fields=it.fields+mapOf("isPipe" to 1L,"pipeLengths" to 1L,"pipeFeet" to 10.0,"unitPricePaisa" to 40000L,"pipeFootPricePaisa" to 3500L,"itemTotalPaisa" to 75000L)) }
        SyncProtocol.validate(m)
        val receipt=SyncProtocol.decide(SyncProtocol.Actor("owner",SyncProtocol.Role.OWNER),m,null,null)
        assertEquals(10.0,receipt.mutation.fields["pipeFeet"])
        assertEquals(3500L,receipt.mutation.fields["pipeFootPricePaisa"])
        assertEquals(receipt,SyncProtocol.decide(SyncProtocol.Actor("owner",SyncProtocol.Role.OWNER),m,SyncProtocol.nextRecord(receipt),receipt))
    }
    @Test fun legacyFinancialRowsRemainValidWithoutPipeFields() { SyncProtocol.validate(mutation("bill_items")) }
    @Test(expected=IllegalArgumentException::class) fun mismatchedPipeAmountIsRejected() {
        val m=mutation("bill_items");SyncProtocol.validate(m.copy(fields=m.fields+mapOf("isPipe" to 1L,"pipeLengths" to 1L,"pipeFeet" to 10.0,"unitPricePaisa" to 40000L,"pipeFootPricePaisa" to 3500L,"itemTotalPaisa" to 40000L)))
    }
}

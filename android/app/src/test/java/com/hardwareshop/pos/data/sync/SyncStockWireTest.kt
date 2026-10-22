package com.hardwareshop.pos.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire-contract tests for the stock transport added with the release gate. */
class SyncStockWireTest {
    private val owner = SyncProtocol.Actor("owner_uid_000000000001", SyncProtocol.Role.OWNER)
    private val worker = SyncProtocol.Actor("worker_uid_00000000001", SyncProtocol.Role.WORKER)

    private fun movement(actor: SyncProtocol.Actor, kind: String, delta: Double) = SyncProtocol.Mutation(
        operationId = "op_${kind.lowercase()}_0000000001", recordId = "movement_id_0000000001",
        entity = "stock_movements", scope = SyncProtocol.Scope.PUBLIC, authorUid = actor.uid,
        baseVersion = 0, deleted = false,
        fields = mapOf("productSyncId" to "product_sync_id_000001", "delta" to delta, "kind" to kind))

    private fun baseline(actor: SyncProtocol.Actor, quantity: Double) = SyncProtocol.Mutation(
        operationId = "op_baseline_000000000001", recordId = "base_product_sync_id_000001",
        entity = "stock_baselines", scope = SyncProtocol.Scope.PUBLIC, authorUid = actor.uid,
        baseVersion = 0, deleted = false,
        fields = mapOf("productSyncId" to "product_sync_id_000001", "quantity" to quantity))

    @Test
    fun `owner publishes opening and positive movements`() {
        SyncProtocol.validate(movement(owner, "OPENING", 12.0))
        val receipt = SyncProtocol.decide(owner, movement(owner, "OPENING", 12.0), null, null)
        assertEquals(1L, receipt.version)
        SyncProtocol.validate(movement(owner, "DELTA", 5.0))
    }

    @Test
    fun `worker may only publish sales deltas`() {
        SyncProtocol.decide(worker, movement(worker, "DELTA", -3.0), null, null)
        assertThrows(IllegalArgumentException::class.java) {
            SyncProtocol.decide(worker, movement(worker, "DELTA", 3.0), null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncProtocol.decide(worker, movement(worker, "OPENING", 12.0), null, null)
        }
    }

    @Test
    fun `baselines are owner only and never deletable`() {
        SyncProtocol.validate(baseline(owner, 40.0))
        assertEquals(1L, SyncProtocol.decide(owner, baseline(owner, 40.0), null, null).version)
        assertThrows(IllegalArgumentException::class.java) {
            SyncProtocol.decide(worker, baseline(worker, 40.0), null, null)
        }
        assertTrue(SyncProtocol.ownerOnly.contains("stock_baselines"))
        // Tombstones are rejected: stock history can never be erased from the cloud.
        assertThrows(IllegalArgumentException::class.java) {
            SyncProtocol.validate(baseline(owner, 40.0).copy(deleted = true, fields = emptyMap()))
        }
    }

    @Test
    fun `baseline content is immutable once committed`() {
        val first = SyncProtocol.decide(owner, baseline(owner, 40.0), null, null)
        val current = SyncProtocol.nextRecord(first)
        assertEquals(first.version, SyncProtocol.decide(owner, baseline(owner, 40.0), null, first).version)
        assertThrows(IllegalArgumentException::class.java) {
            SyncProtocol.decide(owner, baseline(owner, 55.0), current, null)
        }
    }

    @Test
    fun `movement content is immutable once committed`() {
        val first = SyncProtocol.decide(owner, movement(owner, "OPENING", 12.0), null, null)
        val current = SyncProtocol.nextRecord(first)
        // Same content re-commits idempotently through the receipt path.
        assertEquals(first.version, SyncProtocol.decide(owner, movement(owner, "OPENING", 12.0), null, first).version)
        // Different content on the same record identity is a protocol violation.
        assertThrows(IllegalArgumentException::class.java) {
            SyncProtocol.decide(owner, movement(owner, "OPENING", 13.0), current, null)
        }
    }
}

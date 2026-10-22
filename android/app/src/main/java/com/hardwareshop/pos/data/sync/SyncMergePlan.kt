package com.hardwareshop.pos.data.sync

import java.text.Normalizer
import java.util.Locale

/** First-link review for two phones with independent history. This creates proposals,
 * never deletes bills, changes buying prices or sums possibly duplicated shop stock.
 */
object SyncMergePlan {
    data class Product(val syncId: String, val deviceId: String, val revision: Long,
        val category: String, val type: String, val name: String, val size: String,
        val unit: String, val stock: Double, val stockRevision: Long = 0)
    data class CandidateGroup(val products: List<Product>)
    data class StockDecision(val quantitiesReviewed: Map<String, Double>,
        val revisionsReviewed: Map<String, Long>, val confirmedPhysicalQuantity: Double,
        val stockRevisionsReviewed: Map<String, Long>)
    data class ApprovedGroup(val canonicalProductId: String, val aliases: Set<String>,
        val stock: Double, val reviewedProducts: List<Product>)

    /** Conservative matching is only a suggestion: PVC != CPVC, per-foot != per-piece,
     * different size != same item. Avoid guessing brands, fitting standards or dimensions.
     */
    fun candidates(products: List<Product>): List<CandidateGroup> {
        products.forEach(::validate)
        require(products.map { it.deviceId to it.syncId }.distinct().size == products.size)
        return products.groupBy { listOf(normalize(it.category),normalize(it.type),normalize(it.name),
            normalize(it.size),normalize(it.unit)) }.values
            .filter { group -> group.map { it.deviceId }.distinct().size > 1 }
            .map { CandidateGroup(it.sortedWith(compareBy<Product> { p -> p.deviceId }.thenBy { p -> p.syncId })) }
    }

    /** Explicit reviewed quantity, not a silent sum of phone snapshots. Revalidate under
     * the eventual commit transaction; sales made during review invalidate the decision.
     */
    fun approve(group: CandidateGroup, canonicalProductId: String, decision: StockDecision): ApprovedGroup {
        require(group.products.isNotEmpty())
        group.products.forEach(::validate)
        val ids = group.products.map { it.syncId }.toSet()
        require(canonicalProductId in ids)
        require(decision.confirmedPhysicalQuantity.isFinite() && decision.confirmedPhysicalQuantity >= 0)
        require(decision.quantitiesReviewed.keys == ids && decision.revisionsReviewed.keys == ids)
        require(decision.stockRevisionsReviewed.keys==ids)
        group.products.forEach {
            require(decision.revisionsReviewed[it.syncId] == it.revision && decision.quantitiesReviewed[it.syncId] == it.stock &&
                decision.stockRevisionsReviewed[it.syncId]==it.stockRevision) {
                "Stock changed while reviewing. Refresh both phones before confirming."
            }
        }
        return ApprovedGroup(canonicalProductId,ids-canonicalProductId,decision.confirmedPhysicalQuantity,group.products)
    }

    /** Bill number, amount, date and customer name never identify an existing sale.
     * Two independent receipts numbered 000001 both survive. Identical global identities
     * are delivery retries; incompatible payloads require review instead of last-write-wins.
     */
    fun combineHistory(records: List<SyncProtocol.Record>): List<SyncProtocol.Record> {
        val byIdentity = linkedMapOf<Pair<SyncProtocol.Scope,String>,SyncProtocol.Record>()
        records.forEach { record ->
            val key = record.scope to record.recordId
            val existing = byIdentity[key]
            require(existing == null || existing == record) { "Shared history identity differs; owner review required" }
            byIdentity[key] = record
        }
        return byIdentity.values.toList()
    }

    private fun normalize(value: String): String = Normalizer.normalize(value,Normalizer.Form.NFC)
        .trim().lowercase(Locale.ROOT).replace(Regex("\\s+")," ")

    private fun validate(product: Product) {
        require(product.syncId.matches(Regex("[A-Za-z0-9_-]{16,120}")))
        require(product.deviceId.isNotBlank() && product.revision >= 0 && product.stockRevision >= 0 && product.stock.isFinite())
        require(product.name.isNotBlank() && product.unit.isNotBlank())
    }
}

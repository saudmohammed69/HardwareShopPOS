package com.hardwareshop.pos.data.sync

/** Structured transport contract. No Room row ID or credential is a wire identity. */
object SyncProtocol {
    enum class Scope(val collection: String) { PUBLIC("sync_v2_public"), PRIVATE("sync_v2_private") }
    enum class Role { OWNER, WORKER }
    data class Actor(val uid: String, val role: Role)
    data class Mutation(
        val operationId: String,
        val recordId: String,
        val entity: String,
        val scope: Scope,
        val authorUid: String,
        val baseVersion: Long,
        val deleted: Boolean,
        val fields: Map<String, Any?>
    )
    data class Record(val recordId: String, val entity: String, val scope: Scope,
                      val version: Long, val deleted: Boolean, val authorUid: String,
                      val fields: Map<String, Any?>)
    data class Receipt(val mutation: Mutation, val version: Long)
    class Conflict(val remote: Record) : IllegalStateException("This record changed on another device")

    private val idPattern = Regex("[A-Za-z0-9_-]{16,120}")
    val immutable = setOf("bill_items", "payments", "credit_transactions", "stock_adjustments",
        "purchase_bill_items", "supplier_payments", "sale_return_items", "audit_logs", "stock_movements",
        "stock_baselines")
    val ownerOnly = setOf("categories", "product_types", "workers", "suppliers", "purchase_bills",
        "purchase_bill_items", "supplier_payments", "stock_adjustments", "sale_returns", "sale_return_items",
        "day_closings", "settings", "product_costs", "shop_profile", "stock_baselines")
    private val noDelete = immutable + setOf("bills", "purchase_bills", "sale_returns", "day_closings")

    fun validate(mutation: Mutation) {
        require(idPattern.matches(mutation.operationId) && idPattern.matches(mutation.recordId)) { "Invalid sync identity" }
        require(mutation.authorUid.isNotBlank() && mutation.authorUid.length <= 128)
        require(mutation.baseVersion >= 0 && mutation.baseVersion < Long.MAX_VALUE)
        val spec = requireNotNull(SyncWireSchema.byName[mutation.entity]) { "Unknown business record type" }
        require(spec.scope == mutation.scope) { "Incorrect privacy boundary" }
        require(mutation.fields.keys.all { it in spec.fields }) { "Unexpected or private field" }
        require(mutation.fields.size <= 64)
        mutation.fields.forEach { (field, value) ->
            require(value == null || value is String || value is Boolean || value is Long || value is Int || value is Double) {
                "Only structured scalar fields are supported"
            }
            if (value is Double) require(value.isFinite()) { "Invalid numeric value" }
            if (value is String) require(value.length <= 50_000) { "Field is too long" }
            if (value != null) require(when (SyncWireSchema.kind(mutation.entity,field)) {
                "TEXT" -> value is String
                "INTEGER" -> value is Long || value is Int
                "REAL" -> value is Number
                else -> false
            }) { "Invalid field type" }
            if (field in setOf("sellingPricePaisa","purchasePricePaisa","unitPricePaisa","creditLimitPaisa","minimumStock","pipeLengthPricePaisa","pipeFootPricePaisa","pipeLengths","pipeFeet","pipeFeetPerLength") && value is Number)
                require(value.toDouble() >= 0) { "Negative price or limit is not allowed" }
            if (field == "gstRateBp" && value is Number) require(value.toLong() in 0..10000) { "Invalid GST rate" }
            if (field in setOf("isActive","gstEnabled","restock","requireOwnerApprovalForDiscount","isPipe") && value is Number)
                require(value.toLong() in 0..1) { "Invalid boolean value" }
        }
        if (!mutation.deleted) {
            require(mutation.fields.keys.containsAll(spec.required)) { "Required business fields are missing" }
            spec.required.forEach { require(mutation.fields[it] != null) { "Required value is null" } }
            spec.references.forEach { (field, _) ->
                mutation.fields[field]?.let { require(it is String && idPattern.matches(it)) { "Foreign key must be a global sync ID" } }
            }
        }
        require(!(mutation.deleted && mutation.entity in noDelete)) { "Financial history must not be deleted" }
        if(mutation.deleted) require(mutation.fields.isEmpty()) { "A tombstone cannot carry field changes" }
        if(mutation.entity=="credit_transactions") {
            fun amount(field: String)=(mutation.fields.getValue(field) as Number).toLong()
            val delta=amount("amountPaisa")
            require(when(mutation.fields["type"]) {
                "CREDIT_CREATED" -> delta>0
                "PARTIAL_PAYMENT","FULL_PAYMENT" -> delta<0
                "ADJUSTMENT" -> true
                else -> false
            }) { "Invalid credit entry type or amount" }
            val expected=try { Math.addExact(amount("previousBalancePaisa"),delta) }
                catch(_: ArithmeticException) { throw IllegalArgumentException("Credit balance overflow") }
            require(expected==amount("newBalancePaisa")) { "Credit entry balance does not add up" }
        }
        require(mutation.fields.entries.sumOf { (k,v) ->
            k.toByteArray(Charsets.UTF_8).size + (v?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 4) + 32
        } <= 700_000) { "Business record is too large" }
        if (!mutation.deleted && (mutation.fields["isPipe"] as? Number)?.toLong() == 1L) {
            if (mutation.entity == "products") {
                require((mutation.fields["pipeLengthPricePaisa"] as? Number)?.toLong()?.let { it > 0 } == true)
                require((mutation.fields["pipeFootPricePaisa"] as? Number)?.toLong()?.let { it > 0 } == true)
                require((mutation.fields["pipeFeetPerLength"] as? Number)?.toInt() in 1..10000)
            }
            if (mutation.entity == "bill_items") {
                val lengths = (mutation.fields["pipeLengths"] as? Number)?.toLong() ?: error("Missing pipe lengths")
                val feet = (mutation.fields["pipeFeet"] as? Number)?.toDouble() ?: error("Missing pipe feet")
                val lp = (mutation.fields["unitPricePaisa"] as Number).toLong()
                val fp = (mutation.fields["pipeFootPricePaisa"] as? Number)?.toLong() ?: error("Missing pipe rate")
                require(lengths > 0 || feet > 0)
                require(com.hardwareshop.pos.util.PipeBilling.total(lengths, feet, lp, fp) == (mutation.fields["itemTotalPaisa"] as Number).toLong())
            }
        }
        if (mutation.entity == "stock_movements") {
            require(mutation.fields["kind"] in setOf("OPENING", "DELTA"))
            require(mutation.fields["delta"] is Number)
        }
        if (mutation.entity == "settings" && !mutation.deleted) require(
            mutation.fields["key"] in setOf("shop_name", "shop_phone", "shop_address", "shop_gst_number")
        ) { "Device settings cannot be synchronized" }
    }

    /** Called inside the backend's transaction. Authorization is also enforced by rules.
     * A retry checks the immutable receipt BEFORE version conflict checks, so an already
     * committed operation is acknowledged even after another phone writes a newer version.
     */
    fun canonical(mutation: Mutation): Mutation = mutation.copy(fields=mutation.fields.mapValues { (field,value) ->
        when {
            value is Number && SyncWireSchema.kind(mutation.entity,field) == "REAL" -> value.toDouble()
            value is Int -> value.toLong()
            else -> value
        }
    })

    fun decide(actor: Actor, requested: Mutation, current: Record?, receipt: Receipt?): Receipt {
        validate(requested)
        val mutation = canonical(requested)
        validate(mutation)
        require(actor.uid == mutation.authorUid) { "Operation author does not match authentication" }
        if (actor.role != Role.OWNER) require(mutation.scope == Scope.PUBLIC && mutation.entity !in ownerOnly) {
            "Owner access required"
        }
        if (receipt != null) {
            require(canonical(receipt.mutation) == mutation) { "An operation ID cannot be reused with different content" }
            return receipt
        }
        if (actor.role != Role.OWNER) {
            require(!mutation.deleted) { "Owner access required for deletion" }
            if (mutation.entity == "products") {
                require(current == null && (mutation.fields["sellingPricePaisa"] as? Number)?.toLong() == 0L) {
                    "Workers can only submit new products for owner pricing"
                }
            }
            if (mutation.entity == "bills") require(current == null || current.fields == mutation.fields) {
                "Editing an existing bill requires owner access"
            }
            if(mutation.entity=="bills" && current==null) require(mutation.fields["status"]=="ACTIVE")
            if (mutation.entity == "customers") {
                val limit = (mutation.fields["creditLimitPaisa"] as? Number)?.toLong() ?: 0L
                val oldLimit = (current?.fields?.get("creditLimitPaisa") as? Number)?.toLong() ?: 0L
                require(limit == oldLimit) { "Changing a credit limit requires owner access" }
                require(mutation.fields["isActive"] == (current?.fields?.get("isActive") ?: 1L)) {
                    "Disabling a customer requires owner access"
                }
            }
            if(mutation.entity=="credit_transactions") require(mutation.fields["type"] in
                setOf("CREDIT_CREATED","PARTIAL_PAYMENT","FULL_PAYMENT")) { "Credit adjustments require owner access" }
            if (mutation.entity == "stock_movements") require(
                mutation.fields["kind"] == "DELTA" && (mutation.fields["delta"] as Number).toDouble() <= 0.0
            ) { "Opening or increasing stock requires owner access" }
        }
        if (current != null) {
            require(current.recordId == mutation.recordId && current.entity == mutation.entity && current.scope == mutation.scope)
            require(!current.deleted || mutation.deleted) { "Deleted identity cannot be resurrected" }
            if(mutation.entity=="bills" && !mutation.deleted) require(current.fields["billNumber"]==mutation.fields["billNumber"]) {
                "A saved bill number cannot be reassigned"
            }
            if (mutation.entity in immutable) require(current.fields == mutation.fields && !mutation.deleted) {
                "An immutable financial record cannot be overwritten"
            }
        }
        if ((current?.version ?: 0L) != mutation.baseVersion) throw Conflict(requireNotNull(current))
        return Receipt(mutation, mutation.baseVersion + 1)
    }

    fun nextRecord(receipt: Receipt): Record = receipt.mutation.let {
        Record(it.recordId, it.entity, it.scope, receipt.version, it.deleted, it.authorUid, it.fields)
    }
}

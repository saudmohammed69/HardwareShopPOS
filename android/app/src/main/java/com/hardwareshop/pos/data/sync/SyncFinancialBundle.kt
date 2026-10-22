package com.hardwareshop.pos.data.sync

import java.math.BigDecimal
import java.math.RoundingMode

/** Describes a complete financial graph. Downloaded row receipts stay in the inbox
 * until all manifest members are present and this validation succeeds.
 * Bill numbers never identify or merge graphs from independently used phones.
 */
object SyncFinancialBundle {
    data class Member(val operationId: String, val recordId: String, val entity: String, val version: Long)
    data class Manifest(val bundleId: String, val rootId: String, val rootEntity: String,
                        val scope: SyncProtocol.Scope, val members: List<Member>)
    private val saleEntities=setOf("bills","bill_items","payments","credit_transactions","sale_returns","sale_return_items")
    private val purchaseEntities=setOf("purchase_bills","purchase_bill_items","supplier_payments")
    val entities=saleEntities+purchaseEntities

    fun manifest(rootId: String, receipts: List<SyncProtocol.Receipt>): Manifest {
        val root=receipts.single { it.mutation.recordId==rootId }
        return Manifest(root.mutation.operationId,rootId,root.mutation.entity,root.mutation.scope,
            receipts.map { Member(it.mutation.operationId,it.mutation.recordId,it.mutation.entity,it.version) })
            .also { validate(it,receipts) }
    }

    fun validate(manifest: Manifest, receipts: List<SyncProtocol.Receipt>) {
        require(manifest.members.size in 2..2000) { "Financial bundle is empty or too large" }
        require(receipts.size==manifest.members.size) { "Financial download is incomplete" }
        require(manifest.members.map { it.operationId }.distinct().size==manifest.members.size)
        require(manifest.members.map { it.recordId }.distinct().size==manifest.members.size)
        require(receipts.map { it.mutation.recordId }.distinct().size==receipts.size)
        val byOperation=receipts.associateBy { it.mutation.operationId }
        val allowed=when(manifest.rootEntity) {
            "bills" -> saleEntities.also { require(manifest.scope==SyncProtocol.Scope.PUBLIC) }
            "purchase_bills" -> purchaseEntities.also { require(manifest.scope==SyncProtocol.Scope.PRIVATE) }
            else -> throw IllegalArgumentException("Unsupported financial bundle")
        }
        manifest.members.forEach { member ->
            val receipt=requireNotNull(byOperation[member.operationId]) { "Missing financial record" }
            val m=receipt.mutation
            SyncProtocol.validate(m)
            require(!m.deleted && m.scope==manifest.scope && m.entity in allowed)
            require(m.recordId==member.recordId && m.entity==member.entity && receipt.version==member.version)
            require(receipt.version==m.baseVersion+1)
        }
        val root=receipts.single { it.mutation.recordId==manifest.rootId }
        require(root.mutation.entity==manifest.rootEntity && root.mutation.operationId==manifest.bundleId)
        require(receipts.count { it.mutation.entity==manifest.rootEntity }==1) { "Bundle contains unrelated invoices" }
        val rows=receipts.map { it.mutation }
        if(manifest.rootEntity=="bills") validateSale(root.mutation,rows) else validatePurchase(root.mutation,rows)
    }

    private fun validateSale(bill: SyncProtocol.Mutation, rows: List<SyncProtocol.Mutation>) {
        val lines=rows.filter { it.entity=="bill_items" }
        require(lines.isNotEmpty()) { "Bill items are missing" }
        lines.forEach { require(it.fields["billId"]==bill.recordId); line(it,"unitPricePaisa") }
        val subtotal=sum(lines,"itemTotalPaisa")
        val tax=sum(lines,"gstAmountPaisa")
        val discount=amount(bill,"discountPaisa")
        require(discount<=subtotal)
        require(tax==amount(bill,"gstAmountPaisa")) { "Bill line tax and header differ" }
        val net=Math.subtractExact(subtotal,discount)
        // Inclusive billing stores gross line prices and the discounted taxable
        // base in the header. Exclusive billing stores pre-tax line prices.
        val inclusive=amount(bill,"subtotalPaisa")==Math.subtractExact(net,tax) &&
            amount(bill,"totalPaisa")==net
        require(inclusive || subtotal==amount(bill,"subtotalPaisa")) { "Bill lines and header differ" }
        val total=if(inclusive) net else Math.addExact(net,tax)
        require(total==amount(bill,"totalPaisa")) { "Bill total is inconsistent" }
        require(Math.addExact(amount(bill,"paidPaisa"),amount(bill,"creditPaisa"))==total)
        require(bill.fields["status"] in setOf("ACTIVE","EDITED","CANCELLED"))
        require(bill.fields["paymentMethod"] in setOf("CASH","CARD","UPI","CREDIT","SPLIT"))
        val payments=rows.filter { it.entity=="payments" }
        payments.forEach { require(it.fields["billId"]==bill.recordId); payment(it) }
        require(sum(payments,"amountPaisa")==amount(bill,"paidPaisa")) { "Initial bill payment is missing" }
        if(bill.fields["paymentMethod"]=="SPLIT") {
            require(amount(bill,"creditPaisa")==0L && payments.all { it.fields["method"] in setOf("CASH","UPI") }) { "Invalid split payment" }
            require(payments.map { it.fields["method"] }.distinct().size==payments.size) { "Duplicate split component" }
        }
        val credit=rows.filter { it.entity=="credit_transactions" }
        credit.forEach {
            require(it.fields["billId"]==bill.recordId && it.fields["customerId"]==bill.fields["customerId"])
            require(it.fields["type"] in setOf("CREDIT_CREATED","PARTIAL_PAYMENT","FULL_PAYMENT","ADJUSTMENT"))
            require(Math.addExact(signed(it,"previousBalancePaisa"),signed(it,"amountPaisa"))==signed(it,"newBalancePaisa"))
        }
        require(sum(credit.filter { it.fields["type"]=="CREDIT_CREATED" },"amountPaisa")==amount(bill,"creditPaisa")) {
            "Original credit entry is missing"
        }
        if(amount(bill,"creditPaisa")>0) require(bill.fields["customerId"] is String)
        val returns=rows.filter { it.entity=="sale_returns" }
        val returnLines=rows.filter { it.entity=="sale_return_items" }
        returns.forEach { header ->
            require(header.fields["billId"]==bill.recordId)
            val children=returnLines.filter { it.fields["returnId"]==header.recordId }
            require(children.isNotEmpty()) { "Return items are missing" }
            require(sum(children,"totalPaisa")==amount(header,"totalPaisa"))
            require(sum(children,"discountPaisa")==amount(header,"discountPaisa"))
            require(sum(children,"taxPaisa")==amount(header,"taxPaisa"))
            require(Math.addExact(amount(header,"refundPaisa"),amount(header,"creditReductionPaisa"))==amount(header,"totalPaisa"))
        }
        val sold=lines.associateBy { it.recordId }
        returnLines.forEach {
            require(returns.any { header -> header.recordId==it.fields["returnId"] }) { "Return header is missing" }
            require(it.fields["billItemId"] in sold) { "Return refers to another bill" }
            quantity(it)
        }
        returnLines.groupBy { it.fields["billItemId"] as String }.forEach { (id,returned) ->
            val count=returned.fold(BigDecimal.ZERO) { sum,row -> sum+quantity(row) }
            require(count<=quantity(sold.getValue(id))) { "Returned quantity exceeds sold quantity" }
        }
    }

    private fun validatePurchase(bill: SyncProtocol.Mutation, rows: List<SyncProtocol.Mutation>) {
        val lines=rows.filter { it.entity=="purchase_bill_items" }
        require(lines.isNotEmpty()) { "Purchase items are missing" }
        lines.forEach { require(it.fields["purchaseBillId"]==bill.recordId); line(it,"purchasePricePaisa") }
        val total=Math.addExact(sum(lines,"itemTotalPaisa"),sum(lines,"gstAmountPaisa"))
        require(total==amount(bill,"totalPaisa")) { "Purchase items and invoice total differ" }
        val payments=rows.filter { it.entity=="supplier_payments" }
        payments.forEach { require(it.fields["purchaseBillId"]==bill.recordId); payment(it) }
        require(sum(payments,"amountPaisa")<=total) { "Supplier payment exceeds invoice total" }
    }

    private fun line(row: SyncProtocol.Mutation, price: String) {
        require(quantity(row).multiply(BigDecimal.valueOf(amount(row,price))).setScale(0,RoundingMode.HALF_UP)
            .longValueExact()==amount(row,"itemTotalPaisa")) { "Line quantity and price do not match its total" }
        amount(row,"gstAmountPaisa")
    }
    private fun payment(row: SyncProtocol.Mutation) {
        require(amount(row,"amountPaisa")>0 && row.fields["method"] in setOf("CASH","UPI","CARD"))
    }
    private fun quantity(row: SyncProtocol.Mutation): BigDecimal =
        BigDecimal.valueOf((row.fields.getValue("quantity") as Number).toDouble()).setScale(2,RoundingMode.UNNECESSARY)
            .also { require(it>BigDecimal.ZERO) }
    private fun signed(row: SyncProtocol.Mutation,key: String)=(row.fields.getValue(key) as Number).toLong()
    private fun amount(row: SyncProtocol.Mutation,key: String)=signed(row,key).also { require(it>=0) { "Negative $key" } }
    private fun sum(rows: List<SyncProtocol.Mutation>,key: String)=rows.fold(0L) { total,row -> Math.addExact(total,amount(row,key)) }
}

package com.hardwareshop.pos.data.sync

import org.junit.Assert.*
import org.junit.Test

class SyncFinancialBundleTest {
    private val billId="bill_from_phone_0001"
    private fun record(entity: String,id: String,overrides: Map<String,Any?>): SyncProtocol.Receipt {
        val spec=SyncWireSchema.byName.getValue(entity)
        val fields=spec.fields.associateWith { field -> when {
            field in spec.references -> if(field in spec.required) "linked_record_0001" else null
            SyncWireSchema.kind(entity,field)=="TEXT" -> ""
            SyncWireSchema.kind(entity,field)=="REAL" -> 0.0
            else -> 0L
        } }+overrides
        return SyncProtocol.Receipt(SyncProtocol.Mutation("operation_$id",id,entity,spec.scope,"owner_device_001",0,false,fields),1)
    }
    private fun sale(credit: Boolean=false): List<SyncProtocol.Receipt> {
        val header=record("bills",billId,mapOf("billNumber" to "000001","subtotalPaisa" to 3000L,"totalPaisa" to 3000L,
            "paidPaisa" to if(credit) 0L else 3000L,"creditPaisa" to if(credit) 3000L else 0L,"status" to "ACTIVE",
            "paymentMethod" to if(credit) "CREDIT" else "CASH","customerId" to if(credit) "customer_phone_001" else null))
        val line=record("bill_items","line_from_phone_001",mapOf("billId" to billId,"quantity" to 3.0,"unitPricePaisa" to 1000L,"itemTotalPaisa" to 3000L))
        val payment=if(credit) record("credit_transactions","credit_from_phone_001",mapOf("billId" to billId,
            "customerId" to "customer_phone_001","type" to "CREDIT_CREATED","amountPaisa" to 3000L,"newBalancePaisa" to 3000L))
        else record("payments","payment_from_phone_001",mapOf("billId" to billId,"amountPaisa" to 3000L,"method" to "CASH"))
        return listOf(header,line,payment)
    }
    private fun changed(rows: List<SyncProtocol.Receipt>,entity: String,fields: Map<String,Any?>)=rows.map {
        if(it.mutation.entity==entity) it.copy(mutation=it.mutation.copy(fields=it.mutation.fields+fields)) else it
    }
    @Test fun fullCashAndCreditBillsValidate() {
        assertEquals(3,SyncFinancialBundle.manifest(billId,sale()).members.size)
        assertEquals(3,SyncFinancialBundle.manifest(billId,sale(true)).members.size)
    }
    @Test fun inclusiveTaxPreservesGrossBillTotal() {
        val rows=changed(changed(sale(),"bills",mapOf("subtotalPaisa" to 2542L,
            "gstAmountPaisa" to 458L)),"bill_items",mapOf("gstAmountPaisa" to 458L))
        assertEquals(3,SyncFinancialBundle.manifest(billId,rows).members.size)
    }
    @Test(expected=IllegalArgumentException::class) fun inconsistentInclusiveTaxIsRejected() {
        val rows=changed(changed(sale(),"bills",mapOf("subtotalPaisa" to 2541L,
            "gstAmountPaisa" to 458L)),"bill_items",mapOf("gstAmountPaisa" to 458L))
        SyncFinancialBundle.manifest(billId,rows)
    }
    @Test fun inclusiveDiscountIsNotSubtractedTwice() {
        var rows=changed(sale(),"bills",mapOf("subtotalPaisa" to 2458L,
            "discountPaisa" to 100L,"gstAmountPaisa" to 442L,
            "totalPaisa" to 2900L,"paidPaisa" to 2900L))
        rows=changed(rows,"bill_items",mapOf("gstAmountPaisa" to 442L))
        rows=changed(rows,"payments",mapOf("amountPaisa" to 2900L))
        assertEquals(3,SyncFinancialBundle.manifest(billId,rows).members.size)
    }
    @Test fun manifestRoundTripPreservesReceiptReferences() {
        val manifest=SyncFinancialBundle.manifest(billId,sale())
        assertEquals(manifest,SyncBundleJson.decode(SyncBundleJson.encode(manifest)))
    }
    @Test(expected=IllegalArgumentException::class) fun interruptedDownloadCannotApplyPartialBill() {
        val rows=sale();val manifest=SyncFinancialBundle.manifest(billId,rows)
        SyncFinancialBundle.validate(manifest,rows.dropLast(1))
    }
    @Test(expected=IllegalArgumentException::class) fun wrongQuantityCannotProduceTrustedHeader() {
        SyncFinancialBundle.manifest(billId,changed(sale(),"bill_items",mapOf("quantity" to 2.0)))
    }
    @Test(expected=IllegalArgumentException::class) fun paidBillWithoutPaymentIsRejected() {
        SyncFinancialBundle.manifest(billId,sale().filter { it.mutation.entity!="payments" })
    }
    @Test(expected=IllegalArgumentException::class) fun creditBillWithoutLedgerEntryIsRejected() {
        SyncFinancialBundle.manifest(billId,sale(true).filter { it.mutation.entity!="credit_transactions" })
    }
    @Test(expected=IllegalArgumentException::class) fun billItemsFromAnotherInvoiceAreRejected() {
        SyncFinancialBundle.manifest(billId,changed(sale(),"bill_items",mapOf("billId" to "another_bill_00001")))
    }
    @Test(expected=IllegalArgumentException::class) fun duplicateLineReceiptIsRejected() {
        val rows=sale()
        SyncFinancialBundle.manifest(billId,rows+rows[1])
    }
    @Test(expected=IllegalArgumentException::class) fun publicBundleCannotContainSupplierCosts() {
        val rows=sale()+record("supplier_payments","supplier_payment_001",mapOf("purchaseBillId" to "purchase_record_001",
            "amountPaisa" to 1000L,"method" to "CASH"))
        SyncFinancialBundle.manifest(billId,rows)
    }
    @Test fun privatePurchaseBundleChecksLinesAndPayment() {
        val id="purchase_invoice_001"
        val header=record("purchase_bills",id,mapOf("totalPaisa" to 2360L))
        val line=record("purchase_bill_items","purchase_line_001",mapOf("purchaseBillId" to id,"quantity" to 2.0,
            "purchasePricePaisa" to 1000L,"itemTotalPaisa" to 2000L,"gstAmountPaisa" to 360L))
        val payment=record("supplier_payments","supplier_payment_001",mapOf("purchaseBillId" to id,"amountPaisa" to 1000L,"method" to "UPI"))
        val manifest=SyncFinancialBundle.manifest(id,listOf(header,line,payment))
        assertEquals(SyncProtocol.Scope.PRIVATE,manifest.scope)
    }

    @Test fun exactCashAndUpiRowsValidateAsOneSplitBill() {
        val original=changed(sale(),"bills",mapOf("paymentMethod" to "SPLIT")).filter {it.mutation.entity!="payments"}
        val cash=record("payments","payment_cash_0001",mapOf("billId" to billId,"method" to "CASH","amountPaisa" to 1101L))
        val upi=record("payments","payment_upi_0001",mapOf("billId" to billId,"method" to "UPI","amountPaisa" to 1899L))
        assertEquals(4,SyncFinancialBundle.manifest(billId,original+cash+upi).members.size)
    }
    @Test(expected=IllegalArgumentException::class) fun duplicateSplitComponentCannotPassWireValidation() {
        val original=changed(sale(),"bills",mapOf("paymentMethod" to "SPLIT")).filter {it.mutation.entity!="payments"}
        val a=record("payments","payment_cash_0001",mapOf("billId" to billId,"method" to "CASH","amountPaisa" to 1500L))
        val b=record("payments","payment_cash_0002",mapOf("billId" to billId,"method" to "CASH","amountPaisa" to 1500L))
        SyncFinancialBundle.manifest(billId,original+a+b)
    }
}

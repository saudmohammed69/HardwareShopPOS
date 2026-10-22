package com.hardwareshop.pos.presentation.owner.misc

import androidx.annotation.Keep
import androidx.compose.runtime.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.util.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

@Keep data class PurchaseInput(val productId: Long, val qty: String = "1", val cost: String = "0", val gst: String = "0")
@Keep data class PurchaseForm(val requestId: String = UUID.randomUUID().toString(), val supplierId: Long? = null,
    val invoice: String = "", val date: String = LocalDate.now().toString(), val lines: List<PurchaseInput> = emptyList(),
    val paid: String = "0", val method: String = "CASH", val notes: String = "")
@Keep data class ReorderPlan(val productId: Long, val quantity: Double, val supplierId: Long?)
@Keep data class ReturnInput(val itemId: Long, val qty: String = "", val restock: Boolean = true)
@Keep data class ReplacementInput(val productId: Long, val qty: String = "1")
@Keep data class ReturnForm(val requestId: String = UUID.randomUUID().toString(), val billId: Long = 0,
    val number: String = "", val lines: List<ReturnInput> = emptyList(), val replacements: List<ReplacementInput> = emptyList(),
    val reason: String = "", val method: String = "CASH")

@HiltViewModel
class TradeViewModel @Inject constructor(private val repository: TradeRepository, private val db: AppDatabase,
    private val session: SessionHolder, private val saved: SavedStateHandle,
    private val printer: BluetoothPrintManager, private val settings: SettingsRepository) : ViewModel() {
    private val gson = Gson()
    var data by mutableStateOf<TradeData?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var tab by mutableIntStateOf(0)
    var form by mutableStateOf(saved.get<String>("purchase")?.let { gson.fromJson(it, PurchaseForm::class.java) } ?: PurchaseForm()); private set
    var returns by mutableStateOf(saved.get<String>("return")?.let { gson.fromJson(it, ReturnForm::class.java) } ?: ReturnForm()); private set
    var original by mutableStateOf<ReturnBill?>(null); private set
    var quote by mutableStateOf<ReturnQuote?>(null); private set
    var plans by mutableStateOf<List<ReorderPlan>>(emptyList()); private set
    var purchaseDetail by mutableStateOf<List<PurchaseBillItemEntity>?>(null); private set
    var lastReturn by mutableStateOf<SaleReturnEntity?>(null); private set
    var returnDetail by mutableStateOf<ReturnDetail?>(null); private set
    fun showReturn(id: String) = action { returnDetail = repository.returnDetail(id) }
    fun closeReturnDetail() { if (!busy) returnDetail = null }
    fun printerError(value: String) { error = value }
    fun printReturn() = action {
        val detail = requireNotNull(returnDetail)
        val key = "local_print_return_${detail.header.id}"
        val copy = db.settingsDao().getSetting(key) != null
        val shop = settings.getShopInfo()
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ReceiptBuilder.encode(ReturnReceipt.build(detail, shop.name, copy))
        }
        when (val result = printer.printRawBytes(bytes) { db.settingsDao().upsertSetting(SettingsEntity(key, "ATTEMPTED")) }) {
            is com.hardwareshop.pos.domain.model.Result.Success -> message = "Return receipt sent to printer. Check paper output."
            is com.hardwareshop.pos.domain.model.Result.Error -> error = result.message
            else -> Unit
        }
    }
    init { refresh() }
    private fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; message = null
        viewModelScope.launch {
            try { PermissionChecker.enforceOwner(session.current); block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not complete this action" }
            finally { busy = false }
        }
    }
    private suspend fun reload() {
        data = repository.load()
        plans = db.settingsDao().getAllSettings().filter { it.key.startsWith("reorder:") }.map { gson.fromJson(it.value, ReorderPlan::class.java) }
        if (returns.billId > 0) original = repository.findBill(requireNotNull(db.billDao().getBillById(returns.billId)).billNumber)
    }
    fun refresh() = action { reload() }
    fun update(value: PurchaseForm) { if (!busy) { form = value; saved["purchase"] = gson.toJson(value) } }
    private fun resetPurchase() { form = PurchaseForm(); saved["purchase"] = gson.toJson(form) }
    fun discardPurchase() { if (!busy) resetPurchase() }
    fun editReturn(value: ReturnForm) { if (!busy) { returns = value; quote = null; saved["return"] = gson.toJson(value) } }
    private fun qty(text: String): Double = requireNotNull(text.trim().toDoubleOrNull()) { "Enter a valid quantity" }.also { TradeMath.quantity(it) }
    private fun money(text: String): Long = requireNotNull(CalculationEngine.parseRupeesToPaisa(text)) { "Enter an amount with at most two decimals" }
    private fun purchaseLines() = form.lines.map { PurchaseLine(it.productId, qty(it.qty), money(it.cost), money(it.gst).also { rate -> require(rate <= 10000) { "GST must be between 0 and 100%" } }.toInt()) }
    fun total(): Long? = runCatching { purchaseLines().fold(0L) { sum, line ->
        val base = CalculationEngine.itemTotalPaisa(line.quantity, line.price)
        Math.addExact(sum, Math.addExact(base, CalculationEngine.gstAmountPaisa(base, line.gstBp)))
    } }.getOrNull()
    fun addProduct(p: ProductEntity) {
        if (form.lines.any { it.productId == p.id }) return
        update(form.copy(lines = form.lines + PurchaseInput(p.id, cost = java.math.BigDecimal.valueOf(p.purchasePricePaisa, 2).toPlainString())))
    }
    fun savePurchase() = action {
        val id = repository.purchase(form.requestId, requireNotNull(form.supplierId) { "Choose a supplier" }, form.invoice,
            LocalDate.parse(form.date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), purchaseLines(), money(form.paid), form.method, form.notes)
        resetPurchase(); reload(); message = "Purchase #$id saved. Stock and supplier dues updated."
    }
    fun supplier(row: SupplierEntity, done: () -> Unit) = action {
        val id = repository.saveSupplier(row); reload(); form = form.copy(supplierId = id); saved["purchase"] = gson.toJson(form); done()
    }
    fun pay(id: String, billId: Long, amount: String, method: String, reference: String, done: () -> Unit) = action {
        repository.paySupplier(id, billId, money(amount), method, reference); reload(); done(); message = "Supplier payment recorded."
    }
    fun details(id: Long) = action { purchaseDetail = repository.purchaseItems(id) }
    fun closeDetails() { purchaseDetail = null }
    fun suggested(p: ProductEntity): Double = plans.find { it.productId == p.id }?.quantity
        ?: java.math.BigDecimal.valueOf((maxOf(p.minimumStock * 2, 1.0) - p.currentStock).coerceAtLeast(0.01)).setScale(2, java.math.RoundingMode.CEILING).toDouble()
    fun supplierFor(p: ProductEntity): Long? = plans.find { it.productId == p.id }?.supplierId
    fun savePlan(p: ProductEntity, quantity: String, supplierId: Long?, done: () -> Unit) = action {
        require(supplierId == null || data?.suppliers?.any { it.id == supplierId && it.isActive } == true) { "Choose an active supplier" }
        db.settingsDao().upsertSetting(SettingsEntity("reorder:${p.id}", gson.toJson(ReorderPlan(p.id, qty(quantity), supplierId))))
        reload(); done(); message = "Reorder quantity and supplier saved."
    }
    fun clearPlan(id: Long) = action { db.settingsDao().deleteSetting("reorder:$id"); reload() }
    fun reorderProducts() = data?.products.orEmpty().filter { it.isActive && (it.currentStock <= 0 || (it.minimumStock > 0 && it.currentStock <= it.minimumStock) || plans.any { plan -> plan.productId == it.id }) }
    fun orderSupplier(id: Long) {
        if (busy) return
        if (form.lines.isNotEmpty()) { error = "Finish or discard the current purchase before opening a reorder group."; return }
        val rows = reorderProducts().filter { supplierFor(it) == id }
        update(PurchaseForm(supplierId = id, lines = rows.map { PurchaseInput(it.id, CalculationEngine.formatQuantity(suggested(it)), java.math.BigDecimal.valueOf(it.purchasePricePaisa,2).toPlainString()) }))
        tab = 0; message = "Review received quantities, actual costs and GST. Saving this purchase will increase stock."
    }
    fun loadBill(id: Long = 0) = action {
        val number = if (id > 0) requireNotNull(db.billDao().getBillById(id)).billNumber else returns.number
        val bill = repository.findBill(number)
        original = bill
        returns = ReturnForm(billId = bill.bill.id, number = bill.bill.billNumber, lines = bill.items.map { ReturnInput(it.id, restock = it.productId > 0) })
        saved["return"] = gson.toJson(returns); quote = null; lastReturn = null
    }
    private fun returnLines() = returns.lines.filter { it.qty.isNotBlank() && it.qty.toDoubleOrNull() != 0.0 }.map { ReturnLine(it.itemId, qty(it.qty), it.restock) }
    private fun replacements() = returns.replacements.map { ReplacementLine(it.productId, qty(it.qty)) }
    fun previewReturn() = action { quote = repository.quote(returns.billId, returnLines(), replacements()) }
    fun closeQuote() { quote = null }
    fun saveReturn() = action {
        val preview = requireNotNull(quote) { "Preview the return first" }
        val row = repository.returnSale(returns.requestId, returns.billId, returnLines(), replacements(), returns.method, returns.reason, preview)
        returns = ReturnForm(); saved["return"] = gson.toJson(returns); quote = null; original = null
        reload(); lastReturn = row
        message = if (preview.collect > 0) "Recorded: collect ${CalculationEngine.formatRupees(preview.collect)} from customer."
            else if (preview.collect < 0) "Recorded: refund ${CalculationEngine.formatRupees(-preview.collect)} to customer."
            else "Return recorded. No cash settlement needed."
    }
}

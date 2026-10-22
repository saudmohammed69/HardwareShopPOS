// Compose state delegate writes are observed during recomposition; retain the UI state updates.
@file:Suppress("AssignedValueIsNeverRead")

package com.hardwareshop.pos.presentation.owner.misc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.PurchaseSummary
import com.hardwareshop.pos.presentation.common.*
import com.hardwareshop.pos.util.CalculationEngine
import java.util.UUID

@Composable
private fun Entry(label: String, value: String, change: (String) -> Unit, numeric: Boolean = false, enabled: Boolean = true) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), enabled = enabled, label = { Text(label) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}
@Composable
private fun Methods(value: String, enabled: Boolean, change: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("CASH", "UPI", "CARD").forEach { method -> FilterChip(value == method, { change(method) }, enabled = enabled, label = { Text(method) }) }
    }
}
@Composable
private fun <T> Picker(title: String, rows: List<T>, label: (T) -> String, choose: (T) -> Unit, dismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column {
            Entry("Search", query, { query = it })
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                val filtered = rows.filter { label(it).contains(query, true) }
                if (filtered.isEmpty()) item { Text("No matches", Modifier.padding(16.dp)) }
                items(filtered) { row -> Text(label(row), Modifier.fillMaxWidth().clickable { choose(row) }.padding(14.dp)) }
            }
        }
    }, confirmButton = { TextButton(dismiss) { Text("Close") } })
}

@Composable
fun PurchasesScreen(onNavigateUp: () -> Unit, initialTab: Int = 0, vm: TradeViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.tab = initialTab }
    var chooseSupplier by remember { mutableStateOf(false) }
    var chooseProduct by remember { mutableStateOf(false) }
    var supplierEdit by remember { mutableStateOf<SupplierEntity?>(null) }
    var payment by remember { mutableStateOf<PurchaseSummary?>(null) }
    var reorderEdit by remember { mutableStateOf<ProductEntity?>(null) }
    var confirmPurchase by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val data = vm.data
    val form = vm.form
    val suppliers = data?.suppliers.orEmpty().filter { it.isActive }
    if (chooseSupplier) Picker("Choose supplier", suppliers, { it.name }, { vm.update(form.copy(supplierId = it.id)); chooseSupplier = false }, { chooseSupplier = false })
    if (chooseProduct) Picker("Add purchase item", data?.products.orEmpty().filter { it.isActive },
        { "${it.name} ${it.size} / ${it.unit}" }, { vm.addProduct(it); chooseProduct = false }, { chooseProduct = false })
    supplierEdit?.let { original -> SupplierDialog(original, vm) { supplierEdit = null } }
    payment?.let { purchase -> SupplierPaymentDialog(purchase, vm) { payment = null } }
    reorderEdit?.let { p -> ReorderDialog(p, vm) { reorderEdit = null } }
    if (confirmPurchase) AlertDialog(onDismissRequest = { if (!vm.busy) confirmPurchase = false }, title = { Text("Receive these goods?") },
        text = { Column { Text("This records the supplier invoice, adds received quantities to stock and records the amount paid. Check every line first.")
            Text("Total: ${vm.total()?.let(CalculationEngine::formatRupees) ?: "Invalid amount"}"); vm.error?.let { ErrorMessage(it) } } },
        confirmButton = { TextButton({ confirmPurchase = false; vm.savePurchase() }, enabled = !vm.busy) { Text("SAVE PURCHASE") } },
        dismissButton = { TextButton({ confirmPurchase = false }, enabled = !vm.busy) { Text("Review") } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("Discard purchase form?") },
        text = { Text("The unsaved entries will be cleared. Recorded purchases stay unchanged.") },
        confirmButton = { TextButton({ vm.discardPurchase(); confirmDiscard = false }) { Text("Discard") } },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text("Keep") } })
    vm.purchaseDetail?.let { lines -> AlertDialog(onDismissRequest = vm::closeDetails, title = { Text("Received items") },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) { items(lines) { row ->
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("${row.productName} ${row.productSize}", fontWeight = FontWeight.Bold)
                Text("${CalculationEngine.formatQuantity(row.quantity)} ${row.productUnit} × ${CalculationEngine.formatRupees(row.purchasePricePaisa)}")
                Text("GST ${CalculationEngine.formatRupees(row.gstAmountPaisa)}; total ${CalculationEngine.formatRupees(row.itemTotalPaisa + row.gstAmountPaisa)}")
            }
        } } }, confirmButton = { TextButton(vm::closeDetails) { Text("Close") } }) }
    Scaffold(topBar = { Column {
        AppTopBar("Purchases & Supplier Dues", onNavigateUp = onNavigateUp)
        TabRow(vm.tab) { listOf("Purchase", "Suppliers", "Reorder").forEachIndexed { i, title ->
            Tab(vm.tab == i, { vm.tab = i }, text = { Text(title) })
        } }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            vm.error?.let { item { ErrorMessage(it) } }
            vm.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            item { TextButton(vm::refresh, enabled = !vm.busy) { Text("Refresh") } }
            when (vm.tab) {
                0 -> {
                    item { Text("Receive stock", style = MaterialTheme.typography.titleLarge) }
                    item { OutlinedButton({ chooseSupplier = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(suppliers.find { it.id == form.supplierId }?.name ?: "Choose supplier") } }
                    item { TextButton({ supplierEdit = SupplierEntity(name = "") }, enabled = !vm.busy) { Text("+ Add supplier") } }
                    item { Entry("Supplier invoice number *", form.invoice, { vm.update(form.copy(invoice = it)) }, enabled = !vm.busy) }
                    item { Entry("Invoice date (YYYY-MM-DD)", form.date, { vm.update(form.copy(date = it)) }, enabled = !vm.busy) }
                    items(form.lines, key = { it.productId }) { line ->
                        val p = data?.products?.find { it.id == line.productId }
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), Arrangement.spacedBy(8.dp)) {
                            Text(p?.let { "${it.name} ${it.size} (${it.unit})" } ?: "Unavailable product", fontWeight = FontWeight.Bold)
                            fun change(value: PurchaseInput) = vm.update(form.copy(lines = form.lines.map { if (it.productId == line.productId) value else it }))
                            Entry("Received quantity", line.qty, { change(line.copy(qty = it)) }, true, !vm.busy)
                            Entry("Unit cost before GST (₹)", line.cost, { change(line.copy(cost = it)) }, true, !vm.busy)
                            Entry("GST % on purchase", line.gst, { change(line.copy(gst = it)) }, true, !vm.busy)
                            TextButton({ vm.update(form.copy(lines = form.lines.filterNot { it.productId == line.productId })) }, enabled = !vm.busy) { Text("Remove") }
                        } }
                    }
                    item { OutlinedButton({ chooseProduct = true }, enabled = !vm.busy) { Text("+ Add received item") } }
                    item { Text("Total: ${vm.total()?.let(CalculationEngine::formatRupees) ?: "Check quantities, cost and tax"}", fontWeight = FontWeight.Bold) }
                    item { Entry("Paid now (₹); 0 for all on credit", form.paid, { vm.update(form.copy(paid = it)) }, true, !vm.busy) }
                    item { Methods(form.method, !vm.busy) { vm.update(form.copy(method = it)) } }
                    item { Entry("Notes", form.notes, { vm.update(form.copy(notes = it)) }, enabled = !vm.busy) }
                    item { Button({ confirmPurchase = true }, enabled = !vm.busy && form.lines.isNotEmpty() && form.supplierId != null && vm.total() != null, modifier = Modifier.fillMaxWidth()) { Text("REVIEW & SAVE PURCHASE") } }
                    item { TextButton({ confirmDiscard = true }, enabled = !vm.busy) { Text("Discard unsaved purchase") } }
                    item { HorizontalDivider(); Text("Purchase history", style = MaterialTheme.typography.titleLarge) }
                    if (data?.purchases.isNullOrEmpty()) item { Text("No purchases recorded yet.") }
                    items(data?.purchases.orEmpty(), key = { it.bill.id }) { row ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), Arrangement.spacedBy(6.dp)) {
                            Text("${row.supplier} · ${row.bill.supplierBillNumber}", fontWeight = FontWeight.Bold)
                            Text("Total ${CalculationEngine.formatRupees(row.bill.totalPaisa)} · Paid ${CalculationEngine.formatRupees(row.paid)}")
                            Text("Due ${CalculationEngine.formatRupees(row.due)}", color = if (row.due > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            TextButton({ vm.details(row.bill.id) }, enabled = !vm.busy) { Text("View items") }
                            if (row.due > 0) Button({ payment = row }, enabled = !vm.busy) { Text("Record payment") }
                            data?.payments.orEmpty().filter { it.purchaseBillId == row.bill.id }.forEach { paid ->
                                Text("${java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(paid.createdAt))}: ${CalculationEngine.formatRupees(paid.amountPaisa)} ${paid.method} ${paid.reference}", style = MaterialTheme.typography.bodySmall)
                            }
                        } }
                    }
                }
                1 -> {
                    item { Text("Outstanding supplier dues: ${CalculationEngine.formatRupees(data?.purchases.orEmpty().sumOf { it.due })}", fontWeight = FontWeight.Bold) }
                    item { Button({ supplierEdit = SupplierEntity(name = "") }, enabled = !vm.busy) { Text("Add supplier") } }
                    items(suppliers, key = { it.id }) { supplier ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            Text(supplier.name, fontWeight = FontWeight.Bold); Text(supplier.phone)
                            Text("Due: ${CalculationEngine.formatRupees(data?.purchases.orEmpty().filter { it.bill.supplierId == supplier.id }.sumOf { it.due })}")
                            TextButton({ supplierEdit = supplier }, enabled = !vm.busy) { Text("Edit details") }
                            Text("Record repayments against the invoice in Purchase history.", style = MaterialTheme.typography.bodySmall)
                        } }
                    }
                }
                2 -> {
                    item { Text("Low-stock reorder list", style = MaterialTheme.typography.titleLarge)
                        Text("Suggested quantity fills stock to twice the minimum (at least one unit). Edit quantities and assign a supplier. Stock changes only when you save a received purchase.") }
                    if (vm.reorderProducts().isEmpty()) item { Text("No low-stock items or saved plans.") }
                    items(vm.reorderProducts(), key = { it.id }) { p ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), Arrangement.spacedBy(6.dp)) {
                            Text("${p.name} ${p.size}", fontWeight = FontWeight.Bold)
                            Text("Stock ${CalculationEngine.formatQuantity(p.currentStock)} · Minimum ${CalculationEngine.formatQuantity(p.minimumStock)} ${p.unit}")
                            Text("Order ${CalculationEngine.formatQuantity(vm.suggested(p))} ${p.unit}")
                            Text(suppliers.find { it.id == vm.supplierFor(p) }?.name ?: "Supplier not assigned")
                            TextButton({ reorderEdit = p }, enabled = !vm.busy) { Text("Edit quantity / supplier") }
                            if (vm.plans.any { it.productId == p.id }) TextButton({ vm.clearPlan(p.id) }, enabled = !vm.busy) { Text("Reset saved plan") }
                        } }
                    }
                    items(suppliers.filter { supplier -> vm.reorderProducts().any { vm.supplierFor(it) == supplier.id } }, key = { "supplier-${it.id}" }) { supplier ->
                        Button({ vm.orderSupplier(supplier.id) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Receive order: ${supplier.name}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SupplierDialog(original: SupplierEntity, vm: TradeViewModel, dismiss: () -> Unit) {
    var name by rememberSaveable(original.id) { mutableStateOf(original.name) }
    var phone by rememberSaveable(original.id) { mutableStateOf(original.phone) }
    var address by rememberSaveable(original.id) { mutableStateOf(original.address) }
    var contact by rememberSaveable(original.id) { mutableStateOf(original.contactName) }
    var gst by rememberSaveable(original.id) { mutableStateOf(original.gstNumber) }
    AlertDialog(onDismissRequest = { if (!vm.busy) dismiss() }, title = { Text("Supplier details") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
            Entry("Name *", name, { name = it }, enabled = !vm.busy); Entry("Contact person", contact, { contact = it }, enabled = !vm.busy)
            Entry("Phone", phone, { phone = it }, enabled = !vm.busy); Entry("Address", address, { address = it }, enabled = !vm.busy)
            Entry("GST number", gst, { gst = it }, enabled = !vm.busy); vm.error?.let { ErrorMessage(it) }
        }
    }, confirmButton = { TextButton({ vm.supplier(original.copy(name = name, phone = phone, address = address, contactName = contact, gstNumber = gst), dismiss) }, enabled = !vm.busy && name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(dismiss, enabled = !vm.busy) { Text("Cancel") } })
}

@Composable
private fun SupplierPaymentDialog(purchase: PurchaseSummary, vm: TradeViewModel, dismiss: () -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }; var method by rememberSaveable { mutableStateOf("CASH") }
    var reference by rememberSaveable { mutableStateOf("") }; val id = rememberSaveable { UUID.randomUUID().toString() }
    AlertDialog(onDismissRequest = { if (!vm.busy) dismiss() }, title = { Text("Record supplier payment") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
            Text("${purchase.supplier} · ${purchase.bill.supplierBillNumber}\nDue ${CalculationEngine.formatRupees(purchase.due)}")
            Entry("Amount paid (₹)", amount, { amount = it }, true, !vm.busy); Methods(method, !vm.busy) { method = it }
            Entry("Payment reference / note", reference, { reference = it }, enabled = !vm.busy); vm.error?.let { ErrorMessage(it) }
        }
    }, confirmButton = { TextButton({ vm.pay(id, purchase.bill.id, amount, method, reference, dismiss) }, enabled = !vm.busy) { Text("Record payment") } },
        dismissButton = { TextButton(dismiss, enabled = !vm.busy) { Text("Cancel") } })
}

@Composable
private fun ReorderDialog(p: ProductEntity, vm: TradeViewModel, dismiss: () -> Unit) {
    var qty by rememberSaveable { mutableStateOf(CalculationEngine.formatQuantity(vm.suggested(p))) }
    var supplier by rememberSaveable { mutableStateOf(vm.supplierFor(p)) }
    var pick by remember { mutableStateOf(false) }
    if (pick) Picker("Assign supplier", vm.data?.suppliers.orEmpty().filter { it.isActive }, { it.name }, { supplier = it.id; pick = false }, { pick = false })
    AlertDialog(onDismissRequest = { if (!vm.busy) dismiss() }, title = { Text(p.name) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Entry("Order quantity (${p.unit})", qty, { qty = it }, true, !vm.busy)
            OutlinedButton({ pick = true }, enabled = !vm.busy) { Text(vm.data?.suppliers?.find { it.id == supplier }?.name ?: "Choose supplier") }
            vm.error?.let { ErrorMessage(it) }
        }
    }, confirmButton = { TextButton({ vm.savePlan(p, qty, supplier, dismiss) }, enabled = !vm.busy) { Text("Save plan") } },
        dismissButton = { TextButton(dismiss, enabled = !vm.busy) { Text("Cancel") } })
}

@Composable
fun ReturnsScreen(onNavigateUp: () -> Unit, initialBillId: Long = 0, onReceipt: (Long) -> Unit, vm: TradeViewModel = hiltViewModel()) {
    val bluetooth = rememberBluetoothGate(vm::printerError)
    LaunchedEffect(initialBillId, vm.busy) { if (!vm.busy && initialBillId > 0 && vm.returns.billId == 0L && vm.lastReturn == null && vm.original == null && vm.error == null) vm.loadBill(initialBillId) }
    var pick by remember { mutableStateOf(false) }
    val form = vm.returns
    vm.returnDetail?.let { detail -> AlertDialog(onDismissRequest = vm::closeReturnDetail,
        title = { Text("Return for bill ${detail.originalNumber}") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
                detail.items.forEach { (item, returned) ->
                    Text("${item.productName} ${item.productSize}", fontWeight = FontWeight.Bold)
                    Text("${CalculationEngine.formatQuantity(returned.quantity)} ${item.productUnit} · ${if (returned.restock) "Restocked" else "Not restocked"}")
                    AmountRow("Returned value", returned.totalPaisa)
                }
                HorizontalDivider(); AmountRow("Customer debt reduced", detail.header.creditReductionPaisa)
                val net = detail.replacementTotal - detail.header.refundPaisa
                AmountRow(if (net >= 0) "Collect from customer" else "Refund to customer", kotlin.math.abs(net))
                Text("Method: ${detail.header.method}"); Text(detail.header.reason)
                vm.message?.let { Text(it) }; vm.error?.let { ErrorMessage(it) }
            }
        }, confirmButton = { TextButton({ bluetooth { vm.printReturn() } }, enabled = !vm.busy) { Text("Print return receipt") } },
        dismissButton = { TextButton(vm::closeReturnDetail, enabled = !vm.busy) { Text("Close") } }) }
    if (pick) Picker("Replacement product", vm.data?.products.orEmpty().filter { it.isActive },
        { "${it.name} ${it.size} / ${CalculationEngine.formatRupees(it.sellingPricePaisa)}" }, { product ->
            if (form.replacements.none { it.productId == product.id }) vm.editReturn(form.copy(replacements = form.replacements + ReplacementInput(product.id)))
            pick = false
        }, { pick = false })
    vm.quote?.let { quote -> AlertDialog(onDismissRequest = { if (!vm.busy) vm.closeQuote() }, title = { Text("Confirm return / exchange") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
            AmountRow("Return value", quote.total); AmountRow("Reduce customer debt", quote.debtReduction)
            AmountRow("Return refund / exchange allowance", quote.refund); AmountRow("Replacement bill", quote.replacement)
            HorizontalDivider()
            AmountRow(if (quote.collect >= 0) "Collect from customer" else "Refund to customer", kotlin.math.abs(quote.collect))
            Text("Settlement via ${form.method}. Confirm only after checking the goods and settlement. Refunds and payments are recorded here; the app does not transfer money.")
            Text("Returns of credit bills reduce the customer's overall outstanding debt first. Only items marked Restock are added back.", style = MaterialTheme.typography.bodySmall)
            vm.error?.let { ErrorMessage(it) }
        }
    }, confirmButton = { TextButton(vm::saveReturn, enabled = !vm.busy && form.reason.isNotBlank()) { Text("CONFIRM & SAVE") } },
        dismissButton = { TextButton(vm::closeQuote, enabled = !vm.busy) { Text("Review") } }) }
    Scaffold(topBar = { Column { AppTopBar("Returns & Exchanges", onNavigateUp = onNavigateUp)
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            vm.error?.let { item { ErrorMessage(it) } }; vm.message?.let { item { Text(it, fontWeight = FontWeight.Bold) } }
            item { Entry("Original bill number (e.g. 000001)", form.number, { vm.editReturn(form.copy(number = it)) }, enabled = !vm.busy) }
            item { Button({ vm.loadBill() }, enabled = !vm.busy) { Text("Find bill") } }
            vm.original?.let { original ->
                item { Text("Bill ${original.bill.billNumber} · ${original.bill.customerName.ifBlank { "Walk-in customer" }}", fontWeight = FontWeight.Bold)
                    Text("Enter quantities only for items being returned. Leave other rows blank.") }
                items(form.lines, key = { it.itemId }) { input ->
                    val item = original.items.find { it.id == input.itemId }
                    if (item != null) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), Arrangement.spacedBy(6.dp)) {
                        Text("${item.productName} ${item.productSize}", fontWeight = FontWeight.Bold)
                        Text("Sold ${CalculationEngine.formatQuantity(item.quantity)} · Already returned ${CalculationEngine.formatQuantity(original.returned[item.id] ?: 0.0)} ${item.productUnit}")
                        fun change(row: ReturnInput) = vm.editReturn(form.copy(lines = form.lines.map { if (it.itemId == input.itemId) row else it }))
                        Entry("Return quantity", input.qty, { change(input.copy(qty = it)) }, true, !vm.busy)
                        if (item.productId > 0) Row { Checkbox(input.restock, { change(input.copy(restock = it)) }, enabled = !vm.busy); Text("Restock sellable goods", Modifier.padding(top = 12.dp)) }
                        else Text("Manual bill item — no stock to restore")
                    } }
                }
                item { Text("Replacement items (optional)", style = MaterialTheme.typography.titleMedium)
                    Text("Replacement items use current prices, with the original bill's GST on/off setting. No replacement discount is applied.", style = MaterialTheme.typography.bodySmall) }
                items(form.replacements, key = { "replacement-${it.productId}" }) { line ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(vm.data?.products?.find { it.id == line.productId }?.name ?: "Unavailable product")
                        Entry("Replacement quantity", line.qty, { value -> vm.editReturn(form.copy(replacements = form.replacements.map { if (it.productId == line.productId) it.copy(qty = value) else it })) }, true, !vm.busy)
                        TextButton({ vm.editReturn(form.copy(replacements = form.replacements.filterNot { it.productId == line.productId })) }, enabled = !vm.busy) { Text("Remove") }
                    } }
                }
                item { OutlinedButton({ pick = true }, enabled = !vm.busy) { Text("+ Add replacement") } }
                item { Entry("Return reason *", form.reason, { vm.editReturn(form.copy(reason = it)) }, enabled = !vm.busy) }
                item { Methods(form.method, !vm.busy) { vm.editReturn(form.copy(method = it)) } }
                item { Button(vm::previewReturn, enabled = !vm.busy && form.reason.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("PREVIEW SETTLEMENT") } }
            }
            item { HorizontalDivider(); Text("Return history", style = MaterialTheme.typography.titleLarge) }
            items(vm.data?.returns.orEmpty(), key = { it.id }) { row ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), Arrangement.spacedBy(6.dp)) {
                    Text("Return ${row.id.take(8)}", fontWeight = FontWeight.Bold)
                    Text(java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(row.createdAt)))
                    Text(row.reason); AmountRow("Returned value", row.totalPaisa)
                    TextButton({ vm.showReturn(row.id) }, enabled = !vm.busy) { Text("View return / print settlement") }
                    AmountRow("Customer debt reduced", row.creditReductionPaisa); AmountRow("Refund / exchange allowance (${row.method})", row.refundPaisa)
                    TextButton({ onReceipt(row.billId) }, enabled = !vm.busy) { Text("Original sale receipt") }
                    row.exchangeBillId?.let { id -> TextButton({ onReceipt(id) }, enabled = !vm.busy) { Text("Replacement sale receipt") } }
                } }
            }
        }
    }
}

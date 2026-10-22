package com.hardwareshop.pos.presentation.owner.misc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hardwareshop.pos.data.repository.WarrantyRepository
import com.hardwareshop.pos.presentation.common.AppTopBar
import com.hardwareshop.pos.util.WarrantyRules
import java.time.LocalDate

@Composable
fun WarrantyScreen(onNavigateUp: () -> Unit, viewModel: WarrantyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var search by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("All") }
    val innerPage = state.selected != null || state.editing || state.addingService
    BackHandler(enabled = innerPage && !state.busy) { viewModel.cancel() }
    val title = when {
        state.addingService -> "Add service entry"
        state.editing -> if (state.editingRecord == null) "New warranty record" else "Edit warranty record"
        state.selected != null -> "Warranty details"
        else -> "Warranty & Serial Register"
    }
    Scaffold(topBar = { AppTopBar(title, onNavigateUp = {
        if (!state.busy) { if (innerPage) viewModel.cancel() else onNavigateUp() }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Saved on this phone. Included in your backups.", style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { error -> item {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("warranty-error"))
                if (!state.editing && !state.addingService) TextButton(onClick = viewModel::refresh) { Text("Retry loading") }
            } }
            state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            when {
                state.editing -> item { WarrantyForm(state, viewModel) }
                state.addingService -> item { WarrantyServiceForm(state, viewModel) }
                state.selected != null -> {
                    val record = state.selected!!
                    item { WarrantyDetails(record) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = viewModel::edit, enabled = !state.busy) { Text("Edit record") }
                            Button(onClick = viewModel::beginService, enabled = !state.busy,
                                modifier = Modifier.testTag("warranty-add-service")) { Text("Add service") }
                        }
                        TextButton(onClick = viewModel::cancel) { Text("Back to register") }
                    }
                    item { Text("Service history (${record.services.size})", fontWeight = FontWeight.Bold) }
                    if (record.services.isEmpty()) item { Text("No service entries yet") }
                    items(record.services.sortedWith(compareByDescending<WarrantyRepository.Service> { it.date }.thenByDescending { it.createdAt }),
                        key = { it.id }) { service ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(service.date, fontWeight = FontWeight.Bold)
                                Text("Problem: ${service.problem}")
                                Text("Action / notes: ${service.action}")
                            }
                        }
                    }
                }
                else -> {
                    item {
                        Button(onClick = viewModel::newRecord, modifier = Modifier.fillMaxWidth().testTag("warranty-new"),
                            enabled = !state.loading) { Text("Add warranty record") }
                    }
                    item {
                        OutlinedTextField(search, { search = it }, label = { Text("Search serial, customer or product") },
                            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("warranty-search"))
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("All", "Active", "Expired").forEach { filter ->
                                FilterChip(selected = statusFilter == filter, onClick = { statusFilter = filter }, label = { Text(filter) })
                            }
                        }
                    }
                    val today = LocalDate.now()
                    val matches = state.records.filter { record ->
                        "${record.serial} ${record.customerName} ${record.customerPhone} ${record.productLabel} ${record.billNumber}"
                            .contains(search.trim(), ignoreCase = true) &&
                            (statusFilter == "All" || record.expired(today) == (statusFilter == "Expired"))
                    }
                    item { Text("${matches.size} records", style = MaterialTheme.typography.bodySmall) }
                    if (!state.loading && state.error == null && matches.isEmpty()) item { Text("No warranty records found") }
                    items(matches, key = { it.id }) { record ->
                        Card(Modifier.fillMaxWidth().clickable { viewModel.select(record) }.testTag("warranty-record-${record.id}")) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(record.productLabel, fontWeight = FontWeight.SemiBold)
                                Text("Serial: ${record.serial}")
                                Text(record.customerName + if (record.customerPhone.isBlank()) "" else " · ${record.customerPhone}")
                                Text("${if (record.expired(today)) "Expired" else "Active"} · Expires ${record.expiryDate}",
                                    color = if (record.expired(today)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WarrantyForm(state: WarrantyUiState, viewModel: WarrantyViewModel) {
    val draft = state.draft
    var choosing by remember(state.editingRecord?.id) { mutableStateOf(draft.productId == 0L) }
    var query by remember(state.editingRecord?.id) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val selectedProduct = state.products.firstOrNull { it.id == draft.productId }
        val selectedLabel = selectedProduct?.let { "${it.categoryName} · ${it.displayLabel}" }
            ?: state.editingRecord?.takeIf { it.productId == draft.productId }?.productLabel
        if (selectedLabel != null) {
            Text("Product: $selectedLabel", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { choosing = !choosing }, enabled = !state.busy) {
                Text(if (choosing) "Close product search" else "Change product")
            }
        }
        if (choosing) {
            OutlinedTextField(query, { query = it }, label = { Text("Find product by name or size") }, singleLine = true,
                enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("warranty-product-search"))
            val tokens = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            val choices = state.products.filter { product ->
                val text = "${product.categoryName} ${product.typeName} ${product.displayLabel} ${product.searchAliases}"
                tokens.all { text.contains(it, ignoreCase = true) }
            }
            choices.take(8).forEach { product ->
                OutlinedButton(onClick = {
                    viewModel.updateDraft { it.copy(productId = product.id) }
                    choosing = false
                    query = ""
                }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("warranty-product-${product.id}")) {
                    Text("${product.categoryName} · ${product.displayLabel}")
                }
            }
            if (choices.size > 8) Text("${choices.size} matches. Type more to find the exact product.", style = MaterialTheme.typography.bodySmall)
            if (choices.isEmpty()) Text("No saved products match")
        }
        WarrantyField("Serial number", draft.serial, "warranty-serial", !state.busy) { value -> viewModel.updateDraft { it.copy(serial = value) } }
        WarrantyField("Customer name", draft.customerName, "warranty-customer", !state.busy) { value -> viewModel.updateDraft { it.copy(customerName = value) } }
        WarrantyField("Customer phone (optional)", draft.customerPhone, "warranty-phone", !state.busy, KeyboardType.Phone) { value -> viewModel.updateDraft { it.copy(customerPhone = value) } }
        WarrantyField("Sale date (yyyy-MM-dd)", draft.saleDate, "warranty-sale-date", !state.busy) { value -> viewModel.updateDraft { it.copy(saleDate = value) } }
        WarrantyField("Warranty months (1–120)", draft.warrantyMonths, "warranty-months", !state.busy, KeyboardType.Number) { value -> viewModel.updateDraft { it.copy(warrantyMonths = value) } }
        val expiry = runCatching { WarrantyRules.expiry(draft.saleDate, WarrantyRules.months(draft.warrantyMonths)) }.getOrNull()
        expiry?.let { Text("Expires $it (coverage includes this date)", style = MaterialTheme.typography.bodySmall) }
        WarrantyField("Existing bill number (optional)", draft.billNumber, "warranty-bill", !state.busy) { value -> viewModel.updateDraft { it.copy(billNumber = value) } }
        Text("If entered, the bill must contain this product.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = viewModel::save, enabled = !state.busy && draft.productId > 0,
            modifier = Modifier.fillMaxWidth().testTag("warranty-save")) { Text(if (state.busy) "Saving…" else "Save warranty record") }
        OutlinedButton(onClick = viewModel::cancel, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
private fun WarrantyServiceForm(state: WarrantyUiState, viewModel: WarrantyViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.selected?.let { Text("${it.productLabel}\nSerial: ${it.serial}", fontWeight = FontWeight.SemiBold) }
        WarrantyField("Service date (yyyy-MM-dd)", state.serviceDate, "warranty-service-date", !state.busy) { viewModel.updateService(date = it) }
        OutlinedTextField(state.serviceProblem, { viewModel.updateService(problem = it) }, label = { Text("Problem reported") },
            enabled = !state.busy, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("warranty-service-problem"))
        OutlinedTextField(state.serviceAction, { viewModel.updateService(action = it) }, label = { Text("Action taken / service notes") },
            enabled = !state.busy, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("warranty-service-action"))
        Text("Service entries stay in the history after saving.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = viewModel::saveService, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("warranty-service-save")) { Text(if (state.busy) "Saving…" else "Save service entry") }
        OutlinedButton(onClick = viewModel::cancel, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
private fun WarrantyDetails(record: WarrantyRepository.Record) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(record.productLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Serial: ${record.serial}")
            Text("Customer: ${record.customerName}")
            if (record.customerPhone.isNotBlank()) Text("Phone: ${record.customerPhone}")
            Text("Sale date: ${record.saleDate}")
            Text("Warranty: ${record.warrantyMonths} months")
            Text("Expires: ${record.expiryDate}")
            Text(if (record.expired()) "Expired" else "Active", fontWeight = FontWeight.Bold,
                color = if (record.expired()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            if (record.billNumber.isNotBlank()) Text("Linked bill: #${record.billNumber}")
        }
    }
}

@Composable
private fun WarrantyField(label: String, value: String, tag: String, enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType), modifier = Modifier.fillMaxWidth().testTag(tag))
}

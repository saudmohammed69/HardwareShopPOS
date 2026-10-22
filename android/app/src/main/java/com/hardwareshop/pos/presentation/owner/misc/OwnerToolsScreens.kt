// Compose state delegate writes are observed during recomposition; retain the UI state updates.
@file:Suppress("AssignedValueIsNeverRead")

package com.hardwareshop.pos.presentation.owner.misc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.data.local.entity.DayClosingEntity
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.presentation.common.*
import com.hardwareshop.pos.util.CalculationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OwnerToolsState(val days: Long = 1, val report: SalesReport? = null,
    val closings: List<DayClosingEntity> = emptyList(), val loading: Boolean = false,
    val error: String? = null, val message: String? = null)

@HiltViewModel
class OwnerToolsViewModel @Inject constructor(private val repository: OwnerToolsRepository) : ViewModel() {
    private val _state = MutableStateFlow(OwnerToolsState())
    val state = _state.asStateFlow()
    private var reportJob: Job? = null
    init {
        load(1)
        viewModelScope.launch { repository.observeClosings().collect { rows -> _state.update { it.copy(closings = rows) } } }
    }
    fun load(days: Long) {
        reportJob?.cancel()
        _state.update { it.copy(days = days, loading = true, error = null) }
        reportJob = viewModelScope.launch {
            try { val report = repository.report(days); _state.update { it.copy(report = report) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Report unavailable") } }
            finally { _state.update { it.copy(loading = false) } }
        }
    }
    fun closeDay() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try { repository.closeToday(); _state.update { it.copy(message = "Today's closing snapshot is saved on this device.") } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Closing failed") } }
            finally { _state.update { it.copy(loading = false) } }
        }
    }
}

@Composable
private fun SalesSummary(report: SalesReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${report.totals.billCount} completed bills", fontWeight = FontWeight.Bold)
            AmountRow("Net sales incl. GST", report.totals.sales)
            AmountRow("Discounts", report.totals.discounts)
            AmountRow("GST collected on sales", report.totals.tax)
            HorizontalDivider()
            AmountRow("Cash sales less cash refunds", report.totals.cash)
            AmountRow("UPI", report.totals.upi)
            AmountRow("Card", report.totals.card)
            AmountRow("Credit given less return adjustments", report.totals.credit)
            Text("Cancelled bills excluded. Returns are deducted on the return date. Credit recoveries, expenses and opening cash are separate. Top products below show original gross sales before returns.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ReportsScreen(onNavigateUp: () -> Unit, viewModel: OwnerToolsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    Scaffold(topBar = { AppTopBar("Sales Reports", onNavigateUp = onNavigateUp) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1L to "Today", 7L to "7 days", 30L to "30 days").forEach { (days, label) ->
                    FilterChip(state.days == days, { viewModel.load(days) }, label = { Text(label) })
                }
            } }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { ErrorMessage(it) } }
            state.report?.let { report ->
                item { SalesSummary(report) }
                item { SectionHeader("Top 20 products by value")
                    Text("Product values before bill discounts and GST", style = MaterialTheme.typography.bodySmall) }
                if (report.products.isEmpty()) item { Text("No sales in this period") }
                items(report.products) { product ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(product.name, fontWeight = FontWeight.Bold)
                        Text("${CalculationEngine.formatQuantity(product.quantity)} ${product.unit} • ${CalculationEngine.formatRupees(product.grossSales)}")
                    } }
                }
            }
        }
    }
}

@Composable
fun DayCloseScreen(onNavigateUp: () -> Unit, viewModel: OwnerToolsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Save today's closing?") },
        text = { Text("This saves a sales snapshot. Billing stays available. If more bills are made or cancelled, save again to update today's snapshot.") },
        confirmButton = { TextButton({ confirm = false; viewModel.closeDay() }) { Text("SAVE") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Back") } })
    Scaffold(topBar = { AppTopBar("Day Closing", onNavigateUp = onNavigateUp) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.report?.let { item { SalesSummary(it) } }
            state.error?.let { item { ErrorMessage(it) } }
            state.message?.let { item { Text(it, color = AppColors.Success) } }
            item { Button({ confirm = true }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("SAVE TODAY'S CLOSING") } }
            item { SectionHeader("Saved closings — latest 30") }
            items(state.closings, key = { it.id }) { closing ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(closing.closingDate, fontWeight = FontWeight.Bold)
                    AmountRow("Sales • ${closing.totalBillCount} bills", closing.totalSalesPaisa)
                    AmountRow("Cash paid on bills", closing.cashSalesPaisa)
                    Text("Snapshot saved ${java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(closing.createdAt))}", style = MaterialTheme.typography.bodySmall)
                } }
            }
        }
    }
}

data class CustomerToolsState(val customers: List<Customer> = emptyList(), val query: String = "",
    val onlyDue: Boolean = false, val adding: Boolean = false, val name: String = "",
    val phone: String = "", val address: String = "", val gst: String = "", val editing: Customer? = null, val saving: Boolean = false, val error: String? = null)

@HiltViewModel
class CustomerToolsViewModel @Inject constructor(private val repository: CustomerRepository) : ViewModel() {
    private val _state = MutableStateFlow(CustomerToolsState())
    val state = _state.asStateFlow()
    init { viewModelScope.launch { repository.observeActiveCustomers().collect { list -> _state.update { it.copy(customers = list) } } } }
    fun update(block: CustomerToolsState.() -> CustomerToolsState) = _state.update(block)
    fun create() {
        val current = _state.value
        if (current.saving) return
        if (current.name.isBlank()) { update { copy(error = "Customer name is required") }; return }
        update { copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val result = if (current.editing != null) repository.updateCustomer(current.editing.copy(
                    name = current.name, phone = current.phone, address = current.address, gstNumber = current.gst))
                else repository.createCustomer(current.name, current.phone, current.address, current.gst)
                when (result) {
                    is Result.Success -> update { copy(adding = false, name = "", phone = "", address = "", gst = "", editing = null) }
                    is Result.Error -> update { copy(error = result.message) }
                    else -> Unit
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { update { copy(error = e.message ?: "Could not add customer") } }
            finally { update { copy(saving = false) } }
        }
    }
}

@Composable
fun CustomersScreen(onNavigateUp: () -> Unit, onCredit: (Long) -> Unit, viewModel: CustomerToolsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    if (state.adding) AlertDialog(onDismissRequest = { if (!state.saving) viewModel.update { copy(adding = false) } },
        title = { Text(if (state.editing == null) "Add Customer" else "Edit Customer") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.name, { viewModel.update { copy(name = it) } }, label = { Text("Name *") })
            OutlinedTextField(state.phone, { viewModel.update { copy(phone = it) } }, label = { Text("Phone") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            OutlinedTextField(state.address, { viewModel.update { copy(address = it) } }, label = { Text("Address") })
            OutlinedTextField(state.gst, { viewModel.update { copy(gst = it) } }, label = { Text("GST number (optional)") })
            state.error?.let { ErrorMessage(it) }
        } }, confirmButton = { TextButton(viewModel::create, enabled = !state.saving) { Text("SAVE") } },
        dismissButton = { TextButton({ viewModel.update { copy(adding = false) } }, enabled = !state.saving) { Text("Cancel") } })
    Scaffold(topBar = { AppTopBar("Customers & Credit", onNavigateUp = onNavigateUp) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Button({ viewModel.update { copy(adding = true, editing = null, name = "", phone = "", address = "", gst = "", error = null) } }, modifier = Modifier.fillMaxWidth()) { Text("ADD CUSTOMER") } }
            item { OutlinedTextField(state.query, { viewModel.update { copy(query = it) } }, label = { Text("Search name or phone") }, modifier = Modifier.fillMaxWidth()) }
            item { FilterChip(state.onlyDue, { viewModel.update { copy(onlyDue = !onlyDue) } }, label = { Text("Outstanding credit only") }) }
            val visible = state.customers.filter { (!state.onlyDue || it.outstandingBalancePaisa > 0) &&
                (it.name.contains(state.query, true) || it.phone.contains(state.query)) }
                .sortedWith(compareByDescending<Customer> { if (state.onlyDue) it.outstandingBalancePaisa else 0 }.thenBy { it.name })
            if (visible.isEmpty()) item { Text("No customers found. Add a customer before making a credit bill.") }
            items(visible, key = { it.id }) { customer ->
                Card(Modifier.fillMaxWidth().clickable { onCredit(customer.id) }) { Column(Modifier.padding(12.dp)) {
                    Text(customer.name, fontWeight = FontWeight.Bold)
                    if (customer.phone.isNotBlank()) Text(customer.phone)
                    AmountRow(if (customer.outstandingBalancePaisa < 0) "Customer credit / refund due" else "Outstanding", customer.outstandingBalancePaisa)
                    Text("View ledger and record payment", style = MaterialTheme.typography.bodySmall)
                    TextButton({ viewModel.update { copy(adding = true, editing = customer, name = customer.name,
                        phone = customer.phone, address = customer.address, gst = customer.gstNumber, error = null) } }) { Text("EDIT DETAILS") }
                } }
            }
        }
    }
}

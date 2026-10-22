package com.hardwareshop.pos.presentation.owner.misc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.repository.QuotationRepository
import com.hardwareshop.pos.presentation.common.*
import com.hardwareshop.pos.presentation.worker.billing.BillingUiState
import com.hardwareshop.pos.presentation.worker.billing.BillingViewModel
import com.hardwareshop.pos.util.CalculationEngine
import com.hardwareshop.pos.util.PipeBilling
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QuotationCartViewModel @Inject constructor(private val repository: QuotationRepository) : ViewModel() {
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var record by mutableStateOf<QuotationRepository.Record?>(null); private set
    var conversion by mutableStateOf<QuotationRepository.Conversion?>(null); private set

    fun save(state: BillingUiState, name: String, phone: String, validUntil: String, notes: String, done: (String) -> Unit) {
        if (busy || state.isLoading || state.savedBillId != null) return
        busy = true; error = null
        viewModelScope.launch {
            try {
                val saved = repository.saveFromCart(state.cart.toList(), name, phone, validUntil, notes,
                    state.discountPaisa, state.gstEnabled, state.gstInclusive)
                done(saved.number)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not save quotation" }
            finally { busy = false }
        }
    }

    fun load(id: String) {
        busy = true; error = null; record = null; conversion = null
        viewModelScope.launch {
            try {
                val saved = repository.snapshot().firstOrNull { it.id == id } ?: throw IllegalArgumentException("Quotation is no longer available")
                val current = repository.prepareConversion(saved)
                record = saved; conversion = current
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not review quotation" }
            finally { busy = false }
        }
    }

    fun loadIntoCart(billing: BillingViewModel, done: () -> Unit) {
        val saved = record ?: return
        if (busy) return
        busy = true; error = null
        viewModelScope.launch {
            try {
                val current = repository.prepareConversion(saved)
                billing.importQuotation(saved, current.items, done)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not load quotation" }
            finally { busy = false }
        }
    }
}

@Composable
fun CartQuotationAction(state: BillingUiState, viewModel: QuotationCartViewModel = hiltViewModel()) {
    var open by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var validity by rememberSaveable { mutableStateOf(QuotationRepository.defaultValidUntil()) }
    var notes by rememberSaveable { mutableStateOf("") }
    var savedNumber by remember { mutableStateOf<String?>(null) }
    TextButton({
        name = state.customerName.ifBlank { state.customer?.name.orEmpty() }
        phone = state.customerPhone.ifBlank { state.customer?.phone.orEmpty() }
        notes = state.notes; savedNumber = null; open = true
    }, enabled = !state.isLoading && state.savedBillId == null && state.cart.isNotEmpty(),
        modifier = Modifier.testTag("save-quotation-action")) { Text("QUOTATION", color = AppColors.OnPrimary) }
    if (open) AlertDialog(onDismissRequest = { if (!viewModel.busy) open = false },
        title = { Text("Save quotation") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Save these items and rates as an estimate. Stock and customer dues change only when a bill is saved.")
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Customer name *") },
                modifier = Modifier.testTag("quotation-customer"), singleLine = true, enabled = !viewModel.busy)
            OutlinedTextField(phone, { phone = it.take(40) }, label = { Text("Phone") }, singleLine = true, enabled = !viewModel.busy)
            OutlinedTextField(validity, { validity = it.take(10) }, label = { Text("Valid until (YYYY-MM-DD) *") },
                singleLine = true, enabled = !viewModel.busy)
            OutlinedTextField(notes, { notes = it.take(1000) }, label = { Text("Notes / terms") }, enabled = !viewModel.busy)
            Text("Total: ${CalculationEngine.formatRupees(state.calculation?.totalPaisa ?: 0)}")
            viewModel.error?.let { ErrorMessage(it) }
        } },
        confirmButton = { TextButton({ viewModel.save(state, name, phone, validity, notes) { number ->
            savedNumber = number; open = false
        } }, enabled = !viewModel.busy && name.isNotBlank(), modifier = Modifier.testTag("save-quotation-confirm")) {
            Text(if (viewModel.busy) "SAVING…" else "SAVE QUOTATION")
        } }, dismissButton = { TextButton({ open = false }, enabled = !viewModel.busy) { Text("CANCEL") } })
    savedNumber?.let { number -> AlertDialog(onDismissRequest = { savedNumber = null },
        title = { Text("Quotation saved") }, text = { Text("$number\nYour cart is kept. Find the estimate in Owner Panel → Quotations to share it or convert it into a bill.") },
        confirmButton = { TextButton({ savedNumber = null }) { Text("OK") } }) }
}

@Composable
fun QuotationImportScreen(id: String, billing: BillingViewModel, onNavigateUp: () -> Unit, onImported: () -> Unit,
    viewModel: QuotationCartViewModel = hiltViewModel()) {
    val state by billing.state.collectAsState()
    LaunchedEffect(id) { viewModel.load(id) }
    Scaffold(topBar = { AppTopBar("Review quotation", onNavigateUp) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (viewModel.busy || state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            viewModel.error?.let { ErrorMessage(it) }; state.error?.let { ErrorMessage(it) }
            val record = viewModel.record; val conversion = viewModel.conversion
            if (record != null && conversion != null) {
                Text(record.number, style = MaterialTheme.typography.titleLarge)
                Text("${record.customerName} · valid until ${record.validUntil}")
                if (conversion.expired) Text("This quotation has expired. Confirm the terms with the customer before billing.", color = AppColors.Warning)
                Text("${conversion.changedPriceCount} items have changed rates or tax. The bill will use current catalogue rates.")
                conversion.items.forEach { item -> Text("${item.product.displayLabel}\n${CalculationEngine.formatRupees(PipeBilling.amount(item))}") }
                val calculation = runCatching { QuotationRepository.calculateSnapshot(conversion.items,
                    record.discountPaisa, record.gstEnabled, record.gstInclusive) }.getOrNull()
                Text("Quoted total: ${CalculationEngine.formatRupees(record.totalPaisa)}")
                calculation?.let { Text("Current total: ${CalculationEngine.formatRupees(it.totalPaisa)}", style = MaterialTheme.typography.titleMedium) }
                if (state.cart.isNotEmpty()) Text("An unfinished bill is open. Loading this quotation replaces that cart. Go back and park it first if you want to keep it.", color = AppColors.Warning)
                Button({ viewModel.loadIntoCart(billing, onImported) }, enabled = !state.isLoading && !state.recoveryFailed &&
                    state.savedBillId == null && !viewModel.busy && calculation != null,
                    modifier = Modifier.fillMaxWidth().testTag("import-quotation-confirm")) {
                    Text(if (state.cart.isEmpty()) "LOAD INTO CART" else "REPLACE CART WITH QUOTATION")
                }
                Text("Review quantities, stock and payment in the cart. Loading a quotation does not save a sale.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onNavigateUp) { Text("CANCEL") }
        }
    }
}

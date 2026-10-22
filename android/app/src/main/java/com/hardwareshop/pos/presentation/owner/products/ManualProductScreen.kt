package com.hardwareshop.pos.presentation.owner.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.presentation.common.AppTopBar
import com.hardwareshop.pos.presentation.common.ErrorMessage
import com.hardwareshop.pos.util.CalculationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ManualProductReviewState(
    val bills: List<ManualBillReview> = emptyList(), val loading: Boolean = true,
    val busy: Boolean = false, val error: String? = null, val message: String? = null
)

@HiltViewModel
class ManualProductReviewViewModel @Inject constructor(private val repository: ManualProductRepository) : ViewModel() {
    private val _state = MutableStateFlow(ManualProductReviewState())
    val state = _state.asStateFlow()
    init {
        viewModelScope.launch {
            repository.observeBills().catch { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Cannot load manual bills") }
            }.collect { rows -> _state.update { it.copy(bills = rows, loading = false) } }
        }
    }
    private fun action(block: suspend () -> String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try { val message = block(); _state.update { it.copy(message = message) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Cannot review this item") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    fun review(key: String, accept: Boolean) = action {
        if (accept) repository.accept(key) else repository.reject(key)
        if (accept) "Added to Products & Prices. Issued bill unchanged." else "Removed from pending products. Issued bill unchanged."
    }
    fun edit(key: String, name: String, price: String) = action {
        repository.edit(key, name, price)
        "Review details saved. Press Accept to add this product."
    }
}

@Composable
fun ManualProductScreen(
    onNavigateUp: () -> Unit, onBill: (Long) -> Unit, onProducts: () -> Unit,
    viewModel: ManualProductReviewViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var confirmation by remember { mutableStateOf<Pair<ManualProductSuggestion, Boolean>?>(null) }
    var editing by remember { mutableStateOf<ManualProductSuggestion?>(null) }
    var editName by remember { mutableStateOf("") }
    var editPrice by remember { mutableStateOf("") }
    confirmation?.let { (item, accept) ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(if (accept) "Add this item to Products?" else "Reject this new product?") },
            text = { Text("${item.name} · ${CalculationEngine.formatRupees(item.pricePaisa)} per Piece\n" +
                if (accept) "The product starts with zero stock. The issued bill stays unchanged."
                else "It will leave the pending list. The item stays on the issued bill.") },
            confirmButton = { TextButton({
                viewModel.review(item.key, accept); confirmation = null
            }, enabled = !state.busy) { Text(if (accept) "ACCEPT" else "REJECT") } },
            dismissButton = { TextButton({ confirmation = null }) { Text("CANCEL") } })
    }
    editing?.let { item ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Edit new product details") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(editName, { editName = it.take(120) }, label = { Text("Item name") }, singleLine = true)
                OutlinedTextField(editPrice, { editPrice = it }, label = { Text("Price per piece ₹") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                Text("These changes apply to the proposed product. The issued bill stays unchanged.")
            } },
            confirmButton = { TextButton({
                viewModel.edit(item.key, editName, editPrice); editing = null
            }, enabled = !state.busy && editName.isNotBlank() && (CalculationEngine.parseRupeesToPaisa(editPrice) ?: 0) > 0) { Text("SAVE CHANGES") } },
            dismissButton = { TextButton({ editing = null }) { Text("CANCEL") } })
    }
    Scaffold(topBar = { AppTopBar("Manually Added Bills", onNavigateUp = onNavigateUp) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Manual items appear here after the bill is saved. Review the full bill, then choose which new products to accept. No product is added or removed automatically.")
                TextButton(onProducts) { Text("MANAGE PRODUCTS · ADD / EDIT / REMOVE") }
            }
            state.error?.let { item { ErrorMessage(it) } }
            state.message?.let { message -> item { Text(message) } }
            if (state.loading) item { CircularProgressIndicator() }
            else if (state.bills.isEmpty()) item { Text("No finalized bills with manual items") }
            items(state.bills, key = { it.bill.id }) { review ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Bill #${review.bill.billNumber}", style = MaterialTheme.typography.titleMedium)
                        Text("${review.bill.workerName.ifBlank { "Owner" }} · ${review.bill.customerName.ifBlank { "Walk-in customer" }} · ${review.bill.status}")
                        Text("Bill total: ${CalculationEngine.formatRupees(review.bill.totalPaisa)}")
                        TextButton({ onBill(review.bill.id) }) { Text("OPEN FULL BILL / RECEIPT") }
                        Text("Items as billed", style = MaterialTheme.typography.titleSmall)
                        review.items.forEach { line ->
                            Text("${line.productName}${if (line.productId == 0L) " · Manual" else ""}\n" +
                                "${CalculationEngine.formatQuantity(line.quantity)} ${line.productUnit} × ${CalculationEngine.formatRupees(line.unitPricePaisa)}")
                        }
                        HorizontalDivider()
                        Text("New products to review", style = MaterialTheme.typography.titleSmall)
                        review.manualItems.forEach { suggestion ->
                            Text("${suggestion.name} · ${CalculationEngine.formatRupees(suggestion.pricePaisa)} per Piece")
                            if (suggestion.status == "PENDING") {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton({
                                        editing = suggestion; editName = suggestion.name
                                        editPrice = java.math.BigDecimal.valueOf(suggestion.pricePaisa, 2).toPlainString()
                                    }, enabled = !state.busy) { Text("EDIT") }
                                    TextButton({ confirmation = suggestion to true }, enabled = !state.busy) { Text("ACCEPT") }
                                    TextButton({ confirmation = suggestion to false }, enabled = !state.busy) { Text("REJECT") }
                                }
                            } else Text(if (suggestion.status == "ACCEPTED") "Accepted into products" else "Rejected · kept on issued bill")
                        }
                    }
                }
            }
        }
    }
}

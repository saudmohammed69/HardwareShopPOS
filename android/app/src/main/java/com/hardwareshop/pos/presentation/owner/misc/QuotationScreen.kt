package com.hardwareshop.pos.presentation.owner.misc

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.repository.QuotationRepository
import com.hardwareshop.pos.data.repository.SettingsRepository
import com.hardwareshop.pos.domain.model.CartItem
import com.hardwareshop.pos.presentation.common.AppColors
import com.hardwareshop.pos.presentation.common.AppTopBar
import com.hardwareshop.pos.presentation.common.ErrorMessage
import com.hardwareshop.pos.util.CalculationEngine
import com.hardwareshop.pos.util.QuotationPdf
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class QuotationState(
    val records: List<QuotationRepository.Record> = emptyList(), val busy: Boolean = false,
    val error: String? = null, val message: String? = null, val shareUri: Uri? = null,
    val conversion: QuotationRepository.Conversion? = null, val conversionRecord: QuotationRepository.Record? = null
)

@HiltViewModel
class QuotationViewModel @Inject constructor(
    private val quotations: QuotationRepository, private val settings: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val mutable = MutableStateFlow(QuotationState())
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch {
            quotations.observe().catch { error -> mutable.update { it.copy(error = error.message ?: "Could not load quotations") } }
                .collect { rows -> mutable.update { it.copy(records = rows) } }
        }
    }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun reportError(message: String) { mutable.update { it.copy(error = message) } }
    fun shareConsumed() { mutable.update { it.copy(shareUri = null) } }
    fun dismissConversion() { mutable.update { it.copy(conversion = null, conversionRecord = null) } }
    private fun action(work: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try { work() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "Could not complete quotation action") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun remove(record: QuotationRepository.Record) = action {
        quotations.remove(record)
        mutable.update { it.copy(message = "Quotation deleted") }
    }
    fun convert(record: QuotationRepository.Record) = action {
        val prepared = quotations.prepareConversion(record)
        mutable.update { it.copy(conversion = prepared, conversionRecord = record) }
    }
    fun share(record: QuotationRepository.Record) = action {
        val current = quotations.snapshot().firstOrNull { it.id == record.id } ?: error("This quotation was removed")
        val shop = settings.getShopInfo()
        val file = withContext(Dispatchers.IO) { QuotationPdf.create(context, current, shop) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        mutable.update { it.copy(shareUri = uri) }
    }
}

@Composable
fun QuotationScreen(onNavigateUp: () -> Unit, onNewQuotation: () -> Unit,
    onConvert: (QuotationRepository.Record, List<CartItem>) -> Unit, viewModel: QuotationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<QuotationRepository.Record?>(null) }
    val selected = state.records.firstOrNull { it.id == selectedId }
    LaunchedEffect(selectedId, state.error, state.message) { listState.scrollToItem(0) }
    LaunchedEffect(state.shareUri) {
        state.shareUri?.let { uri ->
            try {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = android.content.ClipData.newRawUri("Quotation", uri)
                }
                context.startActivity(Intent.createChooser(share, "Share quotation PDF"))
            } catch (e: Exception) { viewModel.reportError(e.message ?: "No app can share this PDF") }
            finally { viewModel.shareConsumed() }
        }
    }
    delete?.let { record ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete this quotation?") },
            text = { Text("${record.number} · ${record.customerName}\nThe saved estimate will be removed.") },
            confirmButton = { TextButton({ delete = null; selectedId = null; viewModel.remove(record) }, enabled = !state.busy) { Text("DELETE") } },
            dismissButton = { TextButton({ delete = null }) { Text("KEEP") } })
    }
    val conversion = state.conversion
    val conversionRecord = state.conversionRecord
    if (conversion != null && conversionRecord != null) {
        AlertDialog(onDismissRequest = viewModel::dismissConversion, title = { Text("Review quotation in Billing") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (conversion.expired) Text("This quotation expired on ${conversionRecord.validUntil}.", color = MaterialTheme.colorScheme.error)
                if (conversion.changedPriceCount > 0) Text("${conversion.changedPriceCount} item(s) have different prices or GST. Billing will use current catalogue prices.")
                Text("Manual items keep their quoted prices. Review customer details, discount, quantities and total before checkout.")
                Text("Stock and payment are recorded when the bill is saved.")
            } },
            confirmButton = { TextButton({
                viewModel.dismissConversion(); onConvert(conversionRecord, conversion.items)
            }) { Text("REVIEW IN BILLING") } },
            dismissButton = { TextButton(viewModel::dismissConversion) { Text("CANCEL") } })
    }
    Scaffold(topBar = { AppTopBar("Quotations", onNavigateUp = { if (selectedId != null) selectedId = null else onNavigateUp() }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("quotation-list"), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { error -> item { ErrorMessage(error); TextButton(viewModel::dismissError) { Text("DISMISS") } } }
            state.message?.let { message -> item { Text(message, color = AppColors.Success) } }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (selected != null) {
                item {
                    Text(selected.number, style = MaterialTheme.typography.titleLarge)
                    Text(selected.customerName, style = MaterialTheme.typography.titleMedium)
                    if (selected.customerPhone.isNotBlank()) Text(selected.customerPhone)
                    Text("Valid through ${selected.validUntil}" + if (selected.isExpired()) " · Expired" else "")
                    selected.convertedBillId?.let { Text("Converted to a saved bill", color = AppColors.Success) }
                }
                items(selected.lines) { line ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(line.label, style = MaterialTheme.typography.titleSmall)
                        Text(line.quantityDisplay)
                        if (line.isPipe) Text("${CalculationEngine.formatRupees(line.unitPricePaisa)} / length · ${CalculationEngine.formatRupees(line.pipeFootPricePaisa)} / ft")
                        else Text("${CalculationEngine.formatRupees(line.unitPricePaisa)} / ${line.productUnit}")
                        Text(CalculationEngine.formatRupees(line.itemTotalPaisa), style = MaterialTheme.typography.titleMedium)
                        if (selected.gstEnabled) Text("GST ${CalculationEngine.formatGstRate(line.gstRateBp)}: ${CalculationEngine.formatRupees(line.gstAmountPaisa)}")
                    } }
                }
                item {
                    Text("Items: ${CalculationEngine.formatRupees(selected.grossSubtotalPaisa)}")
                    Text("Discount: ${CalculationEngine.formatRupees(selected.discountPaisa)}")
                    if (selected.gstEnabled) Text("GST ${if (selected.gstInclusive) "included" else "added"}: ${CalculationEngine.formatRupees(selected.gstAmountPaisa)}")
                    Text("Total: ${CalculationEngine.formatRupees(selected.totalPaisa)}", style = MaterialTheme.typography.titleLarge)
                    if (selected.notes.isNotBlank()) { Text("Notes / terms", style = MaterialTheme.typography.titleSmall); Text(selected.notes) }
                }
                item {
                    Button({ viewModel.share(selected) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("quotation-share")) { Text("SHARE PDF") }
                    Button({ viewModel.convert(selected) }, enabled = !state.busy && selected.convertedBillId == null,
                        modifier = Modifier.fillMaxWidth().testTag("quotation-convert")) { Text("CONVERT TO BILL") }
                    TextButton({ delete = selected }, enabled = !state.busy && selected.convertedBillId == null) { Text("DELETE QUOTATION") }
                }
            } else {
                item {
                    Text("Prepare an estimate from your billing cart, then convert it after approval.", style = MaterialTheme.typography.titleMedium)
                    Text("Saved on this phone and included in backups. Quotations do not sync between phones.", style = MaterialTheme.typography.bodySmall)
                    Button(onNewQuotation, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("quotation-new")) { Text("NEW QUOTATION") }
                    OutlinedTextField(query, { query = it.take(160) }, label = { Text("Find customer, phone, quotation or item") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("quotation-search"))
                }
                val matches = state.records.filter { quote ->
                    query.isBlank() || "${quote.number} ${quote.customerName} ${quote.customerPhone} ${quote.lines.joinToString(" ") { it.label }}".contains(query.trim(), true)
                }
                if (matches.isEmpty()) item { Text(if (state.records.isEmpty()) "No quotations saved yet." else "No matching quotations.") }
                items(matches, key = { it.id }) { record ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(record.customerName, style = MaterialTheme.typography.titleMedium)
                        Text(record.number, style = MaterialTheme.typography.bodySmall)
                        Text("${record.lines.size} item(s) · ${CalculationEngine.formatRupees(record.totalPaisa)}")
                        Text(if (record.convertedBillId != null) "Billed" else "Valid through ${record.validUntil}${if (record.isExpired()) " · Expired" else ""}")
                        TextButton({ selectedId = record.id }, modifier = Modifier.testTag("quotation-open-${record.id}")) { Text("VIEW QUOTATION") }
                    } }
                }
            }
        }
    }
}

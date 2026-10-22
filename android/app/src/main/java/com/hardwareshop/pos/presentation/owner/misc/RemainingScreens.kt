// Compose state delegate writes are observed during recomposition; retain the UI state updates.
@file:Suppress("AssignedValueIsNeverRead")

package com.hardwareshop.pos.presentation.owner.misc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.hardwareshop.pos.data.local.entity.BillEntity
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.data.sync.SyncJournal
import com.hardwareshop.pos.data.sync.SyncProtocol
import com.hardwareshop.pos.data.remote.CloudSyncRepository
import com.hardwareshop.pos.data.sync.FirestoreSyncTransport
import com.hardwareshop.pos.service.CloudSyncScheduler
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.presentation.common.*
import com.hardwareshop.pos.util.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable

// ─────────────────────────────────────────────────────────────────────────────
// BILL HISTORY
// ─────────────────────────────────────────────────────────────────────────────

data class BillHistoryState(
    val bills:         List<BillEntity> = emptyList(),
    val query: String = "",
    val hasMore: Boolean = true, val error: String? = null,
    val filterMethod:  String           = "",
    val filterStatus:  String           = "",
    val isLoading:     Boolean          = false
)

@HiltViewModel
class BillHistoryViewModel @Inject constructor(private val billingRepo: BillingRepository) : ViewModel() {
    private val _state = MutableStateFlow(BillHistoryState())
    val state: StateFlow<BillHistoryState> = _state.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null
    init { load() }
    fun setFilter(method: String = _state.value.filterMethod, status: String = _state.value.filterStatus) {
        _state.update { it.copy(filterMethod = method, filterStatus = status) }; load()
    }
    fun search(query: String) { _state.update { it.copy(query = query) }; load() }
    fun resetFilters() {
        _state.update { it.copy(query = "", filterMethod = "", filterStatus = "") }
        load()
    }
    fun loadMore() { if (!_state.value.isLoading && _state.value.hasMore) load(true) }
    private fun load(more: Boolean = false) {
        loadJob?.cancel()
        val current = _state.value
        _state.update { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                if (!more) kotlinx.coroutines.delay(200)
                val rows = billingRepo.getBillsFiltered(method = current.filterMethod, status = current.filterStatus,
                    limit = 50, offset = if (more) current.bills.size else 0, query = current.query.trim())
                _state.update { it.copy(bills = if (more) current.bills + rows else rows, hasMore = rows.size == 50, isLoading = false) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(isLoading = false, error = e.message ?: "Could not load bills") } }
        }
    }
}

@Composable
fun BillHistoryScreen(
    onNavigateUp: () -> Unit,
    onBillClick:  (Long) -> Unit,
    viewModel: BillHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    Scaffold(topBar = { AppTopBar("Bill History", onNavigateUp = onNavigateUp) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(state.query, viewModel::search, label = { Text("Bill number, customer or phone") },
                trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { viewModel.search("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear bill search")
                } },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(8.dp))
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("" to "All", "ACTIVE" to "Active", "CANCELLED" to "Cancelled").forEach { (key, label) ->
                    FilterChip(state.filterStatus == key, { viewModel.setFilter(status = key) }, label = { Text(label) })
                }
            }
            state.error?.let { ErrorMessage(it) }
            // Method filter chips
            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("" to "All", "CASH" to "Cash", "UPI" to "UPI", "SPLIT" to "Cash + UPI", "CARD" to "Card", "CREDIT" to "Credit").forEach { (k, l) ->
                    FilterChip(selected = state.filterMethod == k, onClick = { viewModel.setFilter(method = k) }, label = { Text(l) })
                }
            }
            if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.query.isNotBlank() || state.filterMethod.isNotEmpty() || state.filterStatus.isNotEmpty()) {
                TextButton(onClick = viewModel::resetFilters, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("RESET SEARCH & FILTERS")
                }
            }
            LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.bills.isEmpty() && !state.isLoading) item { Text("No bills match your search") }
                items(state.bills, key = { it.id }) { bill ->
                    Card(Modifier.fillMaxWidth().clickable { onBillClick(bill.id) }, elevation = CardDefaults.cardElevation(1.dp)) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("#${bill.billNumber}", fontWeight = FontWeight.Bold, color = AppColors.Primary)
                                if (bill.customerName.isNotBlank()) Text(bill.customerName, style = MaterialTheme.typography.bodySmall)
                                Text(bill.workerName, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(CalculationEngine.formatRupees(bill.totalPaisa), fontWeight = FontWeight.Bold)
                                Text("${bill.paymentMethod} • ${bill.status}", style = MaterialTheme.typography.bodySmall,
                                    color = if (bill.paymentMethod == "CREDIT") AppColors.Credit else AppColors.Success)
                                Text(fmt.format(Date(bill.createdAt)), style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                            }
                        }
                    }
                }
                if (state.hasMore) item { TextButton(viewModel::loadMore, enabled = !state.isLoading) { Text("LOAD MORE") } }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BILL VIEW  (detail + cancel)
// ─────────────────────────────────────────────────────────────────────────────

data class BillViewState(val bill: Bill? = null, val isLoading: Boolean = false, val error: String? = null)

@HiltViewModel
class BillViewViewModel @Inject constructor(private val billingRepo: BillingRepository) : ViewModel() {
    private val _state = MutableStateFlow(BillViewState())
    val state: StateFlow<BillViewState> = _state.asStateFlow()
    fun load(billId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            _state.update { it.copy(bill = billingRepo.getBillById(billId), isLoading = false) }
        }
    }
    fun cancelBill(billId: Long, reason: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val r = billingRepo.cancelBill(billId, reason)
            if (r is Result.Success) onDone()
            else if (r is Result.Error) _state.update { it.copy(error = r.message) }
        }
    }
}

@Composable
fun BillViewScreen(onReturn: (Long) -> Unit = {}, billId: Long, onNavigateUp: () -> Unit, onReceipt: (Long) -> Unit = {}, viewModel: BillViewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var showCancelDialog by remember { mutableStateOf(false) }
    var cancelReason by remember { mutableStateOf("") }
    LaunchedEffect(billId) { viewModel.load(billId) }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title   = { Text("Cancel Bill") },
            text    = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This will restore stock and reverse any credit. Provide a reason:")
                    if (state.bill?.paymentMethod == PaymentMethod.CREDIT) {
                        Text("If money was received or this customer has repayments/adjustments, use Returns to record the settlement.")
                        TextButton(onClick = { showCancelDialog = false; onReturn(billId) }) { Text("OPEN RETURNS") }
                    }
                    OutlinedTextField(value = cancelReason, onValueChange = { cancelReason = it }, label = { Text("Reason *") }, modifier = Modifier.fillMaxWidth())
                    state.error?.let { ErrorMessage(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelBill(billId, cancelReason) { showCancelDialog = false; onNavigateUp() } },
                    enabled = cancelReason.isNotBlank()) { Text("CANCEL BILL", color = AppColors.Error) }
            },
            dismissButton = { TextButton(onClick = { showCancelDialog = false }) { Text("Back") } }
        )
    }

    Scaffold(topBar = { AppTopBar("Bill Detail", onNavigateUp = onNavigateUp) }) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        } else state.bill?.let { bill ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text("#${bill.billNumber}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = AppColors.Primary)
                                Badge(containerColor = when (bill.status) { BillStatus.CANCELLED -> AppColors.Error; BillStatus.EDITED -> AppColors.Warning; else -> AppColors.Success }) {
                                    Text(bill.status.name, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                            }
                            Text(SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(bill.createdAt)), color = AppColors.TextSecondary)
                            if (bill.customerName.isNotBlank()) Text("Customer: ${bill.customerName}")
                            Text("Worker: ${bill.workerName}")
                            if(bill.paymentMethod==PaymentMethod.SPLIT)bill.payments.forEach {
                                Text("${it.method.displayName}: ${CalculationEngine.formatRupees(it.amountPaisa)}")
                            }
                        }
                    }
                }
                items(bill.items) { item ->
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("${item.productName} ${item.productSize}".trim(), fontWeight = FontWeight.Medium)
                            Text(if (item.isPipe) "${item.pipeLengths} Len + ${CalculationEngine.formatQuantity(item.pipeFeet)} Ft" else "${CalculationEngine.formatQuantity(item.quantity)} ${item.productUnit} × ${CalculationEngine.formatRupees(item.unitPricePaisa)}", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        }
                        Text(CalculationEngine.formatRupees(item.itemTotalPaisa), fontWeight = FontWeight.Bold)
                    }
                }
                item { HorizontalDivider() }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), Arrangement.spacedBy(6.dp)) {
                            AmountRow("Subtotal", bill.subtotalPaisa)
                            if (bill.discountPaisa > 0) AmountRow("Discount", bill.discountPaisa, amountColor = AppColors.Success)
                            if (bill.gstEnabled) AmountRow("GST", bill.gstAmountPaisa)
                            HorizontalDivider()
                            AmountRow("TOTAL", bill.totalPaisa,
                                labelStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                amountStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text("Payment: ${bill.paymentMethod.displayName}", color = AppColors.TextSecondary)
                            if (bill.creditPaisa > 0) Text("Credit originally given: ${CalculationEngine.formatRupees(bill.creditPaisa)}", color = AppColors.Credit, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                item { OutlinedButton({ onReceipt(bill.id) }, modifier = Modifier.fillMaxWidth()) { Text("OPEN / PRINT RECEIPT") } }
                item {ReceiptShareAction(bill.id,Modifier.fillMaxWidth())}
                if (bill.status == BillStatus.ACTIVE) {
                    item { Button({ onReturn(bill.id) }, Modifier.fillMaxWidth()) { Text("RETURN / EXCHANGE ITEMS") } }
                    item {
                        OutlinedButton(onClick = { showCancelDialog = true }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Error),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Error)) {
                            Icon(Icons.Default.Cancel, null); Spacer(Modifier.width(8.dp)); Text("CANCEL THIS BILL")
                        }
                    }
                }
                state.error?.let { item { ErrorMessage(it) } }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AUDIT LOG
// ─────────────────────────────────────────────────────────────────────────────

data class AuditState(val logs: List<AuditLog> = emptyList(), val isLoading: Boolean = false)

@HiltViewModel
class AuditViewModel @Inject constructor(private val auditRepo: AuditRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuditState())
    val state: StateFlow<AuditState> = _state.asStateFlow()
    init {
        viewModelScope.launch {
            auditRepo.observeRecentLogs().collect { logs -> _state.update { it.copy(logs = logs) } }
        }
    }
}

@Composable
fun AuditScreen(onNavigateUp: () -> Unit, viewModel: AuditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }
    Scaffold(topBar = { AppTopBar("Audit Log", onNavigateUp = onNavigateUp) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.logs.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { Text("No audit records yet", color = AppColors.TextSecondary) } }
            }
            items(state.logs, key = { it.id }) { log ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppColors.Surface)) {
                    Column(Modifier.padding(10.dp), Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text(log.actionType.replace("_", " "), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text(fmt.format(Date(log.createdAt)), style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                        }
                        Text("${log.actorRole}: ${log.actorName}", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        if (log.reason.isNotBlank()) Text("Reason: ${log.reason}", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        log.beforeValue?.let { Text("Before: $it", style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary) }
                        log.afterValue?.let { Text("After: $it", style = MaterialTheme.typography.labelSmall, color = AppColors.Primary) }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// STOCK MANAGEMENT
// ─────────────────────────────────────────────────────────────────────────────

data class StockState(val query: String = "", val filter: String = "All", val lowStockProducts: List<Product> = emptyList(), val adjusting: Product? = null,
                      val newStock: String = "", val reason: String = "", val notes: String = "", val isLoading: Boolean = false, val error: String? = null, val message: String? = null)

@HiltViewModel
class StockViewModel @Inject constructor(private val productRepo: ProductRepository, private val stockRepo: StockRepository) : ViewModel() {
    private val _state = MutableStateFlow(StockState())
    val state: StateFlow<StockState> = _state.asStateFlow()
    init { viewModelScope.launch { productRepo.observeAllActiveProducts().collect { products -> _state.update { it.copy(lowStockProducts = products) } } } }
    fun startAdjust(product: Product) = _state.update { it.copy(adjusting = product, newStock = product.currentStock.toString(), reason = "", notes = "") }
    fun update(block: StockState.() -> StockState) = _state.update(block)
    fun confirmAdjust() {
        val s = _state.value
        val product = s.adjusting ?: return
        if (s.isLoading) return
        val newStock = s.newStock.toDoubleOrNull()?.takeIf { it.isFinite() } ?: run { _state.update { it.copy(error = "Invalid stock value") }; return }
        if (s.reason.isBlank()) { _state.update { it.copy(error = "Reason is required") }; return }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val result = stockRepo.adjustStock(product.id, newStock, s.reason, s.notes, product.currentStock)
            when (result) {
                is Result.Success -> _state.update { it.copy(isLoading = false, adjusting = null, message = "Stock updated for ${product.name}") }
                is Result.Error   -> _state.update { it.copy(isLoading = false, error = result.message) }
                else -> Unit
            }
        }
    }
    fun dismiss() = _state.update { it.copy(adjusting = null, error = null) }
    fun clearMessage() = _state.update { it.copy(message = null) }
}

@Composable
fun StockScreen(onNavigateUp: () -> Unit, viewModel: StockViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val reasons = listOf("Physical stock correction","Damaged goods","Opening stock","Purchase received","Return from customer","Other")

    state.adjusting?.let { product ->
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("Adjust Stock: ${product.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Current: ${CalculationEngine.formatQuantity(product.currentStock)} ${product.unit}", color = AppColors.TextSecondary)
                    OutlinedTextField(value = state.newStock, onValueChange = { viewModel.update { copy(newStock = it) } },
                        label = { Text("New Stock *") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    var reasonExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = reasonExpanded, onExpandedChange = { reasonExpanded = it }) {
                        OutlinedTextField(value = state.reason.ifBlank { "Select reason *" }, onValueChange = {},
                            readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(reasonExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(type = MenuAnchorType.PrimaryNotEditable, enabled = true))
                        ExposedDropdownMenu(expanded = reasonExpanded, onDismissRequest = { reasonExpanded = false }) {
                            reasons.forEach { r -> DropdownMenuItem(text = { Text(r) }, onClick = { viewModel.update { copy(reason = r) }; reasonExpanded = false }) }
                        }
                    }
                    OutlinedTextField(value = state.notes, onValueChange = { viewModel.update { copy(notes = it) } },
                        label = { Text("Notes (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = AppColors.Error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmAdjust, enabled = !state.isLoading) { Text("SAVE") } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("Cancel") } }
        )
    }

    Scaffold(topBar = { AppTopBar("Stock Management", onNavigateUp = onNavigateUp) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.message?.let { msg ->
                LaunchedEffect(msg) { kotlinx.coroutines.delay(2000); viewModel.clearMessage() }
                Text(msg, color = AppColors.Success, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.lowStockProducts.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CheckCircle, null, tint = AppColors.Success, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No products yet", color = AppColors.TextSecondary)
                        Spacer(Modifier.height(4.dp))
                        Text("Add products from Products & Prices", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    }
                }
            } else {
                SectionHeader("Stock (${state.lowStockProducts.size} products)")
                LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                OutlinedTextField(state.query, { viewModel.update { copy(query = it) } }, label = { Text("Search stock") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("All", "Low", "Out").forEach { filter ->
                        FilterChip(state.filter == filter, { viewModel.update { copy(filter = filter) } }, label = { Text(filter) })
                    }
                }
            }
            items(state.lowStockProducts.filter { product ->
                product.displayLabel.contains(state.query, true) && when (state.filter) {
                    "Low" -> product.isLowStock
                    "Out" -> product.currentStock <= 0
                    else -> true
                }
            }, key = { it.id }) { product ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppColors.LowStock.copy(alpha = 0.06f))) {
                            Row(Modifier.padding(12.dp).fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(product.displayLabel, fontWeight = FontWeight.SemiBold)
                                    Text("Stock: ${CalculationEngine.formatQuantity(product.currentStock)} / Min: ${CalculationEngine.formatQuantity(product.minimumStock)} ${product.unit}",
                                        color = AppColors.LowStock, style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(onClick = { viewModel.startAdjust(product) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Adjust")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SETTINGS
// ─────────────────────────────────────────────────────────────────────────────

data class SettingsState(
    val upiId: String = "",
    val upiName: String = "",
    val shopName:        String  = "",
    val shopPhone:       String  = "",
    val shopAddress:     String  = "",
    val shopGst:         String  = "",
    val receiptFooter:String=ReceiptFooter.DEFAULT,
    val printerMac:      String  = "",
    val pairedPrinters:  List<BluetoothPrintManager.BluetoothDeviceInfo> = emptyList(),
    val latestBackupInfo: String? = null,
    val backupUri: android.net.Uri? = null,
    val isLoading:       Boolean = false,
    val isPrinting:      Boolean = false,
    val isBackingUp:     Boolean = false,
    val isSaved:         Boolean = false,
    val error:           String? = null,
    val message:         String? = null,
    val linkedShopId:     String? = null,
    val shopLinkInput:    String = "",
    val dirtyRecords:     Int = 0,
    val queuedUploads:    Int = 0,
    val reviewCount:      Int = 0,
    val isLinking:        Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo:  SettingsRepository,
    private val printManager:  BluetoothPrintManager,
    private val backupManager: BackupManager,
    private val syncJournal: SyncJournal,
    private val cloudSync: CloudSyncRepository,
    private val shopLink: com.hardwareshop.pos.data.remote.ShopLinkRepository,
    private val cloudAccount: com.hardwareshop.pos.data.remote.CloudAccountRepository,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val linked = syncJournal.linkedStore()
            val info = settingsRepo.getShopInfo()
            _state.update { it.copy(upiId = info.upiId, upiName = info.upiName,receiptFooter=info.receiptFooter) }
            _state.update { it.copy(shopName = info.name, shopPhone = info.phone, shopAddress = info.address, shopGst = info.gstNumber, linkedShopId = linked, shopLinkInput = linked ?: "") }
            refreshSyncQueue()
            val mac = printManager.getSavedPrinterAddress()
            _state.update { it.copy(
                printerMac       = mac,
                latestBackupInfo = backupManager.latestBackupInfo()
            )}
        }
    }

    private suspend fun refreshSyncQueue() {
        val (dirty, queued, review) = syncJournal.pendingCounts()
        _state.update { it.copy(dirtyRecords = dirty, queuedUploads = queued, reviewCount = review) }
    }

    fun update(block: SettingsState.() -> SettingsState) = _state.update(block)

    fun connectCloudAccount(email: String, password: String, create: Boolean) {
        if (_state.value.isLinking) return
        _state.update { it.copy(isLinking = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val shop = cloudAccount.connect(email, password, create)
                _state.update { it.copy(linkedShopId = shop, shopLinkInput = shop,
                    message = "Cloud account connected. Use this account on your other phone; sync is starting.") }
                refreshSyncQueue()
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _state.update { it.copy(error = "Cloud connection timed out. Check internet and Firebase setup.") }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message) } }
            finally { _state.update { it.copy(isLinking = false) } }
        }
    }

    fun saveReceiptFooter() {
        if(_state.value.isLoading)return
        _state.update {it.copy(isLoading=true,error=null,message=null)}
        viewModelScope.launch {
            try {
                val value=ReceiptFooter.validate(_state.value.receiptFooter)
                settingsRepo.set("receipt_footer",value)
                _state.update {it.copy(receiptFooter=value,message="Receipt footer saved on this phone and included in backups")}
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){_state.update {it.copy(error=e.message ?: "Could not save footer")}}
            finally {_state.update {it.copy(isLoading=false)}}
        }
    }
    fun printFooterTest() {
        if(_state.value.isPrinting)return
        val value=try {ReceiptFooter.lines(_state.value.receiptFooter)}catch(e:Exception){_state.update {it.copy(error=e.message)};return}
        _state.update {it.copy(isPrinting=true,error=null,message=null)}
        viewModelScope.launch {
            try {
                val bytes=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    ReceiptBuilder.encode(listOf(ReceiptLayout.Line("FOOTER PREVIEW / TEST",ReceiptLayout.Align.CENTER,bold=true))+
                        value.map {ReceiptLayout.Line(it,ReceiptLayout.Align.CENTER)})
                }
                when(val result=printManager.printRawBytes(bytes)) {
                    is Result.Success->_state.update {it.copy(message="Footer test sent. Check the paper receipt. Save the footer to use it on bills.")}
                    is Result.Error->_state.update {it.copy(error=result.message)}
                    else->Unit
                }
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){_state.update {it.copy(error=e.message ?: "Could not print footer test")}}
            finally {_state.update {it.copy(isPrinting=false)}}
        }
    }

    fun saveShopInfo() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val r = settingsRepo.updateShopInfo(SettingsRepository.ShopInfo(
                _state.value.shopName, _state.value.shopPhone,
                _state.value.shopAddress, _state.value.shopGst
            ))
            when (r) {
                is Result.Success -> _state.update { it.copy(isLoading = false, message = "Shop info saved") }
                is Result.Error   -> _state.update { it.copy(isLoading = false, error = r.message) }
                else -> Unit
            }
        }
    }

    fun saveUpi() {
        viewModelScope.launch {
            try {
                val id = _state.value.upiId.trim()
                val name = _state.value.upiName.trim()
                UpiPaymentQr.validate(id, name)
                settingsRepo.setMerchantUpi(id, name)
                _state.update { it.copy(upiId = id, upiName = name, error = null, message = "UPI settings saved. Verify with a Rs.1 test before customer use.") }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Cannot save UPI settings") } }
        }
    }

    /** Re-read paired devices (after permission grant, or after pairing in Android settings). */
    fun refreshPrinters() {
        viewModelScope.launch {
            val printers=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {printManager.getPairedDevices()}
            _state.update { it.copy(pairedPrinters = printers) }
        }
    }

    fun showError(msg: String) = _state.update { it.copy(error = msg) }

    fun savePrinter(mac: String) {
        viewModelScope.launch {
            printManager.savePrinterAddress(mac)
            _state.update { it.copy(printerMac = mac, message = "Printer selected") }
        }
    }

    fun testPrint() {
        if (_state.value.isPrinting) return
        _state.update { it.copy(isPrinting = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                when (val result = printManager.printTestPage()) {
                    is Result.Success -> _state.update { it.copy(message = "Test sent. Check the paper receipt.") }
                    is Result.Error -> _state.update { it.copy(error = result.message) }
                    else -> Unit
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = "Test print failed: ${e.message}") } }
            finally { _state.update { it.copy(isPrinting = false) } }
        }
    }

    fun createBackup() {
        if (_state.value.isBackingUp) return
        _state.update { it.copy(isBackingUp = true, error = null) }
        viewModelScope.launch {
            when (val result = backupManager.createBackup()) {
                is Result.Success -> _state.update { it.copy(
                    isBackingUp      = false,
                    message = "Backup created. Use Export to keep a copy outside this phone.",
                    backupUri = result.data,
                    latestBackupInfo = backupManager.latestBackupInfo()
                )}
                is Result.Error -> _state.update { it.copy(isBackingUp = false, error = result.message) }
                else -> Unit
            }
        }
    }

    fun exportBackup(destination: android.net.Uri) {
        val source = _state.value.backupUri ?: return
        if (_state.value.isBackingUp) return
        _state.update { it.copy(isBackingUp = true, error = null) }
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(source)).use { input ->
                        requireNotNull(context.contentResolver.openOutputStream(destination, "w")).use { output -> input.copyTo(output) }
                    }
                }
                _state.update { it.copy(message = "Backup exported successfully") }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = "Export failed: ${e.message}") } }
            finally { _state.update { it.copy(isBackingUp = false) } }
        }
    }

    fun restoreBackup(source: android.net.Uri) {
        if (_state.value.isBackingUp) return
        _state.update { it.copy(isBackingUp = true, error = null) }
        viewModelScope.launch {
            try {
                when (val result = backupManager.restoreBackup(source)) {
                    is Result.Success -> _state.update { it.copy(message = result.data, latestBackupInfo = backupManager.latestBackupInfo()) }
                    is Result.Error -> _state.update { it.copy(error = result.message) }
                    else -> Unit
                }
            } finally { _state.update { it.copy(isBackingUp = false) } }
        }
    }

    /** Binds the journal only after Firebase confirms this device is an approved
     * shop member. bindVerifiedStore requires verified membership; linking must
     * not write a local link the server would reject at sync time.
     */
    fun linkShop() {
        val shopId = _state.value.shopLinkInput.trim()
        if (shopId.isBlank()) { _state.update { it.copy(error = "Enter your Shop ID") }; return }
        if (_state.value.isLinking) return
        _state.update { it.copy(isLinking = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val member = shopLink.link(shopId)
                refreshSyncQueue()
                _state.update {
                    it.copy(isLinking = false, linkedShopId = shopId,
                        message = "Linked as ${if (member.actor.role == SyncProtocol.Role.OWNER) "OWNER" else "WORKER"}")
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _state.update { it.copy(isLinking = false, error = "Link check timed out. Check your internet connection and try again.") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLinking = false, error = "Could not link shop: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun syncNow() {
        if (_state.value.isLoading) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, message = null) }
            try {
                when (val result = cloudSync.runOnce()) {
                    is CloudSyncRepository.SyncResult.Success -> {
                        val counts = syncJournal.pendingCounts()
                        val blocked = syncJournal.blockedReasons()
                        if (result.moreWork) CloudSyncScheduler.requestNow(context)
                        _state.update {
                            it.copy(isLoading = false, message = "${if (result.moreWork) "Sync continuing in background" else "Sync pass finished"}: ${result.uploaded} sent, ${result.downloaded} received. " +
                                "${counts.first} local changes remain; ${counts.second} uploads queued; ${counts.third} need review." +
                                if (blocked.isNotEmpty()) "\n" + blocked.joinToString("\n") else "")
                        }
                    }
                    is CloudSyncRepository.SyncResult.NotLinked -> _state.update {
                        it.copy(isLoading = false, error = "Link this phone to your shop first")
                    }
                    is CloudSyncRepository.SyncResult.Failed -> _state.update { it.copy(isLoading = false, error = result.message) }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _state.update { it.copy(isLoading = false, error = "Sync timed out. Saved progress is safe; retrying in the background.") }
                CloudSyncScheduler.requestNow(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Sync failed: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(isLoading = false) }
                refreshSyncQueue()
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

@Composable
fun SettingsScreen(onNavigateUp: () -> Unit, onRemoteSetup: () -> Unit = {}, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    val withBluetooth = rememberBluetoothGate { viewModel.showError(it) }
    // Settings and footer editing work without Bluetooth; printer actions retain the gate.
    LaunchedEffect(Unit) { viewModel.refreshPrinters() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(viewModel::exportBackup) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri = it }
    if (restoreUri != null) AlertDialog(onDismissRequest = { restoreUri = null }, title = { Text("Replace data from backup?") },
        text = { Text("Products, bills, customers, credit and stock will be replaced. A safety backup is created first. Local passwords stay on this phone. Unfinished carts are cleared. Review old receipt copies before reprinting.") },
        confirmButton = { TextButton({ restoreUri?.let(viewModel::restoreBackup); restoreUri = null }) { Text("RESTORE") } },
        dismissButton = { TextButton({ restoreUri = null }) { Text("Cancel") } })

    Scaffold(topBar = { AppTopBar("Settings", onNavigateUp = onNavigateUp) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Shop info ─────────────────────────────────────────────────────
            SectionHeader("Shop Information")
            OutlinedTextField(value = state.shopName,    onValueChange = { viewModel.update { copy(shopName    = it) } }, label = { Text("Shop Name") },    singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = state.shopPhone,   onValueChange = { viewModel.update { copy(shopPhone   = it) } }, label = { Text("Phone") },         singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            OutlinedTextField(value = state.shopAddress, onValueChange = { viewModel.update { copy(shopAddress = it) } }, label = { Text("Address") },                          modifier = Modifier.fillMaxWidth(), maxLines = 3)
            OutlinedTextField(value = state.shopGst,     onValueChange = { viewModel.update { copy(shopGst     = it.uppercase()) } }, label = { Text("GST Number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            state.error?.let { ErrorMessage(it) }
            state.message?.let { Text(it, color = AppColors.Success, style = MaterialTheme.typography.bodySmall) }
            PrimaryActionButton(text = "SAVE SHOP INFO", onClick = viewModel::saveShopInfo, isLoading = state.isLoading, icon = Icons.Default.Save)

            ReceiptFooterControls(state,viewModel) {withBluetooth {viewModel.printFooterTest()}}

            // ── Cloud sync ───────────────────────────────────────────────────
            HorizontalDivider()
            SectionHeader("UPI Payment")
            OutlinedTextField(value = state.upiId, onValueChange = { viewModel.update { copy(upiId = it) } }, label = { Text("Merchant UPI ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = state.upiName, onValueChange = { viewModel.update { copy(upiName = it) } }, label = { Text("Payee Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Use your verified merchant UPI address. Printing a QR does not confirm payment.", style = MaterialTheme.typography.bodySmall)
            PrimaryActionButton(text = "SAVE UPI SETTINGS", onClick = viewModel::saveUpi, icon = Icons.Default.Save)
            HorizontalDivider()
            SectionHeader("Cloud Sync")
            var cloudEmail by remember { mutableStateOf("") }
            var cloudPassword by remember { mutableStateOf("") }
            OutlinedTextField(value = cloudEmail, onValueChange = { cloudEmail = it },
                label = { Text("Cloud account email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = cloudPassword, onValueChange = { cloudPassword = it },
                label = { Text("Cloud account password") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
            Text("Create once on your main owner phone. Signing in on another phone uses cloud catalogue details; unsent local edits are kept for review. Manage product details on the main phone. Local passwords stay separate.",
                style = MaterialTheme.typography.bodySmall)
            PrimaryActionButton(text = "SIGN IN & USE CLOUD CATALOGUE", isLoading = state.isLinking,
                onClick = { viewModel.connectCloudAccount(cloudEmail, cloudPassword, false); cloudPassword = "" }, icon = Icons.Default.CloudUpload)
            OutlinedButton(onClick = { viewModel.connectCloudAccount(cloudEmail, cloudPassword, true); cloudPassword = "" },
                enabled = !state.isLinking, modifier = Modifier.fillMaxWidth()) { Text("CREATE CLOUD ACCOUNT") }
            OutlinedTextField(
                value = state.shopLinkInput,
                onValueChange = { viewModel.update { copy(shopLinkInput = it) } },
                label = { Text("Shop ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (state.linkedShopId == null) "This phone is not linked" else "Linked to ${state.linkedShopId}",
                color = if (state.linkedShopId == null) AppColors.Warning else AppColors.Success
            )
            Text(
                when {
                    state.dirtyRecords == 0 && state.queuedUploads == 0 && state.reviewCount == 0 ->
                        "No pending changes on this phone"
                    state.reviewCount > 0 ->
                        "${state.dirtyRecords} changed, ${state.queuedUploads} queued, ${state.reviewCount} need review"
                    else -> "${state.dirtyRecords} changed records, ${state.queuedUploads} queued uploads"
                },
                style = MaterialTheme.typography.bodySmall
            )
            PrimaryActionButton(text = "LINK THIS PHONE TO SHOP", onClick = viewModel::linkShop, isLoading = state.isLinking, icon = Icons.Default.CloudUpload)
            PrimaryActionButton(text = "SYNC NOW", onClick = viewModel::syncNow, isLoading = state.isLoading, icon = Icons.Default.Sync)

            // ── Printer ───────────────────────────────────────────────────────
            HorizontalDivider()
            SectionHeader("Bluetooth Printer (58mm ESC/POS)")
            if (state.pairedPrinters.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.Warning.copy(alpha = 0.1f))) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = AppColors.Warning)
                        Spacer(Modifier.width(8.dp))
                        Text("No paired Bluetooth devices. Pair your printer in Android Settings → Bluetooth first, then tap Refresh.", style = MaterialTheme.typography.bodySmall, color = AppColors.Warning)
                    }
                }
            } else {
                state.pairedPrinters.forEach { device ->
                    val selected = device.address == state.printerMac
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.savePrinter(device.address) },
                        colors   = CardDefaults.cardColors(containerColor = if (selected) AppColors.Primary.copy(alpha = 0.1f) else AppColors.Surface)
                    ) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = { viewModel.savePrinter(device.address) })
                            Column(Modifier.weight(1f)) {
                                Text(device.name, fontWeight = FontWeight.Medium)
                                Text(device.address, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                            }
                            if (selected) Icon(Icons.Default.CheckCircle, null, tint = AppColors.Success, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick  = { withBluetooth { viewModel.refreshPrinters() } },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("REFRESH") }
                OutlinedButton(
                    onClick  = {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Icon(Icons.Default.Bluetooth, null); Spacer(Modifier.width(6.dp)); Text("PAIR") }
            }
            if (state.printerMac.isNotBlank()) {
                OutlinedButton(
                    onClick  = { withBluetooth { viewModel.testPrint() } },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled  = !state.isPrinting
                ) {
                    if (state.isPrinting) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Printing…") }
                    else { Icon(Icons.Default.Print, null); Spacer(Modifier.width(8.dp)); Text("TEST PRINT") }
                }
            }

            OutlinedButton(onClick = onRemoteSetup, modifier = Modifier.fillMaxWidth()) { Text("REMOTE PRINTER SETUP") }
            // ── Backup ────────────────────────────────────────────────────────
            HorizontalDivider()
            SectionHeader("Backup & Restore")
            Text("Saves all products, bills, customers, credit and stock to a .hwpos.zip file.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            if (state.backupUri != null) OutlinedButton({ exportLauncher.launch("HardwareShopPOS-backup.hwpos.zip") }, enabled = !state.isBackingUp) { Text("EXPORT BACKUP TO FILES") }
            OutlinedButton({ restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !state.isBackingUp) { Text("RESTORE FROM FILE") }
            Text("Includes product photos and saved day closings. Local passwords and printer pairing are not exported.", style = MaterialTheme.typography.bodySmall)
            state.latestBackupInfo?.let { Text("Latest: $it", style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary) }
            OutlinedButton(
                onClick  = viewModel::createBackup,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled  = !state.isBackingUp
            ) {
                if (state.isBackingUp) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Creating backup…") }
                else { Icon(Icons.Default.Backup, null); Spacer(Modifier.width(8.dp)); Text("CREATE BACKUP") }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// WORKERS
// ─────────────────────────────────────────────────────────────────────────────

data class WorkersState(val workers: List<Worker> = emptyList(), val showAddDialog: Boolean = false,
                        val newName: String = "", val newUsername: String = "", val newPassword: String = "",
                        val newPhone: String = "", val newMaxDiscount: String = "0", val isLoading: Boolean = false, val error: String? = null)

@HiltViewModel
class WorkersViewModel @Inject constructor(private val workerRepo: WorkerRepository) : ViewModel() {
    private val _state = MutableStateFlow(WorkersState())
    val state: StateFlow<WorkersState> = _state.asStateFlow()
    init { viewModelScope.launch { workerRepo.observeActiveWorkers().collect { workers -> _state.update { it.copy(workers = workers) } } } }
    fun update(block: WorkersState.() -> WorkersState) = _state.update(block)
    fun addWorker() {
        val s = _state.value
        if (s.newName.isBlank() || s.newUsername.isBlank() || s.newPassword.isBlank()) { _state.update { it.copy(error = "Name, username and password are required") }; return }
        val maxDiscountPaisa = CalculationEngine.parseRupeesToPaisa(s.newMaxDiscount) ?: run { _state.update { it.copy(error = "Enter a valid discount") }; return }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val r = workerRepo.createWorker(s.newName, s.newUsername, s.newPassword, s.newPhone, maxDiscountPaisa, true)
            when (r) {
                is Result.Success -> _state.update { it.copy(isLoading = false, showAddDialog = false, newName = "", newUsername = "", newPassword = "", newPhone = "", newMaxDiscount = "0") }
                is Result.Error   -> _state.update { it.copy(isLoading = false, error = r.message) }
                else -> Unit
            }
        }
    }
    fun toggleWorker(id: Long, active: Boolean) { viewModelScope.launch { workerRepo.setWorkerActive(id, active) } }
}

@Composable
fun WorkersScreen(onNavigateUp: () -> Unit, viewModel: WorkersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    if (state.showAddDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.update { copy(showAddDialog = false, error = null) } },
            title = { Text("Add Worker") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = state.newName, onValueChange = { viewModel.update { copy(newName = it) } }, label = { Text("Full Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = state.newUsername, onValueChange = { viewModel.update { copy(newUsername = it.lowercase()) } }, label = { Text("Username *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = state.newPassword, onValueChange = { viewModel.update { copy(newPassword = it) } }, label = { Text("Password *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = state.newPhone, onValueChange = { viewModel.update { copy(newPhone = it) } }, label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = state.newMaxDiscount, onValueChange = { viewModel.update { copy(newMaxDiscount = it) } }, label = { Text("Max Discount ₹") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    state.error?.let { Text(it, color = AppColors.Error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::addWorker, enabled = !state.isLoading) { Text("ADD") } },
            dismissButton = { TextButton(onClick = { viewModel.update { copy(showAddDialog = false, error = null) } }) { Text("Cancel") } }
        )
    }
    Scaffold(
        topBar = { AppTopBar("Workers", onNavigateUp = onNavigateUp) },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.update { copy(showAddDialog = true) } }, containerColor = AppColors.Secondary) {
                Icon(Icons.Default.PersonAdd, "Add Worker", tint = AppColors.OnPrimary)
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.workers, key = { it.id }) { worker ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(worker.name, fontWeight = FontWeight.SemiBold)
                            Text("@${worker.username}", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                            Text("Max discount: ${CalculationEngine.formatRupees(worker.maxDiscountPaisa)}", style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                        }
                        Switch(checked = worker.isActive, onCheckedChange = { viewModel.toggleWorker(worker.id, it) })
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REPORTS  (simplified — full charts in a future iteration)
// ─────────────────────────────────────────────────────────────────────────────

data class CreditScreenState(val customer: Customer? = null, val transactions: List<CreditTransaction> = emptyList(),
                             val paymentAmount: String = "", val isLoading: Boolean = false, val error: String? = null, val message: String? = null)

@HiltViewModel
class CreditViewModel @Inject constructor(
    private val customerRepo: CustomerRepository, private val creditRepo: CreditRepository,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val _state = MutableStateFlow(CreditScreenState(paymentAmount = savedState["creditPaymentAmount"] ?: ""))
    val state: StateFlow<CreditScreenState> = _state.asStateFlow()
    private var customerId = 0L
    private var requestId = savedState.get<String>("creditPaymentRequest") ?: UUID.randomUUID().toString().also {
        savedState["creditPaymentRequest"] = it
    }
    fun init(id: Long) {
        customerId = id
        viewModelScope.launch {
            val customer = customerRepo.getCustomerById(id)
            _state.update { it.copy(customer = customer) }
            creditRepo.observeTransactionsForCustomer(id).collect { txns -> _state.update { it.copy(transactions = txns) } }
        }
    }
    fun recordPayment() {
        if (_state.value.isLoading) return
        val paisa = CalculationEngine.parseRupeesToPaisa(_state.value.paymentAmount) ?: 0L
        if (paisa <= 0) { _state.update { it.copy(error = "Enter a valid amount") }; return }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val r = creditRepo.recordPayment(customerId, paisa, "Cash payment received", requestId = requestId)
            when (r) {
                is Result.Success -> {
                    requestId = UUID.randomUUID().toString()
                    savedState["creditPaymentRequest"] = requestId
                    savedState["creditPaymentAmount"] = ""
                    _state.update { it.copy(isLoading = false, paymentAmount = "", message = "Payment recorded") }
                    try {
                        val customer = customerRepo.getCustomerById(customerId)
                        _state.update { it.copy(customer = customer) }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { _state.update { it.copy(error = "Payment was recorded. Reopen this screen to refresh the balance.") } }
                }
                is Result.Error   -> _state.update { it.copy(isLoading = false, error = r.message) }
                else -> Unit
            }
        }
    }
    fun setPaymentAmount(v: String) {
        if (_state.value.isLoading || v == _state.value.paymentAmount) return
        requestId = UUID.randomUUID().toString()
        savedState["creditPaymentRequest"] = requestId
        savedState["creditPaymentAmount"] = v
        _state.update { it.copy(paymentAmount = v, error = null, message = null) }
    }
    fun clearMessage() = _state.update { it.copy(message = null) }
}

@Composable
fun CreditScreen(customerId: Long, onNavigateUp: () -> Unit, viewModel: CreditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var confirmPayment by remember { mutableStateOf(false) }
    if (confirmPayment) AlertDialog(
        onDismissRequest = { confirmPayment = false },
        title = { Text("Confirm cash repayment") },
        text = { Text("Record ${CalculationEngine.formatRupees(CalculationEngine.parseRupeesToPaisa(state.paymentAmount) ?: 0)} received from ${state.customer?.name.orEmpty()}?") },
        confirmButton = { TextButton(onClick = { confirmPayment = false; viewModel.recordPayment() }, enabled = !state.isLoading) { Text("CONFIRM PAYMENT") } },
        dismissButton = { TextButton(onClick = { confirmPayment = false }) { Text("CANCEL") } }
    )
    val fmt = remember { SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()) }
    LaunchedEffect(customerId) { viewModel.init(customerId) }
    state.message?.let { LaunchedEffect(it) { kotlinx.coroutines.delay(2000); viewModel.clearMessage() } }
    Scaffold(topBar = { AppTopBar("Credit — ${state.customer?.name ?: ""}", onNavigateUp = onNavigateUp) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.customer?.let { c ->
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (c.outstandingBalancePaisa > 0) AppColors.Error.copy(alpha = 0.08f) else AppColors.Success.copy(alpha = 0.08f))) {
                        Column(Modifier.padding(16.dp), Arrangement.spacedBy(4.dp)) {
                            Text(c.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            if (c.phone.isNotBlank()) Text(c.phone, color = AppColors.TextSecondary)
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text("Outstanding Balance", fontWeight = FontWeight.Medium)
                                Text(CalculationEngine.formatRupees(c.outstandingBalancePaisa), fontWeight = FontWeight.Bold, color = if (c.outstandingBalancePaisa > 0) AppColors.Error else AppColors.Success)
                            }
                        }
                    }
                }
                if (c.outstandingBalancePaisa > 0) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                                Text("Record Payment", fontWeight = FontWeight.Bold)
                                OutlinedTextField(value = state.paymentAmount, onValueChange = { viewModel.setPaymentAmount(it) },
                                    enabled = !state.isLoading,
                                    label = { Text("Amount ₹") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                state.error?.let { Text(it, color = AppColors.Error, style = MaterialTheme.typography.bodySmall) }
                                state.message?.let { Text(it, color = AppColors.Success, style = MaterialTheme.typography.bodySmall) }
                                Button(onClick = { confirmPayment = true }, enabled = !state.isLoading &&
                                    (CalculationEngine.parseRupeesToPaisa(state.paymentAmount)?.let { it > 0 && it <= c.outstandingBalancePaisa } == true),
                                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Success)) {
                                    Text("RECORD PAYMENT", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            item {CreditReminderControls(customerId)}
            item { SectionHeader("Transaction History") }
            items(state.transactions, key = { it.id }) { tx ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (tx.amountPaisa < 0) AppColors.Success.copy(alpha = 0.06f) else AppColors.Surface)) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column {
                            Text(tx.type.name.replace("_", " "), fontWeight = FontWeight.Medium)
                            Text(fmt.format(Date(tx.createdAt)), style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                (if (tx.amountPaisa < 0) "− " else "+ ") + CalculationEngine.formatRupees(Math.abs(tx.amountPaisa)),
                                color = if (tx.amountPaisa < 0) AppColors.Success else AppColors.Error,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Balance: ${CalculationEngine.formatRupees(tx.newBalancePaisa)}", style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                        }
                    }
                }
            }
        }
    }
}


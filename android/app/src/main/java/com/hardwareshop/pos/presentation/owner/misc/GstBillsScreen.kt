package com.hardwareshop.pos.presentation.owner.misc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.repository.BillingRepository
import com.hardwareshop.pos.domain.model.Bill
import com.hardwareshop.pos.presentation.common.AppTopBar
import com.hardwareshop.pos.presentation.common.AppColors
import com.hardwareshop.pos.presentation.common.AmountRow
import com.hardwareshop.pos.util.CalculationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// GST BILLS VIEWMODEL
// ─────────────────────────────────────────────────────────────────────────────

data class GstBillsUiState(
    val bills: List<Bill>    = emptyList(),
    val isLoading: Boolean   = true,
    val totalGstPaisa: Long  = 0L,
    val period: String       = "today",   // "today" | "week" | "month"
    val error: String?       = null
)

@HiltViewModel
class GstBillsViewModel @Inject constructor(
    private val billingRepository: BillingRepository
) : ViewModel() {

    private val _state = MutableStateFlow(GstBillsUiState())
    val state: StateFlow<GstBillsUiState> = _state.asStateFlow()

    init { load("today") }

    fun setPeriod(period: String) { load(period) }

    private fun load(period: String) {
        _state.update { it.copy(isLoading = true, period = period, error = null) }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val fromMs = when (period) {
                    "week"  -> now - 7L * 24 * 60 * 60 * 1000
                    "month" -> now - 30L * 24 * 60 * 60 * 1000
                    else    -> {
                        // Start of today (midnight)
                        val cal = Calendar.getInstance()
                        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                        cal.timeInMillis
                    }
                }
                // Fetch all bills in the period (max 500 for performance)
                val entities = billingRepository.getBillsFiltered(from = fromMs, to = now, limit = 500)
                // Map only GST bills to full domain objects
                val gstBills = mutableListOf<Bill>()
                for (entity in entities) {
                    if (!entity.gstEnabled || entity.gstAmountPaisa <= 0) continue
                    val bill = billingRepository.getBillById(entity.id) ?: continue
                    gstBills.add(bill)
                }
                val totalGst = gstBills.fold(0L) { acc, b -> acc + b.gstAmountPaisa }
                _state.update { it.copy(bills = gstBills, totalGstPaisa = totalGst, isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load GST bills") }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GST BILLS SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GstBillsScreen(
    onNavigateUp: () -> Unit,
    onBillClick: (Long) -> Unit,
    viewModel: GstBillsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US) }

    Scaffold(
        topBar = { AppTopBar("GST Bills", onNavigateUp = onNavigateUp) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Period filter
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("today" to "Today", "week" to "Week", "month" to "Month").forEach { (k, l) ->
                        FilterChip(
                            selected = state.period == k,
                            onClick  = { viewModel.setPeriod(k) },
                            label    = { Text(l) }
                        )
                    }
                }
            }

            // Summary card
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                        Text("GST Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text("GST Bills")
                            Text("${state.bills.size}", fontWeight = FontWeight.Medium)
                        }
                        val (cgst, sgst) = if (state.totalGstPaisa > 0)
                            CalculationEngine.splitCgstSgst(state.totalGstPaisa)
                        else 0L to 0L
                        AmountRow("Total CGST", cgst, amountColor = AppColors.Primary)
                        AmountRow("Total SGST", sgst, amountColor = AppColors.Primary)
                        HorizontalDivider()
                        AmountRow("Total GST Collected", state.totalGstPaisa,
                            amountStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            amountColor = AppColors.Primary)
                    }
                }
            }

            if (state.isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.bills.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                        Text("No GST bills in this period", color = AppColors.TextSecondary)
                    }
                }
            } else {
                items(state.bills, key = { it.id }) { bill ->
                    GstBillCard(
                        bill       = bill,
                        dateFormat = dateFormat,
                        onClick    = { onBillClick(bill.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GstBillCard(
    bill: Bill,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    val (cgst, sgst) = CalculationEngine.splitCgstSgst(bill.gstAmountPaisa)
    Card(
        modifier  = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(14.dp), Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("#${bill.billNumber}", fontWeight = FontWeight.Bold, color = AppColors.Primary)
                Text(dateFormat.format(Date(bill.createdAt)), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
            if (bill.customerName.isNotBlank())
                Text(bill.customerName, style = MaterialTheme.typography.bodySmall)
            if (bill.customerGstNumber.isNotBlank())
                Text("GSTIN: ${bill.customerGstNumber}", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Column {
                    Text("CGST: ${CalculationEngine.formatRupees(cgst)}", style = MaterialTheme.typography.bodySmall)
                    Text("SGST: ${CalculationEngine.formatRupees(sgst)}", style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Total GST", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    Text(CalculationEngine.formatRupees(bill.gstAmountPaisa), fontWeight = FontWeight.Bold)
                    Text("Bill: ${CalculationEngine.formatRupees(bill.totalPaisa)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                Icon(Icons.Default.Receipt, null, tint = AppColors.TextSecondary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

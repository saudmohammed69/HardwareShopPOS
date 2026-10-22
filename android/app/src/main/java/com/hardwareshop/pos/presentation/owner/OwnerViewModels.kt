package com.hardwareshop.pos.presentation.owner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// OWNER HOME VIEW MODEL
// ─────────────────────────────────────────────────────────────────────────────

data class OwnerHomeUiState(
    val pendingItemCount: Int = 0,
    val shopName:          String = "",
    val todaySales:        Long   = 0L,
    val todayBills:        Int    = 0,
    val outstandingCredit: Long   = 0L,
    val lowStockCount:     Int    = 0
)

@HiltViewModel
class OwnerHomeViewModel @Inject constructor(
    private val billingRepository:  BillingRepository,
    private val creditRepository:   CreditRepository,
    private val productRepository:  ProductRepository,
    private val settingsRepository: SettingsRepository,
    private val authRepository:     AuthRepository,
    private val pendingProducts:    PendingProductRepository
) : ViewModel() {

    private val _state = MutableStateFlow(OwnerHomeUiState())
    val state: StateFlow<OwnerHomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            pendingProducts.observePending().catch { timber.log.Timber.w(it, "Cannot load pending item count") }
                .collect { items -> _state.update { it.copy(pendingItemCount = items.size) } }
        }
        viewModelScope.launch {
            val info = settingsRepository.getShopInfo()
            _state.update { it.copy(shopName = info.name) }
        }
        viewModelScope.launch {
            val summary = billingRepository.getDashboardSummary(startOfDay())
            _state.update { it.copy(todaySales = summary.totalSalesPaisa, todayBills = summary.billCount) }
        }
        viewModelScope.launch {
            creditRepository.observeTotalOutstanding()
                .collect { bal -> _state.update { it.copy(outstandingCredit = bal) } }
        }
        viewModelScope.launch {
            productRepository.observeLowStockCount()
                .collect { count -> _state.update { it.copy(lowStockCount = count) } }
        }
    }

    fun refresh() { viewModelScope.launch {
        try {
            val summary = billingRepository.getDashboardSummary(startOfDay())
            val info = settingsRepository.getShopInfo()
            _state.update { it.copy(todaySales = summary.totalSalesPaisa, todayBills = summary.billCount, shopName = info.name) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { timber.log.Timber.w(e, "Owner overview refresh failed") }
    } }

    fun logout() = authRepository.logout()

    private fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

// ─────────────────────────────────────────────────────────────────────────────
// DASHBOARD VIEW MODEL
// ─────────────────────────────────────────────────────────────────────────────

data class DashboardUiState(
    val period:    String           = "today",
    val summary:   DashboardSummary? = null,
    val isLoading: Boolean           = false
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val billingRepository: BillingRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init { loadSummary("today") }

    fun setPeriod(period: String) { _state.update { it.copy(period = period) }; loadSummary(period) }

    private fun loadSummary(period: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val fromMs = when (period) {
                "today" -> startOfDay()
                "week"  -> startOfDay() - 6 * 86_400_000L
                "month" -> startOfMonth()
                else    -> startOfDay()
            }
            _state.update { it.copy(summary = billingRepository.getDashboardSummary(fromMs), isLoading = false) }
        }
    }

    private fun startOfDay() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfMonth() = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

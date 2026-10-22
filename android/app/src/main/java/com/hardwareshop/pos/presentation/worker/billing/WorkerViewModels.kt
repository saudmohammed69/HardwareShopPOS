package com.hardwareshop.pos.presentation.worker.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.local.entity.BillEntity
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.SessionHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.hardwareshop.pos.util.UserSession
// ─────────────────────────────────────────────────────────────────────────────
// WORKER HOME VIEW MODEL
// ─────────────────────────────────────────────────────────────────────────────

data class WorkerHomeUiState(
    val workerName:    String           = "",
    val recentBills:   List<BillEntity> = emptyList(),
    val lowStockCount: Int              = 0
)

@HiltViewModel
class WorkerHomeViewModel @Inject constructor(
    private val billingRepository: BillingRepository,
    private val productRepository: ProductRepository,
    private val authRepository:    AuthRepository,
    session:                       SessionHolder
) : ViewModel() {

    private val _state = MutableStateFlow(WorkerHomeUiState())
    val state: StateFlow<WorkerHomeUiState> = _state.asStateFlow()

    init {
        val workerSession = session.current as? UserSession.WorkerSession
        _state.update { it.copy(workerName = workerSession?.workerName ?: "Worker") }

        viewModelScope.launch {
            // Recent bills for this worker
            val workerId = workerSession?.workerId ?: return@launch
            billingRepository.observeWorkerRecentBills(workerId)
                .collect { bills -> _state.update { it.copy(recentBills = bills) } }
        }

        viewModelScope.launch {
            productRepository.observeLowStockCount()
                .collect { count -> _state.update { it.copy(lowStockCount = count) } }
        }
    }

    fun logout() {
        authRepository.logout()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BILL SUCCESS VIEW MODEL
// ─────────────────────────────────────────────────────────────────────────────

data class BillSuccessUiState(
    val bill:      Bill?    = null,
    val isPrinting: Boolean = false,
    val printError: String? = null,
    val hasPrintAttempt: Boolean = false,
    val printMessage: String? = null
)

@HiltViewModel
class BillSuccessViewModel @Inject constructor(
    private val billingRepository: BillingRepository,
    private val printManager:      com.hardwareshop.pos.util.BluetoothPrintManager
) : ViewModel() {

    private val _state = MutableStateFlow(BillSuccessUiState())
    val state: StateFlow<BillSuccessUiState> = _state.asStateFlow()

    fun loadBill(billId: Long) {
        viewModelScope.launch {
            val bill = billingRepository.getBillById(billId)
            val attempted = printManager.hasPreviousAttempt(billId)
            _state.update { it.copy(bill = bill, hasPrintAttempt = attempted) }
        }
    }

    fun showPrintError(msg: String) = _state.update { it.copy(isPrinting = false, printError = msg) }

    fun printBill() {
        val current = _state.value
        val bill = current.bill ?: return
        if (current.isPrinting) return
        _state.update { it.copy(isPrinting = true, printError = null, printMessage = null) }
        viewModelScope.launch {
            try {
                when (val result = printManager.printBill(bill, copy = current.hasPrintAttempt || bill.isPrinted)) {
                    is Result.Success -> {
                        billingRepository.markBillPrinted(bill.id)
                        _state.update { it.copy(bill = bill.copy(isPrinted = true), hasPrintAttempt = true,
                            printMessage = "Sent to printer. Please check the paper receipt.") }
                    }
                    is Result.Error -> _state.update { it.copy(printError = result.message) }
                    else -> Unit
                }
                val attempted = printManager.hasPreviousAttempt(bill.id)
                _state.update { it.copy(hasPrintAttempt = attempted) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                _state.update { it.copy(printError = "Print status could not be saved. Check the paper before reprinting.", hasPrintAttempt = true) }
            } finally { _state.update { it.copy(isPrinting = false) } }
        }
    }
}

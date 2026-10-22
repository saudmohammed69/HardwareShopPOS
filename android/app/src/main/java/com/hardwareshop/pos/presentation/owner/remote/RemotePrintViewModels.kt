package com.hardwareshop.pos.presentation.owner.remote

import com.hardwareshop.pos.util.printerDeviceId
import com.hardwareshop.pos.util.UserSession
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.local.entity.ShopDeviceEntity
import com.hardwareshop.pos.data.remote.RemotePrintRepository
import com.hardwareshop.pos.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// REMOTE PRINT VIEW MODEL  (owner-only)
// ─────────────────────────────────────────────────────────────────────────────

data class RemotePrintUiState(
    val recentJobs:          List<RemotePrintJob> = emptyList(),
    val registeredDevice:    ShopDeviceEntity?    = null,
    val liveDeviceStatus:    ShopDevice?          = null,
    val pendingJobCount:     Int                  = 0,
    val isLoading:           Boolean              = false,
    val error:               String?              = null,
    val message:             String?              = null
)

@HiltViewModel
class RemotePrintViewModel @Inject constructor(
    private val remotePrintRepository: RemotePrintRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RemotePrintUiState())
    val state: StateFlow<RemotePrintUiState> = _state.asStateFlow()

    init {
        // Observe local Room jobs
        viewModelScope.launch {
            remotePrintRepository.observeRecentJobs()
                .collect { jobs -> _state.update { it.copy(recentJobs = jobs) } }
        }
        // Observe registered device (local Room)
        viewModelScope.launch {
            remotePrintRepository.observeRegisteredDevice()
                .collect { device ->
                    _state.update { it.copy(registeredDevice = device) }
                    // Once we know the shopId, start observing live Firestore status
                    if (device != null) observeLiveStatus(device.shopId)
                }
        }
        // Observe pending job count badge
        viewModelScope.launch {
            remotePrintRepository.observePendingJobCount()
                .collect { count -> _state.update { it.copy(pendingJobCount = count) } }
        }
    }

    private var liveJob: kotlinx.coroutines.Job? = null
    private fun observeLiveStatus(shopId: String) {
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            remotePrintRepository.observeShopDeviceLive(shopId)
                .retryWhen { cause, _ ->
                    _state.update { it.copy(liveDeviceStatus = null, error = "Cannot read printer status: ${cause.message}") }
                    kotlinx.coroutines.delay(5_000); true
                }
                .collect { device -> _state.update { it.copy(liveDeviceStatus = device) } }
        }
    }

    /** Owner retries a FAILED job — does NOT create a new bill */
    fun retryJob(jobId: String) {
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                when (val r = remotePrintRepository.retryJob(jobId)) {
                    is Result.Success -> _state.update { it.copy(message = "Receipt copy queued") }
                    is Result.Error   -> _state.update { it.copy(error = r.message) }
                    else              -> Unit
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Cannot retry receipt") } }
            finally { _state.update { it.copy(isLoading = false) } }
        }
    }

    /** Owner syncs any offline-queued jobs to Firestore */
    fun syncOfflineJobs() {
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                when (val result = remotePrintRepository.syncPendingJobsToFirestore()) {
                    is Result.Success -> _state.update { it.copy(message = "${result.data} job(s) synced") }
                    is Result.Error -> _state.update { it.copy(error = result.message) }
                    else -> Unit
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Cannot sync print jobs") } }
            finally { _state.update { it.copy(isLoading = false) } }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null, error = null) }
}

// ─────────────────────────────────────────────────────────────────────────────
// SHOP DEVICE VIEW MODEL  (owner registration + print-agent toggle)
// ─────────────────────────────────────────────────────────────────────────────

data class ShopDeviceUiState(
    val registeredDevice: ShopDeviceEntity? = null,
    val firebaseUid: String = "",
    val liveStatus:       ShopDevice?       = null,
    // Registration form
    val shopId:           String            = "",
    val deviceName:       String            = android.os.Build.MODEL,
    val isRegistering:    Boolean           = false,
    val isPrintAgentRunning: Boolean        = false,
    val isThisPrinter: Boolean             = false,
    val canManage: Boolean                 = false,
    val isRefreshing: Boolean              = false,
    val isStartingAgent: Boolean           = false,
    val pairedPrinters: List<com.hardwareshop.pos.util.BluetoothPrintManager.BluetoothDeviceInfo> = emptyList(),
    val printerMac: String                 = "",
    val isTestingPrinter: Boolean          = false,
    val agentError:       String?           = null,
    val error:            String?           = null,
    val message:          String?           = null
)

@HiltViewModel
class ShopDeviceViewModel @Inject constructor(
    private val remotePrintRepository: RemotePrintRepository,
    private val firebase: com.hardwareshop.pos.data.remote.FirebaseManager,
    private val settings: com.hardwareshop.pos.data.repository.SettingsRepository,
    private val journal: com.hardwareshop.pos.data.sync.SyncJournal,
    private val session: com.hardwareshop.pos.util.SessionHolder,
    private val printer: com.hardwareshop.pos.util.BluetoothPrintManager,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(ShopDeviceUiState())
    val state: StateFlow<ShopDeviceUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(canManage = session.current is UserSession.OwnerSession) }
        viewModelScope.launch {
            _state.update { it.copy(shopId = journal.linkedStore() ?: settings.getOrCreateShopId()) }
            when (val result = firebase.ensureSignedIn()) {
                is Result.Success -> _state.update { it.copy(firebaseUid = result.data) }
                is Result.Error -> _state.update { it.copy(error = result.message) }
                else -> Unit
            }
            refreshShopPrinter()
        }
        viewModelScope.launch {
            remotePrintRepository.observeRegisteredDevice()
                .collect { device ->
                    _state.update { it.copy(registeredDevice = device,
                        isThisPrinter = device != null && device.deviceId == printerDeviceId(context)) }
                    if (device != null) {
                        _state.update { it.copy(shopId = device.shopId) }
                        observeLiveStatus(device.shopId)
                    }
                }
        }
        // Check if foreground service is running
        _state.update { it.copy(isPrintAgentRunning = isServiceRunning()) }
        val previousError = context.getSharedPreferences("printer_mode", Context.MODE_PRIVATE).getString("last_error", null)
        if (previousError != null) _state.update { it.copy(agentError = previousError) }
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(2_000)
                val agentError = context.getSharedPreferences("printer_mode", Context.MODE_PRIVATE).getString("last_error", null)
                _state.update { it.copy(isPrintAgentRunning = isServiceRunning(), agentError = agentError) }
            }
        }
    }

    fun update(block: ShopDeviceUiState.() -> ShopDeviceUiState) = _state.update(block)

    fun refreshShopPrinter() {
        if(_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing=true,error=null) }
        viewModelScope.launch {
            try {
                when(val result=remotePrintRepository.refreshRegisteredPrinter()) {
                    is Result.Success -> _state.update { it.copy(registeredDevice=result.data,
                        shopId=result.data.shopId,isThisPrinter=result.data.deviceId==printerDeviceId(context)) }
                    is Result.Error -> _state.update { it.copy(error=result.message) }
                    else -> Unit
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error=e.message ?: "Cannot refresh shop printer") } }
            finally { _state.update { it.copy(isRefreshing=false) } }
        }
    }
    fun refreshPrinters() {
        viewModelScope.launch {
            _state.update { it.copy(pairedPrinters=printer.getPairedDevices(),printerMac=printer.getSavedPrinterAddress()) }
        }
    }
    fun selectPrinter(address:String) {
        if(!_state.value.isThisPrinter) return
        viewModelScope.launch {
            try {
                printer.savePrinterAddress(address)
                _state.update { it.copy(printerMac=address,error=null,message="Printer selected on this phone") }
            } catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(e:Exception) { _state.update { it.copy(error=e.message ?: "Cannot select printer") } }
        }
    }
    fun testPrinter() {
        if(_state.value.isTestingPrinter || !_state.value.isThisPrinter) return
        _state.update { it.copy(isTestingPrinter=true,error=null,message=null) }
        viewModelScope.launch {
            try {
                when(val result=printer.printTestPage()) {
                    is Result.Success -> _state.update { it.copy(message="Test sent. Check the paper before enabling remote printing.") }
                    is Result.Error -> _state.update { it.copy(error=result.message) }
                    else -> Unit
                }
            } catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(e:Exception) { _state.update { it.copy(error=e.message ?: "Test print failed") } }
            finally { _state.update { it.copy(isTestingPrinter=false) } }
        }
    }

    /** Register THIS device as the shop printer (owner PIN-authenticated) */
    fun registerDevice() {
        val s = _state.value
        if (s.isRegistering) return
        if (s.shopId.isBlank())    { _state.update { it.copy(error = "Enter the Shop ID shown on the owner's phone") }; return }
        if (s.deviceName.isBlank()) { _state.update { it.copy(error = "Enter a name for this device") }; return }

        _state.update { it.copy(isRegistering = true, error = null) }
        viewModelScope.launch {
            when (val r = remotePrintRepository.registerThisDevice(s.shopId.trim(), s.deviceName.trim())) {
                is Result.Success -> {
                    _state.update { it.copy(isRegistering = false, message = "Device registered as shop printer!") }
                }
                is Result.Error -> {
                    _state.update { it.copy(isRegistering = false, error = r.message) }
                }
                else -> Unit
            }
        }
    }

    fun linkSender() {
        if (_state.value.isRegistering) return
        val shopId = _state.value.shopId.trim()
        _state.update { it.copy(isRegistering = true, error = null) }
        viewModelScope.launch {
            try {
                when (val result = remotePrintRepository.linkSender(shopId)) {
                    is Result.Success -> _state.update { it.copy(message = "Linked to shop printer. Create a bill, then send it remotely.") }
                    is Result.Error -> _state.update { it.copy(error = result.message) }
                    else -> Unit
                }
            } finally { _state.update { it.copy(isRegistering = false) } }
        }
    }

    fun unregisterDevice() {
        viewModelScope.launch {
            stopPrintAgent()
            remotePrintRepository.unregisterDevice()
            _state.update { it.copy(message = "Device unregistered") }
        }
    }

    fun startPrintAgent() {
        if(_state.value.isStartingAgent) return
        _state.update { it.copy(isStartingAgent=true,error=null) }
        viewModelScope.launch {
        try {
            val thisDevice = printerDeviceId(context)
            val refreshed=remotePrintRepository.refreshRegisteredPrinter()
            check(refreshed is Result.Success) { (refreshed as? Result.Error)?.message ?: "Cannot check printer registration" }
            val device=refreshed.data
            check(device.deviceId == thisDevice) { "This phone is linked as a sender. Register the phone beside the printer during owner setup." }
            check(printer.isBluetoothEnabled()) { "Turn Bluetooth on, then select and test the printer below." }
            val selected=printer.getSavedPrinterAddress()
            check(printer.getPairedDevices().any { it.address==selected }) {
                "Select a paired printer below and print a test receipt before starting the agent."
            }
            firebase.verifyRegisteredPrinter(device.shopId, device.deviceId)
            com.hardwareshop.pos.service.ShopPrintAgentService.start(context)
            _state.update { it.copy(isPrintAgentRunning = true, error = null, message = "Print Agent started") }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            _state.update { it.copy(isPrintAgentRunning = false, error = "Printer access check timed out. Check internet and cloud setup.") }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(isPrintAgentRunning = false, error = e.message ?: "Cannot start print agent") }
        } finally { _state.update { it.copy(isStartingAgent=false) } }
        }
    }

    fun stopPrintAgent() {
        com.hardwareshop.pos.service.ShopPrintAgentService.stop(context)
        _state.update { it.copy(isPrintAgentRunning = false, agentError = null, error = null) }
    }

    fun clearMessage() = _state.update { it.copy(message = null, error = null) }

    private var liveJob: kotlinx.coroutines.Job? = null
    private fun observeLiveStatus(shopId: String) {
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            remotePrintRepository.observeShopDeviceLive(shopId)
                .retryWhen { cause, _ ->
                    _state.update { it.copy(liveStatus = null, error = "Cannot read printer status: ${cause.message}") }
                    kotlinx.coroutines.delay(5_000); true
                }
                .collect { device -> _state.update { it.copy(liveStatus = device,
                    isThisPrinter = device?.deviceId == printerDeviceId(context)) } }
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(100).any {
            it.service.className == com.hardwareshop.pos.service.ShopPrintAgentService::class.java.name
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BILL SUCCESS REMOTE PRINT VIEW MODEL  (used from BillSuccessScreen)
// ─────────────────────────────────────────────────────────────────────────────

data class RemotePrintForBillState(
    val job:       RemotePrintJob? = null,
    val isSending: Boolean         = false,
    val error:     String?         = null,
    val message:   String?         = null
)

@HiltViewModel
class RemotePrintForBillViewModel @Inject constructor(
    private val remotePrintRepository: RemotePrintRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RemotePrintForBillState())
    val state: StateFlow<RemotePrintForBillState> = _state.asStateFlow()

    fun observeJobForBill(billId: Long) {
        viewModelScope.launch {
            remotePrintRepository.observeJobForBill(billId)
                .collect { job -> _state.update { it.copy(job = job) } }
        }
    }

    /**
     * Send a remote print job for this bill.
     * Permission enforced in RemotePrintRepository.createRemotePrintJob().
     * WorkerSession → SecurityException → shown as error in UI.
     */
    fun sendRemotePrintJob(billId: Long) {
        if(_state.value.isSending) return
        _state.update { it.copy(isSending = true, error = null) }
        viewModelScope.launch {
            when (val r = remotePrintRepository.createRemotePrintJob(billId)) {
                is Result.Success -> _state.update { it.copy(isSending = false, message = "Print request saved. Check its status below.") }
                is Result.Error   -> _state.update { it.copy(isSending = false, error = r.message) }
                else              -> Unit
            }
        }
    }

    /** Called only after the user confirms that another paper copy is wanted. */
    fun retryFailedJob() {
        val job=_state.value.job ?: return
        if(_state.value.isSending || job.status!=RemotePrintStatus.FAILED) return
        _state.update { it.copy(isSending=true,error=null,message=null) }
        viewModelScope.launch {
            try {
                when(val result=remotePrintRepository.retryJob(job.jobId)) {
                    is Result.Success -> _state.update { it.copy(message="Copy requested from shop printer") }
                    is Result.Error -> _state.update { it.copy(error=result.message) }
                    else -> Unit
                }
            } catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(e:Exception) { _state.update { it.copy(error=e.message ?: "Cannot retry print") } }
            finally { _state.update { it.copy(isSending=false) } }
        }
    }

}

package com.hardwareshop.pos.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.hardwareshop.pos.data.local.dao.SettingsDao
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import com.hardwareshop.pos.data.repository.SettingsRepository
import com.hardwareshop.pos.domain.model.Bill
import com.hardwareshop.pos.domain.model.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Bluetooth Classic/SPP ESC/POS transport. Success means bytes sent, not paper acknowledged. */
@SuppressLint("MissingPermission")
@Singleton
class BluetoothPrintManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsDao: SettingsDao,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val ADDRESS_KEY = "bluetooth_printer_mac"
    }
    private val transport = PrinterTransport()
    @Volatile private var activeSocket: BluetoothSocket? = null
    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    data class BluetoothDeviceInfo(val name: String, val address: String)
    fun hasPermission() = context.hasBluetoothPermissions()
    fun isBluetoothEnabled() = try { adapter?.isEnabled == true } catch (_: SecurityException) { false }
    fun getPairedDevices(): List<BluetoothDeviceInfo> {
        if (!hasPermission()) return emptyList()
        return try {
            adapter?.bondedDevices?.map { BluetoothDeviceInfo(it.name ?: "Unknown", it.address) }
                ?.sortedBy { it.name.lowercase() }.orEmpty()
        } catch (_: SecurityException) { emptyList() }
    }
    suspend fun savePrinterAddress(mac: String) {
        require(BluetoothAdapter.checkBluetoothAddress(mac)) { "Invalid printer address" }
        settingsDao.upsertSetting(SettingsEntity(ADDRESS_KEY, mac))
    }
    suspend fun getSavedPrinterAddress(): String = settingsDao.getSetting(ADDRESS_KEY)?.value.orEmpty()
    /** This is an active socket observation only. Idle printers connect on the next print. */
    fun isConnected(): Boolean = hasPermission() && isBluetoothEnabled() && activeSocket?.isConnected == true

    suspend fun hasPreviousAttempt(billId: Long): Boolean =
        !settingsDao.getSetting("local_print_attempt_$billId")?.value.isNullOrBlank()

    suspend fun printBill(bill: Bill, shopInfo: SettingsRepository.ShopInfo? = null,
                          copy: Boolean = bill.isPrinted): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val markedCopy = copy || hasPreviousAttempt(bill.id)
            val bytes = ReceiptBuilder.buildReceipt(bill, shopInfo ?: settingsRepository.getShopInfo(), markedCopy)
            sendBytes(bytes) {
                // Durable before the first byte: a crash must not hide an uncertain previous attempt.
                settingsDao.upsertSetting(SettingsEntity("local_print_attempt_${bill.id}", "ATTEMPTED"))
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.Error("Cannot prepare receipt: ${e.message}", e) }
    }
    suspend fun printRawBytes(bytes: ByteArray, beforeWrite: suspend () -> Unit = {}): Result<Unit> = sendBytes(bytes, beforeWrite)
    suspend fun printTestPage(): Result<Unit> = withContext(Dispatchers.IO) { sendBytes(ReceiptBuilder.testPage()) }

    private suspend fun sendBytes(bytes: ByteArray, beforeWrite: suspend () -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val bt = adapter ?: return@withContext Result.Error("This device has no Bluetooth.")
            if (!hasPermission()) return@withContext Result.Error("Allow Nearby devices permission to print.")
            if (!bt.isEnabled) return@withContext Result.Error("Turn Bluetooth on to print.")
            val mac = getSavedPrinterAddress()
            if (!BluetoothAdapter.checkBluetoothAddress(mac))
                return@withContext Result.Error("Select a paired printer in Settings first.")
            val device = bt.getRemoteDevice(mac)
            if (device.bondState != BluetoothDevice.BOND_BONDED)
                return@withContext Result.Error("Pair this printer in Android Bluetooth settings first.")
            // The app connects only to paired devices; SCAN permission is not required.
            // cancelDiscovery needs SCAN on Android 12+, so it is best effort.
            runCatching { bt.cancelDiscovery() }
            fun connection(secure: Boolean): PrinterConnection {
                val socket = if (secure) device.createRfcommSocketToServiceRecord(SPP_UUID)
                    else device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                return object : PrinterConnection {
                    override fun connect() { socket.connect(); activeSocket = socket }
                    override val output get() = socket.outputStream
                    override fun close() {
                        try { socket.close() } finally { if (activeSocket === socket) activeSocket = null }
                    }
                }
            }
            transport.send(bytes, listOf({ connection(true) }, { connection(false) }), beforeWrite)
            Result.Success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: SecurityException) { Result.Error("Bluetooth permission was removed. Allow Nearby devices and retry.", e) }
        catch (e: Exception) {
            Timber.w(e, "Printer transfer failed")
            Result.Error(e.message ?: "Printer transfer failed", e)
        }
    }
}

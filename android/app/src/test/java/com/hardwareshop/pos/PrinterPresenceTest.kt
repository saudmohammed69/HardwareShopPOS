package com.hardwareshop.pos

import com.hardwareshop.pos.domain.model.ShopDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterPresenceTest {
    private fun device(heartbeat: Long?) = ShopDevice(
        deviceId = "shop-phone", deviceName = "Shop phone", shopId = "shop",
        isOnline = true, bluetoothPrinterName = "Veer",
        bluetoothPrinterConnected = false, lastHeartbeatMs = heartbeat
    )

    @Test fun heartbeatExpiresWithoutAnotherServerSnapshot() {
        val printer = device(1_000_000L)
        assertTrue(printer.isRecentlyOnlineAt(1_089_999L))
        assertFalse(printer.isRecentlyOnlineAt(1_090_000L))
        assertFalse(printer.isRecentlyOnlineAt(1_200_000L))
    }

    @Test fun missingOrInvalidHeartbeatDoesNotReportPresence() {
        assertFalse(device(null).isRecentlyOnlineAt(1_000_000L))
        assertFalse(device(0L).isRecentlyOnlineAt(1_000_000L))
        assertFalse(device(-1L).isRecentlyOnlineAt(1_000_000L))
    }

    @Test fun newHeartbeatRestoresPresenceWithoutAContinuousBluetoothConnection() {
        val printer = device(1_000_000L)
        assertFalse(printer.isRecentlyOnlineAt(1_100_000L))
        assertTrue(printer.copy(lastHeartbeatMs = 1_100_000L).isRecentlyOnlineAt(1_100_000L))
    }
}

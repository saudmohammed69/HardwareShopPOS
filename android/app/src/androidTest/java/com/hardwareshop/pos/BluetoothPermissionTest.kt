package com.hardwareshop.pos

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.repository.SettingsRepository
import com.hardwareshop.pos.service.ShopPrintAgentService
import com.hardwareshop.pos.util.*
import org.junit.Assert.*
import org.junit.Test

@SdkSuppress(minSdkVersion = 31)
class BluetoothPermissionTest {
    private fun deniedContext(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.BLUETOOTH_CONNECT) PackageManager.PERMISSION_DENIED
            else super.checkPermission(permission, pid, uid)
    }
    @Test fun deniedNearbyPermissionCannotStartServiceOrChangeAgentPreference() {
        val context = deniedContext()
        val enabledBefore = ShopPrintAgentService.isEnabled(context)
        assertFalse(ShopPrintAgentService.hasBluetoothPermission(context))
        try { ShopPrintAgentService.start(context); fail("Permission denial must block start") }
        catch (error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("Nearby devices")) }
        assertEquals(enabledBefore, ShopPrintAgentService.isEnabled(context))
    }
    @Test fun deniedNearbyPermissionReturnsNoPairedDevicesWithoutSecurityCrash() {
        val context = deniedContext()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val manager = BluetoothPrintManager(context, db.settingsDao(),
                SettingsRepository(db.settingsDao(), db.ownerCredentialDao(), SessionHolder()))
            assertFalse(manager.hasPermission())
            assertTrue(manager.getPairedDevices().isEmpty())
            assertFalse(manager.isConnected())
        } finally { db.close() }
    }
}

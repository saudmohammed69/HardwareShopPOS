package com.hardwareshop.pos.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hardwareshop.pos.data.local.database.AppDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Restarts ShopPrintAgentService after device reboot.
 *
 * Uses Hilt EntryPoint pattern instead of @AndroidEntryPoint — this is the
 * correct approach for BroadcastReceivers that may run before the Activity
 * lifecycle has started (e.g., on cold boot from BOOT_COMPLETED).
 *
 * @AndroidEntryPoint on BroadcastReceiver can fail on cold boot because the
 * Application may not have fully initialized Hilt yet at the point the
 * receiver is called.  EntryPointAccessors.fromApplication() is safer because
 * it retrieves the already-initialized Hilt component from the Application.
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootEntryPoint {
        fun database(): AppDatabase
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Timber.i("BootReceiver: ${intent.action}")
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val entryPoint = EntryPointAccessors.fromApplication(
                            context.applicationContext,
                            BootEntryPoint::class.java
                        )
                        val device = entryPoint.database()
                            .shopDeviceDao()
                            .getRegisteredDevice()

                        if (device != null && device.isRegistered && ShopPrintAgentService.isEnabled(context)) {
                            Timber.i("BootReceiver: starting ShopPrintAgentService")
                            ShopPrintAgentService.start(context)
                        } else {
                            Timber.d("BootReceiver: no registered shop device — skip")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "BootReceiver: failed to start service")
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}

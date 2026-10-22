package com.hardwareshop.pos.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Runtime permissions needed to talk to a paired Bluetooth printer (none needed below Android 12). */
fun requiredBluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT)
    else emptyArray()

fun Context.hasBluetoothPermissions(): Boolean =
    requiredBluetoothPermissions().all { hasPermission(it) }


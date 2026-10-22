package com.hardwareshop.pos.util

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

/** Matches existing shop-printer registrations. This ID is not used for advertising or analytics.
 * Firebase authentication and shop membership remain the authorization checks.
 * Migrating to an installation UUID requires explicitly re-registering existing printers.
 */
@SuppressLint("HardwareIds")
fun printerDeviceId(context: Context): String? =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

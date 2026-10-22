package com.hardwareshop.pos.data.remote

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Creating the account designates this installation as its catalogue source. */
@Singleton
class CloudCataloguePolicy @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val preferences = context.getSharedPreferences("cloud_catalogue", Context.MODE_PRIVATE)
    private val deviceId = com.hardwareshop.pos.util.printerDeviceId(context)
    private fun isPrimary(uid: String): Boolean = preferences.getString("primary_uid", null) == uid &&
        deviceId != null && preferences.getString("primary_device", null) == deviceId
    fun recordPrimary(uid: String) = preferences.edit { putString("primary_uid", uid); putString("primary_device", deviceId) }
    fun connected(uid: String, created: Boolean) = preferences.edit {
        putString("account_uid", uid)
        putBoolean("use_cloud", !created && !isPrimary(uid))
    }
    fun usesCloud(uid: String): Boolean = preferences.getString("account_uid", null) == uid &&
        preferences.getBoolean("use_cloud", false) && !isPrimary(uid)
}

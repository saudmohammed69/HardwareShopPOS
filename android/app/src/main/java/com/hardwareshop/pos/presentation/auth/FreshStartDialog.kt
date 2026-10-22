package com.hardwareshop.pos.presentation.auth

import android.app.ActivityManager
import android.content.Context
import androidx.compose.material3.*
import androidx.compose.runtime.*

/** Android clears only this app's local storage and ends its process; no cloud deletion is called. */
internal fun clearLocalShopData(context: Context): Boolean =
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.clearApplicationUserData() == true

@Composable
internal fun FreshStartDialog(onDismiss: () -> Unit, onClearLocalData: () -> Boolean) {
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Start fresh on this phone?") },
        text = { Text(error ?: "Deletes this app's local bills, products, customers, stock, passwords, photos and settings. Your cloud account and cloud shop stay unchanged. The app will close; reopen it and sign in with email and password.") },
        confirmButton = {
            TextButton(onClick = {
                val started = try { onClearLocalData() } catch (_: Exception) { false }
                if (!started) error = "Could not reset the app. Open Android App info → Storage → Clear data, then reopen the app."
            }) { Text("CLEAR LOCAL DATA") }
        },
        dismissButton = { TextButton(onDismiss) { Text("KEEP DATA") } })
}

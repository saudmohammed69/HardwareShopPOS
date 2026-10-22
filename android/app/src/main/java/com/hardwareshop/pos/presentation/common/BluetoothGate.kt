package com.hardwareshop.pos.presentation.common

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hardwareshop.pos.util.hasBluetoothPermissions
import com.hardwareshop.pos.util.requiredBluetoothPermissions

/**
 * Makes sure the app may use Bluetooth before a printer action runs:
 *   1. asks for the Android 12+ "Nearby devices" permission if missing,
 *   2. asks the user to turn Bluetooth on if it is off,
 *   3. only then runs the action.
 *
 * Usage:
 *   val withBluetooth = rememberBluetoothGate { msg -> showError(msg) }
 *   Button(onClick = { withBluetooth { viewModel.print() } })
 */
@Composable
fun rememberBluetoothGate(onBlocked: (String) -> Unit): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val enableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK) action?.invoke()
        else onBlocked("Bluetooth must be turned on to print.")
    }

    fun continueWhenEnabled() {
        val action = pending ?: return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        when {
            adapter == null -> { pending = null; onBlocked("This device has no Bluetooth.") }
            adapter.isEnabled -> { pending = null; action() }
            else -> enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (context.hasBluetoothPermissions()) continueWhenEnabled()
        else {
            pending = null
            onBlocked("Bluetooth permission denied. Allow \"Nearby devices\" in the app's permission settings to print.")
        }
    }

    return { action ->
        pending = action
        if (context.hasBluetoothPermissions()) continueWhenEnabled()
        else permissionLauncher.launch(requiredBluetoothPermissions())
    }
}

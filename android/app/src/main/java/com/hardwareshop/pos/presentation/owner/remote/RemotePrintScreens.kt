// Compose state delegate writes are observed during recomposition; retain the UI state updates.
@file:Suppress("AssignedValueIsNeverRead")

package com.hardwareshop.pos.presentation.owner.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.presentation.common.*
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// REMOTE PRINT QUEUE SCREEN  (owner only)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RemotePrintQueueScreen(
    onNavigateUp: () -> Unit,
    viewModel:    RemotePrintViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    var showPrintingHelp by remember { mutableStateOf(false) }
    if (showPrintingHelp) RemotePrintingHelpDialog { showPrintingHelp = false }

    state.message?.let { LaunchedEffect(it) { kotlinx.coroutines.delay(2000); viewModel.clearMessage() } }

    Scaffold(
        topBar = {
            AppTopBar(
                title        = "Remote Print Queue",
                onNavigateUp = onNavigateUp,
                actions      = {
                    IconButton(onClick = viewModel::syncOfflineJobs, enabled = !state.isLoading) {
                        Icon(Icons.Default.Sync, "Sync offline jobs", tint = AppColors.OnPrimary)
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // ── Device status banner ──────────────────────────────────────────
            state.liveDeviceStatus?.let { device ->
                DeviceStatusBanner(device)
            } ?: run {
                state.registeredDevice?.let {
                    Card(
                        Modifier.fillMaxWidth().padding(8.dp),
                        colors = CardDefaults.cardColors(containerColor = AppColors.Warning.copy(alpha = 0.1f))
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.WifiOff, null, tint = AppColors.Warning)
                            Spacer(Modifier.width(8.dp))
                            Text("Connecting to shop device…", color = AppColors.Warning)
                        }
                    }
                } ?: run {
                    Card(
                        Modifier.fillMaxWidth().padding(8.dp),
                        colors = CardDefaults.cardColors(containerColor = AppColors.Error.copy(alpha = 0.1f))
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PrintDisabled, null, tint = AppColors.Error)
                            Spacer(Modifier.width(8.dp))
                            Text("No shop printer device registered", color = AppColors.Error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (state.message != null) {
                Text(state.message!!, color = AppColors.Success, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { ErrorMessage(it, Modifier.padding(horizontal = 8.dp)) }
            TextButton(onClick = { showPrintingHelp = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("PRINTING HELP")
            }

            if (state.recentJobs.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Print, null, tint = AppColors.TextSecondary, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No remote print jobs yet", color = AppColors.TextSecondary)
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.recentJobs, key = { it.jobId }) { job ->
                        RemotePrintJobCard(
                            job    = job,
                            timeFmt = fmt,
                            isBusy = state.isLoading,
                            onRetry = { viewModel.retryJob(job.jobId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemotePrintJobCard(
    job:     RemotePrintJob,
    timeFmt: SimpleDateFormat,
    isBusy: Boolean,
    onRetry: () -> Unit
) {
    var confirmRetry by remember { mutableStateOf(false) }
    if (confirmRetry) AlertDialog(
        onDismissRequest = { confirmRetry = false },
        title = { Text("Retry as a copy?") },
        text = { Text("Check the printer for a complete or partial receipt first. An interrupted print may already be on paper. Active jobs cannot be retried until their lease expires.") },
        confirmButton = { TextButton(enabled = !isBusy, onClick = { confirmRetry = false; onRetry() }) { Text("PRINT COPY") } },
        dismissButton = { TextButton(onClick = { confirmRetry = false }) { Text("CANCEL") } }
    )
    val (statusColor, statusIcon) = when (job.status) {
        RemotePrintStatus.PRINTED  -> AppColors.Success to Icons.Default.CheckCircle
        RemotePrintStatus.FAILED   -> AppColors.Error   to Icons.Default.Cancel
        RemotePrintStatus.PRINTING -> AppColors.Primary to Icons.Default.Print
        RemotePrintStatus.RECEIVED -> AppColors.Primary to Icons.Default.Download
        else                       -> AppColors.Warning to Icons.Default.HourglassBottom
    }

    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Bill #${job.billNumber}", fontWeight = FontWeight.Bold)
                }
                Text(
                    "${job.status.emoji} ${job.status.displayLabel}",
                    color = statusColor,
                    style = MaterialTheme.typography.bodySmall
                )
                if (!job.lastError.isNullOrBlank()) {
                    Text("⚠ ${job.lastError}", style = MaterialTheme.typography.labelSmall, color = AppColors.Error)
                }
                Text(timeFmt.format(Date(job.createdAt)), style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                if (job.retryCount > 0) Text("Retry #${job.retryCount}", style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
            }
            if (job.status in listOf(RemotePrintStatus.FAILED, RemotePrintStatus.RECEIVED, RemotePrintStatus.PRINTING)) {
                OutlinedButton(enabled = !isBusy, onClick = { confirmRetry = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun DeviceStatusBanner(device: ShopDevice) {
    // Heartbeat expiry must update even if Firestore stops sending snapshots.
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            value = System.currentTimeMillis()
        }
    }
    val isOnline = device.isOnline && device.isRecentlyOnlineAt(now)
    val btOk      = device.bluetoothPrinterConnected
    val bannerColor = when {
        !isOnline -> AppColors.Error.copy(alpha = 0.08f)
        !btOk     -> AppColors.Primary.copy(alpha = 0.08f)
        else      -> AppColors.Success.copy(alpha = 0.08f)
    }
    Card(Modifier.fillMaxWidth().padding(8.dp), colors = CardDefaults.cardColors(containerColor = bannerColor)) {
        Column(Modifier.padding(12.dp), Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text(device.deviceName, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dotColor = if (isOnline) AppColors.Success else AppColors.Error
                    Box(Modifier.size(8.dp).background(dotColor, androidx.compose.foundation.shape.CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isOnline) "Online" else "Offline", style = MaterialTheme.typography.labelSmall, color = dotColor)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bluetooth, null, modifier = Modifier.size(14.dp),
                        tint = if (btOk) AppColors.Success else AppColors.TextSecondary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (btOk) "Sending to printer" else "Connects when printing",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (btOk) AppColors.Success else AppColors.TextSecondary
                    )
                }
                if (device.pendingJobCount > 0) {
                    Text("${device.pendingJobCount} pending", style = MaterialTheme.typography.labelSmall, color = AppColors.Warning)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SHOP DEVICE REGISTRATION SCREEN  (owner + shop device setup)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ShopDeviceScreen(
    onNavigateUp: () -> Unit,
    viewModel:    ShopDeviceViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    state.message?.let { LaunchedEffect(it) { kotlinx.coroutines.delay(2000); viewModel.clearMessage() } }

    val withBluetooth = rememberBluetoothGate { message -> viewModel.update { copy(error = message) } }
    val printerContext=androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.isThisPrinter) { if(state.isThisPrinter) viewModel.refreshPrinters() }
    var confirmPrinterReplacement by remember { mutableStateOf(false) }
    if (confirmPrinterReplacement) {
        AlertDialog(onDismissRequest = { confirmPrinterReplacement = false },
            title = { Text("Use this phone as the shop printer?") },
            text = { Text("This replaces the registered printer phone. The phone beside the Bluetooth printer should receive remote jobs.") },
            confirmButton = { TextButton(onClick = { confirmPrinterReplacement = false; viewModel.registerDevice() }) { Text("USE THIS PHONE") } },
            dismissButton = { TextButton(onClick = { confirmPrinterReplacement = false }) { Text("CANCEL") } })
    }
    Scaffold(topBar = { AppTopBar("Shop Printer Device", onNavigateUp = onNavigateUp) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding      = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
        androidx.compose.foundation.text.selection.SelectionContainer {
                Text("Cloud device UID: ${state.firebaseUid.ifBlank { "Connecting…" }}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Sign in with the shop account on both phones. Register only the phone beside the printer; the owner phone sends jobs to it. Older anonymous connections need approved shop membership.", style = MaterialTheme.typography.bodySmall)
                        }
            state.error?.let { item { ErrorMessage(it) } }
            state.agentError?.takeIf { it != state.error }?.let { item { ErrorMessage(it) } }
            state.message?.let { item { Text(it, color = AppColors.Success) } }
            item {
                OutlinedButton(onClick=viewModel::refreshShopPrinter,enabled=!state.isRefreshing && !state.isRegistering,
                    modifier=Modifier.fillMaxWidth()) { Text(if(state.isRefreshing) "CHECKING SHOP PRINTER…" else "REFRESH SHOP PRINTER") }
            }
            if(state.isThisPrinter) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Printer on this phone",fontWeight=FontWeight.Bold)
                        Text("Pair your Seznik Veer or other 58mm Bluetooth receipt printer, select it, then print a test.",
                            style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick={
                            try { printerContext.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
                            catch(e:Exception) { viewModel.update { copy(error="Open Android Settings → Bluetooth to pair the printer") } }
                        },modifier=Modifier.fillMaxWidth()) { Text("PAIR IN ANDROID BLUETOOTH SETTINGS") }
                        OutlinedButton(onClick={withBluetooth { viewModel.refreshPrinters() }},modifier=Modifier.fillMaxWidth()) {
                            Text("REFRESH PAIRED PRINTERS")
                        }
                        if(state.pairedPrinters.isEmpty()) Text("No paired devices shown. Allow Nearby devices and pair the printer first.",
                            style=MaterialTheme.typography.bodySmall)
                        state.pairedPrinters.forEach { device ->
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                RadioButton(selected=state.printerMac==device.address,
                                    enabled=!state.isTestingPrinter,onClick={viewModel.selectPrinter(device.address)})
                                Column(Modifier.weight(1f)) {
                                    Text(device.name)
                                    Text(device.address,style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        PrimaryActionButton(text="PRINT TEST RECEIPT",isLoading=state.isTestingPrinter,
                            enabled=state.printerMac.isNotBlank(),onClick={withBluetooth { viewModel.testPrinter() }})
                    }
                }
            }
            if (state.registeredDevice != null) {
                // ── Registered state ─────────────────────────────────────────
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppColors.Success.copy(alpha = 0.08f))) {
                        Column(Modifier.padding(16.dp), Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = AppColors.Success)
                                Spacer(Modifier.width(8.dp))
                                Text("Device Registered", fontWeight = FontWeight.Bold, color = AppColors.Success)
                            }
                            Text("Name: ${state.registeredDevice!!.deviceName}")
                            Text("Shop ID: ${state.registeredDevice!!.shopId}", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        }
                    }
                }
                item {
                    state.liveStatus?.let { DeviceStatusBanner(it) }
                }
                // ── Print Agent toggle ────────────────────────────────────────
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                            Text("Shop Print Agent", fontWeight = FontWeight.Bold)
                            Text("Keep this running to receive remote print jobs from the owner.",
                                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                            if (!state.isThisPrinter) {
                                Text("This phone sends jobs. Start the agent on the registered printer phone.",
                                    style = MaterialTheme.typography.bodyMedium)
                                if (state.canManage) OutlinedButton(onClick = { confirmPrinterReplacement = true }, enabled = !state.isRegistering,
                                    modifier = Modifier.fillMaxWidth()) { Text("USE THIS PHONE AS SHOP PRINTER") }
                                else Text("Ask the owner to register this phone as the printer during setup.")
                            } else if (state.isPrintAgentRunning) {
                                Button(onClick = viewModel::stopPrintAgent, modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Error)) {
                                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("STOP PRINT AGENT")
                                }
                            } else {
                                PrimaryActionButton(
                                    text    = "START PRINT AGENT",
                                    isLoading = state.isStartingAgent,
                                    onClick = { withBluetooth { viewModel.startPrintAgent() } },
                                    icon    = Icons.Default.PlayArrow
                                )
                            }
                        }
                    }
                }
                if (state.canManage) item {
                    OutlinedButton(onClick = viewModel::linkSender, enabled = !state.isRegistering,
                        modifier = Modifier.fillMaxWidth()) { Text("LINK TO EXISTING SHOP PRINTER") }
                }
                item {
                    OutlinedButton(
                        onClick  = viewModel::unregisterDevice,
                        enabled = state.canManage,
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Error),
                        border   = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Error)
                    ) {
                        Icon(Icons.Default.LinkOff, null)
                        Spacer(Modifier.width(8.dp))
                        Text("UNREGISTER THIS DEVICE")
                    }
                }
            } else {
                if (!state.canManage) item { Text("Printer setup needs owner login on this phone. Workers can start the agent after setup.") }
                // ── Registration form ─────────────────────────────────────────
                item {
                    Text("Register this Android device as the shop's printer.",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Use the same Shop ID on both phones. Register the phone next to the printer first, then link the owner phone as a sender.",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                }
                item {
                    OutlinedTextField(
                        value         = state.shopId,
                        onValueChange = { viewModel.update { copy(shopId = it.trim()) } },
                        label         = { Text("Shop ID (from owner's phone) *") },
                        leadingIcon   = { Icon(Icons.Default.Key, null) },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value         = state.deviceName,
                        onValueChange = { viewModel.update { copy(deviceName = it) } },
                        label         = { Text("Device Name (e.g. \"Shop Tablet\")") },
                        leadingIcon   = { Icon(Icons.Default.PhoneAndroid, null) },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth()
                    )
                }
                item {
                    PrimaryActionButton(
                        text      = "REGISTER AS SHOP PRINTER",
                        onClick   = viewModel::registerDevice,
                        isLoading = state.isRegistering,
                        enabled = state.canManage,
                        icon      = Icons.Default.Print
                    )
                }
                item { OutlinedButton(viewModel::linkSender, enabled = state.canManage && !state.isRegistering, modifier = Modifier.fillMaxWidth()) { Text("LINK TO EXISTING SHOP PRINTER") } }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REMOTE PRINT BUTTON  (shown in BillSuccessScreen for owner only)
// Owner's bill success screen gets an extra "Remote Print" button.
// Workers never see this composable (navigation guards + PermissionChecker).
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RemotePrintButton(
    billId:    Long,
    viewModel: RemotePrintForBillViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var confirmRetry by remember(billId) { mutableStateOf(false) }
    var showPrintingHelp by remember(billId) { mutableStateOf(false) }
    if (showPrintingHelp) RemotePrintingHelpDialog { showPrintingHelp = false }
    if(confirmRetry) AlertDialog(onDismissRequest={confirmRetry=false},
        title={Text("Request another receipt copy?")},
        text={Text("Check the printer paper first. An interrupted job may already have printed. Continuing requests a marked copy.")},
        confirmButton={TextButton(onClick={confirmRetry=false;viewModel.retryFailedJob()}) { Text("REQUEST COPY") }},
        dismissButton={TextButton(onClick={confirmRetry=false}) { Text("CANCEL") }})

    LaunchedEffect(billId) { viewModel.observeJobForBill(billId) }

    val job = state.job

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            state.isSending -> {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Sending to shop printer…")
                }
            }
            job != null -> {
                // Job already exists — show status
                val (color, label) = when (job.status) {
                    RemotePrintStatus.PRINTED  -> AppColors.Success to "Sent to shop printer — check paper"
                    RemotePrintStatus.FAILED   -> AppColors.Error   to "❌ Remote print failed — retry?"
                    RemotePrintStatus.PRINTING -> AppColors.Primary to "🖨️ Printing at shop…"
                    RemotePrintStatus.RECEIVED -> AppColors.Primary to "📥 Shop device received job"
                    else                       -> AppColors.Warning to "⏳ Waiting for shop printer"
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f))) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text(label, color = color, fontWeight = FontWeight.Medium)
                        if (job.status == RemotePrintStatus.FAILED) {
                            TextButton(onClick = { confirmRetry=true }) { Text("Retry") }
                        }
                    }
                }
                job.lastError?.takeIf { it.isNotBlank() }?.let {
                    Text("Print job waiting: $it", color = AppColors.Error, style = MaterialTheme.typography.bodySmall)
                }
                if (job.status != RemotePrintStatus.PRINTED) {
                    TextButton(onClick = { showPrintingHelp = true }) { Text("PRINTING HELP") }
                }
            }
            else -> {
                // No job yet — show send button
                OutlinedButton(
                    onClick  = { viewModel.sendRemotePrintJob(billId) },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Default.CloudUpload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("SEND TO SHOP PRINTER (Remote)")
                }
            }
        }
        state.error?.let { Text(it, color = AppColors.Error, style = MaterialTheme.typography.bodySmall) }
        state.message?.let { Text(it, color = AppColors.Success, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun RemotePrintingHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Get the shop printer ready") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1. Use the same shop login and Shop ID on both phones.")
            Text("2. On the phone beside the printer, open Shop Printer Device. Refresh the registration and check it is the registered printer phone.")
            Text("3. Turn on internet and Bluetooth, allow Nearby devices, select the paired printer and print a test receipt.")
            Text("4. Start Print Agent on that phone. It should show STOP PRINT AGENT. Allow its notification and keep the service running.")
            Text("5. On the sending phone, sync offline jobs and check the queue. Pending means the printer phone has not taken the job yet.")
            Text("Check the paper before retrying. A retry may print another copy. This app currently prints through Bluetooth, including on USB/Bluetooth printer models.")
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("GOT IT") } })
}

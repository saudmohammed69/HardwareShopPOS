package com.hardwareshop.pos.presentation.owner.misc

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.remote.ExcelSyncConfig
import com.hardwareshop.pos.data.remote.ExcelSyncRepository
import com.hardwareshop.pos.domain.model.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ExcelSyncViewModel @Inject constructor(
    private val excelSyncRepository: ExcelSyncRepository
) : ViewModel() {

    val config: StateFlow<ExcelSyncConfig> = excelSyncRepository.observeSyncConfig()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExcelSyncConfig())

    var isSyncing by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    fun saveConfig(url: String, enabled: Boolean) {
        viewModelScope.launch {
            when (val res = excelSyncRepository.updateConfig(url, enabled)) {
                is Result.Success -> message = "Configuration saved successfully"
                is Result.Error -> message = res.message
                else -> {}
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            isSyncing = true
            message = null
            when (val res = excelSyncRepository.performTwoWaySync()) {
                is Result.Success -> {
                    message = "Sync complete! Imported: ${res.data.importedCount} items, Exported: ${res.data.exportedCount} items."
                }
                is Result.Error -> {
                    message = res.message
                }
                else -> {}
            }
            isSyncing = false
        }
    }

    fun exportToDeviceCsv(type: String, onDone: (String, String) -> Unit) {
        viewModelScope.launch {
            val (csv, filename) = when (type) {
                "BILLS" -> excelSyncRepository.exportBillsToCsv() to "Sales_Bills.csv"
                "CUSTOMERS" -> excelSyncRepository.exportCustomersToCsv() to "Customer_Udhar_Ledger.csv"
                "SUPPLIERS" -> excelSyncRepository.exportSuppliersToCsv() to "Suppliers_Purchases.csv"
                "CLOSINGS" -> excelSyncRepository.exportDayClosingsToCsv() to "Day_Closings.csv"
                else -> excelSyncRepository.exportLocalProductsToCsv() to "Products_Inventory.csv"
            }
            onDone(csv, filename)
        }
    }

    fun clearMessage() {
        message = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcelSyncScreen(
    onNavigateUp: () -> Unit,
    viewModel: ExcelSyncViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsState()
    val context = LocalContext.current

    var urlInput by remember(config.url) { mutableStateOf(config.url) }
    var autoSyncEnabled by remember(config.enabled) { mutableStateOf(config.enabled) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Excel / Google Sheets Sync") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Info Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CloudSync,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            "Two-Way Excel Live Sync",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            "Sync products, prices, and stock between your phone app and Google Sheets or Microsoft Excel.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Sync Status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sync Status", fontWeight = FontWeight.Bold)
                    if (config.lastSyncTime > 0) {
                        val formattedTime = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(config.lastSyncTime))
                        Text("Last Sync: $formattedTime", fontSize = 13.sp)
                    } else {
                        Text("Last Sync: Never", fontSize = 13.sp)
                    }
                    if (config.lastStatus.isNotBlank()) {
                        Text("Status: ${config.lastStatus}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Configuration Form
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("Google Sheet or Public CSV URL") },
                placeholder = { Text("https://docs.google.com/spreadsheets/d/...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Enable Automatic Hourly Sync", fontWeight = FontWeight.Medium)
                    Text("Syncs in background when online", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
                Switch(
                    checked = autoSyncEnabled,
                    onCheckedChange = { autoSyncEnabled = it }
                )
            }

            Button(
                onClick = { viewModel.saveConfig(urlInput, autoSyncEnabled) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Configuration")
            }

            Button(
                onClick = { viewModel.syncNow() },
                enabled = !viewModel.isSyncing && urlInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (viewModel.isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Syncing Catalog & Stock...")
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sync Now (Products & Stock)")
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("📤 Export Shop Sheets to Excel / CSV:", fontWeight = FontWeight.Bold, fontSize = 15.sp)

            fun triggerExport(type: String) {
                viewModel.exportToDeviceCsv(type) { csvData, filename ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        this.type = "text/csv"
                        putExtra(Intent.EXTRA_SUBJECT, filename)
                        putExtra(Intent.EXTRA_TEXT, csvData)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share $filename"))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { triggerExport("PRODUCTS") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export Products & Inventory (.csv)")
                }

                OutlinedButton(
                    onClick = { triggerExport("BILLS") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export Sales Bills & Invoices (.csv)")
                }

                OutlinedButton(
                    onClick = { triggerExport("CUSTOMERS") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export Customer Udhar Ledger (.csv)")
                }

                OutlinedButton(
                    onClick = { triggerExport("SUPPLIERS") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export Suppliers & Purchases (.csv)")
                }

                OutlinedButton(
                    onClick = { triggerExport("CLOSINGS") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export Day Closings Summary (.csv)")
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // Instructions Guide
            Text("📖 Quick Setup Guide for Google Sheets:", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("1. Open your product list in Google Sheets.", fontSize = 13.sp)
                    Text("2. Click File → Share → Publish to web.", fontSize = 13.sp)
                    Text("3. Select 'Comma-separated values (.csv)' and click Publish.", fontSize = 13.sp)
                    Text("4. Copy the published link and paste it into the URL field above.", fontSize = 13.sp)
                    Text("5. Tap 'Save Configuration' and 'Sync Now'!", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

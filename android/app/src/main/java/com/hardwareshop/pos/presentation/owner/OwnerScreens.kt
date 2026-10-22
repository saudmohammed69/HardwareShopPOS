package com.hardwareshop.pos.presentation.owner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.hardwareshop.pos.presentation.common.*
import com.hardwareshop.pos.util.CalculationEngine

// ─────────────────────────────────────────────────────────────────────────────
// OWNER HOME SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun OwnerHomeScreen(
    onOrders:()->Unit={},newOrderCount:Int=0,
    onWantList: () -> Unit = {},
    onCutPlanner: () -> Unit = {}, onPipeOffcuts: () -> Unit = {},
    onPurchases: () -> Unit = {}, onReturns: () -> Unit = {}, onReorder: () -> Unit = {},
    onNewBill: () -> Unit = {}, onDashboard: () -> Unit, onProducts: () -> Unit, onCustomers: () -> Unit,
    onReports: () -> Unit, onAudit: () -> Unit, onWorkers: () -> Unit,
    onSettings: () -> Unit, onStock: () -> Unit, onDayClose: () -> Unit,
    onBillHistory: () -> Unit, onRemotePrint: () -> Unit = {}, onPendingItems: () -> Unit = {},
    onGstBills: () -> Unit = {}, onManualProducts: () -> Unit = {}, onInventoryManager: () -> Unit = {},
    onQuotations: () -> Unit = {}, onWarranty: () -> Unit = {}, onExcelSync: () -> Unit = {},
    onLogout: () -> Unit,
    viewModel: OwnerHomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Scaffold(
        topBar = {
            AppTopBar(
                title   = state.shopName.ifBlank { "Owner Panel" },
                actions = {
                    IconButton(onClick = { viewModel.logout(); onLogout() }) {
                        Icon(Icons.AutoMirrored.Filled.Logout, "Logout", tint = AppColors.OnPrimary)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SectionHeader("Today's Overview")
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement   = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        StatCard(
                            label = "Total Sales",
                            value = CalculationEngine.formatRupees(state.todaySales),
                            icon  = Icons.AutoMirrored.Filled.TrendingUp,
                            color = AppColors.Primary
                        )
                    }
                    item {
                        StatCard(
                            label = "Bills Today",
                            value = state.todayBills.toString(),
                            icon  = Icons.Default.Receipt,
                            color = AppColors.Primary
                        )
                    }
                    item {
                        StatCard(
                            label = "Outstanding",
                            value = CalculationEngine.formatRupees(state.outstandingCredit),
                            icon  = Icons.Default.AccountBalanceWallet,
                            color = AppColors.Warning
                        )
                    }
                    item {
                        StatCard(
                            label = "Low Stock",
                            value = state.lowStockCount.toString(),
                            icon  = Icons.Default.Inventory,
                            color = if (state.lowStockCount > 0) AppColors.Error else AppColors.Success
                        )
                    }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            item { SectionHeader("Management") }

            val menuItems = listOf(
                Triple("New / Resume Bill", Icons.Default.AddShoppingCart, onNewBill),
                Triple("Orders ($newOrderCount new)", Icons.Default.Checklist, onOrders),
                Triple("Cut Planner", Icons.Default.ContentCut, onCutPlanner),
                Triple("Want List / Kharidi List", Icons.Default.Checklist, onWantList),
                Triple("Pipe Offcuts", Icons.Default.Inventory2, onPipeOffcuts),
                Triple("Dashboard & Reports",  Icons.Default.Dashboard,       onDashboard),
                Triple("Pending Item Details (${state.pendingItemCount})", Icons.Default.FolderOpen, onPendingItems),
                Triple("Manually Added Bills", Icons.Default.FolderOpen, onManualProducts),
                Triple("Purchases & Supplier Dues", Icons.Default.LocalShipping, onPurchases),
                Triple("Returns & Exchanges", Icons.Default.SwapHoriz, onReturns),
                Triple("Reorder List", Icons.Default.ShoppingCart, onReorder),
                Triple("Product & Inventory Manager", Icons.Default.Inventory, onInventoryManager),
                Triple("Products & Prices",    Icons.Default.Inventory2,      onProducts),
                Triple("Bill History",         Icons.Default.History,         onBillHistory),
                Triple("Quotations",           Icons.Default.Description,     onQuotations),
                Triple("Warranty & Serial Register", Icons.Default.Verified,  onWarranty),
                Triple("GST Bills 🧾",         Icons.Default.Receipt,         onGstBills),
                Triple("Customers & Credit",   Icons.Default.PeopleAlt,       onCustomers),
                Triple("Stock Management",     Icons.Default.Warehouse,       onStock),
                Triple("Workers",              Icons.Default.ManageAccounts,  onWorkers),
                Triple("Reports",              Icons.Default.Assessment,      onReports),
                Triple("Audit Log",            Icons.Default.Security,        onAudit),
                Triple("Remote Printing 🌐",   Icons.Default.CloudQueue,      onRemotePrint),
                Triple("Live Excel / Google Sheets Sync 🌐", Icons.Default.CloudSync, onExcelSync),
                Triple("Day Closing",          Icons.Default.EventAvailable,  onDayClose),
                Triple("Settings",             Icons.Default.Settings,        onSettings)
            )
            items(menuItems) { (label, icon, action) ->
                HierarchyCard(label = label, icon = icon, onClick = action)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DASHBOARD SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun DashboardScreen(
    onNavigateUp: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(topBar = { AppTopBar("Dashboard", onNavigateUp = onNavigateUp) }) { padding ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("today" to "Today", "week" to "Week", "month" to "Month").forEach { (k, l) ->
                        FilterChip(selected = state.period == k, onClick = { viewModel.setPeriod(k) }, label = { Text(l) })
                    }
                }
            }
            if (state.isLoading) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
            }
            state.summary?.let { s ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), Arrangement.spacedBy(10.dp)) {
                            Text("Sales Breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            HorizontalDivider()
                            AmountRow("Total Sales",      s.totalSalesPaisa,             amountColor = AppColors.Primary,
                                amountStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                            AmountRow("  Cash",           s.cashSalesPaisa)
                            AmountRow("  UPI",            s.upiSalesPaisa)
                            AmountRow("  Card",           s.cardSalesPaisa)
                            AmountRow("  Credit Given",   s.creditSalesPaisa,            amountColor = AppColors.Warning)
                            HorizontalDivider()
                            AmountRow("Discounts Given",  s.totalDiscountsPaisa,         amountColor = AppColors.Error)
                            AmountRow("Total Outstanding", s.totalOutstandingCreditPaisa, amountColor = AppColors.Warning)
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text("Bills: ${s.billCount}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                if (s.lowStockCount > 0)
                                    Text("⚠ ${s.lowStockCount} low stock", color = AppColors.LowStock, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

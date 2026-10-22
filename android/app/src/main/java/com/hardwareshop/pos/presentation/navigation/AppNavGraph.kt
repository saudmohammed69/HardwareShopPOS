package com.hardwareshop.pos.presentation.navigation

import com.hardwareshop.pos.presentation.website.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.*
import androidx.navigation.compose.*
import com.hardwareshop.pos.presentation.auth.*
import com.hardwareshop.pos.presentation.owner.*
import com.hardwareshop.pos.presentation.owner.misc.*
import com.hardwareshop.pos.presentation.owner.products.*
import com.hardwareshop.pos.presentation.owner.remote.*
import com.hardwareshop.pos.presentation.worker.billing.*

// ─────────────────────────────────────────────────────────────────────────────
// ROUTES
// ─────────────────────────────────────────────────────────────────────────────
object Routes {
    const val WEBSITE_ORDERS="website_orders"
    const val WEBSITE_REVIEW="website_review/{orderId}"
    fun websiteReview(id:String)="website_review/$id"
    const val SPLASH         = "splash"
    const val OWNER_SETUP    = "owner_setup"
    const val LOGIN          = "login"
    const val CLOUD_LOGIN    = "cloud_login"

    // Worker — nested under BILLING_GRAPH so BillingViewModel is graph-scoped
    const val BILLING_GRAPH  = "billing_graph"
    const val WORKER_HOME    = "worker_home"
    const val PRODUCT_SELECT = "product_select"
    const val CART           = "cart"
    const val PAYMENT        = "payment"
    const val BILL_SUCCESS   = "bill_success/{billId}?printRequested={printRequested}"
    fun billSuccess(id: Long, printRequested: Boolean = false) = "bill_success/$id?printRequested=$printRequested"

    // Owner
    const val CUT_PLANNER = "cut_planner"
    const val PIPE_OFFCUTS = "pipe_offcuts"
    const val WANT_LIST = "want_list"
    const val PURCHASES = "purchases"
    const val REORDER = "reorder"
    const val RETURNS = "returns/{billId}"
    fun returns(id: Long = 0) = "returns/$id"
    const val OWNER_HOME     = "owner_home"
    const val DASHBOARD      = "dashboard"
    const val PRODUCTS       = "products"
    const val INVENTORY_MANAGER = "inventory_manager"
    const val QUOTATIONS = "quotations"
    const val WARRANTY = "warranty"
    const val QUOTATION_REVIEW = "quotation_review/{quotationId}"
    fun quotationReview(id: String) = "quotation_review/${android.net.Uri.encode(id)}"
    const val PENDING_PRODUCTS = "pending_products"
    const val MANUAL_PRODUCTS = "manual_products"
    const val PRODUCT_DRAFT = "product_draft/{draftId}"
    fun productDraft(id: String) = "product_draft/${android.net.Uri.encode(id)}"
    const val NEW_PRODUCT    = "new_product"
    const val PRODUCT_DETAIL = "product_detail/{productId}"
    fun productDetail(id: Long) = "product_detail/$id"
    const val RECEIPT = "receipt/{billId}"
    fun receipt(id: Long) = "receipt/$id"
    const val BILL_HISTORY   = "bill_history"
    const val BILL_VIEW      = "bill_view/{billId}"
    fun billView(id: Long) = "bill_view/$id"
    const val AUDIT          = "audit"
    const val STOCK          = "stock"
    const val SETTINGS       = "settings"
    const val EXCEL_SYNC     = "excel_sync"
    const val WORKERS        = "workers"
    const val REPORTS        = "reports"
    const val DAY_CLOSE      = "day_close"
    const val CUSTOMERS      = "customers"
    const val CREDIT         = "credit/{customerId}"
    fun credit(id: Long) = "credit/$id"

    // Remote printing (owner only)
    const val REMOTE_QUEUE   = "remote_print_queue"
    const val SHOP_DEVICE    = "shop_device"

    // GST
    const val GST_BILLS      = "gst_bills"
}

// ─────────────────────────────────────────────────────────────────────────────
// ROOT NAV GRAPH
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AppNavGraph(startDestination: String = Routes.SPLASH) {
    val nav = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val session = remember { dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext,
        com.hardwareshop.pos.di.SessionEntryPoint::class.java).sessionHolder() }

    val websiteVM:WebsiteOrdersViewModel = hiltViewModel()
    val websiteSession by session.sessions.collectAsState()
    val websiteLifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(websiteLifecycle,websiteSession) {
        websiteLifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while(isActive) { websiteVM.foregroundRefresh();delay(60_000) }
        }
    }
    LaunchedEffect(websiteSession) {
        val activity=context as? android.app.Activity
        if(session.isLoggedIn&&activity?.intent?.getBooleanExtra("open_website_orders",false)==true) {
            activity.intent.removeExtra("open_website_orders");nav.navigate(Routes.WEBSITE_ORDERS){launchSingleTop=true}
        }
    }

    NavHost(navController = nav, startDestination = startDestination) {
        composable(Routes.WEBSITE_ORDERS) {
            WebsiteOrdersScreen(onNavigateUp={nav.navigateUp()},onBill={nav.navigate(Routes.websiteReview(it))},
                onHistory={nav.navigate(if(session.isOwner)Routes.billView(it) else Routes.receipt(it))})
        }


        // ── Auth ──────────────────────────────────────────────────────────────
        composable(Routes.SPLASH) {
            SplashScreen(
                onOwnerNotSetup   = { nav.navigate(Routes.OWNER_SETUP) { popUpTo(Routes.SPLASH) { inclusive = true } } },
                onNavigateToLogin = { nav.navigate(Routes.LOGIN)       { popUpTo(Routes.SPLASH) { inclusive = true } } },
                onCloud = { nav.navigate(Routes.CLOUD_LOGIN) { popUpTo(Routes.SPLASH) { inclusive = true } } },
                onOwner = { nav.navigate(Routes.OWNER_HOME) { popUpTo(Routes.SPLASH) { inclusive = true } } },
                onWorker = { nav.navigate(Routes.WORKER_HOME) { popUpTo(Routes.SPLASH) { inclusive = true } } }
            )
        }
        composable(Routes.CLOUD_LOGIN) {
            val vm: CloudLoginViewModel = hiltViewModel()
            CloudLoginScreen(
                onOwner={nav.navigate(Routes.OWNER_HOME){popUpTo(Routes.CLOUD_LOGIN){inclusive=true}}},
                onWorker={nav.navigate(Routes.LOGIN){popUpTo(Routes.CLOUD_LOGIN){inclusive=true}}},
                onOffline={nav.navigate(if(vm.state.value.hasLocalOwner) Routes.LOGIN else Routes.OWNER_SETUP)},viewModel=vm)
        }
        composable(Routes.OWNER_SETUP) {
            OwnerSetupScreen(onSetupComplete = {
                nav.navigate(Routes.OWNER_HOME) { popUpTo(Routes.OWNER_SETUP) { inclusive = true } }
            })
        }
        composable(Routes.LOGIN) {
            LoginScreen(
                onWorkerLogin = { nav.navigate(Routes.WORKER_HOME) { popUpTo(Routes.LOGIN) { inclusive = true } } },
                onOwnerLogin  = { nav.navigate(Routes.OWNER_HOME)  { popUpTo(Routes.LOGIN) { inclusive = true } } }
            )
        }

        // ── Worker home (outside billing graph so back stack is clean) ─────────
        composable(Routes.WORKER_HOME) {
            // Keep Orders cache updates scoped to the menu screen. Collecting
            // them at the root invalidates the entire NavHost during billing.
            val websiteState by websiteVM.state.collectAsState()
            WorkerHomeScreen(
                onOrders={nav.navigate(Routes.WEBSITE_ORDERS)}, newOrderCount=websiteState.rows.count{it.unread},
                onNewBill = { nav.navigate(Routes.BILLING_GRAPH) },
                onBillClick = { nav.navigate(Routes.receipt(it)) },
                onWantList = {nav.navigate(Routes.WANT_LIST)},
                onPrinterSetup = { nav.navigate(Routes.SHOP_DEVICE) },
                onLogout  = { nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }
            )
        }

        // ── Billing nested graph — BillingViewModel scoped to this graph ───────
        // All screens inside share the SAME BillingViewModel instance, preventing
        // the "double ViewModel" bug where cart items added in ProductSelect
        // would be invisible to CartScreen.
        navigation(
            startDestination = Routes.PRODUCT_SELECT,
            route            = Routes.BILLING_GRAPH
        ) {
            composable(Routes.PRODUCT_SELECT) { entry ->
                val graphEntry = remember(entry) { nav.getBackStackEntry(Routes.BILLING_GRAPH) }
                val billingVM: BillingViewModel = hiltViewModel(graphEntry)
                val selectionVM: com.hardwareshop.pos.presentation.worker.product.ProductSelectionViewModel = hiltViewModel(graphEntry)
                ProductSelectionScreen(
                    onNavigateUp     = {
                        billingVM.holdBill { nav.popBackStack(Routes.BILLING_GRAPH, true) }
                    },
                    onGoToCart       = { nav.navigate(Routes.CART) },
                    billingViewModel = billingVM,
                    viewModel = selectionVM
                )
            }
            composable(Routes.WEBSITE_REVIEW,arguments=listOf(navArgument("orderId"){type=NavType.StringType})){entry->
                val graphEntry=remember(entry){nav.getBackStackEntry(Routes.BILLING_GRAPH)}
                val billingVM:BillingViewModel=hiltViewModel(graphEntry)
                WebsiteBillingReview(entry.arguments?.getString("orderId").orEmpty(),billingVM,onNavigateUp={nav.navigateUp()},onImported={nav.navigate(Routes.CART)})
            }
            composable(Routes.QUOTATION_REVIEW, arguments = listOf(navArgument("quotationId") { type = NavType.StringType })) { entry ->
                val graphEntry = remember(entry) { nav.getBackStackEntry(Routes.BILLING_GRAPH) }
                val billingVM: BillingViewModel = hiltViewModel(graphEntry)
                QuotationImportScreen(entry.arguments?.getString("quotationId").orEmpty(), billingVM,
                    onNavigateUp = { nav.navigateUp() }, onImported = {
                        nav.navigate(Routes.PRODUCT_SELECT) { popUpTo(Routes.QUOTATION_REVIEW) { inclusive = true }; launchSingleTop = true }
                        nav.navigate(Routes.CART)
                    })
            }
            composable(Routes.CART) { entry ->
                val graphEntry = remember(entry) { nav.getBackStackEntry(Routes.BILLING_GRAPH) }
                val billingVM: BillingViewModel = hiltViewModel(graphEntry)
                CartScreen(
                    onNavigateUp   = { nav.navigateUp() },
                    onAddMoreItems = { nav.navigateUp() },
                    onProceed      = { nav.navigate(Routes.PAYMENT) },
                    onBillSaved    = { billId ->
                        nav.navigate(Routes.billSuccess(billId, billingVM.state.value.printAfterSave)) {
                            popUpTo(Routes.BILLING_GRAPH) { inclusive = false }
                        }
                    },
                    viewModel      = billingVM
                )
            }
            composable(Routes.PAYMENT) { entry ->
                val graphEntry = remember(entry) { nav.getBackStackEntry(Routes.BILLING_GRAPH) }
                val billingVM: BillingViewModel = hiltViewModel(graphEntry)
                PaymentScreen(
                    onNavigateUp = { nav.navigateUp() },
                    onBillSaved  = { billId ->
                        nav.navigate(Routes.billSuccess(billId, billingVM.state.value.printAfterSave)) {
                            popUpTo(Routes.BILLING_GRAPH) { inclusive = false }
                        }
                    },
                    viewModel = billingVM
                )
            }
            composable(
                route     = Routes.BILL_SUCCESS,
                arguments = listOf(navArgument("billId") { type = NavType.LongType },
                    navArgument("printRequested") { type = NavType.BoolType; defaultValue = false })
            ) { entry ->
                val graphEntry = remember(entry) { nav.getBackStackEntry(Routes.BILLING_GRAPH) }
                val billingVM: BillingViewModel = hiltViewModel(graphEntry)
                BillSuccessScreen(
                    billId    = entry.arguments?.getLong("billId") ?: 0L,
                    printRequested = entry.arguments?.getBoolean("printRequested") ?: false,
                    onNewBill = {
                        billingVM.clearCart()
                        nav.navigate(Routes.PRODUCT_SELECT) {
                            popUpTo(Routes.PRODUCT_SELECT) { inclusive = true }
                        }
                    },
                    onHome = {
                        billingVM.clearCart()
                        nav.navigate(if (session.isOwner) Routes.OWNER_HOME else Routes.WORKER_HOME) {
                            launchSingleTop = true
                            popUpTo(Routes.BILLING_GRAPH) { inclusive = true }
                        }
                    }
                )
            }
        }

        composable(Routes.RECEIPT, arguments = listOf(navArgument("billId") { type = NavType.LongType })) { entry ->
            BillSuccessScreen(billId = entry.arguments?.getLong("billId") ?: 0L,
                onNewBill = { nav.navigate(Routes.BILLING_GRAPH) }, onHome = { nav.navigateUp() })
        }

        // ── Owner ─────────────────────────────────────────────────────────────
        composable(Routes.OWNER_HOME) {
            val websiteState by websiteVM.state.collectAsState()
            OwnerHomeScreen(
                onOrders={nav.navigate(Routes.WEBSITE_ORDERS)}, newOrderCount=websiteState.rows.count{it.unread},
                onQuotations = { nav.navigate(Routes.QUOTATIONS) },
                onWarranty = { nav.navigate(Routes.WARRANTY) },
                onCutPlanner = { nav.navigate(Routes.CUT_PLANNER) },
                onWantList = {nav.navigate(Routes.WANT_LIST)},
                onPipeOffcuts = { nav.navigate(Routes.PIPE_OFFCUTS) },
                onPurchases = { nav.navigate(Routes.PURCHASES) }, onReorder = { nav.navigate(Routes.REORDER) },
                onReturns = { nav.navigate(Routes.returns()) },
                onDashboard   = { nav.navigate(Routes.DASHBOARD) },
                onNewBill = { nav.navigate(Routes.BILLING_GRAPH) },
                onPendingItems = { nav.navigate(Routes.PENDING_PRODUCTS) },
                onManualProducts = { nav.navigate(Routes.MANUAL_PRODUCTS) },
                onProducts    = { nav.navigate(Routes.PRODUCTS) },
                onInventoryManager = { nav.navigate(Routes.INVENTORY_MANAGER) },
                onCustomers   = { nav.navigate(Routes.CUSTOMERS) },
                onReports     = { nav.navigate(Routes.REPORTS) },
                onAudit       = { nav.navigate(Routes.AUDIT) },
                onWorkers     = { nav.navigate(Routes.WORKERS) },
                onSettings    = { nav.navigate(Routes.SETTINGS) },
                onStock       = { nav.navigate(Routes.STOCK) },
                onDayClose    = { nav.navigate(Routes.DAY_CLOSE) },
                onBillHistory = { nav.navigate(Routes.BILL_HISTORY) },
                onGstBills    = { nav.navigate(Routes.GST_BILLS) },
                onRemotePrint = { nav.navigate(Routes.REMOTE_QUEUE) },
                onExcelSync   = { nav.navigate(Routes.EXCEL_SYNC) },
                onLogout      = { nav.navigate(Routes.CLOUD_LOGIN) { popUpTo(0) { inclusive = true } } }
            )
        }
        composable(Routes.CUT_PLANNER) { PipeToolsScreen(onNavigateUp = { nav.navigateUp() }, planner = true) }
        composable(Routes.WANT_LIST) {WantListScreen(onNavigateUp={nav.navigateUp()})}
        composable(Routes.PIPE_OFFCUTS) { PipeToolsScreen(onNavigateUp = { nav.navigateUp() }, planner = false) }
        composable(Routes.PURCHASES) { PurchasesScreen(onNavigateUp = { nav.navigateUp() }) }
        composable(Routes.REORDER) { PurchasesScreen(onNavigateUp = { nav.navigateUp() }, initialTab = 2) }
        composable(Routes.RETURNS, arguments = listOf(navArgument("billId") { type = NavType.LongType })) { entry ->
            ReturnsScreen(onNavigateUp = { nav.navigateUp() }, initialBillId = entry.arguments?.getLong("billId") ?: 0,
                onReceipt = { nav.navigate(Routes.receipt(it)) })
        }
        composable(Routes.DASHBOARD)    { DashboardScreen(onNavigateUp  = { nav.navigateUp() }) }
        composable(Routes.QUOTATIONS) {
            QuotationScreen(onNavigateUp = { nav.navigateUp() },
                onNewQuotation = { nav.navigate(Routes.BILLING_GRAPH) },
                onConvert = { record, _ -> nav.navigate(Routes.quotationReview(record.id)) })
        }
        composable(Routes.WARRANTY) { WarrantyScreen(onNavigateUp = { nav.navigateUp() }) }
        composable(Routes.REPORTS)      { ReportsScreen(onNavigateUp    = { nav.navigateUp() }) }
        composable(Routes.AUDIT)        { AuditScreen(onNavigateUp      = { nav.navigateUp() }) }
        composable(Routes.STOCK)        { StockScreen(onNavigateUp      = { nav.navigateUp() }) }
        composable(Routes.SETTINGS)     { SettingsScreen(onNavigateUp = { nav.navigateUp() }, onRemoteSetup = { nav.navigate(Routes.SHOP_DEVICE) }) }
        composable(Routes.EXCEL_SYNC)   { ExcelSyncScreen(onNavigateUp = { nav.navigateUp() }) }
        composable(Routes.WORKERS)      { WorkersScreen(onNavigateUp    = { nav.navigateUp() }) }
        composable(Routes.DAY_CLOSE)    { DayCloseScreen(onNavigateUp  = { nav.navigateUp() }) }
        composable(Routes.CUSTOMERS)    { CustomersScreen(onNavigateUp = { nav.navigateUp() }, onCredit = { nav.navigate(Routes.credit(it)) }) }
        composable(Routes.BILL_HISTORY) {
            BillHistoryScreen(
                onNavigateUp = { nav.navigateUp() },
                onBillClick  = { id -> nav.navigate(Routes.billView(id)) }
            )
        }
        composable(
            route     = Routes.BILL_VIEW,
            arguments = listOf(navArgument("billId") { type = NavType.LongType })
        ) {
            BillViewScreen(
                billId = it.arguments?.getLong("billId") ?: 0L,
                onReceipt = { id -> nav.navigate(Routes.receipt(id)) },
                onReturn = { id -> nav.navigate(Routes.returns(id)) },
                onNavigateUp = { nav.navigateUp() }
            )
        }
        composable(Routes.INVENTORY_MANAGER) {
            InventoryManagerScreen(onNavigateUp={nav.navigateUp()},onEdit={nav.navigate(Routes.productDetail(it))},onStock={nav.navigate(Routes.STOCK)})
        }
        composable(Routes.PRODUCTS) {
            ProductManagementScreen(
                onNavigateUp   = { nav.navigateUp() },
                onNewProduct   = { nav.navigate(Routes.NEW_PRODUCT) },
                onProductClick = { id -> nav.navigate(Routes.productDetail(id)) }
            )
        }
        composable(Routes.PENDING_PRODUCTS) {
            PendingProductScreen(onNavigateUp = { nav.navigateUp() }, onNewProduct = { nav.navigate(Routes.NEW_PRODUCT) },
                onDraft = { nav.navigate(Routes.productDraft(it)) }, onProduct = { nav.navigate(Routes.productDetail(it)) })
        }
        composable(Routes.MANUAL_PRODUCTS) {
            ManualProductScreen(onNavigateUp = { nav.navigateUp() },
                onBill = { nav.navigate(Routes.billView(it)) }, onProducts = { nav.navigate(Routes.PRODUCTS) })
        }
        composable(Routes.PRODUCT_DRAFT, arguments = listOf(navArgument("draftId") { type = NavType.StringType })) { entry ->
            ProductDetailScreen(productId = null, draftId = entry.arguments?.getString("draftId"),
                onNavigateUp = { nav.navigateUp() }, onSaved = { nav.navigateUp() })
        }
        composable(Routes.NEW_PRODUCT) {
            ProductDetailScreen(productId = null, onNavigateUp = { nav.navigateUp() }, onSaved = { nav.navigateUp() })
        }
        composable(
            route     = Routes.PRODUCT_DETAIL,
            arguments = listOf(navArgument("productId") { type = NavType.LongType })
        ) {
            ProductDetailScreen(
                productId    = it.arguments?.getLong("productId"),
                onNavigateUp = { nav.navigateUp() },
                onSaved      = { nav.navigateUp() }
            )
        }
        composable(
            route     = Routes.CREDIT,
            arguments = listOf(navArgument("customerId") { type = NavType.LongType })
        ) {
            CreditScreen(
                customerId   = it.arguments?.getLong("customerId") ?: 0L,
                onNavigateUp = { nav.navigateUp() }
            )
        }

        // ── Remote printing (owner only — permission enforced at repository) ──
        composable(Routes.REMOTE_QUEUE) {
            RemotePrintQueueScreen(onNavigateUp = { nav.navigateUp() })
        }
        composable(Routes.SHOP_DEVICE) {
            ShopDeviceScreen(onNavigateUp = { nav.navigateUp() })
        }

        // ── GST Bills (owner only) ─────────────────────────────────────────────
        composable(Routes.GST_BILLS) {
            GstBillsScreen(
                onNavigateUp = { nav.navigateUp() },
                onBillClick  = { id -> nav.navigate(Routes.billView(id)) }
            )
        }
    }
}

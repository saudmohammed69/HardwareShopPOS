// Compose state delegate writes are observed during recomposition; retain the UI state updates.
@file:Suppress("AssignedValueIsNeverRead")

package com.hardwareshop.pos.presentation.worker.billing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import com.hardwareshop.pos.util.ReceiptLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.hardwareshop.pos.di.SessionEntryPoint
import com.hardwareshop.pos.domain.model.PaymentMethod
import com.hardwareshop.pos.presentation.common.*
import com.hardwareshop.pos.presentation.owner.remote.RemotePrintButton
import com.hardwareshop.pos.presentation.worker.product.ProductSelectionViewModel
import com.hardwareshop.pos.util.CalculationEngine
import com.hardwareshop.pos.util.ProductSizeGroups
import com.hardwareshop.pos.util.ProductSort

// ─────────────────────────────────────────────────────────────────────────────
// WORKER HOME SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun WorkerHomeScreen(
    onOrders:()->Unit={},newOrderCount:Int=0,
    onNewBill: () -> Unit,
    onLogout: () -> Unit,
    onBillClick: (Long) -> Unit = {},
    onWantList: () -> Unit = {},
    onPrinterSetup: () -> Unit = {},
    viewModel: WorkerHomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            AppTopBar(
                title   = "Hi, ${state.workerName}",
                actions = {
                    IconButton(onClick = onPrinterSetup) {
                        Icon(Icons.Default.Print, "Shop printer status", tint = AppColors.OnPrimary)
                    }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── NEW BILL — the primary action, dominant visual weight ──
            item {
                Button(
                    onClick   = onNewBill,
                    modifier  = Modifier.fillMaxWidth().height(88.dp),
                    colors    = ButtonDefaults.buttonColors(containerColor = AppColors.Secondary),
                    shape     = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                ) {
                    Icon(Icons.Default.AddShoppingCart, null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("NEW / RESUME BILL", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }

            item {OutlinedButton(onOrders,modifier=Modifier.fillMaxWidth()){Text("ORDERS ($newOrderCount NEW)")}}

            item {OutlinedButton(onWantList,modifier=Modifier.fillMaxWidth()){Text("WANT LIST / KHARIDI LIST")}}

            // ── Low-stock alert ──────────────────────────────────────────────
            if (state.lowStockCount > 0) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AppColors.Warning.copy(alpha = 0.12f)),
                        shape  = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Warning, null, tint = AppColors.Warning)
                            Text(
                                "${state.lowStockCount} items low in stock — inform owner",
                                color = AppColors.Warning,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // ── Recent bills ─────────────────────────────────────────────────
            item { SectionHeader("Recent Bills") }

            if (state.recentBills.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), Alignment.Center) {
                        Text("No bills today yet", color = AppColors.TextSecondary)
                    }
                }
            }

            items(state.recentBills, key = { it.id }) { bill ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onBillClick(bill.id) },
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Row(
                        Modifier.padding(14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("#${bill.billNumber}", fontWeight = FontWeight.Bold, color = AppColors.Primary)
                            if (bill.customerName.isNotBlank())
                                Text(bill.customerName, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(CalculationEngine.formatRupees(bill.totalPaisa), fontWeight = FontWeight.Bold)
                            Text(
                                bill.paymentMethod,
                                style = MaterialTheme.typography.labelSmall,
                                color = when (bill.paymentMethod) {
                                    "CREDIT" -> AppColors.Credit
                                    "CASH"   -> AppColors.Success
                                    else     -> AppColors.Primary
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PRODUCT SELECTION SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProductSelectionStepHint(step: String, title: String, detail: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.Primary.copy(alpha = 0.08f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = AppColors.Primary)
            Column {
                Text(step, style = MaterialTheme.typography.labelSmall, color = AppColors.Primary)
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ProductSelectionScreen(
    onNavigateUp:    () -> Unit,
    onGoToCart:      () -> Unit,
    billingViewModel: BillingViewModel           = hiltViewModel(),
    viewModel:        ProductSelectionViewModel = hiltViewModel()
) {
    val state        by viewModel.state.collectAsState()
    val billingState by billingViewModel.state.collectAsState()
    val recordedOffcuts by viewModel.recordedOffcuts.collectAsState()
    LaunchedEffect(state.isLoading,billingState.isLoading) {
        com.hardwareshop.pos.util.BillingLoadDiagnostics.event(
            if(!state.isLoading && !billingState.isLoading) "billing-compose-ready" else "billing-compose-loading")
    }
    val focus = LocalFocusManager.current
    val suggestionProducts=remember(billingState.cart) { billingState.cart.map { it.product } }
    LaunchedEffect(suggestionProducts) {
        viewModel.updateSuggestionCart(suggestionProducts)
    }
    var entryPrice by remember(state.pendingProduct?.id) { mutableStateOf(state.pendingProduct?.let {
        com.hardwareshop.pos.domain.model.ProductForm.decimal(billingState.cart.firstOrNull{line->line.product.id==it.id}?.unitPricePaisa ?: if(it.isPipe)it.pipeLengthPricePaisa else it.sellingPricePaisa)
    }.orEmpty()) }
    var entryFoot by remember(state.pendingProduct?.id) { mutableStateOf(state.pendingProduct?.let {
        com.hardwareshop.pos.domain.model.ProductForm.decimal(billingState.cart.firstOrNull{line->line.product.id==it.id}?.pipeFootPricePaisa ?: it.pipeFootPricePaisa)
    }.orEmpty()) }
    val addToken=remember(state.pendingProduct?.id){java.util.UUID.randomUUID().toString()}
    fun addEntry(product:com.hardwareshop.pos.domain.model.Product,q:Double=0.0,l:Long=0,f:Double=0.0):Boolean {
        val price=CalculationEngine.parseRupeesToPaisa(entryPrice) ?: return false
        val line=try {
            if(product.isPipe) com.hardwareshop.pos.util.PipeBilling.item(product,l,f,price,CalculationEngine.parseRupeesToPaisa(entryFoot) ?: return false)
            else com.hardwareshop.pos.domain.model.CartItem(product,q,price,CalculationEngine.itemTotalPaisa(q,price))
        }catch(_:Exception){return false}
        return billingViewModel.addSelection(addToken,listOf(line))
    }
    val searchFocus = remember { FocusRequester() }
    var requestSearch by remember { mutableStateOf(false) }
    LaunchedEffect(requestSearch,state.pendingProduct) {
        if(requestSearch && state.pendingProduct==null && !state.choosingSize) {
            withFrameNanos { }
            searchFocus.requestFocus()
            requestSearch=false
        }
    }
    val pickerScroll = rememberLazyGridState()
    val matchingPipes = remember(state.availablePipes,state.pendingProduct) { state.matchingPipes }
    val matchingPipeIds = remember(matchingPipes) { matchingPipes.map { it.id }.toSet() }
    val shownSuggestions=if(state.pendingProduct==null && !state.choosingSize && state.suggestionSearchOpened) state.suggestions else emptyList()
    val suggestedIds=shownSuggestions.map { it.product.id }.toSet()
    LaunchedEffect(state.suggestionSearchOpened, state.searchQuery, suggestedIds) {
        if (state.suggestionSearchOpened && state.pendingProduct == null) pickerScroll.scrollToItem(0)
    }
    val favouriteGroups = remember(state.favourites, state.availableProducts, matchingPipeIds, suggestedIds, state.sortOrder) {
        ProductSort.groups(state.favourites.filterNot { it.id in matchingPipeIds || it.id in suggestedIds }, state.availableProducts, state.sortOrder,state.catalogueIndex)
    }
    val searchGroups = remember(state.searchResults, state.availableProducts, matchingPipeIds, suggestedIds, state.sortOrder) {
        ProductSort.groups(state.searchResults.filterNot { it.id in matchingPipeIds || it.id in suggestedIds }, state.availableProducts, state.sortOrder,state.catalogueIndex)
    }
    val productGroups = remember(state.products, state.availableProducts, matchingPipeIds, suggestedIds, state.sortOrder) {
        ProductSort.groups(state.products.filterNot { it.id in matchingPipeIds || it.id in suggestedIds }, state.availableProducts, state.sortOrder,state.catalogueIndex)
    }
    fun chooseProduct(product: com.hardwareshop.pos.domain.model.Product) {
        focus.clearFocus(); viewModel.selectProduct(product)
    }
    fun addOneOrChooseSize(product: com.hardwareshop.pos.domain.model.Product) {
        if (state.catalogueIndex?.hasChoices(product) ?: ProductSizeGroups.hasChoices(state.availableProducts, product)) chooseProduct(product)
        else billingViewModel.addToCart(product, 1.0)
    }
    fun goBack() {
        focus.clearFocus()
        when {
            state.pendingProduct != null || state.choosingSize -> viewModel.goBackInSelection()
            state.selectedType != null -> viewModel.goBackToTypes()
            state.selectedCategory != null -> viewModel.goBackToCategories()
            else -> onNavigateUp()
        }
    }
    val selectedPipeSize = state.pendingProduct?.takeIf { it.isPipe }?.size
        ?.let(com.hardwareshop.pos.util.PipeSizeChoices::key)
    LaunchedEffect(selectedPipeSize) {
        if (selectedPipeSize != null) pickerScroll.scrollToItem(0)
    }

    androidx.activity.compose.BackHandler { goBack() }
    Box(Modifier.fillMaxSize().testTag(if(!state.isLoading && !billingState.isLoading)
        "billing-catalog-ready" else "billing-catalog-loading")) {
    Scaffold(
        topBar = {
            Column {
                AppTopBar(
                    title = when {
                        state.selectedType     != null -> "📦 ${state.selectedType!!.name}"
                        state.selectedCategory != null -> "📂 ${state.selectedCategory!!.name}"
                        else                          -> "Select Product"
                    },
                    onNavigateUp = { goBack() },
                    actions = {
                        ProductSortControl(state.sortOrder, viewModel::setSortOrder, compact = true)
                        if (!state.isSearchActive) {
                            IconButton(onClick = {
                                focus.clearFocus();viewModel.dismissQuantityDialog();viewModel.openSearch();requestSearch=true
                            }) {
                                Icon(Icons.Default.Search, "Search", tint = AppColors.OnPrimary)
                            }
                        }
                        IconButton(onClick = { focus.clearFocus(); onGoToCart() },
                            modifier = Modifier.testTag("billing-header-cart")) {
                            BadgedBox(badge = {
                                if (billingState.cart.isNotEmpty()) Badge { Text(billingState.cart.size.toString()) }
                            }) {
                                Icon(Icons.Default.ShoppingCart,
                                    "Open cart, ${billingState.cart.size} items, ${CalculationEngine.formatRupees(billingState.calculation?.subtotalPaisa ?: 0L)}",
                                    tint = AppColors.OnPrimary)
                            }
                        }
                    }
                )

                if (!(state.pendingProduct?.isPipe == true && WindowInsets.isImeVisible)) {
                Column {
                // Keep search results usable while typing; restore draft controls when the keyboard closes.
                if (!WindowInsets.isImeVisible) {
                if (billingState.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                billingState.draftMessage?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                billingState.error?.let { ErrorMessage(it) }
                ParkedBillControls(billingViewModel)
                if (billingState.recoveryFailed) TextButton(onGoToCart) { Text("OPEN CART TO REVIEW / CLEAR") }
                state.error?.let { ErrorMessage(it) }
                }
                BillingCategoryTabs(state.categories,state.selectedCategory?.id) { category->
                    viewModel.clearSearch();viewModel.selectCategory(category)
                }
                // Search bar
                AnimatedVisibility(visible = true) {
                    OutlinedTextField(
                        value         = state.searchQuery,
                        onValueChange = viewModel::onSearchQueryChange,
                        placeholder   = { Text("Search products, size, aliases…") },
                        leadingIcon   = { Icon(Icons.Default.Search, null) },
                        trailingIcon  = {
                            IconButton(onClick = viewModel::clearSearch) {
                                Icon(Icons.Default.Close, null)
                            }
                        },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth().focusRequester(searchFocus)
                            .onFocusChanged { if(it.isFocused) viewModel.openSearch() }.padding(horizontal = 8.dp, vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.surface),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })
                    )
                }
                }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val pipe=state.pendingProduct?.takeIf { it.isPipe }
            val neededFeet=billingState.cart.firstOrNull { it.product.id==pipe?.id }?.pipeFeet ?: 0.0
            val bestOffcut=recordedOffcuts.filter { it.available && it.productId==pipe?.id && it.feet/100.0>=neededFeet && neededFeet>0 }
                .minByOrNull { it.feet }
            BillingPickerLayout(state.pendingProduct, state.pendingQuantity, viewModel::onQuantityChange,
                    onAdd = {
                        state.pendingProduct?.let { product ->
                            val quantity=state.pendingQuantity.toDoubleOrNull()
                            if(quantity!=null && addEntry(product,quantity))viewModel.dismissQuantityDialog()
                        }
                    }, onCancel = viewModel::goBackInSelection,
                    onCart = { viewModel.dismissQuantityDialog(); focus.clearFocus(); onGoToCart() },
                    pipeItem = billingState.cart.firstOrNull { it.product.id == state.pendingProduct?.id },
                    pipeEnabled = !billingState.isLoading,
                    onPipe = { lengths, feet -> state.pendingProduct?.let { billingViewModel.updatePipe(it, lengths, feet) } },
                    sizeChoiceProduct = state.sizeChoiceProduct, sizeChoices = state.sizeChoices,
                    onSize = viewModel::selectSize,
                    entryRates = (CalculationEngine.parseRupeesToPaisa(entryPrice) ?: 0L) to (CalculationEngine.parseRupeesToPaisa(entryFoot) ?: 0L),
                    entryCart = billingState.cart,
                    onAddItems = { token,items -> billingViewModel.addSelection(token,items).also { if(it)viewModel.dismissQuantityDialog() } },
                    priceEditor = state.pendingProduct?.let { product ->
                        { Column {
                            if(billingViewModel.canEditPrice)EntryPriceEditor(product,entryPrice,entryFoot,{entryPrice=it},{entryFoot=it})
                            else if(!product.isPipe)Text("${CalculationEngine.formatRupees(product.sellingPricePaisa)} / ${product.unit}")
                            TextButton({billingViewModel.addToWantList(product)},enabled=!billingState.isLoading){Text("ADD TO WANT LIST")}
                            billingState.draftMessage?.takeIf {it.contains("Want List")}?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                            if(product.isPipe) {
                                recordedOffcuts.filter {it.available && it.productId==product.id}.sortedBy {it.feet}.take(10).forEach {piece->
                                    val price=com.hardwareshop.pos.util.PipeBilling.total(0,piece.feet/100.0,product.pipeLengthPricePaisa,product.pipeFootPricePaisa)
                                    TextButton({if(billingViewModel.useOffcut(product,piece))viewModel.dismissQuantityDialog()},enabled=!billingState.isLoading) {
                                        Text("USE OFFCUT: ${com.hardwareshop.pos.util.PipeCutPlanner.format(piece.feet)} ft · ${piece.location} · ${CalculationEngine.formatRupees(price)}")
                                    }
                                }
                            }
                        } }
                    },
                    onAddPipe = { product,l,f ->
                        val lengths=if(l.isBlank())0L else l.toLongOrNull()
                        val feet=if(f.isBlank())0.0 else f.toDoubleOrNull()
                        if(lengths!=null && feet!=null && addEntry(product,l=lengths,f=feet))viewModel.dismissQuantityDialog()
                    },
                    pipeHint = bestOffcut?.let { piece ->
                        "Before cutting a new pipe: ${com.hardwareshop.pos.util.PipeCutPlanner.format(piece.feet)} ft offcut available · ${piece.location.ifBlank { piece.id.take(8) }}. Check Pipe Offcuts before using."
                    }) { pickerModifier ->
                LazyVerticalGrid(
                    state = pickerScroll,
                    columns = GridCells.Fixed(2),
                    modifier            = pickerModifier.testTag("billing-picker"),
                    contentPadding      = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (shownSuggestions.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("Complete the job — suggestions for your cart. Check suitability before adding.",
                                style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("job-suggestions"))
                        }
                        gridItems(shownSuggestions, key = { "suggestion_${it.product.id}" }) { suggestion ->
                            Column {
                                Text(suggestion.reason, style = MaterialTheme.typography.labelSmall)
                                BillingProductCard(suggestion.product, suggestion.product.id in state.favouriteIds,
                                    { viewModel.toggleFavourite(suggestion.product) },
                                    { chooseProduct(suggestion.product) },
                                    { addOneOrChooseSize(suggestion.product) })
                            }
                        }
                    }
                    if (matchingPipes.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("${state.pendingProduct!!.size} pipes — all categories",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.testTag("matching-pipe-heading"))
                        }
                        gridItems(matchingPipes, key = { "matching_pipe_${it.id}" }) { product ->
                            BillingProductCard(product, product.id in state.favouriteIds, { viewModel.toggleFavourite(product) },
                                { chooseProduct(product) }, {})
                        }
                    }
                    if (!state.isSearchActive && state.favourites.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Text("Favourites — tap + to add one, or the item to enter quantity.", style = MaterialTheme.typography.bodySmall) }
                        gridItems(favouriteGroups, key = { "fav_${it.key}" }) { group ->
                            BillingChoiceCard(group, state.favouriteIds, viewModel::toggleFavourite, ::chooseProduct, ::addOneOrChooseSize)
                        }
                    }
                    if (state.isSearchActive) {
                        // ── Search mode ──────────────────────────────────────────────
                        if (state.isLoading) {
                            item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
                        } else if (state.searchResults.isEmpty() && state.searchQuery.isNotBlank() && !state.searchPending) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.SearchOff, null, tint = AppColors.TextSecondary, modifier = Modifier.size(48.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text("No products found for \"${state.searchQuery.trim()}\"", color = AppColors.TextSecondary)
                                        Text("Try a shorter item name or choose a category. You can add local names in the product's Search Aliases.", color = AppColors.TextSecondary)
                                    }
                                }
                            }
                        } else {
                            if (state.searchResults.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                                Text("Best matches first; frequently sold items lead matching groups.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                            }
                            gridItems(searchGroups, key = { "search_${it.key}" }) { group ->
                                BillingChoiceCard(group, state.favouriteIds, viewModel::toggleFavourite, ::chooseProduct, ::addOneOrChooseSize)
                            }
                        }
                    } else {
                        // ── Hierarchy mode ────────────────────────────────────────────
                        when {
                            state.selectedCategory == null -> {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    ProductSelectionStepHint(
                                        step = "STEP 1 OF 3",
                                        title = "Choose a category",
                                        detail = "Start with PVC, Electrical, Tools, Paints or another group."
                                    )
                                }
                                gridItems(state.categories, key = { "cat_${it.id}" }) { cat ->
                                    BillingHierarchyCard(label = cat.name, onClick = { viewModel.selectCategory(cat) }, icon = categoryIcon(cat.name),
                                        pinned = cat.id in state.pinnedCategoryIds, onPin = { viewModel.toggleCategoryPin(cat) })
                                }
                            }
                            state.selectedType == null -> {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    ProductSelectionStepHint(
                                        step = "STEP 2 OF 3",
                                        title = "Choose the item type",
                                        detail = "For example: Elbow, Wire, MCB or Paint."
                                    )
                                }
                                gridItems(state.types, key = { "type_${it.id}" }) { type ->
                                    BillingHierarchyCard(label = type.name, onClick = { viewModel.selectType(type) }, icon = Icons.AutoMirrored.Filled.ViewList)
                                }
                            }
                            else -> {
                        if (state.pendingProduct == null) item(span = { GridItemSpan(maxLineSpan) }) {
                            ProductSelectionStepHint(
                                step = "STEP 3 OF 3",
                                title = "Choose the item",
                                detail = "Choose an item, then its size and quantity beside the list."
                            )
                        }
                        if (state.products.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                                    Text("No products in this category yet", color = AppColors.TextSecondary)
                                }
                            }
                        } else {
                            gridItems(productGroups, key = { "prod_${it.key}" }) { group ->
                                BillingChoiceCard(group, state.favouriteIds, viewModel::toggleFavourite, ::chooseProduct, ::addOneOrChooseSize)
                            }
                        }
                    }
                }
            }
        }
        }
        }
    }

    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CART SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CartScreen(
    onNavigateUp:   () -> Unit,
    onAddMoreItems: () -> Unit,
    onProceed:      () -> Unit,
    onBillSaved:    (Long) -> Unit,
    viewModel: BillingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val variants by viewModel.variantProducts.collectAsState()
    var weightItem by remember { mutableStateOf<com.hardwareshop.pos.domain.model.CartItem?>(null) }
    var swipeItem by remember { mutableStateOf<com.hardwareshop.pos.domain.model.CartItem?>(null) }
    var editingVariant by remember { mutableStateOf<com.hardwareshop.pos.domain.model.CartItem?>(null) }
    androidx.activity.compose.BackHandler(enabled=editingVariant!=null) { editingVariant=null }
    val snackbar=remember { SnackbarHostState() }
    LaunchedEffect(state.removedLines.firstOrNull()?.token,state.savedBillId) {
        val removed=state.removedLines.firstOrNull()
        if(removed!=null && state.savedBillId==null) {
            val result=snackbar.showSnackbar("Removed ${removed.item.product.displayLabel}","UNDO",duration=SnackbarDuration.Short)
            if(result==SnackbarResult.ActionPerformed)viewModel.undoRemoved(removed.token)
            viewModel.expireRemoved(removed.token)
        }
    }
    val calc   = state.calculation

    var confirmClear by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showPaymentChoice by rememberSaveable { mutableStateOf(false) }
    val cartFocus = LocalFocusManager.current
    var manualName by remember { mutableStateOf("") }
    var manualPrice by remember { mutableStateOf("") }
    var removeItem by remember { mutableStateOf<com.hardwareshop.pos.domain.model.CartItem?>(null) }
    var quantityItem by remember { mutableStateOf<com.hardwareshop.pos.domain.model.CartItem?>(null) }
    var quantityText by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.savedBillId) { state.savedBillId?.let(onBillSaved) }
    weightItem?.let { item->WeightCalculatorDialog(item,viewModel){weightItem=null} }
    swipeItem?.let { item->CartSwipeEditor(item,state,viewModel,onQuantity={
        if(item.product.id>0)editingVariant=item else {quantityText=CalculationEngine.formatQuantity(item.quantity);quantityItem=item}
        swipeItem=null
    },onDismiss={swipeItem=null}) }
    if (showPaymentChoice) AlertDialog(
        onDismissRequest = { showPaymentChoice = false },
        title = { Text("Payment method") },
        text = { Text("Choose the payment method, then review the bill before saving.") },
        confirmButton = { Column {
            listOf(PaymentMethod.CASH, PaymentMethod.UPI, PaymentMethod.SPLIT).forEach { method ->
                TextButton(onClick = {
                    cartFocus.clearFocus()
                    viewModel.prepareCartPreview(method)
                    showPaymentChoice = false
                    onProceed()
                }, enabled = !state.isLoading) { Text(method.displayName.uppercase()) }
            }
        } },
        dismissButton = { TextButton({ showPaymentChoice = false }) { Text("CANCEL") } })
    quantityItem?.let { item ->
        QuantityInputDialog(item.product.copy(sellingPricePaisa = item.unitPricePaisa), quantityText, { value ->
            if (value.matches(Regex("[0-9]*(?:\\.[0-9]{0,2})?"))) quantityText = value
        }, onConfirm = {
            val quantity = quantityText.toDoubleOrNull()
            if (!state.isLoading && quantity != null && quantity.isFinite() && quantity > 0) {
                viewModel.updateQuantity(item.product.id, quantity); quantityItem = null
            }
        }, onDismiss = { quantityItem = null }, confirmLabel = "APPLY QUANTITY")
    }
    removeItem?.let { item ->
        AlertDialog(onDismissRequest = { removeItem = null }, title = { Text("Remove this item from the cart?") },
            text = { Text(item.product.name) },
            confirmButton = { TextButton({ viewModel.removeFromCart(item.product.id); removeItem = null }, enabled = !state.isLoading) { Text("REMOVE") } },
            dismissButton = { TextButton({ removeItem = null }) { Text("KEEP ITEM") } })
    }
    if (showManual) AlertDialog(
        onDismissRequest = { showManual = false },
        title = { Text("Add Item Manually") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(manualName, { manualName = it.take(120) }, label = { Text("Item name *") }, singleLine = true)
            OutlinedTextField(manualPrice, { manualPrice = it }, label = { Text("Price per piece ₹ *") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text("Added to this bill only. Quantity starts at 1. GST: 18% when bill GST is on. Owner can review it after the bill is saved.", style = MaterialTheme.typography.bodySmall)
            state.error?.let { ErrorMessage(it) }
        } },
        confirmButton = { TextButton(onClick = {
            viewModel.addManualItem(manualName, manualPrice) {
                showManual = false; manualName = ""; manualPrice = ""
            }
        }, enabled = !state.isLoading && manualName.isNotBlank() && (CalculationEngine.parseRupeesToPaisa(manualPrice) ?: 0) > 0) { Text("ADD TO CART") } },
        dismissButton = { TextButton({ showManual = false }) { Text("CANCEL") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Discard unfinished bill?") },
        text = { Text("This clears the cart and its saved draft. Saved bills are kept.") },
        confirmButton = { TextButton({ confirmClear = false; viewModel.clearCart() }) { Text("DISCARD") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Keep bill") } })
    Scaffold(
        snackbarHost={SnackbarHost(snackbar)},
        topBar = { Column {
            AppTopBar("Cart — ${state.cart.size} items", onNavigateUp = onNavigateUp, actions = {
                if (viewModel.canEditPrice && state.sourceWebsiteOrderId==null) com.hardwareshop.pos.presentation.owner.misc.CartQuotationAction(state)
            })
            if(!WindowInsets.isImeVisible) ParkedBillControls(viewModel)
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("Unfinished bill saves automatically", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                TextButton({ confirmClear = true }, enabled = !state.isLoading) { Text("CLEAR") }
            }
            state.error?.let { ErrorMessage(it) }
        } },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
                if(editingVariant!=null) {
                    TotalRow(amountPaisa=calc?.totalPaisa ?: 0)
                } else {
                OutlinedButton(onClick = { showManual = true }, enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Icon(Icons.Default.Add, null)
                    Text("ADD ITEM MANUALLY")
                }
                if (calc != null) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), Arrangement.spacedBy(4.dp)) {
                        AmountRow("Subtotal", calc.subtotalPaisa)
                        if (calc.discountPaisa > 0) {
                            AmountRow("Discount", calc.discountPaisa,
                                amountColor = AppColors.Success,
                                amountStyle = MaterialTheme.typography.bodyMedium)
                        }
                        if (state.gstEnabled && calc.gstAmountPaisa > 0) {
                            val (cgst, sgst) = CalculationEngine.splitCgstSgst(calc.gstAmountPaisa)
                            AmountRow("CGST", cgst)
                            AmountRow("SGST", sgst)
                        }
                    }
                    TotalRow(amountPaisa = calc.totalPaisa)
                }
                TextButton(onClick = {
                    viewModel.setPaymentMethod(PaymentMethod.CREDIT)
                    onProceed()
                }, enabled = state.cart.isNotEmpty() && state.calculation != null && !state.isLoading,
                    modifier = Modifier.fillMaxWidth()) { Text("Credit / Udhaar") }
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick  = onAddMoreItems,
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Text("Add Items")
                    }
                    Button(
                        onClick  = { showPaymentChoice = true },
                        enabled = state.cart.isNotEmpty() && state.calculation != null && state.invalidPipeIds.isEmpty() && !state.isLoading,
                        modifier = Modifier.weight(2f).height(52.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)
                    ) {
                        Icon(Icons.Default.Payment, null)
                        Spacer(Modifier.width(6.dp))
                        Text("PROCEED", fontWeight = FontWeight.Bold)
                    }
                    FilledTonalIconButton(onClick = {
                        cartFocus.clearFocus()
                        viewModel.prepareCartPreview()
                        onProceed()
                    }, enabled = state.cart.isNotEmpty() && state.calculation != null &&
                        state.invalidPipeIds.isEmpty() && !state.isLoading && state.savedBillId == null,
                        modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Default.Visibility, contentDescription = "Direct Preview")
                    }
                }
            }
                }
        }
    ) { padding ->
        if (state.cart.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ShoppingCartCheckout, null, tint = AppColors.TextSecondary, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Cart is empty", color = AppColors.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onAddMoreItems) { Text("Add Products") }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(padding)) {
            BillingPickerLayout(null,"",{}, {}, {}, {},entryContent=editingVariant?.let { selected ->
                { modifier -> CartVariantEditor(selected,variants,state.cart,!state.isLoading,state.error,
                    { target,q,l,f -> viewModel.editCartVariant(selected.product.id,target.id,q,l,f) { editingVariant=null } },
                    { editingVariant=null },modifier) }
            }) { cartModifier ->
            LazyColumn(cartModifier) {
                items(state.cart, key = { it.product.id }) { item ->
                    CartSwipeActions(enabled=!state.isLoading,modifier=Modifier.testTag("cart-swipe-${item.product.id}"),onEdit={
                        swipeItem=item
                    },onRemove={removeItem=item}) {
                    if (item.product.isPipe) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f).clickable(enabled=!state.isLoading) { editingVariant=item }) {
                                    Text(item.product.displayLabel)
                                    Text(CalculationEngine.formatRupees(item.itemTotalPaisa), fontWeight = FontWeight.Bold)
                                }
                                IconButton({ viewModel.removeFromCart(item.product.id) }, enabled = !state.isLoading) {
                                    Icon(Icons.Default.Delete, "Remove pipe")
                                }
                            }
                            item.offcut?.let {Text("Registered offcut · max ${com.hardwareshop.pos.util.PipeCutPlanner.format(it.capacityFeet)} ft",style=MaterialTheme.typography.bodySmall)}
                            PipeQuantityFields(item.product, item, !state.isLoading) { l, ft -> viewModel.updatePipe(item.product, l, ft) }
                        }
                    } else CartItemRow(
                        modifier = Modifier.clickable(enabled=!state.isLoading && item.product.id>0) { editingVariant=item },
                        name       = item.receiptName,
                        size       = if (item.product.id < 0) "Manual item" else item.product.size,
                        qty        = item.quantityDisplay,
                        unitPrice  = "${CalculationEngine.formatRupees(item.unitPricePaisa)} / ${item.product.unit}",
                        total      = CalculationEngine.formatRupees(item.itemTotalPaisa),
                        onIncrease = { viewModel.updateQuantity(item.product.id, item.quantity + 1) },
                        onDecrease = { if (item.quantity <= 1) viewModel.removeFromCart(item.product.id) else viewModel.updateQuantity(item.product.id, item.quantity - 1) },
                        onRemove   = { viewModel.removeFromCart(item.product.id) },
                        onEditQuantity = { if (!state.isLoading) {
                            if(item.product.id>0 && ProductSizeGroups.hasChoices(variants,item.product)) editingVariant=item
                            else { quantityText = CalculationEngine.formatQuantity(item.quantity); quantityItem = item }
                        } }
                    )
                    }
                    if(item.product.id>0 && !item.product.isPipe && com.hardwareshop.pos.util.WeightCalculator.modeForUnit(item.product.unit)!=null) {
                        TextButton({weightItem=item},enabled=!state.isLoading,modifier=Modifier.testTag("weight-open-${item.product.id}")){Text("WEIGHT CALCULATOR")}
                    }
                    if (item.product.id > 0 && item.quantity > item.product.currentStock) {
                        Text("Quantity exceeds recorded stock (${CalculationEngine.formatQuantity(item.product.currentStock)} ${item.product.unit}). Check availability.",
                            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = AppColors.LowStock)
                    }
                    if (item.product.id < 0) {
                        var editing by remember { mutableStateOf(false) }
                        var name by remember(item.product.name) { mutableStateOf(item.product.name) }
                        var price by remember(item.unitPricePaisa) { mutableStateOf(java.math.BigDecimal.valueOf(item.unitPricePaisa, 2).toPlainString()) }
                        TextButton({ editing = true }, enabled = !state.isLoading) { Text("EDIT MANUAL ITEM") }
                        if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("Edit item for this bill") },
                            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(name, { name = it.take(120) }, label = { Text("Item name") }, singleLine = true)
                                OutlinedTextField(price, { price = it }, label = { Text("Price per piece ₹") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                state.error?.let { ErrorMessage(it) }
                            } },
                            confirmButton = { TextButton({ if (viewModel.editManualItem(item.product.id, name, price)) editing = false },
                                enabled = !state.isLoading && name.isNotBlank() && (CalculationEngine.parseRupeesToPaisa(price) ?: 0) > 0) { Text("SAVE CHANGES") } },
                            dismissButton = { TextButton({ editing = false }) { Text("CANCEL") } })
                    } else if (viewModel.canEditPrice) {
                        var editPrice by remember { mutableStateOf(false) }
                        var price by remember(item.unitPricePaisa) { mutableStateOf(java.math.BigDecimal.valueOf(item.unitPricePaisa, 2).toPlainString()) }
                        var footPrice by remember(item.pipeFootPricePaisa) { mutableStateOf(java.math.BigDecimal.valueOf(item.pipeFootPricePaisa, 2).toPlainString()) }
                        TextButton(onClick = { editPrice = true }, enabled = !state.isLoading) { Text("EDIT PRICE FOR THIS BILL") }
                        if (editPrice) AlertDialog(onDismissRequest = { editPrice = false },
                            title = { Text("${item.product.name} — selling price") },
                            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(price, { price = it },
                                    label = { Text(if (item.product.isPipe) "Price per length ₹" else "Price per ${item.product.unit} ₹") },
                                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                if (item.product.isPipe) OutlinedTextField(footPrice, { footPrice = it },
                                    label = { Text("Price per foot ₹") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                Text("Current bill only", style = MaterialTheme.typography.bodySmall)
                                state.error?.let { ErrorMessage(it) }
                            } },
                            confirmButton = { TextButton(onClick = {
                                if (item.product.isPipe) {
                                    if (viewModel.setPipePrices(item.product.id, price, footPrice)) editPrice = false
                                } else { viewModel.setItemPrice(item.product.id, price); editPrice = false }
                            }, enabled = !state.isLoading && (CalculationEngine.parseRupeesToPaisa(price) ?: 0) > 0 &&
                                (!item.product.isPipe || (CalculationEngine.parseRupeesToPaisa(footPrice) ?: 0) > 0)) { Text("APPLY") } },
                            dismissButton = { TextButton(onClick = { editPrice = false }) { Text("CANCEL") } })
                    }
                }
            }
            }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PAYMENT SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PaymentScreen(
    onNavigateUp: () -> Unit,
    onBillSaved:  (Long) -> Unit,
    viewModel: BillingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val calc   = state.calculation
    val focus  = LocalFocusManager.current
    var cashReceived by rememberSaveable { mutableStateOf("") }
    val cashReady = state.paymentMethod != PaymentMethod.CASH || cashReceived.isBlank() ||
        (CalculationEngine.parseRupeesToPaisa(cashReceived)?.let { it >= (calc?.totalPaisa ?: 0L) } == true)

    LaunchedEffect(state.savedBillId) { state.savedBillId?.let(onBillSaved) }

    LaunchedEffect(state.openPreviewOnArrival) {
        if (state.openPreviewOnArrival) viewModel.consumePreviewRequest()
    }

    state.previewLines?.let { lines ->
        AlertDialog(onDismissRequest = { if (!state.isLoading) viewModel.closePreview() },
            title = { Text("Bill Preview") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    item { Text("Bill number is assigned when saved.", style = MaterialTheme.typography.labelSmall) }
                    items(lines.size) { index ->
                        val row = lines[index]
                        if (row.footerStart && state.previewQrUri != null) {
                            val uri = state.previewQrUri!!
                            val bitmap = remember(uri) {
                                val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(uri,
                                    com.google.zxing.BarcodeFormat.QR_CODE, 256, 256,
                                    mapOf(com.google.zxing.EncodeHintType.MARGIN to 4,
                                        com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))
                                val pixels = IntArray(matrix.width * matrix.height) { p ->
                                    if (matrix[p % matrix.width, p / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                                android.graphics.Bitmap.createBitmap(pixels, matrix.width, matrix.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
                            }
                            Image(bitmap, "UPI payment QR", Modifier.fillMaxWidth().height(180.dp))
                            Text("SCAN & PAY ${CalculationEngine.formatRupees(if(state.paymentMethod==PaymentMethod.SPLIT)state.splitUpiPaisa else calc?.totalPaisa ?: 0)}\nPayment confirmation required",
                                Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        Text(row.text, Modifier.fillMaxWidth(), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            fontWeight = if (row.bold) FontWeight.Bold else FontWeight.Normal,
                            textAlign = when (row.align) {
                                ReceiptLayout.Align.LEFT -> androidx.compose.ui.text.style.TextAlign.Left
                                ReceiptLayout.Align.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
                                ReceiptLayout.Align.RIGHT -> androidx.compose.ui.text.style.TextAlign.Right
                            })
                    }
                    if (!cashReady) item { ErrorMessage("Cash received is invalid or below the total. Choose EDIT DETAILS to correct it.") }
                    state.previewNotice?.let { notice -> item { ErrorMessage(notice) } }
                    state.error?.let { error -> item { ErrorMessage(error) } }
                }
            },
            confirmButton = { if (state.previewOnly) {
                TextButton(onClick = viewModel::closePreview) { Text("CLOSE PREVIEW") }
            } else Column {
                Button(onClick = { viewModel.saveBill(printAfterSave = true) }, enabled = !state.isLoading && cashReady) {
                    Text(if (state.paymentMethod == PaymentMethod.UPI) "UPI RECEIVED — SAVE & PRINT" else "CONFIRM, SAVE & PRINT")
                }
                TextButton(onClick = { viewModel.saveBill() }, enabled = !state.isLoading && cashReady) {
                    Text(if (state.paymentMethod == PaymentMethod.UPI) "UPI RECEIVED — SAVE ONLY" else "SAVE ONLY")
                }
            } },
            dismissButton = { TextButton(onClick = viewModel::closePreview, enabled = !state.isLoading) { Text("EDIT DETAILS") } })
    }

    if (state.showCustomerPicker) {
        var newName by remember { mutableStateOf("") }
        var newPhone by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = viewModel::dismissCustomerPicker, title = { Text("Choose Customer") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.customerSearchQuery,
                        onValueChange = viewModel::onCustomerSearchQueryChange,
                        label = { Text("Customer name or phone") },
                        placeholder = { Text("Type a name or phone number") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                        trailingIcon = {
                            if (state.customerSearchQuery.isNotBlank()) {
                                IconButton(onClick = { viewModel.onCustomerSearchQueryChange("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        keyboardActions = KeyboardActions(onSearch = {
                            viewModel.onCustomerSearchQueryChange(state.customerSearchQuery)
                        }),
                        modifier = Modifier.weight(1f)
                    )
                    FilledIconButton(
                        onClick = { viewModel.onCustomerSearchQueryChange(state.customerSearchQuery) },
                        enabled = state.customerSearchQuery.isNotBlank() && !state.isLoading,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search customers")
                    }
                }
                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    items(state.customerSearchResults, key = { it.id }) { customer ->
                        TextButton({ viewModel.selectCustomer(customer) }, enabled = !state.isLoading) { Text("${customer.name} • ${customer.phone}") }
                    }
                }
                HorizontalDivider()
                Text("Or add a new customer")
                OutlinedTextField(newName, { newName = it }, label = { Text("Name *") }, singleLine = true)
                OutlinedTextField(newPhone, { newPhone = it }, label = { Text("Phone") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                state.error?.let { ErrorMessage(it) }
            } },
            confirmButton = { TextButton({ viewModel.createAndSelectCustomer(newName, newPhone) }, enabled = newName.isNotBlank() && !state.isLoading) { Text("ADD & SELECT") } },
            dismissButton = { TextButton(viewModel::dismissCustomerPicker) { Text("Back") } })
    }

    Scaffold(
        topBar = { AppTopBar("Payment", onNavigateUp = onNavigateUp) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
                if (calc != null) TotalRow(amountPaisa = calc.totalPaisa)
                PrimaryActionButton(
                    text      = if(state.paymentMethod==PaymentMethod.SPLIT) "CONFIRM SPLIT & PREVIEW" else "PREVIEW BILL",
                    onClick   = { focus.clearFocus(); viewModel.previewBill() },
                    icon      = Icons.Default.CheckCircle,
                    isLoading = state.isLoading,
                    modifier  = Modifier.padding(16.dp)
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if(state.paymentMethod==PaymentMethod.SPLIT && calc!=null) item {
                SplitPaymentFields(state,viewModel::setSplitAmounts)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text("Customer Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            TextButton(viewModel::openCustomerPicker, enabled = !state.isLoading) {
                                Icon(Icons.Default.PersonSearch, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Saved Customers")
                            }
                        }
                        OutlinedTextField(
                            value = state.customerName,
                            onValueChange = viewModel::setCustomerName,
                            label = { Text(if (state.paymentMethod == PaymentMethod.CREDIT) "Customer Name *" else "Customer Name (Optional)") },
                            placeholder = { Text("e.g. Ramesh Kumar") },
                            singleLine = true,
                            isError = state.error == "Customer Name is required",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isLoading
                        )
                        OutlinedTextField(
                            value = state.customerPhone,
                            onValueChange = viewModel::setCustomerPhone,
                            label = { Text(if (state.paymentMethod == PaymentMethod.CREDIT) "Phone Number *" else "Phone Number (Optional)") },
                            placeholder = { Text("e.g. 9876543210") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isLoading
                        )
                        OutlinedTextField(
                            value = state.customerGstNumber,
                            onValueChange = viewModel::setCustomerGstNumber,
                            label = { Text("GST Number / GSTIN (Optional)") },
                            placeholder = { Text("e.g. 29ABCDE1234F1Z5") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isLoading
                        )
                        if (state.customer != null) {
                            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                                TextButton(viewModel::clearSelectedCustomer, enabled = !state.isLoading) {
                                    Text("CLEAR FIELDS")
                                }
                            }
                        }
                    }
                }
                if (state.paymentMethod == PaymentMethod.CREDIT) {
                    Spacer(Modifier.height(8.dp))
                    var amount by remember { mutableStateOf(if (state.paidPaisa == 0L) "" else java.math.BigDecimal.valueOf(state.paidPaisa, 2).toPlainString()) }
                    OutlinedTextField(amount, { value ->
                        if (value.matches(Regex("[0-9]*(?:\\.[0-9]{0,2})?")) && (value.isBlank() || CalculationEngine.parseRupeesToPaisa(value.trimEnd('.')) != null)) {
                            amount = value
                            viewModel.setPaidAmount(CalculationEngine.parseRupeesToPaisa(value.trimEnd('.')) ?: 0L)
                        }
                    }, label = { Text("Cash paid now ₹ (0 = full credit)") }, enabled = !state.isLoading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    Text("Any amount paid now is recorded as cash; the remainder goes to their credit ledger.", style = MaterialTheme.typography.bodySmall)
                }
            }
            // ── Payment method ────────────────────────────────────────────────
            item {
                Text("Payment Method", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PaymentMethod.entries.forEach { method ->
                        PaymentMethodChip(
                            label    = method.displayName,
                            selected = state.paymentMethod == method,
                            onClick  = { viewModel.setPaymentMethod(method) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ── GST settings ──────────────────────────────────────────────────
            if (state.paymentMethod == PaymentMethod.CASH && calc != null) {
                item { CashChangeCard(cashReceived, { cashReceived = it }, calc.totalPaisa, !state.isLoading) }
            }
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Column {
                                Text("GST Invoice", fontWeight = FontWeight.Medium)
                                Text("Include GST on this bill", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                            }
                            Switch(checked = state.gstEnabled, onCheckedChange = viewModel::setGstEnabled)
                        }
                        if (state.gstEnabled) {
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Column {
                                    Text("Inclusive GST", fontWeight = FontWeight.Medium)
                                    Text("Selling price includes each item's GST rate", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                }
                                Switch(checked = state.gstInclusive, onCheckedChange = viewModel::setGstInclusive)
                            }
                        }
                    }
                }
            }

            // ── Discount ──────────────────────────────────────────────────────
            item {
                var rawDiscount by remember { mutableStateOf(if (state.discountPaisa == 0L) "" else java.math.BigDecimal.valueOf(state.discountPaisa, 2).toPlainString()) }
                Text("Discount (Optional)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value         = rawDiscount,
                    onValueChange = { v ->
                        if (v.matches(Regex("[0-9]*(?:\\.[0-9]{0,2})?")) && (v.isBlank() || CalculationEngine.parseRupeesToPaisa(v.trimEnd('.')) != null)) {
                            rawDiscount = v
                            val p = CalculationEngine.parseRupeesToPaisa(v.trimEnd('.'))
                            if (p != null || v.isBlank()) viewModel.setDiscount(p ?: 0L, "Discount")
                        }
                    },
                    label         = { Text("Amount in ₹") },
                    leadingIcon   = { Icon(Icons.Default.LocalOffer, null) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    enabled = !state.isLoading,
                    isError = state.discountPermissionDenied
                )
                if (state.discountPermissionDenied && state.error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(state.error!!, color = AppColors.Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
                }
            }

            // ── Bill summary card ─────────────────────────────────────────────
            if (calc != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                            Text("Bill Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            HorizontalDivider()
                            AmountRow("Subtotal", calc.subtotalPaisa)
                            if (calc.discountPaisa > 0)
                                AmountRow("Discount (−)", calc.discountPaisa, amountColor = AppColors.Success)
                            if (state.gstEnabled && calc.gstAmountPaisa > 0) {
                                if (state.gstInclusive) {
                                    // Inclusive: show embedded tax with CGST/SGST split
                                    Text("GST (Inclusive — already in price)", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                    calc.gstBreakdown.forEach { (rateBp, gstAmt) ->
                                        val (cgst, sgst) = CalculationEngine.splitCgstSgst(gstAmt)
                                        AmountRow("  CGST @ ${CalculationEngine.formatGstRate(rateBp / 2)}", cgst, amountColor = AppColors.TextSecondary)
                                        AmountRow("  SGST @ ${CalculationEngine.formatGstRate(rateBp / 2)}", sgst, amountColor = AppColors.TextSecondary)
                                    }
                                    AmountRow("Total GST (embedded)", calc.gstAmountPaisa)
                                } else {
                                    // Exclusive: show GST added on top with CGST/SGST split
                                    calc.gstBreakdown.forEach { (rateBp, gstAmt) ->
                                        val (cgst, sgst) = CalculationEngine.splitCgstSgst(gstAmt)
                                        AmountRow("  CGST @ ${CalculationEngine.formatGstRate(rateBp / 2)}", cgst)
                                        AmountRow("  SGST @ ${CalculationEngine.formatGstRate(rateBp / 2)}", sgst)
                                    }
                                    AmountRow("Total GST (+)", calc.gstAmountPaisa)
                                }
                            }
                            HorizontalDivider()
                            AmountRow(
                                "TOTAL",
                                calc.totalPaisa,
                                labelStyle  = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                amountStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                amountColor = AppColors.Primary
                            )
                        }
                    }
                }
            }

            if (state.error != null && !state.discountPermissionDenied) {
                item { ErrorMessage(state.error!!) }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BILL SUCCESS SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun BillSuccessScreen(
    billId:   Long,
    onNewBill: () -> Unit,
    onHome:   () -> Unit,
    printRequested: Boolean = false,
    viewModel: BillSuccessViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(billId) { viewModel.loadBill(billId) }
    val withBluetooth = rememberBluetoothGate { viewModel.showPrintError(it) }
    var consumedPrintRequest by rememberSaveable(billId) { mutableStateOf(false) }
    LaunchedEffect(printRequested, state.bill?.id) {
        if (printRequested && state.bill != null && !consumedPrintRequest) {
            consumedPrintRequest = true
            if (!state.hasPrintAttempt && state.bill?.isPrinted != true) withBluetooth { viewModel.printBill() }
        }
    }
    // The print is owned by this screen's ViewModel. Keep its navigation entry alive until it finishes.
    androidx.activity.compose.BackHandler(enabled = state.isPrinting) { }

    var confirmCopy by remember { mutableStateOf(false) }
    if (confirmCopy) AlertDialog(
        onDismissRequest = { confirmCopy = false },
        title = { Text("Print another copy?") },
        text = { Text("Check the paper first. The previous attempt may already have printed. This receipt will be marked REPRINT / COPY.") },
        confirmButton = { TextButton(onClick = { confirmCopy = false; withBluetooth { viewModel.printBill() } }) { Text("PRINT COPY") } },
        dismissButton = { TextButton(onClick = { confirmCopy = false }) { Text("CANCEL") } }
    )

    Column(
        modifier              = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement   = Arrangement.spacedBy(12.dp),
        horizontalAlignment   = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Icon(Icons.Default.CheckCircle, null, tint = AppColors.Success, modifier = Modifier.size(80.dp))
        Spacer(Modifier.height(8.dp))
        Text("Bill Saved Successfully!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = AppColors.Success)

        state.bill?.let { bill ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                    AmountRow("Bill #${bill.billNumber}", bill.totalPaisa,
                        labelStyle  = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        amountStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    if (bill.customerName.isNotBlank())
                        Text("Customer: ${bill.customerName}", color = AppColors.TextSecondary)
                    Text("Payment: ${bill.paymentMethod.displayName}", color = AppColors.TextSecondary)
                    if (bill.creditPaisa > 0) {
                        Text("Credit at sale: ${CalculationEngine.formatRupees(bill.creditPaisa)}", color = AppColors.Credit, fontWeight = FontWeight.Bold)
                        Text("For current dues, check the customer ledger.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (state.printError != null) ErrorMessage(state.printError!!)
        state.printMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick  = { if (state.hasPrintAttempt || state.bill?.isPrinted == true) confirmCopy = true else withBluetooth { viewModel.printBill() } },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled  = !state.isPrinting
        ) {
            if (state.isPrinting) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Print, null)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (state.isPrinting) "Printing…" else if (state.hasPrintAttempt || state.bill?.isPrinted == true) "REPRINT COPY" else "PRINT RECEIPT")
        }

        if(!state.isPrinting && state.bill!=null)ReceiptShareAction(billId,Modifier.fillMaxWidth())
        if (state.isPrinting) {
            Text("Printing receipt. Please wait before leaving this screen.", style = MaterialTheme.typography.bodySmall)
        }
        PrimaryActionButton(text = "NEW BILL", onClick = { if (!viewModel.state.value.isPrinting) onNewBill() },
            enabled = !state.isPrinting, icon = Icons.Default.Add)
        TextButton(onClick = { if (!viewModel.state.value.isPrinting) onHome() }, enabled = !state.isPrinting) { Text("Back to Home") }

        // Owner only: remote print button.
        // LocalContext MUST be read at composable scope — not inside remember lambda.
        // Business-layer guard: PermissionChecker.enforceRemotePrint() in repository
        // will throw SecurityException if a worker somehow calls this path.
        val appContext = LocalContext.current.applicationContext
        val isOwner = remember(appContext) {
            runCatching {
                dagger.hilt.android.EntryPointAccessors
                    .fromApplication(appContext, SessionEntryPoint::class.java)
                    .sessionHolder()
                    .isOwner
            }.getOrDefault(false)
        }
        if (isOwner && !state.isPrinting) {
            state.bill?.let { bill ->
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text("Remote Print (optional)", style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
                RemotePrintButton(billId = bill.id)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}


package com.hardwareshop.pos.presentation.worker.product

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.repository.ProductRepository
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.data.local.dao.SettingsDao
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import com.hardwareshop.pos.util.ProductSortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class ProductSelectionUiState(
    val categories: List<Category>      = emptyList(),
    val pinnedCategoryIds: Set<Long> = emptySet(),
    val types: List<ProductType>        = emptyList(),
    val products: List<Product>         = emptyList(),
    val availablePipes: List<Product>   = emptyList(),
    val availableProducts: List<Product> = emptyList(),
    val catalogueIndex: com.hardwareshop.pos.util.ProductSizeGroups.Index? = null,
    val sizeChoiceProduct: Product? = null,
    val suggestions: List<com.hardwareshop.pos.util.JobSuggestions.Suggestion> = emptyList(),
    val favourites: List<Product>      = emptyList(),
    val favouriteIds: Set<Long>        = emptySet(),
    val searchQuery: String             = "",
    val searchResults: List<Product>    = emptyList(),
    val sortOrder: ProductSortOrder     = ProductSortOrder.DEFAULT,
    val selectedCategory: Category?     = null,
    val selectedType: ProductType?      = null,
    val isSearchActive: Boolean         = false,
    val suggestionSearchOpened:Boolean = false,
    val isLoading: Boolean              = false,
    val searchPending: Boolean          = false,
    val pendingProduct: Product?        = null,     // Product waiting for quantity input
    val pendingQuantity: String         = "", val error: String? = null
) {
    val choosingSize: Boolean get() = sizeChoiceProduct != null && pendingProduct == null
    val sizeChoices: List<Product> get() {
        val choices=sizeChoiceProduct?.let { catalogueIndex?.choices(it) ?: com.hardwareshop.pos.util.ProductSizeGroups.choices(availableProducts,it) }.orEmpty()
        if(!isSearchActive || searchQuery.isBlank())return choices
        val matcher=com.hardwareshop.pos.util.ProductSearch.Matcher(com.hardwareshop.pos.util.ProductSearch.tokens(searchQuery))
        return choices.sortedBy { product -> matcher.score("${product.name} ${product.size} ${product.categoryName} ${product.typeName}",product.searchAliases) ?: Int.MAX_VALUE }
    }
    val matchingPipes: List<Product> get() =
        com.hardwareshop.pos.util.PipeSizeChoices.matching(availablePipes, pendingProduct)
}

@HiltViewModel
class ProductSelectionViewModel @Inject constructor(
    private val productRepository: ProductRepository,
    private val settingsDao: SettingsDao,
    private val offcutRepository: com.hardwareshop.pos.data.repository.PipeOffcutRepository
) : ViewModel() {
    private val openedAt=android.os.SystemClock.elapsedRealtime()
    private fun trace(stage:String,count:Int=0){if(com.hardwareshop.pos.BuildConfig.DEBUG)android.util.Log.i("BillingLoadTrace","$stage elapsedMs=${android.os.SystemClock.elapsedRealtime()-openedAt} rows=$count")}

    private val _state = MutableStateFlow(ProductSelectionUiState())
    val state: StateFlow<ProductSelectionUiState> = _state.asStateFlow()
    val recordedOffcuts=offcutRepository.observe().catch { emit(emptyList()) }
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())

    private var searchJob: Job? = null
    private var categoryJob: Job? = null
    private var typeJob: Job? = null
    private val favouriteLock = Mutex()
    private val catalogue = MutableStateFlow<List<Product>>(emptyList())
    private val preparedCatalogue = MutableStateFlow<com.hardwareshop.pos.util.BillingCatalogue?>(null)
    private var searchGeneration=0L
    private val cartProducts = MutableStateFlow<List<Product>>(emptyList())
    fun updateSuggestionCart(products: List<Product>) { cartProducts.value = products.distinctBy { it.id } }


    init {
        trace("viewmodel-created")
        viewModelScope.launch {
            combine(catalogue, cartProducts) { products, cart -> products to cart }.collectLatest { (products, cart) ->
                try {
                    val history = productRepository.boughtTogether(cart.map { it.id }.filter { it > 0 })
                    val suggestions = withContext(Dispatchers.Default) {
                        com.hardwareshop.pos.util.JobSuggestions.build(products, cart, history)
                    }
                    _state.update { it.copy(suggestions = suggestions) }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { _state.update { it.copy(suggestions = emptyList()) } }
            }
        }
        _state.update { it.copy(isLoading=true) }
        viewModelScope.launch {
            productRepository.observeBillingCatalogue().catch { error ->
                trace("catalogue-error")
                _state.update { it.copy(isLoading=false,searchPending=false,error="Could not load local products: ${error.message}") }
            }.collect { snapshot ->
                trace("catalogue-received",snapshot.products.size)
                preparedCatalogue.value=snapshot
                catalogue.value=snapshot.products
                _state.update { current -> current.copy(availableProducts=snapshot.products,catalogueIndex=snapshot.families,
                    availablePipes=snapshot.products.filter { it.isPipe },isLoading=false,
                    products=current.selectedType?.let { type -> snapshot.products.filter { it.typeId==type.id } }.orEmpty(),
                    searchResults=snapshot.matchingPrevious(current.searchQuery,current.searchResults)) }
                if(_state.value.searchQuery.isNotBlank()) launchSearch(_state.value.searchQuery)
                trace("catalogue-state-ready",snapshot.products.size)
            }
        }
        viewModelScope.launch {
            combine(preparedCatalogue.filterNotNull(),settingsDao.observeBillingFavourites().distinctUntilChanged()) { snapshot,rows ->
                val byId=snapshot.products.associateBy { it.id }
                rows.mapNotNull { it.key.removePrefix("billing-favourite:").toLongOrNull() }
                    .mapNotNull { byId[snapshot.aliases[it] ?: it] }.distinctBy { it.id }
            }.collect { favourites -> _state.update { it.copy(favourites=favourites,favouriteIds=favourites.map { p->p.id }.toSet()) } }
        }
        viewModelScope.launch {
            combine(productRepository.observeActiveCategories().distinctUntilChanged(), settingsDao.observePinnedBillingCategories().distinctUntilChanged()) { categories, pins ->
                val ids = pins.mapNotNull { it.key.removePrefix("billing-category-pin:").toLongOrNull() }.toSet()
                categories.sortedBy { if (it.id in ids) 0 else 1 } to ids
            }.collect { (categories, ids) ->
                    _state.update { it.copy(categories = categories, pinnedCategoryIds = ids) }
                }
        }
    }

    // ── Hierarchy navigation ──────────────────────────────────────────────────

    fun selectCategory(category: Category) {
        _state.update { it.copy(
            selectedCategory = category,
            pendingProduct = null, pendingQuantity = "", sizeChoiceProduct = null,
            selectedType     = null,
            types            = emptyList(),
            products         = emptyList()
        )}
        categoryJob?.cancel(); typeJob?.cancel()
        categoryJob = viewModelScope.launch {
            productRepository.observeTypesForCategory(category.id)
                .collect { types ->
                    _state.update { it.copy(types = types) }
                }
        }
    }

    fun selectType(type: ProductType) {
        _state.update { it.copy(selectedType = type,products = preparedCatalogue.value?.products?.filter { p->p.typeId==type.id }.orEmpty(), pendingProduct = null, pendingQuantity = "", sizeChoiceProduct = null) }
        typeJob?.cancel()
    }

    fun goBackToCategories() {
        categoryJob?.cancel(); typeJob?.cancel()
        _state.update { it.copy(
            selectedCategory = null,
            pendingProduct = null, pendingQuantity = "", sizeChoiceProduct = null,
            selectedType     = null,
            types            = emptyList(),
            products         = emptyList()
        )}
    }

    fun goBackToTypes() {
        typeJob?.cancel()
        _state.update { it.copy(
            selectedType = null,
            pendingProduct = null, pendingQuantity = "", sizeChoiceProduct = null,
            products     = emptyList()
        )}
    }

    // ── Search ────────────────────────────────────────────────────────────────

    fun setSortOrder(order: ProductSortOrder) { _state.update { it.copy(sortOrder = order) } }

    fun toggleFavourite(product: Product) {
        viewModelScope.launch {
            try {
                favouriteLock.withLock {
                val key = "billing-favourite:${product.id}"
                if (product.id in _state.value.favouriteIds) {
                    settingsDao.deleteSetting(key)
                    settingsDao.getAllSettings().filter {
                        it.key.startsWith("pipe:billing-alias:") && it.value == product.id.toString()
                    }.forEach { alias ->
                        settingsDao.deleteSetting("billing-favourite:${alias.key.removePrefix("pipe:billing-alias:")}")
                    }
                }
                else settingsDao.upsertSetting(SettingsEntity(key, "1"))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "Could not save favourite. Try again.") } }
        }
    }

    fun toggleCategoryPin(category: Category) {
        viewModelScope.launch {
            try {
                favouriteLock.withLock {
                    val key = "billing-category-pin:${category.id}"
                    if (settingsDao.getSetting(key) == null) settingsDao.upsertSetting(SettingsEntity(key, "1"))
                    else settingsDao.deleteSetting(key)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "Could not save category pin. Try again.") } }
        }
    }

    fun openSearch() { _state.update { it.copy(isSearchActive = it.searchQuery.isNotBlank(),suggestionSearchOpened=true) } }
    fun onSearchQueryChange(query: String) {
        if(query==_state.value.searchQuery)return
        searchJob?.cancel();searchGeneration++
        val snapshot=preparedCatalogue.value
        _state.update { it.copy(
            searchQuery    = query,
            suggestionSearchOpened = true,
            pendingProduct = null, pendingQuantity = "", sizeChoiceProduct = null,
            searchResults = snapshot?.matchingPrevious(query,it.searchResults).orEmpty(),
            isLoading = query.isNotBlank() && snapshot==null,
            searchPending = query.isNotBlank(),
            isSearchActive = query.isNotBlank(), error = null
        )}
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), isLoading = false,searchPending=false) }
            return
        }
        launchSearch(query)
    }

    private fun launchSearch(query: String) {
        searchJob?.cancel()
        val snapshot=preparedCatalogue.value ?: return
        val generation=++searchGeneration
        searchJob = viewModelScope.launch {
            // Coalesce same-frame changes, without a forced typing delay or any SQL query.
            yield()
            try {
                val results = snapshot.search(query)
                ensureActive()
                if(generation==searchGeneration && preparedCatalogue.value===snapshot && _state.value.searchQuery==query)
                    _state.update { it.copy(searchResults = results, isLoading = false,searchPending=false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if(generation==searchGeneration) _state.update { it.copy(isLoading = false,searchPending=false, error = "Search failed: ${e.message}") } }
        }
    }

    fun clearSearch() {
        searchJob?.cancel();searchGeneration++
        _state.update { it.copy(
            searchQuery    = "",
            pendingProduct = null, pendingQuantity = "", sizeChoiceProduct = null,
            searchResults  = emptyList(),
            isSearchActive = false, isLoading = false,searchPending=false, error = null
        )}
    }

    // ── Quantity dialog ───────────────────────────────────────────────────────

    fun selectProductForQuantity(product: Product) {
        if (_state.value.pendingProduct?.id == product.id) return
        // Default quantity = 1 for most hardware items
        _state.update { it.copy(
            pendingProduct  = product,
            sizeChoiceProduct = null,
            pendingQuantity = "1"
        )}
    }

    /** All paint and pipe/fitting entry points choose a saved variant first. */
    fun selectProduct(product: Product) {
        if (_state.value.catalogueIndex?.hasChoices(product) ?: com.hardwareshop.pos.util.ProductSizeGroups.hasChoices(_state.value.availableProducts, product)) {
            _state.update { it.copy(sizeChoiceProduct = product, pendingProduct = null, pendingQuantity = "") }
        } else if(product.isPipe) {
            _state.update { it.copy(sizeChoiceProduct=product,pendingProduct=product,pendingQuantity="1") }
        } else selectProductForQuantity(product)
    }

    fun selectSize(product: Product) {
        val current = _state.value.sizeChoices.firstOrNull { it.id == product.id } ?: return
        if (current.isPipe && (current.pipeLengthPricePaisa<=0 || current.pipeFootPricePaisa<=0) ||
            !current.isPipe && current.sellingPricePaisa<=0) {
            _state.update { it.copy(error="Selling price is not set for this size") }; return
        }
        _state.update { it.copy(pendingProduct = current, pendingQuantity = "1") }
    }

    fun goBackInSelection() {
        if (_state.value.pendingProduct != null && _state.value.sizeChoiceProduct != null)
            _state.update { it.copy(pendingProduct = null, pendingQuantity = "") }
        else dismissQuantityDialog()
    }

    fun onQuantityChange(qty: String) {
        // Allow only valid numeric input with up to 2 decimal places
        val filtered = qty.filter { c -> c.isDigit() || c == '.' }
        val dotCount = filtered.count { it == '.' }
        if (dotCount > 1) return
        val decimalPart = filtered.substringAfter('.', "")
        if (decimalPart.length > 2) return
        _state.update { it.copy(pendingQuantity = filtered) }
    }

    /** Returns (product, quantity) if valid, null otherwise */
    fun confirmQuantity(): Pair<Product, Double>? {
        val product = _state.value.pendingProduct ?: return null
        val qty     = _state.value.pendingQuantity.toDoubleOrNull()
        if (qty == null || !qty.isFinite() || qty <= 0) return null
        dismissQuantityDialog()
        return product to qty
    }

    fun dismissQuantityDialog() {
        _state.update { it.copy(pendingProduct = null, pendingQuantity = "", sizeChoiceProduct = null) }
    }
}

package com.hardwareshop.pos.presentation.owner.products

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.repository.ProductRepository
import com.hardwareshop.pos.data.repository.PendingProductRepository
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.ProductImportFileReader
import dagger.hilt.android.lifecycle.HiltViewModel
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// PRODUCT MANAGEMENT VIEW MODEL  (list / search)
// ─────────────────────────────────────────────────────────────────────────────

data class ProductManagementUiState(
    val products:    List<Product>  = emptyList(),
    val categories:  List<Category> = emptyList(),
    val searchQuery: String         = "",
    val isLoading:   Boolean        = false,
    val isImporting: Boolean        = false,
    val deletingProductId: Long?    = null,
    val message:     String?        = null
)

@HiltViewModel
class ProductManagementViewModel @Inject constructor(
    private val productRepository: ProductRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ProductManagementUiState())
    val state: StateFlow<ProductManagementUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            productRepository.observeActiveCategories().collect { cats ->
                _state.update { it.copy(categories = cats) }
            }
        }
        viewModelScope.launch {
            productRepository.observeAllActiveProducts().collect { prods ->
                if (_state.value.searchQuery.isBlank()) _state.update { it.copy(products = prods) }
                else onSearchChange(_state.value.searchQuery)
            }
        }
    }

    fun onSearchChange(query: String) {
        _state.update { it.copy(searchQuery = query, isLoading = false) }
        searchJob?.cancel()
        if (query.isBlank()) {
            searchJob = viewModelScope.launch {
                productRepository.observeAllActiveProducts().take(1).collect { prods ->
                    if (_state.value.searchQuery.isBlank()) _state.update { it.copy(products = prods) }
                }
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _state.update { it.copy(isLoading = true) }
            try {
                val results = productRepository.searchProducts(query)
                if (_state.value.searchQuery == query) _state.update { it.copy(products = results, isLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(isLoading = false, message = "Cannot search products: ${e.message}") } }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun importProducts(context: Context, uri: Uri) {
        if (_state.value.isImporting) return
        _state.update { it.copy(isImporting = true, message = "Reading import file…") }
        viewModelScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) {
                    ProductImportFileReader.read(context.contentResolver, uri)
                }
                when (val result = productRepository.importProducts(rows)) {
                    is Result.Success -> {
                        val summary = result.data
                        val skipped = if (summary.skipped > 0) {
                            " Skipped ${summary.skipped}. ${summary.skippedRows.joinToString("; ")}"
                        } else ""
                        _state.update {
                            it.copy(
                                isImporting = false,
                                message = "Import done: added ${summary.added}, updated ${summary.updated}, stock changed ${summary.stockChanged}.$skipped"
                            )
                        }
                    }
                    is Result.Error -> _state.update { it.copy(isImporting = false, message = result.message) }
                    else -> _state.update { it.copy(isImporting = false, message = "Import did not finish") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isImporting = false, message = "Import failed: ${e.message}") }
            }
        }
    }

    /**
     * Deactivate a product instead of physically deleting its row.  This keeps
     * old bills and stock history valid while removing the product from all
     * active billing/search lists.  ProductRepository already enforces that
     * only the owner can edit products.
     */
    fun deleteProduct(product: Product) {
        if (_state.value.deletingProductId != null) return
        _state.update { it.copy(deletingProductId = product.id, message = null) }
        viewModelScope.launch {
            try {
                when (val result = productRepository.updateProduct(product.copy(isActive = false))) {
                    is Result.Success -> _state.update {
                        it.copy(
                            deletingProductId = null,
                            message = "${product.displayLabel} deleted"
                        )
                    }
                    is Result.Error -> _state.update {
                        it.copy(deletingProductId = null, message = result.message)
                    }
                    else -> _state.update { it.copy(deletingProductId = null, message = "Could not delete product") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(deletingProductId = null, message = e.message ?: "Could not delete product")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PRODUCT DETAIL VIEW MODEL  (create / edit)
// ─────────────────────────────────────────────────────────────────────────────

data class ProductDetailUiState(
    val ignoredOptional: Set<String> = emptySet(),
    val draftMessage: String = "",
    val isInitializing: Boolean = true,
    val loadFailed: Boolean = false,
    val isImageSaving: Boolean = false,
    val isEditMode:      Boolean           = false,
    val existingProduct: Product?          = null,
    val categories:      List<Category>   = emptyList(),
    val types:           List<ProductType> = emptyList(),
    // Form fields
    val selectedCatId:   Long    = 0L,
    val selectedTypeId:  Long    = 0L,
    val name:            String  = "",
    val size:            String  = "",
    val unit:            String  = "Piece",
    val sellingPrice:    String  = "",
    val purchasePrice:   String  = "",   // blank = owner left it empty (optional)
    val gstRatePercent:  String  = "18",
    val initialStock:    String  = "0",
    val minimumStock:    String  = "0",
    val searchAliases:   String  = "",
    val piecesPerBox:    String  = "",
    val isPipe: Boolean = false, val pipeLengthPrice: String = "", val pipeFootPrice: String = "",
    val pipeFeetPerLength: String = "",
    val imagePath:       String? = null,
    // UX
    val isLoading:       Boolean = false,
    val isSaved:         Boolean = false,
    val error:           String? = null
) {
    fun form() = ProductForm(selectedCatId, selectedTypeId, name, size, unit, sellingPrice,
        purchasePrice, gstRatePercent, initialStock, minimumStock, searchAliases, imagePath, piecesPerBox, isPipe, pipeLengthPrice, pipeFootPrice, pipeFeetPerLength)
    fun withForm(f: ProductForm) = copy(selectedCatId = f.categoryId, selectedTypeId = f.typeId,
        name = f.name, size = f.size, unit = f.unit, sellingPrice = f.sellingPrice,
        purchasePrice = f.purchasePrice, gstRatePercent = f.gstPercent, initialStock = f.stock,
        minimumStock = f.minimumStock, searchAliases = f.aliases, imagePath = f.imagePath, piecesPerBox = f.piecesPerBox, isPipe = f.isPipe, pipeLengthPrice = f.pipeLengthPrice.orEmpty(),
        pipeFootPrice = f.pipeFootPrice.orEmpty(), pipeFeetPerLength = f.pipeFeetPerLength.orEmpty())
}

@HiltViewModel
class ProductDetailViewModel @Inject constructor(
    private val productRepository: ProductRepository,
    private val drafts: PendingProductRepository,
    private val imageManager: com.hardwareshop.pos.util.ImageManager,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val _state = MutableStateFlow(ProductDetailUiState())
    val state: StateFlow<ProductDetailUiState> = _state.asStateFlow()
    private var initialized = false
    private var finished = false
    private var hasStoredDraft = false
    private var initialForm = ProductForm()
    private var initialIgnored = emptySet<String>()
    private var draftId: String = savedState.get<String>("workDraftId") ?: java.util.UUID.randomUUID().toString()
    private var autosaveJob: Job? = null
    private var typesJob: Job? = null
    private val saveMutex = Mutex()
    val availableUnits = listOf("Piece", "Meter", "Foot", "Kg", "Bag", "Box", "Packet", "Set", "Litre", "Roll", "Bundle")

    fun init(productId: Long?, resumeDraftId: String? = null) {
        if (initialized) return
        initialized = true
        savedState["workDraftId"] = draftId
        viewModelScope.launch {
            try {
                val draft = if (resumeDraftId != null) drafts.getDraft(resumeDraftId)
                    ?: error("This draft was already saved or discarded.")
                else if (productId != null) drafts.getDraftForProduct(productId)
                else drafts.getDraft(draftId)
                val id = draft?.productId ?: productId
                val product = id?.let { productRepository.getProductById(it) ?: error("Product no longer exists.") }
                val ignored = draft?.ignoredOptional ?: id?.let { drafts.reviewedFields(it) }.orEmpty()
                if (draft != null) { draftId = draft.id; savedState["workDraftId"] = draftId }
                var form = draft?.form ?: product?.let { ProductForm.fromProduct(it, ignored) } ?: ProductForm()
                // Current stock is live, never an editable part of an existing-product draft.
                if (product != null) form = form.copy(stock = product.currentStock.toString())
                if (form.imagePath != null && imageManager.resolveFile(form.imagePath) == null) form = form.copy(imagePath = null)
                val cats  = productRepository.observeActiveCategories().first()
                val types = productRepository.observeTypesForCategory(form.categoryId).first()
                if (cats.none  { it.id == form.categoryId }) form = form.copy(categoryId = 0, typeId = 0)
                if (types.none { it.id == form.typeId })     form = form.copy(typeId = 0)
                hasStoredDraft = draft != null
                initialForm    = form
                initialIgnored = ignored
                _state.value = ProductDetailUiState().withForm(form).copy(
                    isEditMode = product != null, existingProduct = product, categories = cats, types = types,
                    ignoredOptional = ignored, isInitializing = false,
                    draftMessage = if (draft != null) "Saved draft restored" else "Changes are saved as a draft automatically")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(isInitializing = false, loadFailed = true, error = e.message ?: "Cannot load item") } }
        }
    }

    fun onCategorySelected(categoryId: Long) {
        update { copy(selectedCatId = categoryId, selectedTypeId = 0, types = emptyList()) }
        typesJob?.cancel()
        typesJob = viewModelScope.launch {
            try {
                val types = productRepository.observeTypesForCategory(categoryId).first()
                if (_state.value.selectedCatId == categoryId) _state.update { it.copy(types = types) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "Cannot load product types") } }
        }
    }

    fun update(block: ProductDetailUiState.() -> ProductDetailUiState) {
        if (finished || _state.value.isLoading || _state.value.isInitializing || _state.value.loadFailed) return
        _state.update { block(it).copy(error = null, draftMessage = "Saving draft…") }
        scheduleAutosave()
    }
    fun setNotNeeded(key: String, ignored: Boolean) {
        if (key !in ProductForm.OPTIONAL_KEYS) return
        update { copy(ignoredOptional = if (ignored) ignoredOptional + key else ignoredOptional - key) }
    }
    private fun snapshot(): ProductDraft = _state.value.let {
        ProductDraft(draftId, it.existingProduct?.id, it.form(), it.ignoredOptional)
    }
    fun flushDraft() {
        if (initialized && !finished && !_state.value.isInitializing && !_state.value.loadFailed && !_state.value.isLoading)
            scheduleAutosave(immediate = true)
    }
    private fun scheduleAutosave(immediate: Boolean = false) {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            if (!immediate) delay(350)
            try { saveMutex.withLock { persistDraft() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(draftMessage = "Draft not saved", error = "Cannot save draft: ${e.message}") } }
        }
    }
    private suspend fun persistDraft() {
        if (finished) return
        val draft = snapshot()
        if (!draft.needsSaving(initialForm, initialIgnored, hasStoredDraft)) {
            _state.update { it.copy(draftMessage = "No unsaved changes") }
            return
        }
        drafts.saveDraft(draft)
        hasStoredDraft = true
        _state.update { it.copy(draftMessage = "Draft saved · finish it in Pending Item Details") }
    }

    fun onImagePicked(uri: android.net.Uri) {
        if (_state.value.isLoading || _state.value.isImageSaving || finished) return
        _state.update { it.copy(isImageSaving = true) }
        viewModelScope.launch {
            try {
                val path = imageManager.saveImage(uri, System.nanoTime().ushr(1))
                if (path == null) _state.update { it.copy(error = "Could not save photo. Please try another image.") }
                else { _state.update { it.copy(imagePath = path, draftMessage = "Saving draft…") }; scheduleAutosave() }
            } finally { _state.update { it.copy(isImageSaving = false) } }
        }
    }
    fun removeImage() = update { copy(imagePath = null) }

    /** Back and Save for Later flush the latest keystrokes before leaving this screen. */
    fun saveForLater() {
        if (finished || _state.value.isLoading || _state.value.isInitializing) return
        if (_state.value.isImageSaving) { _state.update { it.copy(error = "Please wait for the photo to finish saving.") }; return }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                autosaveJob?.cancelAndJoin()
                saveMutex.withLock { persistDraft(); finished = true }
                _state.update { it.copy(isSaved = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = "Draft could not be saved: ${e.message}") } }
            finally { _state.update { it.copy(isLoading = false) } }
        }
    }

    fun save() {
        val s = _state.value
        if (finished || s.isLoading || s.isInitializing || s.loadFailed) return
        if (s.isImageSaving) { _state.update { it.copy(error = "Please wait for the photo to finish saving.") }; return }

        // Only hard (non-optional) gaps block saving.
        val missing = s.form().gaps(s.ignoredOptional, validateStock = !s.isEditMode,requireSize=s.isPipe || s.size.isNotBlank()).filterNot { it.optional }
        if (missing.isNotEmpty()) {
            _state.update { it.copy(error = "Please complete: " + missing.joinToString { gap -> gap.label }) }
            return
        }
        if (s.categories.none { it.id == s.selectedCatId } || s.types.none { it.id == s.selectedTypeId && it.categoryId == s.selectedCatId }) {
            _state.update { it.copy(error = "Choose an active category and its product type.") }; return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                autosaveJob?.cancelAndJoin()
                saveMutex.withLock {
                    val draft = snapshot()
                    val f = draft.form
                    drafts.complete(draft) {
                        if (s.existingProduct == null) {
                            // New product — purchase price is optional; blank → 0L (sentinel for "not set")
                            when (val result = productRepository.createProduct(
                                f.categoryId, f.typeId, f.name.trim(), f.size.trim(), f.unit,
                                ProductForm.scaled(f.sellingPrice)!!,
                                ProductForm.scaled(f.purchasePrice) ?: 0L,
                                ProductForm.scaled(f.gstPercent)!!.toInt(),
                                (ProductForm.scaled(f.stock) ?: 0L) / 100.0,
                                (ProductForm.scaled(f.minimumStock) ?: 0L) / 100.0,
                                f.imagePath, f.aliases.trim(), piecesPerBox = f.piecesPerBox.toIntOrNull() ?: 0,
                                isPipe = f.isPipe, pipeLengthPricePaisa = ProductForm.scaled(f.pipeLengthPrice) ?: 0,
                                pipeFootPricePaisa = ProductForm.scaled(f.pipeFootPrice) ?: 0,
                                pipeFeetPerLength = f.pipeFeetPerLength.toIntOrNull() ?: 0)) {
                                is Result.Success -> result.data
                                is Result.Error   -> error(result.message)
                                else              -> error("Product could not be saved")
                            }
                        } else {
                            val current = productRepository.getProductById(s.existingProduct.id)
                                ?: error("Product no longer exists")
                            // Edit — blank purchase price → keep the existing stored value unchanged
                            val newPurchasePaisa = ProductForm.scaled(f.purchasePrice)
                                ?: current.purchasePricePaisa
                            when (val result = productRepository.updateProduct(current.copy(
                                categoryId         = f.categoryId,
                                typeId             = f.typeId,
                                name               = f.name.trim(),
                                size               = f.size.trim(),
                                unit               = f.unit,
                                sellingPricePaisa  = ProductForm.scaled(f.sellingPrice)!!,
                                purchasePricePaisa = newPurchasePaisa,
                                gstRateBp          = ProductForm.scaled(f.gstPercent)!!.toInt(),
                                minimumStock       = (ProductForm.scaled(f.minimumStock) ?: 0L) / 100.0,
                                imagePath          = f.imagePath,
                                searchAliases      = f.aliases.trim(),
                                piecesPerBox       = f.piecesPerBox.toIntOrNull() ?: 0,
                                isPipe = f.isPipe, pipeLengthPricePaisa = ProductForm.scaled(f.pipeLengthPrice) ?: 0,
                                pipeFootPricePaisa = ProductForm.scaled(f.pipeFootPrice) ?: 0,
                                pipeFeetPerLength = f.pipeFeetPerLength.toIntOrNull() ?: 0))) {
                                is Result.Success -> current.id
                                is Result.Error   -> error(result.message)
                                else              -> error("Product could not be saved")
                            }
                        }
                    }
                    finished = true
                }
                _state.update { it.copy(isSaved = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Product could not be saved") } }
            finally { _state.update { it.copy(isLoading = false) } }
        }
    }
}

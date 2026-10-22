package com.hardwareshop.pos.presentation.worker.billing

import androidx.room.withTransaction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.google.gson.Gson
import com.hardwareshop.pos.data.local.dao.SettingsDao
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// STATE
// ─────────────────────────────────────────────────────────────────────────────

data class BillingUiState(
    val cart: List<CartItem>            = emptyList(),
    val invalidPipeIds: Set<Long> = emptySet(),
    val removedLines: List<RemovedCartLine> = emptyList(),
    val customer: Customer?             = null,
    val customerName: String            = "",       // Required only for credit
    val customerPhone: String           = "",       // Optional customer phone
    val customerGstNumber: String       = "",       // Optional customer GSTIN
    val discountPaisa: Long             = 0L,
    val discountNote: String            = "",
    val gstEnabled: Boolean             = true,     // GST on by default for every bill
    val gstInclusive: Boolean           = true,     // true = tax embedded in price (default)
    val paymentMethod: PaymentMethod    = PaymentMethod.CASH,
    val paidPaisa: Long                 = 0L,          // Used for split/partial credit payment
    val splitCashPaisa: Long = 0,
    val splitUpiPaisa: Long = 0,
    val cartLabel: String = "",
    val notes: String                   = "",
    val sourceQuotationId: String?      = null,
    val sourceWebsiteOrderId: String? = null,
    val calculation: CalculationEngine.BillCalculation? = null,
    val isLoading: Boolean              = true,
    val draftMessage: String? = null,
    val recoveryFailed: Boolean = false,
    val savedBillId: Long?              = null,
    val printAfterSave: Boolean         = false,
    val error: String?                  = null,
    val previewOnly: Boolean = false,
    val openPreviewOnArrival: Boolean = false,
    val previewLines: List<ReceiptLayout.Line>? = null,
    val previewQrUri: String? = null,
    val previewNotice: String? = null,
    val discountPermissionDenied: Boolean = false,

    // ── Customer picker ──────────────────────────────────────────────────────
    val showCustomerPicker: Boolean     = false,
    val customerSearchQuery: String     = "",
    val customerSearchResults: List<Customer> = emptyList(),
    val isSearchingCustomer: Boolean    = false
) {
    /** The dialog and checkout use the same no-GST amount; never silently clamp a discount. */
    fun directBillCalculation(discount: Long = discountPaisa): CalculationEngine.BillCalculation? = try {
        CalculationEngine.calculateBill(cart.map {
            PipeBilling.calculation(it)
        }, discount, gstEnabled = false).takeIf { cart.isNotEmpty() && it.discountPaisa == discount }
    } catch (_: IllegalArgumentException) { null }
      catch (_: ArithmeticException) { null }
}

// ─────────────────────────────────────────────────────────────────────────────
// VIEWMODEL
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
@OptIn(kotlinx.coroutines.FlowPreview::class)
class BillingViewModel @Inject constructor(
    private val billingRepository: BillingRepository,
    private val customerRepository: CustomerRepository,
    private val productRepository: ProductRepository,
    private val settingsDao: SettingsDao,
    private val settingsRepository: SettingsRepository,
    private val db: com.hardwareshop.pos.data.local.database.AppDatabase,
    private val session: SessionHolder,
    // Keep the separate Orders cache lazy so opening ordinary billing does not
    // initialize another Room database or delay the existing checkout screen.
    private val websiteOrders: javax.inject.Provider<com.hardwareshop.pos.data.website.WebsiteOrdersRepository>? = null
) : ViewModel() {
    init { BillingLoadDiagnostics.event("billing-vm-created") }
    private val _state = MutableStateFlow(BillingUiState())
    val state: StateFlow<BillingUiState> = _state.asStateFlow()
    val canEditPrice: Boolean get() = session.current is UserSession.OwnerSession


    val variantProducts = productRepository.observeAllActiveProducts()
        .map { productRepository.canonicalBillingProducts(it) }.catch { emit(emptyList()) }
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())

    private val addOperations=mutableSetOf<String>()
    /** A submission token is consumed once; failure releases it for an explicit retry. */
    fun addSelection(token:String,items:List<CartItem>):Boolean {
        if(!editable() || token in addOperations || items.isEmpty()) return false
        return try {
            var cart=_state.value.cart
            items.forEach { added ->
                require(added.product.isActive && added.product.id!=0L)
                if(added.product.isPipe) require(added.pipeLengths>=0 && added.pipeFeet.isFinite() && added.pipeFeet>=0 &&
                    (added.pipeLengths>0 || added.pipeFeet>0)) { "Enter non-negative lengths and feet" }
                val existing=cart.firstOrNull { it.product.id==added.product.id }
                require(existing==null || CartVariants.compatible(existing,added.product)) { "Product configuration changed" }
                require(existing?.offcut==null && added.offcut==null) { "Keep an offcut separate from new pipe. Remove that pipe line first." }
                val sameBillRates=existing?.unitPricePaisa==added.unitPricePaisa &&
                    (!added.product.isPipe || existing?.pipeFootPricePaisa==added.pipeFootPricePaisa)
                require(existing==null || sameBillRates) { "This variant already uses another bill price; edit that cart line before combining" }
                val next=if(added.product.isPipe) PipeBilling.item(added.product,
                    Math.addExact(existing?.pipeLengths ?: 0,added.pipeLengths),
                    java.math.BigDecimal.valueOf(existing?.pipeFeet ?: 0.0).add(java.math.BigDecimal.valueOf(added.pipeFeet)).toDouble(),
                    existing?.unitPricePaisa ?: added.unitPricePaisa,existing?.pipeFootPricePaisa ?: added.pipeFootPricePaisa)
                else {
                    val qty=java.math.BigDecimal.valueOf(existing?.quantity ?: 0.0).add(java.math.BigDecimal.valueOf(added.quantity)).toDouble()
                    val price=existing?.unitPricePaisa ?: added.unitPricePaisa
                    require(added.quantity.isFinite() && added.quantity>0 && price>0)
                    CartItem(added.product,qty,price,CalculationEngine.itemTotalPaisa(qty,price),quantityEstimated=added.quantityEstimated || existing?.quantityEstimated==true)
                }
                require(canEditPrice || (next.unitPricePaisa==(if(next.product.isPipe) next.product.pipeLengthPricePaisa else next.product.sellingPricePaisa) &&
                    (!next.product.isPipe || next.pipeFootPricePaisa==next.product.pipeFootPricePaisa))) { "Owner authorization required for price overrides" }
                cart=cart.filterNot {it.product.id==next.product.id}+next
            }
            cart.map(PipeBilling::amount).fold(0L,Math::addExact)
            addOperations.add(token)
            _state.update { it.copy(error=null) }
            updateCartAndRecalculate(cart);true
        } catch(e:IllegalArgumentException){_state.update{it.copy(error=e.message)};false}
        catch(_:ArithmeticException){_state.update{it.copy(error="Amount is too large")};false}
    }

    fun undoRemoved(token:String) {
        if(!editable())return
        val removed=_state.value.removedLines.firstOrNull{it.token==token} ?: return
        if(_state.value.cart.any{it.product.id==removed.item.product.id}) {
            _state.update{it.copy(error="That item is already in the cart. Review it before restoring.")};return
        }
        val cart=_state.value.cart.toMutableList().apply{add(removed.index.coerceAtMost(size),removed.item)}
        _state.update{it.copy(error=null,removedLines=it.removedLines.filterNot{row->row.token==token})}
        updateCartAndRecalculate(cart)
    }
    fun expireRemoved(token:String)=_state.update{it.copy(removedLines=it.removedLines.filterNot{row->row.token==token})}

    private var customerSearchJob: Job? = null

    private val gson = Gson().also { BillingLoadDiagnostics.event("billing-gson-ready") }
    private var checkoutId = java.util.UUID.randomUUID().toString()
    private val cartKey = "cart:" + ((session.current as? UserSession.WorkerSession)?.workerId?.toString() ?: "owner")
    private val parkedPrefix = "$cartKey:parked:"
    val parkedBills = settingsDao.observeCarts().map { rows -> rows.filter { it.key.startsWith(parkedPrefix) }
        .sortedByDescending { it.updatedAt }.map { row ->
            val draft = runCatching { gson.fromJson(row.value, SavedCart::class.java) }.getOrNull()
            ParkedBill(row.key, draft?.label?.takeIf { it.isNotBlank() } ?: "Unfinished bill", draft?.items?.size ?: 0, row.updatedAt)
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val persistence = Mutex()
    private var ready = false
    private var lastPersistedCart: SavedCart? = null

    init {
        viewModelScope.launch {
            try {
                BillingLoadDiagnostics.event("draft-query-start")
                val draftValue=settingsDao.getSetting(cartKey)?.value
                BillingLoadDiagnostics.event("draft-query-return",if(draftValue==null)0 else 1)
                val saved = draftValue?.let { value ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { gson.fromJson(value, SavedCart::class.java) }
                }
                BillingLoadDiagnostics.event("draft-json-ready",saved?.items?.size ?: 0)
                if (saved != null && settingsDao.getSetting("checkout:${saved.checkoutId}") == null) {
                    checkoutId = saved.checkoutId
                    val restored = saved.items.mapNotNull { line ->
                        if (line.product.id < 0) {
                            ManualBillItem.validate(line)
                            return@mapNotNull line.copy(itemTotalPaisa = CalculationEngine.itemTotalPaisa(line.quantity, line.unitPricePaisa))
                        }
                        productRepository.getProductById(line.product.id)?.takeIf { it.isActive }?.let { product ->
                            require(line.product.isPipe || !product.isPipe) { "An unfinished pipe line uses the old quantity format. It has been kept; review or discard the old draft before new pipe billing." }
                            val price = if (canEditPrice && line.unitPricePaisa != line.product.sellingPricePaisa) line.unitPricePaisa else product.sellingPricePaisa
                            if (line.product.isPipe) PipeBilling.restore(line, product) else line.copy(product = product, unitPricePaisa = price,
                                itemTotalPaisa = CalculationEngine.itemTotalPaisa(line.quantity, price))
                        }
                    }
                    require(restored.size == saved.items.size) {
                        "Some products are unavailable. Ask the owner to reactivate them, then reopen billing."
                    }
                    _state.value = BillingUiState(cart = restored,
                        customer = saved.customerId?.let { customerRepository.getCustomerById(it) }?.takeIf { it.isActive },
                        customerName = saved.customerName.ifBlank { saved.customerId?.let { customerRepository.getCustomerById(it) }?.name.orEmpty() },
                        customerPhone = saved.customerPhone.ifBlank { saved.customerId?.let { customerRepository.getCustomerById(it) }?.phone.orEmpty() },
                        customerGstNumber = saved.customerGstNumber,
                        discountPaisa = saved.discountPaisa, discountNote = saved.discountNote,
                        gstEnabled = saved.gstEnabled, gstInclusive = saved.gstInclusive,
                        paymentMethod = saved.paymentMethod,
                        splitCashPaisa=saved.splitCashPaisa,splitUpiPaisa=saved.splitUpiPaisa,cartLabel=saved.label.orEmpty(),
                        paidPaisa = saved.paidPaisa, notes = saved.notes, sourceQuotationId = saved.sourceQuotationId, sourceWebsiteOrderId=saved.sourceWebsiteOrderId,
                        draftMessage = "Unfinished bill restored with current prices. Review quantities, prices and discount.")
                    recalculate()
                } else if (saved != null) {
                    settingsDao.deleteSetting(cartKey)
                    _state.update { it.copy(draftMessage = "Your previous bill was already saved. Find it in recent bills or Bill History.") }
                }
                ready = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(recoveryFailed = true, error = "Could not recover the unfinished bill. It has been kept. ${e.message} Open Cart only if you want to explicitly discard it.") } }
            finally { _state.update { it.copy(isLoading = false) }; BillingLoadDiagnostics.event("draft-recovery-ready") }
            state.debounce(350).collect { current ->
                if (ready && !current.isLoading) try { persistCart() } catch (e: CancellationException) { throw e }
                catch (_: Exception) { _state.update { it.copy(error = "Unfinished bill could not be saved. Keep this screen open and retry.") } }
            }
        }
    }

    private suspend fun persistCart() = persistence.withLock {
        if (!ready) return@withLock
        authorizeCart()
        val current = _state.value
        if (current.cart.isEmpty() || current.savedBillId != null) {
            settingsDao.deleteSetting(cartKey); lastPersistedCart=null
        } else {
            val draft=SavedCart(checkoutId,current.cart,current.customer?.id,current.discountPaisa,current.discountNote,
                current.gstEnabled,current.gstInclusive,current.customerName,current.customerPhone,
                current.customerGstNumber,current.paymentMethod,current.paidPaisa,current.notes,
                updatedAt=0,label=current.cartLabel,sourceQuotationId=current.sourceQuotationId,splitCashPaisa=current.splitCashPaisa,splitUpiPaisa=current.splitUpiPaisa,sourceWebsiteOrderId=current.sourceWebsiteOrderId)
            if(draft!=lastPersistedCart) {
                val json=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    gson.toJson(draft.copy(updatedAt=System.currentTimeMillis()))
                }
                settingsDao.upsertSetting(SettingsEntity(cartKey,json)); lastPersistedCart=draft
            }
        }
    }

    private fun snapshot(label: String? = null): SavedCart {
        val current = _state.value
        return SavedCart(checkoutId, current.cart, current.customer?.id, current.discountPaisa, current.discountNote,
            current.gstEnabled, current.gstInclusive, current.customerName, current.customerPhone,
            current.customerGstNumber, current.paymentMethod, current.paidPaisa, current.notes,
            label = label?.trim()?.take(80) ?: current.cartLabel.ifBlank { current.customerName.ifBlank { current.customer?.name.orEmpty() } },
            sourceQuotationId = current.sourceQuotationId,splitCashPaisa=current.splitCashPaisa,splitUpiPaisa=current.splitUpiPaisa,sourceWebsiteOrderId=current.sourceWebsiteOrderId)
    }

    private fun authorizeCart() {
        PermissionChecker.enforceLogin(session.current)
        val actor = "cart:" + ((session.current as? UserSession.WorkerSession)?.workerId?.toString() ?: "owner")
        check(actor == cartKey) { "Session changed. Reopen billing." }
    }

    fun renameCart(label:String) {
        if(!editable())return
        _state.update { it.copy(cartLabel=label.trim().take(80)) }
    }
    fun saveDraft() {
        if(!editable())return
        _state.update { it.copy(isLoading=true) }
        viewModelScope.launch {
            try { persistCart(); _state.update { it.copy(draftMessage="Draft saved on this phone.") } }
            catch(e:CancellationException){throw e}
            catch(e:Exception){_state.update { it.copy(error="Could not save draft: ${e.message}") }}
            finally {_state.update { it.copy(isLoading=false) }}
        }
    }
    fun renameParked(key:String,label:String) {
        if(!editable())return
        _state.update { it.copy(isLoading=true) }
        viewModelScope.launch {
            try { persistence.withLock { authorizeCart();require(key.startsWith(parkedPrefix))
                db.withTransaction {
                    val row=requireNotNull(settingsDao.getSetting(key)) { "Draft unavailable" }
                    val draft=gson.fromJson(row.value,SavedCart::class.java)
                    settingsDao.upsertSetting(row.copy(value=gson.toJson(draft.copy(label=label.trim().take(80)))))
                }
            } } catch(e:CancellationException){throw e}
            catch(e:Exception){_state.update { it.copy(error=e.message) }}
            finally {_state.update { it.copy(isLoading=false) }}
        }
    }

    fun parkCurrent(label: String) {
        if (!editable() || _state.value.cart.isEmpty()) return
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                persistence.withLock {
                    authorizeCart()
                    val draft = snapshot(label)
                    db.withTransaction {
                        settingsDao.upsertSetting(SettingsEntity(parkedPrefix + draft.checkoutId, gson.toJson(draft)))
                        settingsDao.deleteSetting(cartKey)
                    }
                    checkoutId = java.util.UUID.randomUUID().toString()
                    _state.value = BillingUiState(isLoading = true, draftMessage = "Bill parked. Start another bill or open Parked Bills.")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = "Could not park bill: ${e.message}") } }
            finally { _state.update { it.copy(isLoading = false) } }
        }
    }

    fun resumeParked(key: String) {
        if (!editable()) return
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                persistence.withLock {
                    authorizeCart(); require(key.startsWith(parkedPrefix)) { "This parked bill belongs to another user" }
                    val restored = db.withTransaction {
                        val row = requireNotNull(settingsDao.getSetting(key)) { "Parked bill is no longer available" }
                        val draft = gson.fromJson(row.value, SavedCart::class.java)
                        require(settingsDao.getSetting("checkout:${draft.checkoutId}") == null) { "This bill was already saved. Discard its parked copy and open Bill History." }
                        val items = draft.items.mapNotNull { line ->
                            if (line.product.id < 0) {
                                ManualBillItem.validate(line)
                                return@mapNotNull line.copy(itemTotalPaisa = CalculationEngine.itemTotalPaisa(line.quantity, line.unitPricePaisa))
                            }
                            productRepository.getProductById(line.product.id)?.takeIf { it.isActive }?.let {
                                require(line.product.isPipe || !it.isPipe) { "Parked pipe bill uses the old quantity format. It has been kept." }
                                val price = if (canEditPrice && line.unitPricePaisa != line.product.sellingPricePaisa) line.unitPricePaisa else it.sellingPricePaisa
                                if (line.product.isPipe) PipeBilling.restore(line, it) else line.copy(product = it, unitPricePaisa = price,
                                    itemTotalPaisa = CalculationEngine.itemTotalPaisa(line.quantity, price))
                            }
                        }
                        require(items.size == draft.items.size) {
                            "Some products are unavailable. Ask the owner to reactivate them, then retry."
                        }
                        val customer = draft.customerId?.let { customerRepository.getCustomerById(it) }?.takeIf { it.isActive }
                        val hadCurrentCart = _state.value.cart.isNotEmpty()
                        if (hadCurrentCart) {
                            val active = snapshot()
                            settingsDao.upsertSetting(SettingsEntity(parkedPrefix + active.checkoutId, gson.toJson(active)))
                        }
                        settingsDao.upsertSetting(SettingsEntity(cartKey, gson.toJson(draft.copy(items = items, customerId = customer?.id))))
                        settingsDao.deleteSetting(key)
                        draft to BillingUiState(cart = items, customer = customer,
                            customerName = draft.customerName.ifBlank { customer?.name.orEmpty() },
                            customerPhone = draft.customerPhone.ifBlank { customer?.phone.orEmpty() },
                            customerGstNumber = draft.customerGstNumber.ifBlank { customer?.gstNumber.orEmpty() },
                            discountPaisa = draft.discountPaisa,
                            discountNote = draft.discountNote,
                            gstEnabled = draft.gstEnabled, gstInclusive = draft.gstInclusive,
                            paymentMethod = draft.paymentMethod,
                            splitCashPaisa=draft.splitCashPaisa,splitUpiPaisa=draft.splitUpiPaisa,cartLabel=draft.label.orEmpty(),
                            paidPaisa = draft.paidPaisa, notes = draft.notes, sourceQuotationId = draft.sourceQuotationId, sourceWebsiteOrderId=draft.sourceWebsiteOrderId, isLoading = true,
                            draftMessage = "Parked bill resumed at current prices. Review quantities, prices and discount." +
                                if (hadCurrentCart) " Your previous cart was parked." else "")
                    }
                    checkoutId = restored.first.checkoutId
                    _state.value = restored.second
                    recalculate()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = "Could not resume parked bill; it was kept: ${e.message}") } }
            finally { _state.update { it.copy(isLoading = false) } }
        }
    }

    fun discardParked(key: String) {
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try { persistence.withLock { authorizeCart(); require(key.startsWith(parkedPrefix)); settingsDao.deleteSetting(key) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message) } }
            finally { _state.update { it.copy(isLoading = false) } }
        }
    }

    fun holdBill(onHeld: () -> Unit) {
        if (_state.value.isLoading) return
        if (!ready) { onHeld(); return }
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try { persistCart(); onHeld() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = "Could not save unfinished bill: ${e.message}") } }
            finally { _state.update { it.copy(isLoading = false) } }
        }
    }

    private fun editable() = ready && !_state.value.isLoading && _state.value.savedBillId == null
    private fun invalidAmount(block: () -> Unit) {
        if (!editable()) return
        try { block() } catch (_: IllegalArgumentException) { _state.update { it.copy(error = "Enter a valid quantity with at most 2 decimals") } }
        catch (_: ArithmeticException) { _state.update { it.copy(error = "Quantity or amount is too large, or has more than 2 decimals") } }
    }

    // ── Cart operations ───────────────────────────────────────────────────────

    fun addManualItem(name: String, price: String, onAdded: () -> Unit) {
        if (!editable()) return
        val item = try {
            ManualBillItem.create(name, price, _state.value.cart.map { it.product.id })
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = e.message ?: "Check the item name and price") }
            return
        }
        _state.update { it.copy(error = null) }
        updateCartAndRecalculate(_state.value.cart + item)
        onAdded()
    }

    fun editManualItem(productId: Long, name: String, price: String): Boolean {
        if (!editable()) return false
        return try {
            val current = _state.value.cart.single { it.product.id == productId }
            require(productId < 0)
            val replacement = ManualBillItem.create(name, price, emptyList())
            val total = CalculationEngine.itemTotalPaisa(current.quantity, replacement.unitPricePaisa)
            updateCartAndRecalculate(_state.value.cart.map {
                if (it.product.id == productId) current.copy(
                    product = replacement.product.copy(id = productId),
                    unitPricePaisa = replacement.unitPricePaisa, itemTotalPaisa = total
                ) else it
            })
            _state.update { it.copy(error = null) }
            true
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = e.message ?: "Check the name and price") }; false
        } catch (e: ArithmeticException) {
            _state.update { it.copy(error = "Amount is too large") }; false
        }
    }

    fun editCartVariant(sourceId: Long, targetId: Long, quantity: String, lengths: String, feet: String, onApplied: () -> Unit) {
        if (!editable()) return
        _state.update { it.copy(isLoading=true,error=null) }
        viewModelScope.launch {
            try {
                val target=requireNotNull(productRepository.getProductById(targetId)) { "This size is unavailable" }
                val cart=CartVariants.edit(_state.value.cart,sourceId,target,
                    if(target.isPipe) 0.0 else requireNotNull(quantity.toDoubleOrNull()) { "Check quantity" },
                    if(lengths.isBlank()) 0L else requireNotNull(lengths.toLongOrNull()) { "Check lengths" },
                    if(feet.isBlank()) 0.0 else requireNotNull(feet.toDoubleOrNull()) { "Check feet" })
                updateCartAndRecalculate(cart)
                _state.update { it.copy(invalidPipeIds=it.invalidPipeIds-sourceId-targetId,previewLines=null,previewQrUri=null) }
                onApplied()
            } catch(e: CancellationException) { throw e }
            catch(e: IllegalArgumentException) { _state.update { it.copy(error=e.message ?: "Check the selected size and quantity") } }
            catch(_: ArithmeticException) { _state.update { it.copy(error="Quantity or amount is too large") } }
            catch(_:NoSuchElementException) { _state.update { it.copy(error="This cart item was removed. Close the editor and choose another item.") } }
            finally { _state.update { it.copy(isLoading=false) } }
        }
    }

    /** Calibration records belong to a specific existing variant and billing unit. */
    suspend fun loadWeightCalibration(product:Product):String {
        PermissionChecker.enforceLogin(session.current)
        val current=requireNotNull(productRepository.getProductById(product.id)) { "Product is unavailable" }
        require(current.isActive && current.unit==product.unit && current.name==product.name && current.size==product.size) { "Variant changed. Reopen the calculator for its current calibration." }
        val row=settingsDao.getSetting("weight-calibration:${product.id}") ?: return ""
        val record=gson.fromJson(row.value,WeightCalibration::class.java)
        require(record.unit==product.unit && record.name==product.name && record.size==product.size) { "Variant changed. Ask the owner to measure its calibration again." }
        WeightCalculator.decimal(record.gramsPerUnit,true)
        return record.gramsPerUnit
    }
    suspend fun saveWeightCalibration(product:Product,value:String) {
        PermissionChecker.enforceOwner(session.current)
        require(product.id>0 && !product.isPipe && WeightCalculator.modeForUnit(product.unit)!=null) { "Unsupported billing unit" }
        val number=WeightCalculator.decimal(value,true)
        val current=requireNotNull(productRepository.getProductById(product.id)) { "Product is unavailable" }
        require(current.isActive && current.unit==product.unit && current.name==product.name && current.size==product.size) { "Product changed. Reopen the calculator." }
        settingsRepository.set("weight-calibration:${product.id}",gson.toJson(WeightCalibration(product.unit,product.name,product.size,number.toPlainString())))
    }
    private data class WeightCalibration(val unit:String,val name:String,val size:String,val gramsPerUnit:String)
    fun applyWeightQuantity(productId:Long,text:String):Boolean {
        if(!editable())return false
        return try {
            val item=requireNotNull(_state.value.cart.firstOrNull { it.product.id==productId }) { "This cart item was removed" }
            require(!item.product.isPipe && WeightCalculator.modeForUnit(item.product.unit)!=null) { "Unsupported billing unit" }
            val value=WeightCalculator.decimal(text,true)
            require(value.scale()<=2) { "Billing quantities support up to two decimals" }
            if(WeightCalculator.modeForUnit(item.product.unit)==WeightCalculator.Mode.PIECES)
                require(value.stripTrailingZeros().scale()<=0) { "Enter a whole number of pieces" }
            val quantity=value.toDouble()
            val total=CalculationEngine.itemTotalPaisa(quantity,item.unitPricePaisa)
            updateCartAndRecalculate(_state.value.cart.map {if(it.product.id==productId)it.copy(quantity=quantity,itemTotalPaisa=total,quantityEstimated=true) else it})
            _state.update {it.copy(error=null)}
            true
        }catch(e:IllegalArgumentException){_state.update {it.copy(error=e.message ?: "Check the quantity")};false}
        catch(_:ArithmeticException){_state.update {it.copy(error="Quantity is too large")};false}
    }

    fun addToCart(product: Product, quantity: Double) = invalidAmount {
        require(!product.isPipe) { "Use Length No. and Ft No." }
        require(quantity > 0 && product.isActive)
        val existing = _state.value.cart.indexOfFirst { it.product.id == product.id }
        val newCart = _state.value.cart.toMutableList()

        if (existing >= 0) {
            // Keep a bill override, but never combine changed product configurations.
            val current = newCart[existing]
            if(!CartVariants.compatible(current,product)) {
                _state.update { it.copy(error="Product price, unit or tax changed. Review this cart item first.") }
                return@invalidAmount
            }
            val newQty = java.math.BigDecimal.valueOf(current.quantity).add(java.math.BigDecimal.valueOf(quantity)).toDouble()
            newCart[existing] = current.copy(
                quantity       = newQty,
                itemTotalPaisa = CalculationEngine.itemTotalPaisa(newQty, current.unitPricePaisa)
            )
        } else {
            val total = CalculationEngine.itemTotalPaisa(quantity, product.sellingPricePaisa)
            newCart.add(
                CartItem(
                    product        = product,
                    quantity       = quantity,
                    unitPricePaisa = product.sellingPricePaisa,
                    itemTotalPaisa = total
                )
            )
        }
        updateCartAndRecalculate(newCart)
    }

    fun addToWantList(product:Product) {
        if(!editable())return
        viewModelScope.launch {
            try {
                val added=WantListRepository(db,session).quickAdd(product)
                _state.update {it.copy(draftMessage=if(added)"Added to Want List — review its quantity in Want List" else "This product is already pending in Want List",error=null)}
            }catch(e:CancellationException){throw e}
            catch(e:Exception){_state.update {it.copy(error=e.message ?: "Could not add to Want List")}}
        }
    }

    fun useOffcut(product:Product,record:PipeOffcutRepository.Record):Boolean {
        if(!editable())return false
        return try {
            require(record.available && record.productId==product.id && product.isPipe) { "Choose an available matching offcut" }
            require(_state.value.cart.none {it.product.id==product.id}) { "Remove the existing pipe line before choosing a registered offcut." }
            val line=PipeBilling.item(product,0,record.feet/100.0).copy(offcut=OffcutReference(record.id,record.revision,record.feet))
            updateCartAndRecalculate(_state.value.cart+line);true
        }catch(e:IllegalArgumentException){_state.update {it.copy(error=e.message)};false}
        catch(_:ArithmeticException){_state.update {it.copy(error="Amount is too large")};false}
    }

    fun updatePipe(product: Product, lengths: String, feet: String) {
        if (!editable()) return
        try {
            val l = if (lengths.isBlank()) 0L else requireNotNull(lengths.toLongOrNull())
            val f = if (feet.isBlank()) 0.0 else requireNotNull(feet.toDoubleOrNull())
            val cart = PipeBilling.replace(_state.value.cart, product, l, f)
            cart.map(PipeBilling::amount).fold(0L, Math::addExact)
            updateCartAndRecalculate(cart)
            _state.update { it.copy(error = null, invalidPipeIds = it.invalidPipeIds - product.id) }
        } catch (_: IllegalArgumentException) {
            _state.update { it.copy(error = "Check Length No. and Ft No.", invalidPipeIds = it.invalidPipeIds + product.id) }
        } catch (_: ArithmeticException) {
            _state.update { it.copy(error = "Pipe quantity or amount is too large", invalidPipeIds = it.invalidPipeIds + product.id) }
        }
    }

    fun addPipe(product: Product, lengths: String, feet: String): Boolean {
        if(!editable()) return false
        return try {
        val l=if(lengths.isBlank()) 0L else requireNotNull(lengths.toLongOrNull())
        val f=if(feet.isBlank()) 0.0 else requireNotNull(feet.toDoubleOrNull())
        require(l>=0 && f.isFinite() && f>=0 && (l>0 || f>0))
        val current=_state.value.cart.firstOrNull { it.product.id==product.id }
        require(current?.offcut==null) { "This line uses a registered offcut. Edit its feet directly or remove it first." }
        require(current==null || CartVariants.compatible(current,product)) { "Pipe configuration changed" }
        val next=PipeBilling.item(product,Math.addExact(current?.pipeLengths ?: 0,l),
            java.math.BigDecimal.valueOf(current?.pipeFeet ?: 0.0).add(java.math.BigDecimal.valueOf(f)).toDouble(),
            current?.unitPricePaisa ?: product.pipeLengthPricePaisa,current?.pipeFootPricePaisa ?: product.pipeFootPricePaisa)
        val cart=_state.value.cart.filterNot { it.product.id==product.id }+next
        cart.map(PipeBilling::amount).fold(0L,Math::addExact)
        updateCartAndRecalculate(cart)
        true
        } catch(e: IllegalArgumentException) {
            _state.update { it.copy(error=e.message ?: "Check lengths and feet") }; false
        } catch(_: ArithmeticException) {
            _state.update { it.copy(error="Pipe quantity or amount is too large") }; false
        }
    }

    fun updateQuantity(productId: Long, newQuantity: Double) = invalidAmount {
        require(_state.value.cart.none { it.product.id == productId && it.product.isPipe }) { "Use Length No. and Ft No." }
        if (newQuantity <= 0) {
            removeFromCart(productId)
            return@invalidAmount
        }
        val newCart = _state.value.cart.map { item ->
            if (item.product.id == productId)
                item.copy(
                    quantity       = newQuantity,
                    itemTotalPaisa = CalculationEngine.itemTotalPaisa(newQuantity, item.unitPricePaisa)
                )
            else item
        }
        updateCartAndRecalculate(newCart)
    }

    fun removeFromCart(productId: Long) {
        if (!editable()) return
        val index=_state.value.cart.indexOfFirst{it.product.id==productId}
        if(index<0)return
        var originalIndex=index
        _state.value.removedLines.sortedBy{it.index}.forEach { if(it.index<=originalIndex)originalIndex++ }
        val removed=RemovedCartLine(java.util.UUID.randomUUID().toString(),_state.value.cart[index],originalIndex)
        _state.update { it.copy(invalidPipeIds = it.invalidPipeIds - productId,removedLines=it.removedLines+removed) }
        val newCart = _state.value.cart.filter { it.product.id != productId }
        updateCartAndRecalculate(newCart)
    }

    fun setItemPrice(productId: Long, text: String) {
        if (!editable()) return
        if (!canEditPrice) { _state.update { it.copy(error = "Only the owner can edit billing prices") }; return }
        if (_state.value.cart.any { it.product.id == productId && it.product.isPipe }) return
        val price = CalculationEngine.parseRupeesToPaisa(text)
        if (price == null || price <= 0) { _state.update { it.copy(error = "Enter a selling price greater than zero") }; return }
        try {
            updateCartAndRecalculate(_state.value.cart.map {
                if (it.product.id == productId) it.copy(unitPricePaisa = price,
                    itemTotalPaisa = CalculationEngine.itemTotalPaisa(it.quantity, price)) else it
            })
        } catch (_: Exception) { _state.update { it.copy(error = "Price is too large") } }
    }

    fun setPipePrices(productId: Long, lengthText: String, footText: String): Boolean {
        if (!editable() || !canEditPrice) return false
        val lengthPrice = CalculationEngine.parseRupeesToPaisa(lengthText)
        val footPrice = CalculationEngine.parseRupeesToPaisa(footText)
        if (lengthPrice == null || footPrice == null || lengthPrice <= 0 || footPrice <= 0) {
            _state.update { it.copy(error = "Enter both prices greater than zero with at most 2 decimals") }
            return false
        }
        return try {
            val cart = _state.value.cart.map { item ->
                if (item.product.id == productId && item.product.isPipe)
                    PipeBilling.item(item.product, item.pipeLengths, item.pipeFeet, lengthPrice, footPrice)
                else item
            }
            cart.map(PipeBilling::amount).fold(0L, Math::addExact)
            updateCartAndRecalculate(cart)
            _state.update { it.copy(error = null) }
            true
        } catch (_: IllegalArgumentException) {
            _state.update { it.copy(error = "Check pipe prices") }; false
        } catch (_: ArithmeticException) {
            _state.update { it.copy(error = "Pipe price or amount is too large") }; false
        }
    }

    /** Opening a preview never submits a bill or acknowledges payment. */
    fun prepareCartPreview(method: PaymentMethod? = null) {
        if (!editable() || _state.value.cart.isEmpty() || _state.value.invalidPipeIds.isNotEmpty()) return
        if (method != null) setPaymentMethod(method)
        _state.update { it.copy(previewOnly = method == null, openPreviewOnArrival = method!=PaymentMethod.SPLIT,
            previewLines = null, previewQrUri = null, previewNotice = null) }
    }

    fun consumePreviewRequest() {
        _state.update { it.copy(openPreviewOnArrival = false) }
        previewBill()
    }

    fun importWebsiteOrder(order: com.hardwareshop.pos.data.website.WebsiteHandoff, keepPrevious:Boolean, done:()->Unit) {
        if(!editable())return
        _state.update { it.copy(isLoading=true,error=null) }
        viewModelScope.launch {
            try { persistence.withLock {
                authorizeCart()
                val old=snapshot()
                val websiteCheckout="web-"+order.row.id
                db.withTransaction {
                    if(old.items.isNotEmpty()&&keepPrevious)settingsDao.upsertSetting(SettingsEntity(parkedPrefix+old.checkoutId,gson.toJson(old)))
                    val next=SavedCart(websiteCheckout,order.items,null,0,"",true,true,order.customerName,order.phone,"",PaymentMethod.CASH,0,order.notes,
                        label="Website order "+order.row.id,sourceWebsiteOrderId=order.row.id)
                    settingsDao.upsertSetting(SettingsEntity(cartKey,gson.toJson(next)))
                }
                checkoutId=websiteCheckout
                _state.value=BillingUiState(cart=order.items,customerName=order.customerName,customerPhone=order.phone,notes=order.notes,
                    sourceWebsiteOrderId=order.row.id,isLoading=false,draftMessage="Website order loaded. Availability was checked manually; review current prices, GST and payment.")
                lastPersistedCart=null;recalculate();done()
            }}catch(e:CancellationException){throw e}catch(e:Exception){_state.update{it.copy(error=e.message)}}
            finally{_state.update{it.copy(isLoading=false)}}
        }
    }

    fun clearCart() {
        if (_state.value.isLoading) return
        ready = true
        checkoutId = java.util.UUID.randomUUID().toString()
        _state.update { BillingUiState(isLoading = false) }
        viewModelScope.launch { persistCart() }
    }

    /** Imports a reviewed quotation as an unfinished bill; checkout alone changes stock. */
    fun importQuotation(record: QuotationRepository.Record, items: List<CartItem>, onImported: () -> Unit) {
        if (!editable()) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                PermissionChecker.enforceOwner(session.current)
                require(items.isNotEmpty() && items.map { it.product.id }.distinct().size == items.size) { "Check quotation items" }
                val subtotal = items.map(PipeBilling::amount).fold(0L, Math::addExact)
                require(record.discountPaisa in 0..subtotal) { "Quoted discount exceeds the current subtotal. Review the quotation." }
                persistence.withLock {
                    authorizeCart()
                    require(settingsDao.getSetting("quotation:${record.id}") != null) { "Quotation is no longer available" }
                    require(settingsDao.getSetting("quotation-bill:${record.id}") == null) { "This quotation already has a bill" }
                    checkoutId = java.util.UUID.randomUUID().toString()
                    _state.value = BillingUiState(cart = items.toList(), customerName = record.customerName,
                        customerPhone = record.customerPhone, discountPaisa = record.discountPaisa,
                        discountNote = "From quotation ${record.number}", gstEnabled = record.gstEnabled,
                        gstInclusive = record.gstInclusive, notes = "Quotation ${record.number}\n${record.notes}".trim(),
                        sourceQuotationId = record.id, isLoading = true,
                        draftMessage = "Quotation loaded at current rates. Review the items and payment before saving.")
                    recalculate()
                }
                // Persist before navigating; a failed draft write must remain visible.
                persistCart()
                _state.update { it.copy(isLoading = false) }
                onImported()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(isLoading = false, error = e.message ?: "Could not load quotation") } }
        }
    }

    // ── Billing options ───────────────────────────────────────────────────────

    fun setCustomer(customer: Customer?) {
        if (!editable()) return
        _state.update { it.copy(customer = customer) }
    }

    // ── Customer picker (uses customerRepository to search by name/phone) ─────

    fun openCustomerPicker() {
        _state.update { it.copy(showCustomerPicker = true) }
    }

    fun dismissCustomerPicker() {
        customerSearchJob?.cancel()
        _state.update { it.copy(
            showCustomerPicker     = false,
            customerSearchQuery    = "",
            customerSearchResults  = emptyList(),
            isSearchingCustomer    = false
        )}
    }

    fun onCustomerSearchQueryChange(query: String) {
        _state.update { it.copy(customerSearchQuery = query) }
        customerSearchJob?.cancel()

        if (query.isBlank()) {
            _state.update { it.copy(customerSearchResults = emptyList(), isSearchingCustomer = false) }
            return
        }

        customerSearchJob = viewModelScope.launch {
            _state.update { it.copy(isSearchingCustomer = true) }
            // NOTE: assumes CustomerRepository exposes a suspend fun
            // `searchCustomers(query: String): List<Customer>` that throws on failure
            // (unlike billingRepository.createBill, which wraps its result in Result<T>).
            // If your CustomerRepository actually returns Result<List<Customer>>, or a
            // Flow, or a different method name entirely, adjust this block to match —
            // share CustomerRepository.kt with me and I'll fix it precisely.
            try {
                val results = customerRepository.searchCustomers(query)
                _state.update { it.copy(
                    customerSearchResults = results,
                    isSearchingCustomer    = false
                )}
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(
                    isSearchingCustomer = false,
                    error                = e.message ?: "Failed to search customers"
                )}
            }
        }
    }

    fun selectCustomer(customer: Customer) {
        setCustomer(customer)
        _state.update { it.copy(
            customerName      = customer.name,
            customerPhone     = customer.phone,
            customerGstNumber = customer.gstNumber
        )}
        dismissCustomerPicker()
    }

    fun setCustomerName(name: String) {
        if (!editable()) return
        _state.update { it.copy(customerName = name) }
    }

    fun setCustomerPhone(phone: String) {
        if (!editable()) return
        _state.update { it.copy(customerPhone = phone) }
    }

    fun createAndSelectCustomer(name: String, phone: String) {
        if (!editable()) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                when (val result = customerRepository.createCustomer(name, phone, "")) {
                    is Result.Success -> {
                        val customer = customerRepository.getCustomerById(result.data)
                        _state.update { it.copy(
                            customer          = customer,
                            customerName      = name,
                            customerPhone     = phone,
                            showCustomerPicker = false
                        ) }
                    }
                    is Result.Error -> _state.update { it.copy(error = result.message) }
                    else -> Unit
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Could not add customer") } }
            finally { _state.update { it.copy(isLoading = false) } }
        }
    }

    fun clearSelectedCustomer() {
        setCustomer(null)
        _state.update { it.copy(customerName = "", customerPhone = "", customerGstNumber = "") }
    }

    fun setDiscount(discountPaisa: Long, note: String) {
        if (!editable()) return
        if (discountPaisa < 0) { _state.update { it.copy(error = "Invalid discount") }; return }
        // Business-layer permission check (not just UI hiding)
        val check = PermissionChecker.workerCanApplyDiscount(session.current, discountPaisa)
        if (check is PermissionChecker.CheckResult.Denied) {
            _state.update { it.copy(
                discountPermissionDenied = true,
                error = check.reason
            )}
            return
        }
        _state.update { it.copy(
            discountPaisa            = discountPaisa,
            discountNote             = note,
            discountPermissionDenied = false,
            error                    = null
        )}
        recalculate()
    }


    fun setGstEnabled(enabled: Boolean) {
        if (!editable()) return
        _state.update { it.copy(gstEnabled = enabled) }
        recalculate()
    }

    fun setGstInclusive(inclusive: Boolean) {
        if (!editable()) return
        _state.update { it.copy(gstInclusive = inclusive) }
        recalculate()
    }

    fun setCustomerGstNumber(gstin: String) {
        if (!editable()) return
        _state.update { it.copy(customerGstNumber = gstin.trim().take(15).uppercase()) }
    }

    fun setPaymentMethod(method: PaymentMethod) {
        if (!editable()) return
        _state.update { it.copy(paymentMethod = method, previewOnly = false) }
        // For non-credit payment, paid = total
        if (method != PaymentMethod.CREDIT) {
            val total = _state.value.calculation?.totalPaisa ?: 0L
            _state.update { it.copy(paidPaisa = total) }
        }
    }

    fun setSplitAmounts(cash:Long,upi:Long) {
        if(!editable())return
        _state.update { it.copy(splitCashPaisa=cash,splitUpiPaisa=upi,error=null) }
    }
    private fun splitError(s:BillingUiState):String? = if(s.paymentMethod!=PaymentMethod.SPLIT)null else
        runCatching { PaymentSplit(s.splitCashPaisa,s.splitUpiPaisa).validate(requireNotNull(s.calculation).totalPaisa) }.exceptionOrNull()?.message

    fun setPaidAmount(paisa: Long) {
        if (!editable()) return
        _state.update { it.copy(paidPaisa = paisa) }
    }


    fun closePreview() = _state.update { it.copy(previewLines = null, previewQrUri = null) }

    fun previewBill() {
        if (!editable()) return
        val s = _state.value
        val calc = s.calculation ?: return
        if (s.cart.isEmpty()) return
        if(!s.previewOnly) splitError(s)?.let { error->_state.update { it.copy(error=error) };return }
        val name = s.customerName.ifBlank { s.customer?.name.orEmpty() }.trim()
        val phone = s.customerPhone.ifBlank { s.customer?.phone.orEmpty() }.trim()
        if (!s.previewOnly && s.paymentMethod == PaymentMethod.CREDIT && (s.customer == null || name.isBlank() || phone.isBlank())) {
            _state.update { it.copy(error = "Select a customer with name and phone for credit") }; return
        }
        if (!s.previewOnly && (s.paidPaisa < 0 || (s.paymentMethod == PaymentMethod.CREDIT && s.paidPaisa > calc.totalPaisa))) {
            _state.update { it.copy(error = "Paid amount must be between zero and the bill total") }; return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val shop = settingsRepository.getShopInfo()
                val paid = if (s.paymentMethod == PaymentMethod.CREDIT) s.paidPaisa else calc.totalPaisa
                val bill = Bill(0, "PREVIEW", s.customer, (session.current as? UserSession.WorkerSession)?.workerName ?: "Owner",
                    s.cart.mapIndexed { i, item -> BillItem(0, item.product.id, item.receiptName, item.product.size,
                        item.product.unit, item.quantity, item.unitPricePaisa, item.product.gstRateBp,
                        PipeBilling.amount(item), calc.itemGstPaisa[i], item.product.isPipe, item.pipeLengths, item.pipeFeet, item.pipeFootPricePaisa) },
                    calc.subtotalPaisa, calc.discountPaisa, s.gstEnabled, calc.gstAmountPaisa, calc.totalPaisa,
                    paid, calc.totalPaisa - paid, s.paymentMethod, BillStatus.ACTIVE, "", s.notes, false,
                    System.currentTimeMillis(), name, phone,
                    s.customer?.gstNumber?.takeIf { it.isNotBlank() } ?: s.customerGstNumber, s.discountNote,
                    payments=if(s.paymentMethod==PaymentMethod.SPLIT)listOf(BillPayment(PaymentMethod.CASH,s.splitCashPaisa),BillPayment(PaymentMethod.UPI,s.splitUpiPaisa)) else emptyList())
                var notice: String? = null
                val qr = if (!s.previewOnly && (s.paymentMethod==PaymentMethod.UPI || s.paymentMethod==PaymentMethod.SPLIT && s.splitUpiPaisa>0)) try {
                    UpiPaymentQr.uri(shop.upiId, shop.upiName, if(s.paymentMethod==PaymentMethod.SPLIT)s.splitUpiPaisa else calc.totalPaisa)
                } catch (e: IllegalArgumentException) { notice = e.message; null } else null
                _state.update { it.copy(previewLines = ReceiptLayout.build(bill,
                    ReceiptLayout.Shop(shop.name, shop.phone, shop.address, shop.gstNumber,shop.receiptFooter), showPayment = !s.previewOnly),
                    previewQrUri = qr, previewNotice = notice) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = "Cannot preview bill: ${e.message}") } }
            finally { _state.update { it.copy(isLoading = false) } }
        }
    }

    // ── Save bill ─────────────────────────────────────────────────────────────

    fun saveBill(printAfterSave: Boolean = false) {
        if (_state.value.previewOnly) return
        submitBill(_state.value, printAfterSave)
    }

    fun directDiscountError(discountPaisa: Long): String? =
        (PermissionChecker.workerCanApplyDiscount(session.current, discountPaisa) as? PermissionChecker.CheckResult.Denied)?.reason

    /** The payment choice is the cashier's acknowledgement, never a QR payment signal. */
    fun saveDirectBill(method: PaymentMethod, discountPaisa: Long = _state.value.discountPaisa) {
        val current = _state.value
        if (current.isLoading || current.savedBillId != null) return
        if (method != PaymentMethod.CASH && method != PaymentMethod.UPI) {
            _state.update { it.copy(error = "Direct bill supports Cash or UPI only") }
            return
        }
        if (current.invalidPipeIds.isNotEmpty()) { _state.update { it.copy(error = "Correct pipe quantities before checkout") }; return }
        val calculation = current.directBillCalculation(discountPaisa)
        if (calculation == null) {
            _state.update { it.copy(error = "Cart is empty or discount exceeds the item total") }
            return
        }
        val permission = PermissionChecker.workerCanApplyDiscount(session.current, discountPaisa)
        if (permission is PermissionChecker.CheckResult.Denied) {
            _state.update { it.copy(error = permission.reason) }
            return
        }
        submitBill(current.copy(customer = null, customerName = "", customerPhone = "", customerGstNumber = "",
            discountPaisa = discountPaisa,
            discountNote = if (discountPaisa == current.discountPaisa) current.discountNote else "Direct bill discount",
            discountPermissionDenied = false,
            gstEnabled = false, paymentMethod = method, paidPaisa = calculation.totalPaisa, notes = "",
            calculation = calculation, previewLines = null, previewQrUri = null), printAfterSave = true)
    }

    private fun submitBill(current: BillingUiState, printAfterSave: Boolean) {
        // Guard: prevent double-tap / repeated submission
        if (current.isLoading || current.discountPermissionDenied) return
        if (current.invalidPipeIds.isNotEmpty()) {
            _state.update { it.copy(error = "Correct the pipe quantities before checkout") }; return
        }
        // Guard: bill already saved in this session
        if (current.savedBillId != null) return
        if (current.cart.isEmpty()) {
            _state.update { it.copy(error = "Cart is empty") }
            return
        }
        splitError(current)?.let { error->_state.update { it.copy(error=error) };return }
        val finalCustomerName = current.customerName.ifBlank { current.customer?.name.orEmpty() }.trim()
        if (current.paymentMethod == PaymentMethod.CREDIT &&
            (finalCustomerName.isBlank() || current.customerPhone.ifBlank { current.customer?.phone.orEmpty() }.isBlank() || current.customer == null)) {
            _state.update { it.copy(error = "Select a customer with name and phone for credit") }
            return
        }
        val finalCustomerPhone = current.customerPhone.ifBlank { current.customer?.phone.orEmpty() }.trim()
        val finalCustomerGst = (current.customer?.gstNumber?.takeIf { it.isNotBlank() } ?: current.customerGstNumber).trim()

        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                persistCart()
                val saveExisting: suspend (String) -> Result<Bill> = { stableCheckout -> billingRepository.createBill(
                    cartItems     = current.cart,
                    customerId    = current.customer?.id,
                    customerName  = finalCustomerName,
                    customerPhone = finalCustomerPhone,
                    discountPaisa = current.discountPaisa,
                    discountNote  = current.discountNote,
                    gstEnabled    = current.gstEnabled,
                    paymentMethod = current.paymentMethod,
                    paidPaisa     = if (current.paymentMethod == PaymentMethod.CREDIT)
                        current.paidPaisa
                    else current.calculation?.totalPaisa ?: 0L,
                    notes         = current.notes,
                    customerGstNumber = finalCustomerGst,
                    checkoutId = stableCheckout,
                    gstInclusive = current.gstInclusive,
                    sourceQuotationId = current.sourceQuotationId,
                    paymentSplit=if(current.paymentMethod==PaymentMethod.SPLIT)PaymentSplit(current.splitCashPaisa,current.splitUpiPaisa) else null
                )
                }
                val result = current.sourceWebsiteOrderId?.let { orderId ->
                    requireNotNull(websiteOrders) { "Website billing adapter is unavailable" }.get().commit(orderId,current.cart,saveExisting)
                } ?: saveExisting(checkoutId)
                when (result) {
                    is Result.Success -> {
                        _state.update { it.copy(isLoading = false, savedBillId = result.data.id, printAfterSave = printAfterSave) }
                        try { persistCart() } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* Checkout marker prevents this saved cart being billed again. */ }
                    }
                    is Result.Error -> {
                        _state.update { it.copy(isLoading = false, error = result.message) }
                    }
                    else -> Unit
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = "Could not save bill: ${e.message}") } }
            finally { _state.update { it.copy(isLoading = false) } }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun updateCartAndRecalculate(newCart: List<CartItem>) {
        _state.update { it.copy(cart = newCart) }
        recalculate()
    }

    private fun recalculate() {
        val s = _state.value
        val calcItems = s.cart.map {
            PipeBilling.calculation(it)
        }
        val calc = try {
            if (s.gstEnabled && s.gstInclusive)
                CalculationEngine.calculateBillInclusive(calcItems, s.discountPaisa)
            else
                CalculationEngine.calculateBill(calcItems, s.discountPaisa, s.gstEnabled)
        } catch (e: Exception) {
            _state.update { it.copy(calculation = null, error = "Invalid cart amount: ${e.message}") }
            return
        }
        _state.update { it.copy(
            calculation = calc,
            paidPaisa   = if (s.paymentMethod != PaymentMethod.CREDIT) calc.totalPaisa
            else s.paidPaisa
        )}
    }
}

data class ParkedBill(val key: String, val label: String, val itemCount: Int, val updatedAt: Long)

data class RemovedCartLine(val token:String,val item:CartItem,val index:Int)

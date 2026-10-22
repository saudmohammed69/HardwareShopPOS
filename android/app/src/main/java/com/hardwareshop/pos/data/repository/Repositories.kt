package com.hardwareshop.pos.data.repository

import com.hardwareshop.pos.data.local.dao.*
import com.hardwareshop.pos.data.local.database.*
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// PRODUCT REPOSITORY
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class ProductRepository @Inject constructor(
    private val db: AppDatabase,
    private val categoryDao: CategoryDao,
    private val productTypeDao: ProductTypeDao,
    private val productDao: ProductDao,
    private val stockDao: StockDao,
    private val auditRepository: AuditRepository,
    private val session: SessionHolder
) {
    // ── Category navigation ──────────────────────────────────────────────────

    fun observeActiveCategories(): Flow<List<Category>> =
        categoryDao.observeActiveCategories().map { list -> list.map { it.toDomain() } }

    fun observeTypesForCategory(categoryId: Long): Flow<List<ProductType>> =
        productTypeDao.observeTypesForCategory(categoryId)
            .map { list -> list.map { it.toDomain() } }

    fun observeProductsByType(typeId: Long): Flow<List<Product>> =
        productDao.observeProductsByType(typeId)
            .conflate()
            .map { list -> ProductOrdering.sorted(list.map { it.toDomain() }) }
            .flowOn(Dispatchers.Default)

    fun observeLowStockCount(): Flow<Int> = productDao.observeLowStockCount()

    fun observeAllActiveProducts(): Flow<List<Product>> =
        combine(BillingLoadDiagnostics.observe(productDao.observeAllActiveProducts(),"room-products"){it.size}.distinctUntilChanged(),
            BillingLoadDiagnostics.observe(categoryDao.observeCategoryLabels(),"room-category-labels"){it.size}.distinctUntilChanged(),
            BillingLoadDiagnostics.observe(productTypeDao.observeTypeLabels(),"room-type-labels"){it.size}.distinctUntilChanged()) {
            products,categories,types -> Triple(products,categories,types)
        }
            .conflate()
            .map { (products,categories,types) ->
                val categoryNames=categories.associate { it.id to it.name }
                val typeNames=types.associate { it.id to it.name }
                BillingLoadDiagnostics.event("catalog-domain-start",products.size)
                ProductOrdering.sorted(products.map { it.toDomain(categoryNames[it.categoryId].orEmpty(),typeNames[it.typeId].orEmpty()) })
                    .also { BillingLoadDiagnostics.event("catalog-domain-ready",it.size) }
            }
            .flowOn(Dispatchers.Default)

    // ── Search ───────────────────────────────────────────────────────────────

    /** Billing observes local changes once; search never re-reads products/history per character. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeBillingCatalogue(): Flow<BillingCatalogue> = combine(
        observeAllActiveProducts().distinctUntilChanged(),
        BillingLoadDiagnostics.observe(db.settingsDao().observePipeBillingAliases(),"room-billing-aliases"){it.size}.distinctUntilChanged(),
        BillingLoadDiagnostics.observe(db.billDao().observeProductSalesFrequency(),"room-sales-frequency"){it.size}.distinctUntilChanged()
    ) { products, rows, frequency -> Triple(products,rows,frequency) }
        .conflate().mapLatest { (products,rows,frequency) -> withContext(Dispatchers.Default) {
            val aliases=rows.mapNotNull { row ->
                val source=row.key.removePrefix("pipe:billing-alias:").toLongOrNull()
                val target=row.value.toLongOrNull()
                if(source==null || target==null)null else source to target
            }.toMap()
            BillingLoadDiagnostics.event("catalog-index-start",products.size)
            BillingCatalogue(BillingProductAliases.resolve(products,aliases,products),frequency.associate { it.productId to it.saleCount },aliases,products)
                .also { BillingLoadDiagnostics.event("catalog-index-ready",it.products.size) }
        } }.flowOn(Dispatchers.Default)

    private suspend fun searchSnapshot() = db.withTransaction {
        Triple(productDao.getAllProducts().filter { it.isActive },
            categoryDao.getAllCategories().associate { it.id to it.name },
            productTypeDao.getAllTypes().associate { it.id to it.name })
    }

    /** Warm pure search labels in the background when billing opens, before typing. */
    suspend fun prepareSearch() {
        val (products, categories, types) = searchSnapshot()
        withContext(Dispatchers.Default) {
            products.forEach { product ->
                ensureActive()
                ProductSearch.prepareLabel("${product.name} ${product.size} ${product.unit} ${categories[product.categoryId].orEmpty()} ${types[product.typeId].orEmpty()}", product.searchAliases)
            }
        }
    }

    /** Rank the complete active catalogue before limiting; SQL LIMIT can hide exact matches. */
    suspend fun searchProducts(query: String, preferBestSellers: Boolean = false): List<Product> {
        val terms = ProductSearch.tokens(query.take(160)).take(20)
        if (terms.isEmpty()) return emptyList()
        val (products, categories, types) = searchSnapshot()
        val frequency = if (preferBestSellers) db.billDao().getProductSalesFrequency()
            .associate { it.productId to it.saleCount } else emptyMap()
        return withContext(Dispatchers.Default) {
            val matcher = ProductSearch.Matcher(terms)
            val ranked=products.mapNotNull { product ->
                ensureActive()
                matcher.score("${product.name} ${product.size} ${product.unit} ${categories[product.categoryId].orEmpty()} ${types[product.typeId].orEmpty()}", product.searchAliases)
                    ?.let { score -> product.toDomain(categories[product.categoryId].orEmpty(), types[product.typeId].orEmpty()) to score }
            }
            ProductOrdering.ranked(ranked, frequency).take(100)
        }
    }

    suspend fun boughtTogether(ids: List<Long>): Map<Long, Long> =
        if (ids.isEmpty()) emptyMap() else db.billDao().getBoughtTogether(ids.take(100)).associate { it.productId to it.saleCount }

    suspend fun getProductById(id: Long): Product? {
        val product=productDao.getProductById(id) ?: return null
        return product.toDomain(categoryDao.getCategoryById(product.categoryId)?.name.orEmpty(),
            productTypeDao.getTypesForCategory(product.categoryId).firstOrNull{it.id==product.typeId}?.name.orEmpty())
    }

    // ── Owner CRUD ───────────────────────────────────────────────────────────

    /**
     * [purchasePricePaisa] is optional — pass 0 when the owner did not supply one.
     * The value is stored as-is; 0 is the sentinel for "not set".
     */
    suspend fun createProduct(
        categoryId: Long,
        typeId: Long,
        name: String,
        size: String,
        unit: String,
        sellingPricePaisa: Long,
        purchasePricePaisa: Long,       // 0 = not provided (optional)
        gstRateBp: Int,
        initialStock: Double,
        minimumStock: Double,
        imagePath: String?,
        searchAliases: String = "",
        piecesPerBox: Int = 0,
        isPipe: Boolean = false, pipeLengthPricePaisa: Long = 0, pipeFootPricePaisa: Long = 0, pipeFeetPerLength: Int = 0
    ): Result<Long> {
        PermissionChecker.enforceOwner(session.current)
        return try { db.withTransaction {
            validateProduct(categoryId, typeId, name, unit, sellingPricePaisa, purchasePricePaisa, gstRateBp, minimumStock)
            val duplicateKey=productKey(categoryId,typeId,name,size,unit)
            require(productDao.getAllProducts().none { productKey(it.categoryId,it.typeId,it.name,it.size,it.unit)==duplicateKey }) {
                "This product/variant already exists. Open it to edit or reactivate it."
            }
            if(isPipe) require(pipeLengthPricePaisa>0 && pipeFootPricePaisa>0 && pipeFeetPerLength in 1..10000 &&
                unit.lowercase() in setOf("length","piece","nos","foot","feet","ft")) { "Enter both pipe rates and physical length with a supported stock unit" }
            BoxQuantity.validate(unit, piecesPerBox)
            require(initialStock.isFinite() && initialStock >= 0) { "Opening stock must be 0 or greater" }
            val entity = ProductEntity(
                categoryId         = categoryId,
                typeId             = typeId,
                name               = name.trim(),
                size               = size.trim(),
                unit               = unit.trim(),
                sellingPricePaisa  = sellingPricePaisa,
                purchasePricePaisa = purchasePricePaisa,
                gstRateBp          = gstRateBp,
                searchAliases      = searchAliases.trim(),
                piecesPerBox       = piecesPerBox,
                isPipe = isPipe, pipeLengthPricePaisa = pipeLengthPricePaisa,
                pipeFootPricePaisa = pipeFootPricePaisa, pipeFeetPerLength = pipeFeetPerLength,
                currentStock       = initialStock,
                minimumStock       = minimumStock,
                imagePath          = imagePath
            )
            val id = productDao.insertProduct(entity)
            auditRepository.log(
                actionType = "PRODUCT_CREATED",
                entityType = "PRODUCT",
                entityId   = id,
                afterValue = name,
                reason     = "New product added"
            )
            Result.Success(id)
        } } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Timber.e(e, "createProduct failed")
            Result.Error("Failed to create product: ${e.message}", e)
        }
    }

    /** Billing-only aliases preserve old products, stock and historical IDs. */
    suspend fun canonicalBillingProducts(products: List<Product>): List<Product> = withContext(Dispatchers.Default) {
        val aliases = db.settingsDao().getPipeBillingAliases()
            .mapNotNull { row ->
                val id = row.key.removePrefix("pipe:billing-alias:").toLongOrNull()
                val target = row.value.toLongOrNull()
                if (id == null || target == null) null else id to target
            }.toMap()
        if (aliases.isEmpty()) return@withContext products.distinctBy { it.id }
        val present = products.map { it.id }.toSet()
        val missing = aliases.values.toSet() - present
        val targets = products + if (missing.isEmpty()) emptyList() else
            productDao.getProductsByIds(missing.toList()).map { it.toDomain() }
        BillingProductAliases.resolve(products, aliases, targets)
    }

    suspend fun updateProduct(product: Product): Result<Unit> {
        PermissionChecker.enforceOwner(session.current)
        return try { db.withTransaction {
            val existing = productDao.getProductById(product.id)
                ?: return@withTransaction Result.Error("Product not found")
            require(!existing.isPipe || (product.isPipe && product.unit == existing.unit &&
                product.pipeFeetPerLength == existing.pipeFeetPerLength)) {
                "Pipe inventory units and physical length cannot change without an inventory conversion. Edit the two selling prices instead."
            }
            validateProduct(product.categoryId, product.typeId, product.name, product.unit,
                product.sellingPricePaisa, product.purchasePricePaisa, product.gstRateBp, product.minimumStock)
            val typeNames=productTypeDao.getAllTypes().associate { it.id to it.name }
            val variantIdentity=ProductVariantMetadata.identity(product.copy(typeName=typeNames[product.typeId].orEmpty()))
            require(productDao.getAllProducts().none { other ->
                other.id!=product.id && other.categoryId==product.categoryId && other.typeId==product.typeId &&
                    ProductVariantMetadata.identity(other.toDomain(typeName=typeNames[other.typeId].orEmpty()))==variantIdentity
            }) { "This combination already exists. Open that record to edit it." }
            BoxQuantity.validate(product.unit, product.piecesPerBox)
            if (product.isPipe) {
                require(product.pipeLengthPricePaisa > 0 && product.pipeFootPricePaisa > 0 && product.pipeFeetPerLength in 1..10000)
                require(product.unit.lowercase() in setOf("length", "piece", "nos", "foot", "feet", "ft"))
            }
            auditRepository.log(
                actionType  = "PRODUCT_EDITED",
                entityType  = "PRODUCT",
                entityId    = product.id,
                productId   = product.id,
                beforeValue = buildAuditSnapshot("name=${existing.name}, price=${existing.sellingPricePaisa}"),
                afterValue  = buildAuditSnapshot("name=${product.name}, price=${product.sellingPricePaisa}")
            )
            productDao.updateProduct(product.toEntity().copy(currentStock = existing.currentStock, createdAt = existing.createdAt))
            Result.Success(Unit)
        } } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Timber.e(e, "updateProduct failed")
            Result.Error("Failed to update product: ${e.message}", e)
        }
    }

    /**
     * purchase >= 0 intentionally allows 0 — purchase price is optional.
     * A value of 0 means "not provided by owner", not "free item".
     */
    private suspend fun validateProduct(categoryId: Long, typeId: Long, name: String, unit: String,
                                        selling: Long, purchase: Long, gst: Int, minimumStock: Double) {
        require(name.isNotBlank() && unit.isNotBlank()) { "Product name and unit are required" }
        require(selling > 0)   { "Selling price must be greater than 0" }
        require(purchase >= 0) { "Purchase price must be 0 or greater" }
        require(gst in 0..10000) { "GST must be between 0 and 100 percent" }
        require(minimumStock.isFinite() && minimumStock >= 0) { "Minimum stock must be 0 or greater" }
        require(categoryDao.getCategoryById(categoryId)?.isActive == true) { "Choose an active category" }
        require(productTypeDao.getTypesForCategory(categoryId).any { it.id == typeId && it.isActive }) {
            "Choose a product type belonging to this category"
        }
    }

    suspend fun createCategory(name: String): Result<Long> {
        PermissionChecker.enforceOwner(session.current)
        return try {
            require(name.isNotBlank()) { "Category name is required" }
            val id = categoryDao.insertCategory(CategoryEntity(name = name.trim()))
            Result.Success(id)
        } catch (e: Exception) {
            Result.Error("Category '${name}' may already exist", e)
        }
    }

    suspend fun createProductType(categoryId: Long, name: String): Result<Long> {
        PermissionChecker.enforceOwner(session.current)
        return try {
            require(name.isNotBlank() && categoryDao.getCategoryById(categoryId)?.isActive == true) { "Choose a category and enter a type name" }
            val id = productTypeDao.insertType(
                ProductTypeEntity(categoryId = categoryId, name = name.trim())
            )
            Result.Success(id)
        } catch (e: Exception) {
            Result.Error("Type '${name}' may already exist in this category", e)
        }
    }

    suspend fun importProducts(rows: List<ProductImportRow>): Result<ProductImportSummary> {
        PermissionChecker.enforceOwner(session.current)
        return try { db.withTransaction {
            val categories = categoryDao.getAllCategories()
                .filter { it.isActive }
                .associateBy { it.name.normalizedKey() }
                .toMutableMap()
            val typesByCategory = productTypeDao.getAllTypes()
                .filter { it.isActive }
                .groupBy { it.categoryId }
                .mapValues { entry -> entry.value.associateBy { it.name.normalizedKey() }.toMutableMap() }
                .toMutableMap()
            val products = productDao.getAllProducts()
                .filter { it.isActive }
                .associateBy { productKey(it.categoryId, it.typeId, it.name, it.size, it.unit) }
                .toMutableMap()

            var added = 0
            var updated = 0
            var stockChanged = 0
            var skipped = 0
            val skippedRows = mutableListOf<String>()

            rows.forEachIndexed { index, row ->
                val rowNumber = index + 2
                try {
                    val categoryName = row.category.trim().ifBlank { "General" }
                    val typeName     = row.type.trim().ifBlank { "General" }
                    val name         = row.name.trim()
                    val unit         = normalizeUnit(row.unit)
                    require(name.isNotBlank()) { "missing item name" }
                    require(row.sellingPricePaisa != null && row.sellingPricePaisa > 0) { "missing selling price" }
                    // buyingPricePaisa is optional — null means "not provided", negative is rejected
                    require(row.buyingPricePaisa == null || row.buyingPricePaisa >= 0) { "bad buying price" }
                    require(row.stock == null || row.stock.isFinite() && row.stock >= 0) { "bad stock" }
                    require(row.minimumStock == null || row.minimumStock.isFinite() && row.minimumStock >= 0) { "bad minimum stock" }
                    require(row.gstRateBp == null || row.gstRateBp in 0..10000) { "bad GST rate" }

                    val category = categories.getOrPut(categoryName.normalizedKey()) {
                        val id = categoryDao.insertCategory(CategoryEntity(name = categoryName))
                        CategoryEntity(id = id, name = categoryName)
                    }
                    val typeMap = typesByCategory.getOrPut(category.id) { mutableMapOf() }
                    val type = typeMap.getOrPut(typeName.normalizedKey()) {
                        val id = productTypeDao.insertType(ProductTypeEntity(categoryId = category.id, name = typeName))
                        ProductTypeEntity(id = id, categoryId = category.id, name = typeName)
                    }
                    val key      = productKey(category.id, type.id, name, row.size, unit)
                    val now      = System.currentTimeMillis()
                    val existing = products[key]

                    if (existing == null) {
                        val product = ProductEntity(
                            categoryId         = category.id,
                            typeId             = type.id,
                            name               = name,
                            size               = row.size.trim(),
                            unit               = unit,
                            sellingPricePaisa  = row.sellingPricePaisa,
                            // null buyingPrice → store 0 (sentinel for "not set")
                            purchasePricePaisa = row.buyingPricePaisa ?: 0L,
                            gstRateBp          = row.gstRateBp ?: 0,
                            searchAliases      = row.aliases.trim(),
                            currentStock       = row.stock ?: 0.0,
                            minimumStock       = row.minimumStock ?: 0.0
                        )
                        val id = productDao.insertProduct(product)
                        products[key] = product.copy(id = id)
                        added++
                        auditRepository.log(
                            actionType = "PRODUCT_CREATED",
                            entityType = "PRODUCT",
                            entityId   = id,
                            productId  = id,
                            afterValue = buildAuditSnapshot("name=$name", "source=Excel import"),
                            reason     = "Excel import"
                        )
                    } else {
                        val newStock      = row.stock ?: existing.currentStock
                        val changedStock  = row.stock != null && newStock != existing.currentStock
                        val updatedProduct = existing.copy(
                            sellingPricePaisa  = row.sellingPricePaisa,
                            // null buyingPrice → keep existing value (don't overwrite with 0)
                            purchasePricePaisa = row.buyingPricePaisa ?: existing.purchasePricePaisa,
                            gstRateBp          = row.gstRateBp ?: existing.gstRateBp,
                            searchAliases      = row.aliases.trim().ifBlank { existing.searchAliases },
                            currentStock       = newStock,
                            minimumStock       = row.minimumStock ?: existing.minimumStock,
                            updatedAt          = now
                        )
                        productDao.updateProduct(updatedProduct)
                        products[key] = updatedProduct
                        updated++
                        if (changedStock) {
                            stockChanged++
                            stockDao.insertAdjustment(
                                StockAdjustmentEntity(
                                    productId  = existing.id,
                                    productName = existing.name,
                                    oldStock   = existing.currentStock,
                                    newStock   = newStock,
                                    difference = newStock - existing.currentStock,
                                    reason     = "Excel import",
                                    notes      = "Stock set from import row $rowNumber",
                                    adjustedByOwnerId = 0
                                )
                            )
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    skipped++
                    if (skippedRows.size < 8) skippedRows += "Row $rowNumber: ${e.message ?: "invalid row"}"
                }
            }
            auditRepository.log(
                actionType = "PRODUCT_IMPORT",
                entityType = "PRODUCT",
                afterValue = buildAuditSnapshot("added=$added", "updated=$updated", "stockChanged=$stockChanged", "skipped=$skipped"),
                reason     = "Excel import"
            )
            Result.Success(ProductImportSummary(added, updated, stockChanged, skipped, skippedRows))
        } } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Timber.e(e, "importProducts failed")
            Result.Error("Import failed: ${e.message}", e)
        }
    }

    private fun productKey(categoryId: Long, typeId: Long, name: String, size: String, unit: String): String =
        listOf(categoryId.toString(), typeId.toString(), name.normalizedKey(), size.normalizedKey(), unit.normalizedKey()).joinToString("|")

    private fun normalizeUnit(value: String): String = when (value.trim().lowercase(java.util.Locale.ROOT)) {
        "", "pc", "pcs", "piece", "pieces", "nos", "no" -> "Piece"
        "m", "meter", "metre", "meters", "metres"       -> "Meter"
        "ft", "feet", "foot"                            -> "Foot"
        "kg", "kgs", "kilogram"                         -> "Kg"
        "ltr", "litre", "liter", "l"                    -> "Litre"
        "pkt", "packet"                                 -> "Packet"
        else -> value.trim()
    }

    private fun String.normalizedKey(): String = trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
}

data class ProductImportRow(
    val category: String,
    val type: String,
    val name: String,
    val size: String,
    val unit: String,
    val buyingPricePaisa: Long?,        // null = column absent or blank in the file (optional)
    val sellingPricePaisa: Long?,
    val stock: Double?,
    val minimumStock: Double? = null,
    val gstRateBp: Int? = null,
    val aliases: String = ""
)

data class ProductImportSummary(
    val added: Int,
    val updated: Int,
    val stockChanged: Int,
    val skipped: Int,
    val skippedRows: List<String>
)

// ─────────────────────────────────────────────────────────────────────────────
// CUSTOMER REPOSITORY
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class CustomerRepository @Inject constructor(
    private val customerDao: CustomerDao,
    private val creditDao: CreditDao,
    private val session: SessionHolder
) {
    fun observeActiveCustomers(): Flow<List<Customer>> =
        combine(customerDao.observeActiveCustomers(), creditDao.observeAllBalances()) { customers, balances ->
            val amounts = balances.associate { it.customerId to it.balance }
            customers.map { it.toDomain(amounts[it.id] ?: 0L) }
        }

    suspend fun searchCustomers(query: String): List<Customer> {
        if (query.isBlank()) return emptyList()
        return customerDao.searchCustomers("%$query%").map { it.toDomain() }
    }

    suspend fun getCustomerById(id: Long): Customer? {
        val entity  = customerDao.getCustomerById(id) ?: return null
        val balance = creditDao.getBalanceForCustomer(id)
        return entity.toDomain(outstandingBalancePaisa = balance)
    }

    suspend fun createCustomer(name: String, phone: String, address: String, gstNumber: String = ""): Result<Long> {
        PermissionChecker.enforceLogin(session.current)
        return try {
            val id = customerDao.insertCustomer(
                CustomerEntity(name = name.trim().also { require(it.isNotBlank()) { "Customer name is required" } }, phone = phone.trim(), address = address.trim(), gstNumber = gstNumber.trim().uppercase(java.util.Locale.ROOT))
            )
            Result.Success(id)
        } catch (e: Exception) {
            Result.Error("Failed to create customer: ${e.message}", e)
        }
    }

    suspend fun updateCustomer(customer: Customer): Result<Unit> {
        PermissionChecker.enforceLogin(session.current)
        return try {
            require(customer.name.isNotBlank()) { "Customer name is required" }
            val existing = requireNotNull(customerDao.getCustomerById(customer.id)) { "Customer not found" }
            customerDao.updateCustomer(existing.copy(name = customer.name.trim(), phone = customer.phone.trim(),
                address = customer.address.trim(), gstNumber = customer.gstNumber.trim().uppercase(java.util.Locale.ROOT),
                isActive = customer.isActive, updatedAt = System.currentTimeMillis()))
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error("Failed to update customer: ${e.message}", e)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AUTH REPOSITORY
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class AuthRepository @Inject constructor(
    private val db: AppDatabase,
    private val ownerCredentialDao: OwnerCredentialDao,
    private val workerDao: WorkerDao,
    private val auditRepository: AuditRepository,
    private val session: SessionHolder,
    private val cloudSession: com.hardwareshop.pos.data.remote.CloudSessionStore
) {
    /** Returns true if the owner account has been set up */
    suspend fun isOwnerSetup(): Boolean = ownerCredentialDao.getOwnerCredential() != null

    suspend fun authenticateOwner(password: String): Result<Unit> {
        val cred = ownerCredentialDao.getOwnerCredential()
            ?: return Result.Error("Owner account not set up")
        val ok = SecurityManager.verifyPassword(password, cred.passwordHash, cred.passwordSalt)
        return if (ok) {
            session.setSession(UserSession.OwnerSession(shopName = cred.shopName))
            auditRepository.log(actionType = "OWNER_LOGIN", entityType = "SYSTEM")
            cloudSession.remember()
            Result.Success(Unit)
        } else {
            auditRepository.log(actionType = "OWNER_LOGIN_FAILED", entityType = "SYSTEM")
            Result.Error("Incorrect password")
        }
    }

    suspend fun authenticateWorker(username: String, password: String): Result<Unit> {
        val entity = workerDao.getWorkerByUsername(username.trim().lowercase(java.util.Locale.ROOT))
            ?: return Result.Error("Worker account not found")
        if (!entity.isActive) return Result.Error("Worker account is disabled")
        if (entity.passwordHash.isBlank() || entity.passwordSalt.isBlank())
            return Result.Error("Ask the owner to set this worker's password on this phone first")
        val ok = SecurityManager.verifyPassword(password, entity.passwordHash, entity.passwordSalt)
        return if (ok) {
            session.setSession(
                UserSession.WorkerSession(
                    workerId                        = entity.id,
                    workerName                      = entity.name,
                    username                        = entity.username,
                    maxDiscountPaisa                = entity.maxDiscountPaisa,
                    requireOwnerApprovalForDiscount = entity.requireOwnerApprovalForDiscount
                )
            )
            auditRepository.log(
                actionType = "WORKER_LOGIN",
                entityType = "SYSTEM",
                actorId    = entity.id,
                actorName  = entity.name
            )
            cloudSession.remember()
            Result.Success(Unit)
        } else {
            Result.Error("Incorrect password")
        }
    }

    suspend fun setupOwner(
        password: String,
        shopName: String,
        shopPhone: String,
        shopAddress: String,
        shopGstNumber: String
    ): Result<Unit> {
        if (isOwnerSetup()) return Result.Error("Owner already set up")
        val validation = SecurityManager.validatePassword(password)
        if (validation is SecurityManager.ValidationResult.Error)
            return Result.Error(validation.message)
        return try {
            val hashed = SecurityManager.hashPassword(password)
            db.withTransaction {
            check(!isOwnerSetup()) { "Owner already set up" }
            ownerCredentialDao.upsertCredential(
                OwnerCredentialEntity(
                    passwordHash  = hashed.hash,
                    passwordSalt  = hashed.salt,
                    shopName      = shopName.trim(),
                    shopPhone     = shopPhone.trim(),
                    shopAddress   = shopAddress.trim(),
                    shopGstNumber = shopGstNumber.trim()
                )
            )
            }
            session.setSession(UserSession.OwnerSession(shopName = shopName))
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error("Setup failed: ${e.message}", e)
        }
    }

    @Suppress("unused") // Retained credential-management API; no password-change screen currently calls it.
    suspend fun changeOwnerPassword(currentPassword: String, newPassword: String): Result<Unit> {
        PermissionChecker.enforceOwner(session.current)
        val cred = ownerCredentialDao.getOwnerCredential()
            ?: return Result.Error("Owner credential missing")
        if (!SecurityManager.verifyPassword(currentPassword, cred.passwordHash, cred.passwordSalt))
            return Result.Error("Current password is incorrect")
        val validation = SecurityManager.validatePassword(newPassword)
        if (validation is SecurityManager.ValidationResult.Error)
            return Result.Error(validation.message)
        return try {
            val hashed = SecurityManager.hashPassword(newPassword)
            ownerCredentialDao.upsertCredential(cred.copy(passwordHash = hashed.hash, passwordSalt = hashed.salt))
            auditRepository.log(actionType = "SETTINGS_CHANGED", entityType = "SYSTEM", reason = "Owner password changed")
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error("Password change failed", e)
        }
    }

    fun logout() {
        cloudSession.clear()
        session.clear()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// WORKER REPOSITORY
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class WorkerRepository @Inject constructor(
    private val workerDao: WorkerDao,
    private val session: SessionHolder
) {
    fun observeActiveWorkers(): Flow<List<Worker>> =
        workerDao.observeAllWorkers().map { list -> list.map { it.toDomain() } }

    suspend fun createWorker(
        name: String,
        username: String,
        password: String,
        phone: String,
        maxDiscountPaisa: Long,
        requireOwnerApproval: Boolean
    ): Result<Long> {
        PermissionChecker.enforceOwner(session.current)
        val validation = SecurityManager.validatePassword(password)
        if (validation is SecurityManager.ValidationResult.Error)
            return Result.Error(validation.message)
        if (name.isBlank() || username.isBlank() || maxDiscountPaisa < 0) return Result.Error("Enter valid worker details")
        return try {
            val hashed = SecurityManager.hashPassword(password)
            val id = workerDao.insertWorker(
                WorkerEntity(
                    name                            = name.trim(),
                    username                        = username.trim().lowercase(java.util.Locale.ROOT),
                    passwordHash                    = hashed.hash,
                    passwordSalt                    = hashed.salt,
                    phone                           = phone.trim(),
                    maxDiscountPaisa                = maxDiscountPaisa,
                    requireOwnerApprovalForDiscount = requireOwnerApproval
                )
            )
            Result.Success(id)
        } catch (e: Exception) {
            Result.Error("Username '${username}' may already be taken", e)
        }
    }

    suspend fun setWorkerActive(id: Long, isActive: Boolean): Result<Unit> {
        PermissionChecker.enforceOwner(session.current)
        return try {
            workerDao.setActive(id, isActive)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error("Failed to update worker status", e)
        }
    }

    @Suppress("unused") // Retained credential-management API; no password-change screen currently calls it.
    suspend fun changeWorkerPassword(workerId: Long, newPassword: String): Result<Unit> {
        PermissionChecker.enforceOwner(session.current)
        val worker = workerDao.getWorkerById(workerId)
            ?: return Result.Error("Worker not found")
        val validation = SecurityManager.validatePassword(newPassword)
        if (validation is SecurityManager.ValidationResult.Error)
            return Result.Error(validation.message)
        val hashed = SecurityManager.hashPassword(newPassword)
        return try {
            workerDao.updateWorker(worker.copy(passwordHash = hashed.hash, passwordSalt = hashed.salt))
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error("Password update failed", e)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SETTINGS REPOSITORY
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class SettingsRepository @Inject constructor(
    private val settingsDao: SettingsDao,
    private val ownerCredentialDao: OwnerCredentialDao,
    private val session: SessionHolder
) {
    object Keys {
        const val SHOP_NAME       = "shop_name"
        const val SHOP_PHONE      = "shop_phone"
        const val SHOP_ADDRESS    = "shop_address"
        const val SHOP_GST_NUMBER = "shop_gst_number"
        /** UUID that identifies this shop's Firebase namespace — generated once, never changes */
        const val SHOP_ID         = "shop_id"
        const val UPI_ID = "merchant_upi_id"
        const val UPI_NAME = "merchant_upi_name"
    }

    suspend fun get(key: String, default: String = ""): String =
        settingsDao.getSetting(key)?.value ?: default

    /** Returns the stable shopId, generating and persisting one on first call */
    suspend fun getOrCreateShopId(): String {
        val existing = get(Keys.SHOP_ID)
        if (existing.isNotBlank()) return existing
        val newId = java.util.UUID.randomUUID().toString()
        settingsDao.upsertSetting(SettingsEntity(key = Keys.SHOP_ID, value = newId))
        return newId
    }

    suspend fun set(key: String, value: String) {
        PermissionChecker.enforceOwner(session.current)
        settingsDao.upsertSetting(SettingsEntity(key = key, value = value))
    }

    suspend fun getShopInfo(): ShopInfo {
        val cred = ownerCredentialDao.getOwnerCredential()
        return ShopInfo(
            name      = settingsDao.getSetting(Keys.SHOP_NAME)?.value ?: cred?.shopName.orEmpty(),
            phone     = settingsDao.getSetting(Keys.SHOP_PHONE)?.value ?: cred?.shopPhone.orEmpty(),
            address   = settingsDao.getSetting(Keys.SHOP_ADDRESS)?.value ?: cred?.shopAddress.orEmpty(),
            gstNumber = settingsDao.getSetting(Keys.SHOP_GST_NUMBER)?.value ?: cred?.shopGstNumber.orEmpty(),
            upiId = get(Keys.UPI_ID),
            upiName = get(Keys.UPI_NAME),
            receiptFooter=get("receipt_footer",com.hardwareshop.pos.util.ReceiptFooter.DEFAULT)
        )
    }

    suspend fun setMerchantUpi(id: String, name: String) {
        PermissionChecker.enforceOwner(session.current)
        com.hardwareshop.pos.util.UpiPaymentQr.validate(id, name)
        settingsDao.upsertSettings(listOf(SettingsEntity(Keys.UPI_ID, id), SettingsEntity(Keys.UPI_NAME, name)))
    }

    suspend fun updateShopInfo(info: ShopInfo): Result<Unit> {
        PermissionChecker.enforceOwner(session.current)
        val cred = ownerCredentialDao.getOwnerCredential()
            ?: return Result.Error("Owner not set up")
        return try {
            ownerCredentialDao.upsertCredential(
                cred.copy(
                    shopName      = info.name,
                    shopPhone     = info.phone,
                    shopAddress   = info.address,
                    shopGstNumber = info.gstNumber
                )
            )
            settingsDao.upsertSettings(listOf(
                SettingsEntity(Keys.SHOP_NAME, info.name), SettingsEntity(Keys.SHOP_PHONE, info.phone),
                SettingsEntity(Keys.SHOP_ADDRESS, info.address), SettingsEntity(Keys.SHOP_GST_NUMBER, info.gstNumber)
            ))
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error("Failed to update shop info", e)
        }
    }

    data class ShopInfo(
        val name: String,
        val phone: String,
        val address: String,
        val gstNumber: String,
        val upiId: String = "",
        val upiName: String = "",
        val receiptFooter:String = com.hardwareshop.pos.util.ReceiptFooter.DEFAULT
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// AUDIT REPOSITORY
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class AuditRepository @Inject constructor(
    private val auditLogDao: AuditLogDao,
    private val session: SessionHolder
) {
    /**
     * Append an audit record. Called by other repositories.
     * Usually best effort. Financial callers can require the audit record
     * to commit in their transaction by setting requireSuccess.
     */
    suspend fun log(
        actionType:  String,
        entityType:  String,
        entityId:    Long?   = null,
        billId:      Long?   = null,
        productId:   Long?   = null,
        customerId:  Long?   = null,
        actorId:     Long?   = null,
        actorName:   String  = "",
        beforeValue: String? = null,
        afterValue:  String? = null,
        reason:      String  = "",
        notes:       String  = "",
        requireSuccess: Boolean = false
    ) {
        try {
            val s    = session.current
            val role = when (s) {
                is UserSession.OwnerSession  -> "OWNER"
                is UserSession.WorkerSession -> "WORKER"
                else                         -> "SYSTEM"
            }
            val id   = (s as? UserSession.WorkerSession)?.workerId ?: actorId
            val name = (s as? UserSession.WorkerSession)?.workerName ?: actorName

            auditLogDao.insertLog(
                AuditLogEntity(
                    actionType  = actionType,
                    entityType  = entityType,
                    entityId    = entityId,
                    billId      = billId,
                    productId   = productId,
                    customerId  = customerId,
                    actorRole   = role,
                    actorId     = id,
                    actorName   = name,
                    beforeValue = beforeValue,
                    afterValue  = afterValue,
                    reason      = reason,
                    notes       = notes
                )
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException || requireSuccess) throw e
            Timber.e(e, "Audit log insert failed — non-fatal")
        }
    }

    fun observeRecentLogs(): Flow<List<AuditLog>> =
        auditLogDao.observeRecentLogs().map { list -> list.map { it.toDomain() } }
}

// ─────────────────────────────────────────────────────────────────────────────
// HELPERS
// ─────────────────────────────────────────────────────────────────────────────

internal fun buildAuditSnapshot(vararg pairs: String): String = pairs.joinToString(", ")

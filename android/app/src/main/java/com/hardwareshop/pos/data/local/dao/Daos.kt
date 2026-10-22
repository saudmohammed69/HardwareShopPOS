package com.hardwareshop.pos.data.local.dao

import androidx.room.*
import com.hardwareshop.pos.data.local.entity.*
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
// DAO conventions
//   • Flow<T> — UI observes changes in real time
//   • suspend — one-shot async operations (insert, update, delete)
//   • @Transaction — multi-table reads kept consistent
// ─────────────────────────────────────────────────────────────────────────────


// ═══════════════════════════════════════════════════════════════════════════
// CATEGORY DAO
// ═══════════════════════════════════════════════════════════════════════════
@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories")
    fun observeCategoryLabels(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE isActive = 1 ORDER BY displayOrder ASC, name ASC")
    fun observeActiveCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY displayOrder ASC, name ASC")
    fun observeAllCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getCategoryById(id: Long): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Update
    suspend fun updateCategory(category: CategoryEntity)

    @Query("UPDATE categories SET isActive = :isActive, updatedAt = :now WHERE id = :id")
    suspend fun setActive(id: Long, isActive: Boolean, now: Long = System.currentTimeMillis())

    // ── Backup / restore ──
    @Query("SELECT * FROM categories ORDER BY id ASC")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Query("DELETE FROM categories")
    suspend fun deleteAllCategories()
}


// ═══════════════════════════════════════════════════════════════════════════
// PRODUCT TYPE DAO
// ═══════════════════════════════════════════════════════════════════════════
@Dao
interface ProductTypeDao {
    @Query("SELECT * FROM product_types")
    fun observeTypeLabels(): Flow<List<ProductTypeEntity>>

    @Query("""
        SELECT * FROM product_types
        WHERE categoryId = :categoryId AND isActive = 1
        ORDER BY displayOrder ASC, name ASC
    """)
    fun observeTypesForCategory(categoryId: Long): Flow<List<ProductTypeEntity>>

    @Query("""
        SELECT * FROM product_types
        WHERE categoryId = :categoryId
        ORDER BY displayOrder ASC, name ASC
    """)
    suspend fun getTypesForCategory(categoryId: Long): List<ProductTypeEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertType(type: ProductTypeEntity): Long

    @Update
    suspend fun updateType(type: ProductTypeEntity)

    @Query("UPDATE product_types SET isActive = :isActive, updatedAt = :now WHERE id = :id")
    suspend fun setActive(id: Long, isActive: Boolean, now: Long = System.currentTimeMillis())

    // ── Backup / restore ──
    @Query("SELECT * FROM product_types ORDER BY id ASC")
    suspend fun getAllTypes(): List<ProductTypeEntity>

    @Query("DELETE FROM product_types")
    suspend fun deleteAllTypes()
}


// ═══════════════════════════════════════════════════════════════════════════
// PRODUCT DAO
// ═══════════════════════════════════════════════════════════════════════════
@Dao
interface ProductDao {

    // ── Category → Type → Size hierarchy navigation ──
    @Query("""
        SELECT * FROM products
        WHERE typeId = :typeId AND isActive = 1
        ORDER BY size ASC, name ASC
    """)
    fun observeProductsByType(typeId: Long): Flow<List<ProductEntity>>

    @Query("""
        SELECT * FROM products
        WHERE categoryId = :categoryId AND isActive = 1
        ORDER BY name ASC
    """)
    fun observeProductsByCategory(categoryId: Long): Flow<List<ProductEntity>>

    // ── Search using FTS (fast with 1 000+ products) ──
    @Query("""
        SELECT products.* FROM products
        INNER JOIN products_fts ON products.rowid = products_fts.rowid
        WHERE products_fts MATCH :query AND products.isActive = 1
        ORDER BY products.name ASC
        LIMIT 100
    """)
    suspend fun searchProductsFts(query: String): List<ProductEntity>

    // ── Fallback LIKE search (for partial matches not in FTS) ──
    @Query("""
        SELECT * FROM products
        WHERE (name LIKE :query OR size LIKE :query OR searchAliases LIKE :query)
          AND isActive = 1
        ORDER BY name ASC
        LIMIT 100
    """)
    suspend fun searchProductsLike(query: String): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getProductById(id: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE id IN (:ids)")
    suspend fun getProductsByIds(ids: List<Long>): List<ProductEntity>

    // ── Owner CRUD ──
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProduct(product: ProductEntity): Long

    @Update
    suspend fun updateProduct(product: ProductEntity)

    @Query("UPDATE products SET isActive = :isActive, updatedAt = :now WHERE id = :id")
    suspend fun setActive(id: Long, isActive: Boolean, now: Long = System.currentTimeMillis())

    // ── Stock operations (called inside BillingRepository transaction) ──
    @Query("UPDATE products SET currentStock = currentStock - :qty, updatedAt = :now WHERE id = :id")
    suspend fun decrementStock(id: Long, qty: Double, now: Long = System.currentTimeMillis())

    @Query("UPDATE products SET currentStock = :newStock, updatedAt = :now WHERE id = :id")
    suspend fun setStock(id: Long, newStock: Double, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM products WHERE isActive = 1 AND minimumStock > 0 AND currentStock <= minimumStock ORDER BY name ASC")
    fun observeLowStockProducts(): Flow<List<ProductEntity>>

    @Query("SELECT COUNT(*) FROM products WHERE isActive = 1 AND minimumStock > 0 AND currentStock <= minimumStock")
    fun observeLowStockCount(): Flow<Int>

    // ── Owner reports ──
    @Query("SELECT * FROM products WHERE isActive = 1 ORDER BY name ASC")
    fun observeAllActiveProducts(): Flow<List<ProductEntity>>

    @Query("SELECT COUNT(*) FROM products WHERE isActive = 1")
    suspend fun countActiveProducts(): Int

    // ── Backup / restore ──
    @Query("SELECT * FROM products ORDER BY id ASC")
    suspend fun getAllProducts(): List<ProductEntity>

    @Query("DELETE FROM products")
    suspend fun deleteAllProducts()

    // ── Additional search: category name join for owner reports ──
    @Query("SELECT * FROM products WHERE categoryId = :categoryId ORDER BY name ASC")
    suspend fun getProductsByCategoryId(categoryId: Long): List<ProductEntity>
}


// ═══════════════════════════════════════════════════════════════════════════
// CUSTOMER DAO
// ═══════════════════════════════════════════════════════════════════════════
@Dao
interface CustomerDao {

    @Query("SELECT * FROM customers WHERE isActive = 1 ORDER BY name ASC")
    fun observeActiveCustomers(): Flow<List<CustomerEntity>>

    @Query("""
        SELECT * FROM customers
        WHERE (name LIKE :query OR phone LIKE :query) AND isActive = 1
        ORDER BY name ASC LIMIT 50
    """)
    suspend fun searchCustomers(query: String): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun getCustomerById(id: Long): CustomerEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCustomer(customer: CustomerEntity): Long

    @Update
    suspend fun updateCustomer(customer: CustomerEntity)

    @Query("UPDATE customers SET isActive = :isActive, updatedAt = :now WHERE id = :id")
    suspend fun setActive(id: Long, isActive: Boolean, now: Long = System.currentTimeMillis())

    // ── Backup / restore ──
    @Query("SELECT * FROM customers ORDER BY id ASC")
    suspend fun getAllCustomers(): List<CustomerEntity>

    @Query("DELETE FROM customers")
    suspend fun deleteAllCustomers()
}


// ═══════════════════════════════════════════════════════════════════════════
// WORKER DAO
// ═══════════════════════════════════════════════════════════════════════════
@Dao
interface WorkerDao {

    @Query("SELECT * FROM workers WHERE isActive = 1 ORDER BY name ASC")
    fun observeActiveWorkers(): Flow<List<WorkerEntity>>

    @Query("SELECT * FROM workers ORDER BY name ASC")
    fun observeAllWorkers(): Flow<List<WorkerEntity>>

    @Query("SELECT * FROM workers WHERE username = :username AND isActive = 1 LIMIT 1")
    suspend fun getWorkerByUsername(username: String): WorkerEntity?

    @Query("SELECT * FROM workers WHERE id = :id")
    suspend fun getWorkerById(id: Long): WorkerEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorker(worker: WorkerEntity): Long

    @Update
    suspend fun updateWorker(worker: WorkerEntity)

    @Query("UPDATE workers SET isActive = :isActive, updatedAt = :now WHERE id = :id")
    suspend fun setActive(id: Long, isActive: Boolean, now: Long = System.currentTimeMillis())

    // ── Backup / restore ──
    @Query("SELECT * FROM workers ORDER BY id ASC")
    suspend fun getAllWorkers(): List<WorkerEntity>
}


// ═══════════════════════════════════════════════════════════════════════════
// BILL DAO
// ═══════════════════════════════════════════════════════════════════════════
@Dao
interface BillDao {
    @Query("""SELECT item.productId AS productId, COUNT(DISTINCT item.billId) AS saleCount
        FROM bill_items item INNER JOIN bills b ON b.id = item.billId
        WHERE b.status = 'ACTIVE' AND item.productId NOT IN (:ids)
          AND item.billId IN (SELECT billId FROM bill_items WHERE productId IN (:ids))
        GROUP BY item.productId ORDER BY saleCount DESC, item.productId ASC LIMIT 40""")
    suspend fun getBoughtTogether(ids: List<Long>): List<ProductSalesFrequency>

    @Query("""SELECT i.productId, COUNT(DISTINCT i.billId) AS saleCount
        FROM bill_items i JOIN bills b ON b.id = i.billId
        WHERE b.status = 'ACTIVE' AND i.productId > 0
        GROUP BY i.productId""")
    suspend fun getProductSalesFrequency(): List<ProductSalesFrequency>
    @Query("""SELECT i.productId, COUNT(DISTINCT i.billId) AS saleCount
        FROM bill_items i JOIN bills b ON b.id = i.billId
        WHERE b.status = 'ACTIVE' AND i.productId > 0
        GROUP BY i.productId""")
    fun observeProductSalesFrequency(): Flow<List<ProductSalesFrequency>>
    @Query("""SELECT COUNT(*) AS billCount, COALESCE(SUM(totalPaisa),0) AS sales,
        COALESCE(SUM(discountPaisa),0) AS discounts, COALESCE(SUM(gstAmountPaisa),0) AS tax,
        COALESCE(SUM(CASE WHEN paymentMethod = 'SPLIT' THEN (SELECT COALESCE(SUM(p.amountPaisa),0) FROM payments p WHERE p.billId=bills.id AND p.method='CASH') WHEN paymentMethod IN ('CASH','CREDIT') THEN paidPaisa ELSE 0 END),0) AS cash,
        COALESCE(SUM(CASE WHEN paymentMethod = 'SPLIT' THEN (SELECT COALESCE(SUM(p.amountPaisa),0) FROM payments p WHERE p.billId=bills.id AND p.method='UPI') WHEN paymentMethod = 'UPI' THEN paidPaisa ELSE 0 END),0) AS upi,
        COALESCE(SUM(CASE WHEN paymentMethod = 'CARD' THEN paidPaisa ELSE 0 END),0) AS card,
        COALESCE(SUM(creditPaisa),0) AS credit
        FROM bills WHERE status = 'ACTIVE' AND createdAt >= :from AND createdAt < :to""")
    suspend fun getReportTotals(from: Long, to: Long): SalesTotals

    @Query("""SELECT i.productId, i.productName AS name, i.productUnit AS unit,
        SUM(i.quantity) AS quantity, SUM(i.itemTotalPaisa) AS grossSales
        FROM bill_items i JOIN bills b ON b.id = i.billId
        WHERE b.status = 'ACTIVE' AND b.createdAt >= :from AND b.createdAt < :to
        GROUP BY i.productId, i.productName, i.productUnit ORDER BY grossSales DESC LIMIT 20""")
    suspend fun getTopProducts(from: Long, to: Long): List<TopProductSales>

    @Query("SELECT * FROM day_closings WHERE closingDate = :date LIMIT 1")
    suspend fun getClosing(date: String): DayClosingEntity?

    @Query("SELECT * FROM day_closings ORDER BY closingDate DESC")
    suspend fun getAllClosings(): List<DayClosingEntity>

    @Query("DELETE FROM day_closings")
    suspend fun deleteAllClosings()


    // ── Sequential bill number ──
    @Query("SELECT MAX(CAST(billNumber AS INTEGER)) FROM bills WHERE billNumber != '' AND billNumber NOT GLOB '*[^0-9]*'")
    suspend fun getMaxNumericBillNumber(): Long?

    // ── Save entire bill (called as part of @Transaction in repository) ──
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBill(bill: BillEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBillItems(items: List<BillItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPayment(payment: PaymentEntity): Long

    // ── Fetch ──
    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getBillById(id: Long): BillEntity?

    @Query("SELECT * FROM bills WHERE billNumber = :billNumber LIMIT 1")
    suspend fun getBillByNumber(billNumber: String): BillEntity?

    @Query("SELECT * FROM bill_items WHERE billId = :billId ORDER BY id ASC")
    suspend fun getBillItems(billId: Long): List<BillItemEntity>

    @Query("SELECT * FROM bill_items WHERE id = :id")
    suspend fun getBillItemById(id: Long): BillItemEntity?

    @Query("SELECT * FROM bills WHERE EXISTS (SELECT 1 FROM bill_items WHERE bill_items.billId = bills.id AND bill_items.productId = 0) ORDER BY createdAt DESC")
    fun observeManualBills(): Flow<List<BillEntity>>

    @Query("SELECT * FROM payments WHERE billId = :billId")
    suspend fun getPaymentsForBill(billId: Long): List<PaymentEntity>

    // ── Worker: recent bills (last 50) ──
    @Query("""
        SELECT * FROM bills
        WHERE workerId = :workerId AND status != 'CANCELLED'
        ORDER BY createdAt DESC LIMIT 50
    """)
    fun observeWorkerRecentBills(workerId: Long): Flow<List<BillEntity>>

    // ── Owner: full bill history with filters ──
    @Query("""
        SELECT * FROM bills
        WHERE (:from = 0 OR createdAt >= :from)
          AND (:to = 0 OR createdAt <= :to)
          AND (:workerId = 0 OR workerId = :workerId)
          AND (:method = '' OR paymentMethod = :method)
          AND (:status = '' OR status = :status)
          AND (:query = '' OR instr(lower(billNumber), lower(:query)) > 0 OR instr(lower(customerName), lower(:query)) > 0 OR instr(customerPhone, :query) > 0)
        ORDER BY createdAt DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getBillsFiltered(
        from: Long = 0,
        to: Long = 0,
        workerId: Long = 0,
        method: String = "",
        status: String = "",
        limit: Int = 50,
        offset: Int = 0, query: String = ""
    ): List<BillEntity>

    // ── Dashboard aggregates ──
    @Query("""
        SELECT COALESCE(SUM(totalPaisa),0) FROM bills
        WHERE status = 'ACTIVE' AND createdAt >= :fromMs
    """)
    suspend fun getTotalSalesSince(fromMs: Long): Long

    @Query("""
        SELECT COUNT(*) FROM bills
        WHERE status = 'ACTIVE' AND createdAt >= :fromMs
    """)
    suspend fun getBillCountSince(fromMs: Long): Int

    @Query("""
        SELECT COALESCE(SUM(totalPaisa),0) FROM bills
        WHERE paymentMethod = :method AND status = 'ACTIVE' AND createdAt >= :fromMs
    """)
    suspend fun getSalesByMethodSince(method: String, fromMs: Long): Long

    @Query("""
        SELECT COALESCE(SUM(creditPaisa),0) FROM bills
        WHERE status = 'ACTIVE'
    """)
    suspend fun getTotalOutstandingCredit(): Long

    @Query("""
        SELECT COALESCE(SUM(discountPaisa),0) FROM bills
        WHERE status = 'ACTIVE' AND createdAt >= :fromMs
    """)
    suspend fun getTotalDiscountSince(fromMs: Long): Long

    // ── Owner: edit / cancel ──
    @Update
    suspend fun updateBill(bill: BillEntity)

    @Query("UPDATE bills SET status = 'CANCELLED', cancellationReason = :reason, updatedAt = :now WHERE id = :id")
    suspend fun cancelBill(id: Long, reason: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE bills SET isPrinted = 1 WHERE id = :id")
    suspend fun markPrinted(id: Long)

    // ── Day closing ──
    @Query("""
        SELECT * FROM bills
        WHERE createdAt >= :startOfDay AND createdAt < :endOfDay
        ORDER BY createdAt ASC
    """)
    suspend fun getBillsForDay(startOfDay: Long, endOfDay: Long): List<BillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDayClosing(closing: DayClosingEntity): Long

    @Query("SELECT * FROM day_closings ORDER BY closingDate DESC LIMIT 30")
    fun observeRecentDayClosings(): Flow<List<DayClosingEntity>>

    // ── Backup / restore ──
    @Query("SELECT * FROM bills ORDER BY id ASC")
    suspend fun getAllBills(): List<BillEntity>

    @Query("DELETE FROM bills")
    suspend fun deleteAllBills()

    @Query("SELECT * FROM bill_items ORDER BY id ASC")
    suspend fun getAllBillItems(): List<BillItemEntity>

    @Query("DELETE FROM bill_items")
    suspend fun deleteAllBillItems()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBillItem(item: BillItemEntity)

    @Query("SELECT * FROM payments ORDER BY id ASC")
    suspend fun getAllPayments(): List<PaymentEntity>

    @Query("DELETE FROM payments")
    suspend fun deleteAllPayments()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPaymentForRestore(payment: PaymentEntity)

    // ── Dashboard: cancelled bill count ──
    @Query("SELECT COUNT(*) FROM bills WHERE status = 'CANCELLED' AND createdAt >= :fromMs")
    suspend fun getCancelledBillCountSince(fromMs: Long): Int
}


// ═══════════════════════════════════════════════════════════════════════════
// CREDIT DAO
// ═══════════════════════════════════════════════════════════════════════════
@Dao
interface CreditDao {
    @Query("SELECT EXISTS(SELECT 1 FROM credit_transactions WHERE customerId = :customerId AND amountPaisa < 0)")
    suspend fun hasCustomerSettlements(customerId: Long): Boolean

    @Query("SELECT customerId, SUM(amountPaisa) AS balance FROM credit_transactions GROUP BY customerId")
    fun observeAllBalances(): Flow<List<CustomerBalance>>


    @Query("""
        SELECT COALESCE(SUM(amountPaisa),0) FROM credit_transactions
        WHERE customerId = :customerId
    """)
    suspend fun getBalanceForCustomer(customerId: Long): Long

    @Query("""
        SELECT * FROM credit_transactions
        WHERE customerId = :customerId
        ORDER BY createdAt DESC
    """)
    fun observeTransactionsForCustomer(customerId: Long): Flow<List<CreditTransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransaction(tx: CreditTransactionEntity): Long

    // ── Customers with outstanding balance ──
    @Query("""
        SELECT customerId, SUM(amountPaisa) AS balance
        FROM credit_transactions
        GROUP BY customerId
        HAVING balance > 0
        ORDER BY balance DESC
    """)
    suspend fun getCustomersWithBalance(): List<CustomerBalance>

    @Query("SELECT COALESCE(SUM(balance),0) FROM (SELECT SUM(amountPaisa) AS balance FROM credit_transactions GROUP BY customerId HAVING SUM(amountPaisa) > 0)")
    suspend fun getTotalOutstanding(): Long

    @Query("SELECT COALESCE(SUM(balance),0) FROM (SELECT SUM(amountPaisa) AS balance FROM credit_transactions GROUP BY customerId HAVING SUM(amountPaisa) > 0)")
    fun observeTotalOutstanding(): Flow<Long>

    // ── Backup / restore ──
    @Query("SELECT * FROM credit_transactions ORDER BY id ASC")
    suspend fun getAllTransactions(): List<CreditTransactionEntity>

    @Query("DELETE FROM credit_transactions")
    suspend fun deleteAllTransactions()
}

data class CustomerBalance(val customerId: Long, val balance: Long)


// ═══════════════════════════════════════════════════════════════════════════
// STOCK DAO
// ═══════════════════════════════════════════════════════════════════════════
@Dao
interface StockDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAdjustment(adjustment: StockAdjustmentEntity): Long

    @Query("""
        SELECT * FROM stock_adjustments
        WHERE productId = :productId
        ORDER BY createdAt DESC
    """)
    fun observeAdjustmentsForProduct(productId: Long): Flow<List<StockAdjustmentEntity>>

    @Query("SELECT * FROM stock_adjustments ORDER BY createdAt DESC LIMIT 200")
    fun observeRecentAdjustments(): Flow<List<StockAdjustmentEntity>>

    // ── Backup / restore ──
    @Query("SELECT * FROM stock_adjustments ORDER BY id ASC")
    suspend fun getAllAdjustments(): List<StockAdjustmentEntity>

    @Query("DELETE FROM stock_adjustments")
    suspend fun deleteAllAdjustments()
}


// ═══════════════════════════════════════════════════════════════════════════
// AUDIT LOG DAO
// ═══════════════════════════════════════════════════════════════════════════
@Dao
interface AuditLogDao {

    // INSERT only — never update or delete audit records
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLog(log: AuditLogEntity): Long

    @Query("""
        SELECT * FROM audit_logs
        WHERE (:actionType = '' OR actionType = :actionType)
          AND (:actorId = 0 OR actorId = :actorId)
          AND (:billId = 0 OR billId = :billId)
          AND (:from = 0 OR createdAt >= :from)
          AND (:to = 0 OR createdAt <= :to)
        ORDER BY createdAt DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getLogs(
        actionType: String = "",
        actorId: Long = 0,
        billId: Long = 0,
        from: Long = 0,
        to: Long = 0,
        limit: Int = 100,
        offset: Int = 0
    ): List<AuditLogEntity>

    @Query("SELECT COUNT(*) FROM audit_logs")
    suspend fun count(): Long

    @Query("SELECT * FROM audit_logs ORDER BY createdAt DESC LIMIT 50")
    fun observeRecentLogs(): Flow<List<AuditLogEntity>>
}


// ═══════════════════════════════════════════════════════════════════════════
// SETTINGS DAO
// ═══════════════════════════════════════════════════════════════════════════
@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE `key` LIKE :prefix || '%' ORDER BY updatedAt DESC, `key` ASC")
    suspend fun getFeatureRecords(prefix:String):List<SettingsEntity>

    @Query("SELECT * FROM settings WHERE `key` LIKE :prefix || '%' ORDER BY updatedAt DESC, `key` ASC")
    fun observeFeatureRecords(prefix: String): Flow<List<SettingsEntity>>

    @Query("SELECT * FROM settings WHERE `key` LIKE 'pipe:billing-alias:%'")
    fun observePipeBillingAliases(): Flow<List<SettingsEntity>>

    @Query("SELECT * FROM settings WHERE `key` LIKE 'pipe-offcut:%' ORDER BY updatedAt DESC")
    fun observePipeOffcuts(): Flow<List<SettingsEntity>>
    @Query("SELECT * FROM settings WHERE `key` LIKE 'pipe-offcut:%'")
    suspend fun getPipeOffcuts(): List<SettingsEntity>

    @Query("SELECT * FROM settings WHERE `key` LIKE 'pipe:billing-alias:%'")
    suspend fun getPipeBillingAliases(): List<SettingsEntity>

    @Query("SELECT * FROM settings WHERE `key` LIKE 'billing-category-pin:%' ORDER BY updatedAt ASC, `key` ASC")
    fun observePinnedBillingCategories(): Flow<List<SettingsEntity>>
    @Query("SELECT * FROM settings WHERE `key` LIKE 'billing-favourite:%' ORDER BY updatedAt ASC, `key` ASC")
    fun observeBillingFavourites(): Flow<List<SettingsEntity>>

    @Query("SELECT * FROM settings WHERE `key` LIKE 'manual-product:%' ORDER BY updatedAt DESC")
    fun observeManualProducts(): Flow<List<SettingsEntity>>

    @Query("SELECT * FROM settings WHERE `key` LIKE 'cart:%'")
    fun observeCarts(): Flow<List<SettingsEntity>>

    @Query("DELETE FROM settings WHERE `key` LIKE 'checkout:%' OR `key` LIKE 'cart:%' OR `key` LIKE 'product:%' OR `key` LIKE 'purchase:%' OR `key` LIKE 'reorder:%' OR `key` LIKE 'manual-product:%' OR `key` LIKE 'billing-favourite:%' OR `key` LIKE 'billing-category-pin:%' OR `key` LIKE 'credit-payment:%' OR `key` LIKE 'pipe:billing-alias:%' OR `key` LIKE 'catalog:pipe-dual-rate:%' OR `key` LIKE 'pipe-offcut:%' OR `key` LIKE 'pipe-cut-plan:%' OR `key` LIKE 'quotation:%' OR `key` LIKE 'quotation-bill:%' OR `key` LIKE 'warranty:%' OR `key` LIKE 'weight-calibration:%' OR `key` LIKE 'pipe-offcut-bill:%' OR `key` LIKE 'want-list:%' OR `key` LIKE 'credit-reminder-due:%' OR `key` LIKE 'credit-reminder-attempt:%'")
    suspend fun clearWorkspaces()


    @Query("SELECT * FROM settings WHERE `key` LIKE 'product:%'")
    fun observeProductWorkspace(): Flow<List<SettingsEntity>>

    @Query("SELECT * FROM settings WHERE `key` LIKE 'product:%'")
    suspend fun getProductWorkspace(): List<SettingsEntity>

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun deleteSetting(key: String)


    @Query("SELECT * FROM settings WHERE `key` = :key LIMIT 1")
    suspend fun getSetting(key: String): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSetting(setting: SettingsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: List<SettingsEntity>)

    @Query("SELECT * FROM settings")
    suspend fun getAllSettings(): List<SettingsEntity>
}

data class ProductSalesFrequency(val productId: Long, val saleCount: Long)


// ═══════════════════════════════════════════════════════════════════════════
// OWNER CREDENTIAL DAO
// ═══════════════════════════════════════════════════════════════════════════
@Dao
interface OwnerCredentialDao {

    @Query("SELECT * FROM owner_credentials WHERE id = 1 LIMIT 1")
    suspend fun getOwnerCredential(): OwnerCredentialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCredential(credential: OwnerCredentialEntity)

    @Query("SELECT * FROM owner_credentials WHERE id = 1 LIMIT 1")
    fun observeOwnerCredential(): Flow<OwnerCredentialEntity?>
}


data class SalesTotals(val billCount: Int, val sales: Long, val discounts: Long, val tax: Long,
    val cash: Long, val upi: Long, val card: Long, val credit: Long)
data class TopProductSales(val productId: Long, val name: String, val unit: String,
    val quantity: Double, val grossSales: Long)

package com.hardwareshop.pos.data.local.entity

import androidx.room.*

// ─────────────────────────────────────────────────────────────────────────────
// MONEY CONVENTION
//   All monetary fields are stored in PAISA (Long).
//   1 Rupee = 100 Paisa.  ₹12.50 → 1250L.
//   This eliminates floating-point rounding bugs entirely.
//
// QUANTITY CONVENTION
//   Quantities are stored as Double (max 2 decimal places validated in UI).
//   e.g., 2.5 metres, 10 pieces, 3.00 kg.
//
// GST RATE CONVENTION
//   Stored as Int in basis-points: 18% → 1800.
//   Calculation: amount * gstRateBp / 10000.
//
// TIMESTAMPS
//   Long, epoch-milliseconds (System.currentTimeMillis()).
// ─────────────────────────────────────────────────────────────────────────────


// ═══════════════════════════════════════════════════════════════════════════
// CATEGORY
// ═══════════════════════════════════════════════════════════════════════════
@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                   // "PVC", "Electrical", "Hardware"
    val displayOrder: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)


// ═══════════════════════════════════════════════════════════════════════════
// PRODUCT TYPE  (child of Category)
// ═══════════════════════════════════════════════════════════════════════════
@Entity(
    tableName = "product_types",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT   // Prevent orphan types
        )
    ],
    indices = [
        Index("categoryId"),
        Index(value = ["categoryId", "name"], unique = true)
    ]
)
data class ProductTypeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val name: String,                   // "Elbow", "Tee", "Pipe"
    val displayOrder: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)


// ═══════════════════════════════════════════════════════════════════════════
// PRODUCT
// ═══════════════════════════════════════════════════════════════════════════
@Entity(
    tableName = "products",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProductTypeEntity::class,
            parentColumns = ["id"],
            childColumns = ["typeId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("categoryId"),
        Index("typeId"),
        Index("isActive"),
        // Full-text search is handled by FTS companion table (AppDatabase)
    ]
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val typeId: Long,
    /** Human-readable display name, e.g. "PVC Elbow 1/2\"" */
    val name: String,
    /** Size descriptor, e.g. "1/2\"", "20mm", "2kg" — empty if not applicable */
    val size: String = "",
    /** Display unit, e.g. "Piece", "Meter", "Kg", "Box" */
    val unit: String,
    /** Selling price in paisa. Workers see this; cannot change it. */
    val sellingPricePaisa: Long,
    /** Purchase / cost price in paisa. Owner-only. */
    val purchasePricePaisa: Long = 0L,
    /** GST rate in basis-points (18% = 1800). Per-product override. 0 = exempt. */
    val gstRateBp: Int = 0,
    /** Aliases for search — comma-separated */
    val searchAliases: String = "",
    /** Optional box count for piece-based stock. Zero means not configured. */
    @ColumnInfo(defaultValue = "0") val piecesPerBox: Int = 0,
    @ColumnInfo(defaultValue = "0") val isPipe: Boolean = false,
    @ColumnInfo(defaultValue = "0") val pipeLengthPricePaisa: Long = 0,
    @ColumnInfo(defaultValue = "0") val pipeFootPricePaisa: Long = 0,
    @ColumnInfo(defaultValue = "0") val pipeFeetPerLength: Int = 0,
    val currentStock: Double = 0.0,
    val minimumStock: Double = 0.0,
    /** Relative path under app's private storage, null = no image */
    val imagePath: String? = null,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)


// Full-text search companion table for products
@Fts4(contentEntity = ProductEntity::class)
@Entity(tableName = "products_fts")
data class ProductFtsEntity(
    val name: String,
    val size: String,
    val unit: String,
    val searchAliases: String
)


// ═══════════════════════════════════════════════════════════════════════════
// CUSTOMER
// ═══════════════════════════════════════════════════════════════════════════
@Entity(
    tableName = "customers",
    indices = [
        Index("phone"),
        Index("name")
    ]
)
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val address: String = "",
    val gstNumber: String = "",             // For GST billing
    val creditLimitPaisa: Long = 0L,        // 0 = no limit configured
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)


// ═══════════════════════════════════════════════════════════════════════════
// WORKER
// ═══════════════════════════════════════════════════════════════════════════
@Entity(
    tableName = "workers",
    indices = [Index("username", unique = true)]
)
data class WorkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val username: String,
    /** PBKDF2-derived hash — never plain text */
    val passwordHash: String,
    /** PBKDF2 salt (Base64) */
    val passwordSalt: String,
    val phone: String = "",
    /** Max discount a worker can apply at bill level, in paisa */
    val maxDiscountPaisa: Long = 0L,
    /** Require owner re-auth if worker applies any discount above maxDiscountPaisa */
    val requireOwnerApprovalForDiscount: Boolean = true,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)


// ═══════════════════════════════════════════════════════════════════════════
// BILL
// ═══════════════════════════════════════════════════════════════════════════
@Entity(
    tableName = "bills",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = WorkerEntity::class,
            parentColumns = ["id"],
            childColumns = ["workerId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("billNumber", unique = true),
        Index("customerId"),
        Index("workerId"),
        Index("status"),
        Index("createdAt"),
        Index("paymentMethod")
    ]
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Sequential: "000001", "000002" … */
    val billNumber: String,
    val customerId: Long? = null,
    val customerName: String = "",          // Snapshot — in case customer is deleted
    val customerPhone: String = "",
    val workerId: Long? = null,
    val workerName: String = "",            // Snapshot

    // ── Financials ──
    val subtotalPaisa: Long,
    val discountPaisa: Long = 0L,
    val discountNote: String = "",
    val gstEnabled: Boolean = false,
    val gstAmountPaisa: Long = 0L,
    val totalPaisa: Long,

    // ── Payment ──
    /** CASH | UPI | CARD | CREDIT */
    val paymentMethod: String,
    val paidPaisa: Long = 0L,              // Amount actually paid at time of billing
    val creditPaisa: Long = 0L,            // Remaining on credit

    // ── Status ──
    /** ACTIVE | CANCELLED | EDITED */
    val status: String = "ACTIVE",
    val cancellationReason: String = "",

    // ── GST fields ──
    val shopGstNumber: String = "",
    val customerGstNumber: String = "",

    val notes: String = "",
    val isPrinted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)


// ═══════════════════════════════════════════════════════════════════════════
// BILL ITEM  (line items inside a bill)
// ═══════════════════════════════════════════════════════════════════════════
@Entity(
    tableName = "bill_items",
    foreignKeys = [
        ForeignKey(
            entity = BillEntity::class,
            parentColumns = ["id"],
            childColumns = ["billId"],
            onDelete = ForeignKey.CASCADE   // Items deleted when bill is deleted
        )
    ],
    indices = [Index("billId"), Index("productId")]
)
data class BillItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billId: Long,
    val productId: Long,
    // ── Snapshots at time of sale — history must be immutable ──
    val productName: String,
    val productSize: String,
    val productUnit: String,
    val quantity: Double,
    val unitPricePaisa: Long,               // Selling price at time of sale
    val gstRateBp: Int = 0,
    val itemTotalPaisa: Long,               // immutable sale amount
    @ColumnInfo(defaultValue = "0") val isPipe: Boolean = false,
    @ColumnInfo(defaultValue = "0") val pipeLengths: Long = 0,
    @ColumnInfo(defaultValue = "0") val pipeFeet: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val pipeFootPricePaisa: Long = 0,
    val gstAmountPaisa: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)


// ═══════════════════════════════════════════════════════════════════════════
// PAYMENT  (supports split / partial payments for a single bill)
// ═══════════════════════════════════════════════════════════════════════════
@Entity(
    tableName = "payments",
    foreignKeys = [
        ForeignKey(
            entity = BillEntity::class,
            parentColumns = ["id"],
            childColumns = ["billId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("billId")]
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billId: Long,
    /** CASH | UPI | CARD */
    val method: String,
    val amountPaisa: Long,
    val reference: String = "",             // UPI UTR / card last-4
    val createdAt: Long = System.currentTimeMillis()
)


// ═══════════════════════════════════════════════════════════════════════════
// CREDIT TRANSACTION  (Udhaar ledger)
// ═══════════════════════════════════════════════════════════════════════════
@Entity(
    tableName = "credit_transactions",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("customerId"), Index("billId"), Index("createdAt")]
)
data class CreditTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long,
    val billId: Long? = null,
    /** CREDIT_CREATED | PARTIAL_PAYMENT | FULL_PAYMENT | ADJUSTMENT */
    val type: String,
    val amountPaisa: Long,                  // Positive = credit added, Negative = payment
    val previousBalancePaisa: Long,
    val newBalancePaisa: Long,
    val notes: String = "",
    val recordedByWorkerId: Long? = null,
    val recordedByOwnerId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)


// ═══════════════════════════════════════════════════════════════════════════
// STOCK ADJUSTMENT  (owner-initiated manual corrections)
// ═══════════════════════════════════════════════════════════════════════════
@Entity(
    tableName = "stock_adjustments",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("productId"), Index("createdAt")]
)
data class StockAdjustmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val productName: String,                // Snapshot
    val oldStock: Double,
    val newStock: Double,
    val difference: Double,
    /** Physical stock correction | Damaged goods | Opening stock | Purchase received | Other */
    val reason: String,
    val notes: String = "",
    val adjustedByOwnerId: Long,
    val createdAt: Long = System.currentTimeMillis()
)


// ═══════════════════════════════════════════════════════════════════════════
// AUDIT LOG  (append-only, workers cannot delete)
// ═══════════════════════════════════════════════════════════════════════════
@Entity(
    tableName = "audit_logs",
    indices = [
        Index("actionType"),
        Index("entityType"),
        Index("billId"),
        Index("productId"),
        Index("actorId"),
        Index("createdAt")
    ]
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** BILL_CREATED | BILL_EDITED | BILL_CANCELLED | DISCOUNT_APPLIED |
     *  PAYMENT_RECORDED | CREDIT_CREATED | CREDIT_PAYMENT |
     *  PRICE_CHANGED | STOCK_ADJUSTED | PRODUCT_CREATED | PRODUCT_EDITED |
     *  WORKER_LOGIN | WORKER_LOGOUT | OWNER_LOGIN | OWNER_LOGOUT |
     *  SETTINGS_CHANGED | BACKUP_CREATED | RESTORE_PERFORMED | PRINT_ATTEMPTED | PRINT_OK | PRINT_FAILED */
    val actionType: String,
    /** BILL | PRODUCT | CUSTOMER | WORKER | SETTINGS | STOCK | CREDIT | SYSTEM */
    val entityType: String,
    val entityId: Long? = null,
    val billId: Long? = null,
    val productId: Long? = null,
    val customerId: Long? = null,
    /** WORKER | OWNER | SYSTEM */
    val actorRole: String,
    val actorId: Long? = null,
    val actorName: String = "",
    /** JSON snapshot of state before action */
    val beforeValue: String? = null,
    /** JSON snapshot of state after action */
    val afterValue: String? = null,
    val reason: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)


// ═══════════════════════════════════════════════════════════════════════════
// SETTINGS  (key-value store for owner configuration)
// ═══════════════════════════════════════════════════════════════════════════
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)


// ═══════════════════════════════════════════════════════════════════════════
// DAY CLOSING
// ═══════════════════════════════════════════════════════════════════════════
@Entity(
    tableName = "day_closings",
    indices = [Index("closingDate", unique = true)]
)
data class DayClosingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** yyyy-MM-dd */
    val closingDate: String,
    val totalSalesPaisa: Long,
    val cashSalesPaisa: Long,
    val upiSalesPaisa: Long,
    val cardSalesPaisa: Long,
    val creditSalesPaisa: Long,
    val totalDiscountsPaisa: Long,
    val totalGstPaisa: Long,
    val totalBillCount: Int,
    val cancelledBillCount: Int,
    val expectedCashPaisa: Long,
    val notes: String = "",
    val closedByOwnerId: Long,
    val createdAt: Long = System.currentTimeMillis()
)


// ═══════════════════════════════════════════════════════════════════════════
// OWNER CREDENTIAL  (single owner account)
// ═══════════════════════════════════════════════════════════════════════════
@Entity(tableName = "owner_credentials")
data class OwnerCredentialEntity(
    @PrimaryKey val id: Int = 1,            // Always 1 — single owner
    val passwordHash: String,
    val passwordSalt: String,
    val biometricEnabled: Boolean = false,
    val shopName: String = "",
    val shopPhone: String = "",
    val shopAddress: String = "",
    val shopGstNumber: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)


// ═══════════════════════════════════════════════════════════════════════════
// SUPPLIER  (Phase 2 — schema present now to avoid migrations later)
// ═══════════════════════════════════════════════════════════════════════════
@Entity(tableName = "suppliers")
data class SupplierEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val contactName: String = "",
    val phone: String = "",
    val address: String = "",
    val gstNumber: String = "",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)


// ═══════════════════════════════════════════════════════════════════════════
// PURCHASE BILL  (Phase 2 — schema present, logic not wired yet)
// ═══════════════════════════════════════════════════════════════════════════
@Entity(
    tableName = "purchase_bills",
    foreignKeys = [
        ForeignKey(
            entity = SupplierEntity::class,
            parentColumns = ["id"],
            childColumns = ["supplierId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("supplierId"), Index("billDate")]
)
data class PurchaseBillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val supplierId: Long,
    val billDate: Long = System.currentTimeMillis(),
    val supplierBillNumber: String = "",
    val totalPaisa: Long = 0L,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

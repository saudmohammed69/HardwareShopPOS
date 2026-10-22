package com.hardwareshop.pos.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import timber.log.Timber
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hardwareshop.pos.data.local.dao.*
import com.hardwareshop.pos.data.local.database.migrations.MIGRATION_2_3
import com.hardwareshop.pos.data.local.entity.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        CategoryEntity::class,
        ProductTypeEntity::class,
        ProductEntity::class,
        ProductFtsEntity::class,
        CustomerEntity::class,
        WorkerEntity::class,
        BillEntity::class,
        BillItemEntity::class,
        PaymentEntity::class,
        CreditTransactionEntity::class,
        StockAdjustmentEntity::class,
        AuditLogEntity::class,
        SettingsEntity::class,
        DayClosingEntity::class,
        OwnerCredentialEntity::class,
        SupplierEntity::class,
        PurchaseBillEntity::class,
        PurchaseBillItemEntity::class,
        RemotePrintJobEntity::class,
        ShopDeviceEntity::class,
        SupplierPaymentEntity::class, SaleReturnEntity::class, SaleReturnItemEntity::class,
        SyncOutboxEntity::class, SyncCheckpointEntity::class, ProductAliasEntity::class
    ],
    version = 11,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun productTypeDao(): ProductTypeDao
    abstract fun productDao(): ProductDao
    abstract fun customerDao(): CustomerDao
    abstract fun workerDao(): WorkerDao
    abstract fun billDao(): BillDao
    abstract fun creditDao(): CreditDao
    abstract fun stockDao(): StockDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun settingsDao(): SettingsDao
    abstract fun ownerCredentialDao(): OwnerCredentialDao
    abstract fun tradeDao(): TradeDao
    abstract fun supplierDao(): SupplierDao
    abstract fun purchaseBillDao(): PurchaseBillDao
    abstract fun purchaseBillItemDao(): PurchaseBillItemDao
    abstract fun remotePrintJobDao(): RemotePrintJobDao
    abstract fun shopDeviceDao(): ShopDeviceDao
    abstract fun aliasDao(): ProductAliasDao
    abstract fun syncDao(): SyncDao

    companion object {

        private const val DB_NAME = "hardware_shop_pos.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also {
                    instance = it
                }
            }

        private fun buildDatabase(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
                .addMigrations(*ALL_MIGRATIONS)
                .addCallback(SeedCallback(context.applicationContext))
                .setJournalMode(
                    JournalMode.WRITE_AHEAD_LOGGING
                )
                .build()

        private val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            com.hardwareshop.pos.data.local.database.migrations.MIGRATION_3_4,
            com.hardwareshop.pos.data.local.database.migrations.MIGRATION_4_5,
            com.hardwareshop.pos.data.local.database.migrations.MIGRATION_5_6,
            com.hardwareshop.pos.data.local.database.migrations.MIGRATION_6_7,
            com.hardwareshop.pos.data.local.database.migrations.MIGRATION_7_8,
            com.hardwareshop.pos.data.local.database.migrations.MIGRATION_8_9,
            com.hardwareshop.pos.data.local.database.migrations.MIGRATION_9_10,
            com.hardwareshop.pos.data.local.database.migrations.MIGRATION_10_11
        )
    }

    private class SeedCallback(
        private val context: Context
    ) : Callback() {

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            // CREATE IF NOT EXISTS is safe on reopen; bootstrap is guarded and atomic.
            db.beginTransaction()
            try {
                com.hardwareshop.pos.data.sync.SyncJournalSchema.statements(includeBoxCount = true, includePipe = true).forEach { db.execSQL(it) }
                com.hardwareshop.pos.data.sync.SyncTransferSchema.statements().forEach { db.execSQL(it) }
                AliasIndexSchema.triggerStatements().forEach {db.execSQL(it)}
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }

        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    seedDefaultCategories(getInstance(context))
                } catch (e: Exception) {
                    Timber.e(e, "Could not seed default categories")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// MIGRATION 1 → 2 — Add remote print tables
// ─────────────────────────────────────────────────────────────

private val MIGRATION_1_2 = object : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS remote_print_jobs (
                jobId                TEXT NOT NULL PRIMARY KEY,
                shopId               TEXT NOT NULL,
                billId               INTEGER NOT NULL,
                billNumber           TEXT NOT NULL,
                receiptPayloadBase64 TEXT NOT NULL,
                status               TEXT NOT NULL DEFAULT 'PENDING',
                deviceId             TEXT NOT NULL DEFAULT '',
                createdByUserId      TEXT NOT NULL DEFAULT '',
                createdAt            INTEGER NOT NULL,
                printedAt            INTEGER,
                lastError            TEXT,
                retryCount           INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_remote_print_jobs_status " +
                    "ON remote_print_jobs (status)"
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_remote_print_jobs_billId " +
                    "ON remote_print_jobs (billId)"
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_remote_print_jobs_shopId " +
                    "ON remote_print_jobs (shopId)"
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_remote_print_jobs_createdAt " +
                    "ON remote_print_jobs (createdAt)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS shop_devices (
                deviceId      TEXT NOT NULL PRIMARY KEY,
                deviceName    TEXT NOT NULL,
                fcmToken      TEXT NOT NULL DEFAULT '',
                shopId        TEXT NOT NULL,
                isRegistered  INTEGER NOT NULL DEFAULT 0,
                registeredAt  INTEGER NOT NULL,
                lastHeartbeat INTEGER
            )
            """.trimIndent()
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Seed default categories
// ─────────────────────────────────────────────────────────────

private suspend fun seedDefaultCategories(db: AppDatabase) = db.withTransaction {
    // Serialize seeding with restore and avoid duplicate categories after a concurrent import.
    if (db.categoryDao().getAllCategories().isNotEmpty()) return@withTransaction

    val catDao = db.categoryDao()
    val typeDao = db.productTypeDao()

    data class SC(
        val name: String,
        val types: List<String>
    )

    val seeds = listOf(
        SC(
            "PVC",
            listOf(
                "Elbow", "Tee", "Coupling", "End Cap",
                "Reducer", "Pipe", "Union", "Valve", "Flange"
            )
        ),
        SC(
            "CPVC",
            listOf(
                "Elbow", "Tee", "Coupling", "Pipe",
                "End Cap", "Reducer"
            )
        ),
        SC(
            "GI Pipes & Fittings",
            listOf(
                "Pipe", "Elbow", "Tee", "Union",
                "Socket", "Nipple", "Reducer"
            )
        ),
        SC(
            "Electrical",
            listOf(
                "Wire", "Switch", "Socket", "MCB",
                "Conduit", "Junction Box", "Cable"
            )
        ),
        SC(
            "Plumbing Tools",
            listOf(
                "Wrench", "Tape", "Die Set",
                "Pipe Cutter", "Thread Seal"
            )
        ),
        SC(
            "Construction",
            listOf(
                "Cement", "Sand", "Aggregate",
                "Brick", "Rod", "Block"
            )
        ),
        SC(
            "Hardware & Fasteners",
            listOf(
                "Screw", "Bolt", "Nut", "Washer",
                "Nail", "Anchor"
            )
        ),
        SC(
            "Adhesives & Sealants",
            listOf(
                "PVC Solvent", "Epoxy", "Silicone",
                "Sealant", "Tape"
            )
        ),
        SC(
            "Valves",
            listOf(
                "Ball Valve", "Gate Valve", "Check Valve",
                "Globe Valve", "Butterfly Valve"
            )
        ),
        SC(
            "Tools",
            listOf(
                "Hammer", "Screwdriver", "Drill",
                "Level", "Measuring Tape", "Chisel"
            )
        ),
        SC(
            "Other",
            listOf("Miscellaneous")
        )
    )

    seeds.forEachIndexed { ci, seed ->

        val catId = catDao.insertCategory(
            CategoryEntity(
                name = seed.name,
                displayOrder = ci
            )
        )

        seed.types.forEachIndexed { ti, typeName ->

            typeDao.insertType(
                ProductTypeEntity(
                    categoryId = catId,
                    name = typeName,
                    displayOrder = ti
                )
            )
        }
    }
}

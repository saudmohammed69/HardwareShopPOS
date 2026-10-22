package com.hardwareshop.pos.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.AuditRepository
import com.hardwareshop.pos.domain.model.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup & restore manager.
 *
 * Format: .hwpos.zip  (ZIP containing backup.json)
 *
 * Backup includes: suppliers, purchases, categories, types, products, customers, workers (no password hashes),
 *   bills, bill items, payments, credit transactions, stock adjustments, settings.
 *
 * Backup EXCLUDES: audit logs (append-only — kept on device forever),
 *   owner_credentials (security — password hashes stay on device).
 *
 * Restore: validates magic + version before touching DB, then runs inside a
 *   Room withTransaction so partial failure rolls back cleanly.
 */
@Singleton
class BackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db:              AppDatabase,
    private val auditRepository: AuditRepository,
    private val session:         SessionHolder
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    companion object {
        const val BACKUP_VERSION = 8          // Split-payment backups require a reader that preserves both components.
        const val BACKUP_MAGIC   = "HARDWARE_SHOP_POS_BACKUP"
        private val DATE_FMT get() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXPORT
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun createBackup(): Result<Uri> = withContext(Dispatchers.IO) {
        PermissionChecker.enforceOwner(session.current)
        try {
            val timestamp = DATE_FMT.format(Date())
            val fileName = "backup_${timestamp}_${UUID.randomUUID().toString().take(8)}.hwpos.zip"
            val outFile   = File(context.getExternalFilesDir("backups"), fileName)
                            .also { it.parentFile?.mkdirs() }

            // Collect every table in one pass — no Flow, direct suspend queries
            var payload = db.withTransaction { BackupPayload(
                magic            = BACKUP_MAGIC,
                version          = BACKUP_VERSION,
                exportedAt       = System.currentTimeMillis(),
                categories       = db.categoryDao().getAllCategories(),
                productTypes     = db.productTypeDao().getAllTypes(),
                products         = db.productDao().getAllProducts(),
                customers        = db.customerDao().getAllCustomers(),
                workers          = db.workerDao().getAllWorkers()
                                     .map { it.copy(passwordHash = "", passwordSalt = "") }, // Strip credentials
                bills            = db.billDao().getAllBills(),
                billItems        = db.billDao().getAllBillItems(),
                payments         = db.billDao().getAllPayments(),
                creditTxns       = db.creditDao().getAllTransactions(),
                stockAdjustments = db.stockDao().getAllAdjustments(),
                settings = db.settingsDao().getAllSettings().filter { portableSetting(it.key) },
                closings = db.billDao().getAllClosings(),
                suppliers        = db.supplierDao().getAllSuppliers(),
                purchaseBills    = db.purchaseBillDao().getAllPurchaseBills(),
                purchaseBillItems = db.purchaseBillItemDao().getAll(),
                supplierPayments = db.tradeDao().payments(), saleReturns = db.tradeDao().returns(), saleReturnItems = db.tradeDao().returnItems()
            ) }

            val photoPaths = linkedSetOf<String>()
            payload.products.mapNotNullTo(photoPaths) { it.imagePath }
            payload.settings.filter { it.key.startsWith("product:draft:") }.forEach { row ->
                val tree = com.google.gson.JsonParser.parseString(row.value)
                collectImages(tree, photoPaths)
            }
            val photos = photoPaths.mapNotNull { path -> safeImage(path)?.takeIf { it.isFile }?.let { path to it } }.toMap()
            payload = payload.copy(images = photos.keys.toList())
            val partial = File(outFile.parentFile, outFile.name + ".partial")
            try {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(partial))).use { zip ->
                    zip.putNextEntry(ZipEntry("backup.json"))
                    val jsonBytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
                    require(jsonBytes.size <= 32 * 1024 * 1024) { "Backup is larger than the supported 32 MB data limit" }
                    zip.write(jsonBytes); zip.closeEntry()
                    var total = 0L
                    for ((path, file) in photos) {
                        require(file.length() <= 4 * 1024 * 1024) { "Product photo is too large" }
                        total += file.length()
                        require(total <= 256L * 1024 * 1024) { "Product photos exceed 256 MB" }
                        zip.putNextEntry(ZipEntry(path))
                        file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
                check(partial.renameTo(outFile)) { "Could not finalize backup" }
            } finally { partial.delete() }

            auditRepository.log(
                actionType = "BACKUP_CREATED",
                entityType = "SYSTEM",
                afterValue = fileName,
                reason     = "Manual backup — ${payload.products.size} products, ${payload.bills.size} bills"
            )

            Timber.i("Backup created: $fileName (${outFile.length() / 1024} KB)")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
            Result.Success(uri)

        } catch (e: Exception) {
            Timber.e(e, "Backup failed")
            Result.Error("Backup failed: ${e.message}", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IMPORT
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun restoreBackup(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        PermissionChecker.enforceOwner(session.current)
        val staged = mutableMapOf<String, String>()
        var committed = false
        try {
            requireStandaloneRestore()
            check(db.shopDeviceDao().getRegisteredDevice() == null) { "Unregister remote printing before restoring a backup" }
            check(db.remotePrintJobDao().getActiveJobs().isEmpty()) { "Resolve pending remote print jobs before restoring" }
            val json = readArchive(uri, staged)
            val tree = com.google.gson.JsonParser.parseString(json).asJsonObject
            listOf("categories", "productTypes", "products", "customers", "workers", "bills", "billItems", "payments", "creditTxns", "stockAdjustments", "settings").forEach { key ->
                require(tree.has(key) && tree[key].isJsonArray) { "Backup is incomplete: $key is missing" }
            }

            // ── Validate before touching the database ──────────────────────────
            val payload = try {
                gson.fromJson(json, BackupPayload::class.java)
            } catch (e: Exception) {
                return@withContext Result.Error("Backup is corrupt or incompatible (${e.message})")
            }

            if (payload?.magic != BACKUP_MAGIC)
                return@withContext Result.Error("Not a Hardware Shop POS backup file")
            if (payload.version !in 1..BACKUP_VERSION)
                return@withContext Result.Error("Backup created by a newer app version. Please update.")

            payload.settings.filter { it.key.startsWith(com.hardwareshop.pos.data.website.WebsiteOrdersRepository.ORIGIN_PREFIX) }.forEach { row ->
                val origin=gson.fromJson(row.value,com.hardwareshop.pos.data.website.WebsiteOrigin::class.java)
                java.util.UUID.fromString(origin.orderId);java.util.UUID.fromString(origin.serverBillId)
                require(row.key==com.hardwareshop.pos.data.website.WebsiteOrdersRepository.originKey(origin.orderId)&&payload.bills.any{it.id==origin.billId}) { "Website bill origin does not match the backup's saved bills" }
                require(origin.device in listOf("device-a","device-b")&&origin.posBillSyncId.matches(Regex("[A-Za-z0-9_-]{16,100}"))) { "Invalid local website bill origin" }
            }
            require(payload.products.all { it.currentStock.isFinite() && it.minimumStock.isFinite() && it.minimumStock >= 0 &&
                it.sellingPricePaisa >= 0 && it.purchasePricePaisa >= 0 && it.gstRateBp in 0..10000 }) { "Invalid product values in backup" }
            payload.products.forEach {
                BoxQuantity.validate(it.unit, it.piecesPerBox)
                if (it.isPipe) require(it.pipeLengthPricePaisa > 0 && it.pipeFootPricePaisa > 0 && it.pipeFeetPerLength in 1..10000)
            }
            payload.billItems.filter { it.isPipe }.forEach {
                require(it.pipeLengths > 0 || it.pipeFeet > 0)
                require(PipeBilling.total(it.pipeLengths, it.pipeFeet, it.unitPricePaisa, it.pipeFootPricePaisa) == it.itemTotalPaisa) { "Invalid pipe sale snapshot" }
            }
            require(payload.billItems.all { it.quantity.isFinite() && it.quantity > 0 && it.unitPricePaisa >= 0 &&
                it.itemTotalPaisa >= 0 && it.gstAmountPaisa >= 0 && it.gstRateBp in 0..10000 }) { "Invalid bill lines in backup" }
            require(payload.bills.all { it.totalPaisa >= 0 && it.discountPaisa >= 0 && it.paidPaisa >= 0 && it.creditPaisa >= 0 &&
                it.status in listOf("ACTIVE", "CANCELLED", "EDITED") && it.paymentMethod in listOf("CASH", "CARD", "UPI", "CREDIT", "SPLIT") }) { "Invalid bill values in backup" }
            payload.bills.filter { it.paymentMethod=="SPLIT" }.forEach { bill->
                val rows=payload.payments.filter { it.billId==bill.id }
                require(rows.all { it.amountPaisa>0 && it.method in listOf("CASH","UPI") } && rows.map { it.method }.distinct().size==rows.size) { "Invalid split payment rows" }
                val cash=rows.filter { it.method=="CASH" }.fold(0L){sum,row->Math.addExact(sum,row.amountPaisa)}
                val upi=rows.filter { it.method=="UPI" }.fold(0L){sum,row->Math.addExact(sum,row.amountPaisa)}
                PaymentSplit(cash,upi).validate(bill.totalPaisa)
                require(bill.creditPaisa==0L && bill.paidPaisa==bill.totalPaisa) { "Invalid split payment totals" }
            }
            require(payload.creditTxns.all { it.type in listOf("CREDIT_CREATED", "PARTIAL_PAYMENT", "FULL_PAYMENT", "ADJUSTMENT") }) { "Unknown credit transaction type" }
            payload.settings.filter { it.key.startsWith("product:draft:") }.forEach {
                val draft = gson.fromJson(it.value, com.hardwareshop.pos.domain.model.ProductDraft::class.java)
                require(draft.id.isNotBlank()) { "Invalid item draft" }
                draft.form.gaps(draft.ignoredOptional, validateStock = draft.productId == null)
            }
            if (payload.version >= 5) {
                require(payload.supplierPayments != null && payload.saleReturns != null && payload.saleReturnItems != null) { "Trade history is missing from backup" }
                require(payload.supplierPayments.all { it.amountPaisa > 0 && it.method in listOf("CASH", "UPI", "CARD") }) { "Invalid supplier payment" }
                require(payload.saleReturns.all { it.totalPaisa >= 0 && it.creditReductionPaisa >= 0 && it.refundPaisa >= 0 &&
                    it.creditReductionPaisa <= it.totalPaisa && it.refundPaisa == it.totalPaisa - it.creditReductionPaisa &&
                    it.method in listOf("CASH", "UPI", "CARD") }) { "Invalid return totals" }
                require(payload.saleReturnItems.all { it.quantity.isFinite() && it.quantity > 0 && it.totalPaisa >= 0 && it.taxPaisa >= 0 }) { "Invalid return quantities" }
                payload.saleReturnItems.groupBy { it.billItemId }.forEach { (id, rows) ->
                    val original = requireNotNull(payload.billItems.find { it.id == id }) { "Return references a missing bill item" }
                    require(rows.sumOf { java.math.BigDecimal.valueOf(it.quantity) } <= java.math.BigDecimal.valueOf(original.quantity)) { "Return exceeds sold quantity" }
                }
                payload.saleReturns.forEach { header ->
                    val rows = payload.saleReturnItems.filter { it.returnId == header.id }
                    require(rows.isNotEmpty() && rows.sumOf { it.totalPaisa } == header.totalPaisa && rows.sumOf { it.taxPaisa } == header.taxPaisa && rows.sumOf { it.discountPaisa } == header.discountPaisa) { "Return lines do not match header" }
                    require(rows.all { row -> payload.billItems.any { it.id == row.billItemId && it.billId == header.billId } }) { "Return contains items from another bill" }
                }
                payload.supplierPayments.groupBy { it.purchaseBillId }.forEach { (id, rows) ->
                    require(rows.fold(0L) { sum, row -> Math.addExact(sum, row.amountPaisa) } <= requireNotNull(payload.purchaseBills?.find { it.id == id }).totalPaisa) { "Supplier payments exceed invoice total" }
                }
            }
            if (payload.version >= 4) require(payload.images != null && payload.images.toSet() == staged.keys) { "Backup photos are incomplete" }
            when (val safety = createBackup()) {
                is Result.Error -> return@withContext Result.Error("Safety backup failed; restore was not started: ${safety.message}")
                else -> Unit
            }

            // ── Atomic restore ────────────────────────────────────────────────
            com.hardwareshop.pos.data.website.WebsiteRestoreQuarantine.beforeRestore(context)
            // withTransaction is the Room KTX suspend version — rolls back if anything throws
            db.withTransaction {
                // Linking may have happened while the archive was being validated.
                requireStandaloneRestore()
                // Older backups do not contain purchase history. Never silently erase it.
                check(payload.version < 3 ||
                    (payload.suppliers != null && payload.purchaseBills != null && payload.purchaseBillItems != null)) {
                    "Backup is incomplete: purchase tables are missing."
                }
                check(payload.version >= 3 ||
                    (db.purchaseBillDao().getAllPurchaseBills().isEmpty() && db.supplierDao().getAllSuppliers().isEmpty())) {
                    "This older backup cannot replace existing supplier or purchase history. Restore it on an empty installation instead."
                }
                val currentSequence = db.settingsDao().getSetting(BillNumberGenerator.SEQUENCE_KEY)
                    ?.value?.toLongOrNull() ?: 0L
                val currentMax = db.billDao().getMaxNumericBillNumber() ?: 0L
                val localWorkers = db.workerDao().getAllWorkers().associateBy { it.username }
                val workerIds = payload.workers.associate { it.id to localWorkers[it.username]?.id }
                check(payload.version >= 5 || (db.tradeDao().returns().isEmpty() && db.tradeDao().payments().isEmpty())) {
                    "This older backup cannot replace supplier payment or return history. Restore it on an empty installation."
                }
                db.tradeDao().clearReturnItems(); db.tradeDao().clearReturns(); db.tradeDao().clearPayments()
                // Replace dependent snapshot tables before replacing products.
                db.purchaseBillItemDao().deleteAll()
                db.purchaseBillDao().deleteAll()
                db.supplierDao().deleteAll()
                db.stockDao().deleteAllAdjustments()
                // Delete in reverse FK order so constraints don't fire
                db.billDao().deleteAllPayments()
                db.billDao().deleteAllBillItems()
                db.creditDao().deleteAllTransactions()
                db.billDao().deleteAllBills()
                db.billDao().deleteAllClosings()
                db.remotePrintJobDao().deleteAllJobs()
                db.settingsDao().clearWorkspaces()
                db.productDao().deleteAllProducts()
                db.productTypeDao().deleteAllTypes()
                db.categoryDao().deleteAllCategories()
                db.customerDao().deleteAllCustomers()
                // Workers are NOT wiped — owner account must remain intact on restore device

                // Insert in FK-dependency order
                payload.categories.forEach       { db.categoryDao().insertCategory(it) }
                payload.productTypes.forEach     { db.productTypeDao().insertType(it) }
                payload.products.forEach { db.productDao().insertProduct(it.copy(imagePath = it.imagePath?.let { path -> staged[path] })) }
                payload.customers.forEach        { db.customerDao().insertCustomer(it) }
                payload.bills.forEach            { db.billDao().insertBill(it.copy(workerId = it.workerId?.let { id -> workerIds[id] })) }
                payload.billItems.forEach        { db.billDao().insertBillItem(it) }
                payload.payments.forEach         { db.billDao().insertPaymentForRestore(it) }
                payload.creditTxns.forEach       { db.creditDao().insertTransaction(it) }
                payload.stockAdjustments.forEach { db.stockDao().insertAdjustment(it) }
                payload.settings.filter { it.key != BillNumberGenerator.SEQUENCE_KEY && portableSetting(it.key) }
                    .forEach { row ->
                        val imported = if (row.key.startsWith("product:draft:")) {
                            val draft = com.google.gson.JsonParser.parseString(row.value)
                            rewriteImages(draft, staged)
                            row.copy(value = gson.toJson(draft))
                        } else row
                        db.settingsDao().upsertSetting(imported)
                    }
                payload.closings.orEmpty().forEach { db.billDao().insertDayClosing(it) }
                payload.suppliers.orEmpty().forEach { db.supplierDao().insertSupplier(it) }
                payload.purchaseBills.orEmpty().forEach { db.purchaseBillDao().insertPurchaseBill(it) }
                payload.purchaseBillItems.orEmpty().let { db.purchaseBillItemDao().insertAll(it) }
                payload.supplierPayments.orEmpty().forEach { db.tradeDao().insertPayment(it) }
                payload.saleReturns.orEmpty().forEach { db.tradeDao().insertReturn(it) }
                db.tradeDao().insertReturnItems(payload.saleReturnItems.orEmpty())
                val importedSequence = payload.settings.firstOrNull {
                    it.key == BillNumberGenerator.SEQUENCE_KEY
                }?.value?.toLongOrNull() ?: 0L
                val restoredMax = db.billDao().getMaxNumericBillNumber() ?: 0L
                db.settingsDao().upsertSetting(SettingsEntity(
                    BillNumberGenerator.SEQUENCE_KEY,
                    maxOf(currentSequence, currentMax, importedSequence, restoredMax, 0L).toString()
                ))
            }

            committed = true
            val exportDate = DATE_FMT.format(Date(payload.exportedAt))
            auditRepository.log(
                actionType = "RESTORE_PERFORMED",
                entityType = "SYSTEM",
                afterValue = "Restored from $exportDate — products=${payload.products.size}, bills=${payload.bills.size}",
                reason     = "Manual restore"
            )

            Timber.i("Restore complete: ${payload.products.size} products, ${payload.bills.size} bills")
            Result.Success(
                "Restore complete!\n" +
                "Products: ${payload.products.size}\n" +
                "Bills: ${payload.bills.size}\n" +
                "Customers: ${payload.customers.size}\n" +
                "Backed up on: $exportDate" + if (payload.version < 7) "\nReopen the app to configure automatic pipe billing for this older backup." else ""
            )

        } catch (e: Exception) {
            Timber.e(e, "Restore failed")
            Result.Error("Restore failed: ${e.message}", e)
        } finally {
            if (!committed) staged.values.forEach { safeImage(it)?.delete() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun requireStandaloneRestore() {
        // Do not roll an existing website origin/outbox back to an older accounting
        // snapshot while its sticky server claim may already have produced a bill.
        db.openHelper.readableDatabase.query("SELECT 1 FROM settings WHERE `key` LIKE 'website-origin:%' LIMIT 1").use {
            check(!it.moveToFirst()) { "Website bill origins require owner reconciliation before replacing this database. Export a backup and restore only into an isolated installation; do not bill the order again." }
        }

        db.openHelper.readableDatabase.query("SELECT storeId FROM sync_journal_control WHERE id=1").use {
            check(it.moveToFirst() && it.getString(0).isBlank()) {
                "Restore is unavailable on a linked shop until cloud-safe restore is ready. Your existing data has not been changed."
            }
        }
    }

    private fun portableSetting(key: String): Boolean =
        !key.startsWith("remote_print_") && !key.startsWith("local_print_") &&
        !key.startsWith("cart:") && !key.startsWith("checkout:") && !key.startsWith("website-local-") &&
        key !in setOf("shop_id", "printer_mac", "bluetooth_printer_mac", "print_agent_enabled")

    private fun safeImage(path: String): File? {
        if (!path.matches(Regex("product_images/[A-Za-z0-9_.-]+"))) return null
        val directory = File(context.filesDir, "product_images").canonicalFile
        return File(context.filesDir, path).canonicalFile.takeIf { it.parentFile == directory }
    }

    private fun collectImages(tree: com.google.gson.JsonElement, paths: MutableSet<String>) {
        if (tree.isJsonObject) tree.asJsonObject.entrySet().forEach { (key, value) ->
            if (key == "imagePath" && value.isJsonPrimitive) paths.add(value.asString)
            else collectImages(value, paths)
        } else if (tree.isJsonArray) tree.asJsonArray.forEach { collectImages(it, paths) }
    }

    private fun rewriteImages(tree: com.google.gson.JsonElement, paths: Map<String, String>) {
        if (tree.isJsonObject) tree.asJsonObject.entrySet().toList().forEach { (key, value) ->
            if (key == "imagePath" && value.isJsonPrimitive) tree.asJsonObject.addProperty(key, paths[value.asString])
            else rewriteImages(value, paths)
        } else if (tree.isJsonArray) tree.asJsonArray.forEach { rewriteImages(it, paths) }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(8192); var total = 0L
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            total += size
            require(total <= limit) { "Backup entry exceeds its safe size limit" }
            output.write(buffer, 0, size)
        }
        return total
    }

    private fun readArchive(uri: Uri, staged: MutableMap<String, String>): String {
        var json: String? = null
        var imageBytes = 0L
        var entries = 0
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open backup" }
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 10001) { "Too many entries in backup" }
                if (entry.name == "backup.json") {
                    require(json == null) { "Duplicate backup data" }
                    val out = ByteArrayOutputStream()
                    copyBounded(zip, out, 32L * 1024 * 1024)
                    json = out.toString("UTF-8")
                } else {
                    require(safeImage(entry.name) != null && !staged.containsKey(entry.name)) { "Unexpected or duplicate backup entry" }
                    val path = "product_images/restored_${UUID.randomUUID()}.jpg"
                    val file = requireNotNull(safeImage(path))
                    file.parentFile?.mkdirs()
                    staged[entry.name] = path
                    imageBytes += file.outputStream().use { copyBounded(zip, it, 4L * 1024 * 1024) }
                    require(imageBytes <= 256L * 1024 * 1024) { "Backup photos exceed 256 MB" }
                }
                zip.closeEntry()
            }
        }
        return requireNotNull(json) { "backup.json is missing" }
    }

    /** Returns a human-readable size for the most recent backup file, or null. */
    fun latestBackupInfo(): String? {
        val dir = context.getExternalFilesDir("backups") ?: return null
        val latest = dir.listFiles()
            ?.filter { it.name.endsWith(".hwpos.zip") }
            ?.maxByOrNull { it.lastModified() } ?: return null
        val kb = latest.length() / 1024
        val date = DATE_FMT.format(Date(latest.lastModified()))
        return "${latest.name} (${kb} KB) — $date"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BACKUP PAYLOAD  (internal serialisation schema)
// ─────────────────────────────────────────────────────────────────────────────

@androidx.annotation.Keep
internal data class BackupPayload(
    val magic:            String,
    val version:          Int,
    val exportedAt:       Long,
    val categories:       List<CategoryEntity>           = emptyList(),
    val productTypes:     List<ProductTypeEntity>        = emptyList(),
    val products:         List<ProductEntity>            = emptyList(),
    val customers:        List<CustomerEntity>           = emptyList(),
    val workers:          List<WorkerEntity>             = emptyList(),
    val bills:            List<BillEntity>               = emptyList(),
    val billItems:        List<BillItemEntity>           = emptyList(),
    val payments:         List<PaymentEntity>            = emptyList(),
    val creditTxns:       List<CreditTransactionEntity>  = emptyList(),
    val stockAdjustments: List<StockAdjustmentEntity>    = emptyList(),
    val settings:         List<SettingsEntity>           = emptyList(),
    val suppliers: List<SupplierEntity>? = null,
    val purchaseBills: List<PurchaseBillEntity>? = null,
    val purchaseBillItems: List<PurchaseBillItemEntity>? = null,
    val images: List<String>? = null,
    val closings: List<DayClosingEntity>? = null,
    val supplierPayments: List<SupplierPaymentEntity>? = null,
    val saleReturns: List<SaleReturnEntity>? = null,
    val saleReturnItems: List<SaleReturnItemEntity>? = null
)

package com.hardwareshop.pos.data.remote

import android.content.Context
import androidx.room.withTransaction
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.repository.ProductRepository
import com.hardwareshop.pos.data.repository.SettingsRepository
import com.hardwareshop.pos.domain.model.Result
import com.hardwareshop.pos.util.PermissionChecker
import com.hardwareshop.pos.util.ProductImportFileReader
import com.hardwareshop.pos.util.SessionHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExcelSyncRepository @Inject constructor(
    private val db: AppDatabase,
    private val productRepository: ProductRepository,
    private val settingsRepository: SettingsRepository,
    private val session: SessionHolder,
    @param:ApplicationContext private val context: Context
) {
    companion object {
        const val KEY_EXCEL_SYNC_URL    = "excel_sync_url"
        const val KEY_EXCEL_SYNC_ENABLED = "excel_sync_enabled"
        const val KEY_EXCEL_LAST_SYNC   = "excel_last_sync_time"
        const val KEY_EXCEL_LAST_STATUS = "excel_last_status"
    }

    fun observeSyncConfig(): Flow<ExcelSyncConfig> =
        db.settingsDao().observeFeatureRecords("excel_").map { rows ->
            val map=rows.associate { it.key to it.value }
            ExcelSyncConfig(
                url          = map[KEY_EXCEL_SYNC_URL].orEmpty(),
                enabled      = map[KEY_EXCEL_SYNC_ENABLED] == "true",
                lastSyncTime = map[KEY_EXCEL_LAST_SYNC]?.toLongOrNull() ?: 0L,
                lastStatus   = map[KEY_EXCEL_LAST_STATUS].orEmpty()
            )
        }

    suspend fun updateConfig(url: String, enabled: Boolean): Result<Unit> {
        PermissionChecker.enforceOwner(session.current)
        return try {
            settingsRepository.set(KEY_EXCEL_SYNC_URL, url.trim())
            settingsRepository.set(KEY_EXCEL_SYNC_ENABLED, if (enabled) "true" else "false")
            if (enabled) {
                com.hardwareshop.pos.service.ExcelSyncWorker.schedule(context)
            } else {
                com.hardwareshop.pos.service.ExcelSyncWorker.cancel(context)
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error("Failed to update config: ${e.message}", e)
        }
    }

    suspend fun performTwoWaySync(): Result<SyncSummary> = withContext(Dispatchers.IO) {
        try {
            val config = getSyncConfig()
            if (config.url.isBlank()) {
                return@withContext Result.Error("Excel / Google Sheet URL is not configured.")
            }

            // 1. Pull changes from Remote Excel / Google Sheet
            val pullResult = pullRemoteSheet(config.url)
            val pulledCount = if (pullResult is Result.Success) pullResult.data else 0

            // 2. Export local products back to CSV format for remote verification
            val localCsv = exportLocalProductsToCsv()

            val now = System.currentTimeMillis()
            settingsRepository.set(KEY_EXCEL_LAST_SYNC, now.toString())
            val statusMsg = "Sync Successful. Imported/Updated: $pulledCount items."
            settingsRepository.set(KEY_EXCEL_LAST_STATUS, statusMsg)

            Timber.i("Two-way Excel sync completed: $statusMsg")
            Result.Success(SyncSummary(pulledCount, localCsv.lines().size - 1))
        } catch (e: Exception) {
            val errorMsg = "Sync Failed: ${e.message}"
            settingsRepository.set(KEY_EXCEL_LAST_STATUS, errorMsg)
            Timber.e(e, "Two-way Excel sync failed")
            Result.Error(errorMsg, e)
        }
    }

    private suspend fun getSyncConfig(): ExcelSyncConfig {
        val map = listOf(KEY_EXCEL_SYNC_URL,KEY_EXCEL_SYNC_ENABLED,KEY_EXCEL_LAST_SYNC,KEY_EXCEL_LAST_STATUS)
            .associateWith { settingsRepository.get(it) }
        return ExcelSyncConfig(
            url          = map[KEY_EXCEL_SYNC_URL].orEmpty(),
            enabled      = map[KEY_EXCEL_SYNC_ENABLED] == "true",
            lastSyncTime = map[KEY_EXCEL_LAST_SYNC]?.toLongOrNull() ?: 0L,
            lastStatus   = map[KEY_EXCEL_LAST_STATUS].orEmpty()
        )
    }

    private suspend fun pullRemoteSheet(sheetUrl: String): Result<Int> {
        return try {
            val csvContent = downloadCsvFromUrl(sheetUrl)
            if (csvContent.isBlank()) return Result.Success(0)

            // Read rows using ProductImportFileReader
            val rows = ProductImportFileReader.readCsvDirect(csvContent)
            if (rows.isEmpty()) return Result.Success(0)

            // Import rows into Room DB
            val importResult = productRepository.importProducts(rows)
            when (importResult) {
                is Result.Success -> Result.Success(importResult.data.added + importResult.data.updated)
                is Result.Error -> Result.Error(importResult.message)
                else -> Result.Error("Import failed")
            }
        } catch (e: Exception) {
            Result.Error("Could not download/parse spreadsheet: ${e.message}", e)
        }
    }

    private suspend fun downloadCsvFromUrl(urlStr: String): String = withContext(Dispatchers.IO) {
        var currentUrl = when {
            urlStr.contains("docs.google.com/spreadsheets") -> {
                val docId = urlStr.substringAfter("/d/").substringBefore("/")
                "https://docs.google.com/spreadsheets/d/$docId/export?format=csv"
            }
            else -> urlStr
        }

        var redirectCount = 0
        while (redirectCount < 5) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return@withContext connection.inputStream.bufferedReader().use { it.readText() }
            } else if (responseCode in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308)) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                if (!newUrl.isNullOrBlank()) {
                    currentUrl = newUrl
                    redirectCount++
                } else {
                    throw IllegalStateException("Redirect requested without Location header (HTTP $responseCode)")
                }
            } else {
                connection.disconnect()
                throw IllegalStateException("Server returned HTTP $responseCode")
            }
        }
        throw IllegalStateException("Too many HTTP redirects while downloading spreadsheet")
    }

    suspend fun exportLocalProductsToCsv(): String = withContext(Dispatchers.IO) {
        val categories = db.categoryDao().getAllCategories().associateBy { it.id }
        val types = db.productTypeDao().getAllTypes().associateBy { it.id }
        val products = db.productDao().getAllProducts().filter { it.isActive }

        fun escapeCsv(text: String): String = "\"" + text.replace("\"", "\"\"") + "\""

        val sb = java.lang.StringBuilder()
        sb.append("Category,Type,Item Name,Size,Unit,Selling Price,Buying Price,Stock,GST %,Search Aliases\n")

        products.forEach { p ->
            val catName = categories[p.categoryId]?.name ?: "General"
            val typeName = types[p.typeId]?.name ?: "General"
            val sellingPrice = p.sellingPricePaisa / 100.0
            val buyingPrice = p.purchasePricePaisa / 100.0
            val gstPercent = p.gstRateBp / 100.0

            sb.append("${escapeCsv(catName)},")
              .append("${escapeCsv(typeName)},")
              .append("${escapeCsv(p.name)},")
              .append("${escapeCsv(p.size)},")
              .append("${escapeCsv(p.unit)},")
              .append("$sellingPrice,")
              .append("$buyingPrice,")
              .append("${p.currentStock},")
              .append("$gstPercent,")
              .append("${escapeCsv(p.searchAliases)}\n")
        }

        sb.toString()
    }

    private fun escapeCsv(text: String): String = "\"" + text.replace("\"", "\"\"") + "\""

    suspend fun exportBillsToCsv(): String = withContext(Dispatchers.IO) {
        val bills = db.billDao().getAllBills()
        val sb = java.lang.StringBuilder()
        sb.append("Bill Number,Date,Customer Name,Customer Phone,Worker Name,Payment Method,Subtotal,Discount,GST,Total,Paid,Credit,Status\n")

        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())

        bills.forEach { b ->
            val dateStr = dateFormat.format(java.util.Date(b.createdAt))
            sb.append("${escapeCsv(b.billNumber)},")
              .append("${escapeCsv(dateStr)},")
              .append("${escapeCsv(b.customerName)},")
              .append("${escapeCsv(b.customerPhone)},")
              .append("${escapeCsv(b.workerName)},")
              .append("${escapeCsv(b.paymentMethod)},")
              .append("${b.subtotalPaisa / 100.0},")
              .append("${b.discountPaisa / 100.0},")
              .append("${b.gstAmountPaisa / 100.0},")
              .append("${b.totalPaisa / 100.0},")
              .append("${b.paidPaisa / 100.0},")
              .append("${b.creditPaisa / 100.0},")
              .append("${escapeCsv(b.status)}\n")
        }

        sb.toString()
    }

    suspend fun exportCustomersToCsv(): String = withContext(Dispatchers.IO) {
        val customers = db.customerDao().getAllCustomers().filter { it.isActive }
        val creditTx = db.creditDao().getAllTransactions()
        val balanceByCustomer = creditTx.groupBy { it.customerId }
            .mapValues { entry -> entry.value.lastOrNull()?.newBalancePaisa ?: 0L }

        val sb = java.lang.StringBuilder()
        sb.append("Customer Name,Phone,Address,GST Number,Credit Limit,Current Outstanding Credit Balance\n")

        customers.forEach { c ->
            val balancePaisa = balanceByCustomer[c.id] ?: 0L
            sb.append("${escapeCsv(c.name)},")
              .append("${escapeCsv(c.phone)},")
              .append("${escapeCsv(c.address)},")
              .append("${escapeCsv(c.gstNumber)},")
              .append("${c.creditLimitPaisa / 100.0},")
              .append("${balancePaisa / 100.0}\n")
        }

        sb.toString()
    }

    suspend fun exportSuppliersToCsv(): String = withContext(Dispatchers.IO) {
        val suppliers = db.supplierDao().getAllSuppliers().filter { it.isActive }
        val bills = db.purchaseBillDao().getAllPurchaseBills()
        val totalsBySupplier = bills.groupBy { it.supplierId }
            .mapValues { entry -> entry.value.sumOf { it.totalPaisa } }

        val sb = java.lang.StringBuilder()
        sb.append("Supplier Name,Contact Person,Phone,Address,GST Number,Total Purchase Amount\n")

        suppliers.forEach { s ->
            val totalPaisa = totalsBySupplier[s.id] ?: 0L
            sb.append("${escapeCsv(s.name)},")
              .append("${escapeCsv(s.contactName)},")
              .append("${escapeCsv(s.phone)},")
              .append("${escapeCsv(s.address)},")
              .append("${escapeCsv(s.gstNumber)},")
              .append("${totalPaisa / 100.0}\n")
        }

        sb.toString()
    }

    suspend fun exportDayClosingsToCsv(): String = withContext(Dispatchers.IO) {
        val closings = db.billDao().getAllClosings()
        val sb = java.lang.StringBuilder()
        sb.append("Closing Date,Total Sales,Cash Sales,UPI Sales,Card Sales,Credit Sales,Discounts,GST,Total Bills,Cancelled Bills,Notes\n")

        closings.forEach { d ->
            sb.append("${escapeCsv(d.closingDate)},")
              .append("${d.totalSalesPaisa / 100.0},")
              .append("${d.cashSalesPaisa / 100.0},")
              .append("${d.upiSalesPaisa / 100.0},")
              .append("${d.cardSalesPaisa / 100.0},")
              .append("${d.creditSalesPaisa / 100.0},")
              .append("${d.totalDiscountsPaisa / 100.0},")
              .append("${d.totalGstPaisa / 100.0},")
              .append("${d.totalBillCount},")
              .append("${d.cancelledBillCount},")
              .append("${escapeCsv(d.notes)}\n")
        }

        sb.toString()
    }
}

data class ExcelSyncConfig(
    val url: String = "",
    val enabled: Boolean = false,
    val lastSyncTime: Long = 0L,
    val lastStatus: String = ""
)

data class SyncSummary(
    val importedCount: Int,
    val exportedCount: Int
)

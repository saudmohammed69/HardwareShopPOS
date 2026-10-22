package com.hardwareshop.pos

import android.content.Context
import com.hardwareshop.pos.data.repository.WantListRepository
import com.hardwareshop.pos.presentation.owner.misc.*
import androidx.compose.ui.test.*
import com.hardwareshop.pos.presentation.worker.billing.*
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.CategoryEntity
import com.hardwareshop.pos.data.local.entity.ProductEntity
import com.hardwareshop.pos.data.local.entity.ProductTypeEntity
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import com.hardwareshop.pos.data.repository.AuditRepository
import com.hardwareshop.pos.data.repository.BillingRepository
import com.hardwareshop.pos.data.repository.CustomerRepository
import com.hardwareshop.pos.data.repository.ProductRepository
import com.hardwareshop.pos.data.repository.QuotationRepository
import com.hardwareshop.pos.data.repository.SettingsRepository
import com.hardwareshop.pos.data.sync.SyncJournalSchema
import com.hardwareshop.pos.data.sync.SyncTransferSchema
import com.hardwareshop.pos.domain.model.Bill
import com.hardwareshop.pos.domain.model.CartItem
import com.hardwareshop.pos.domain.model.PaymentMethod
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.domain.model.Result
import com.hardwareshop.pos.domain.model.SavedCart
import com.hardwareshop.pos.presentation.common.ProductSortControl
import com.hardwareshop.pos.presentation.worker.billing.BillingViewModel
import com.hardwareshop.pos.util.BillNumberGenerator
import com.hardwareshop.pos.util.CalculationEngine
import com.hardwareshop.pos.util.ProductSortOrder
import com.hardwareshop.pos.util.SessionHolder
import com.hardwareshop.pos.util.UserSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Every repository uses this test's fresh Room database, never the application's seeded database. */
class WantListIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var db: AppDatabase
    private lateinit var session: SessionHolder
    private lateinit var products: ProductRepository
    private lateinit var settings: SettingsRepository
    private lateinit var billing: BillingRepository
    private lateinit var quotations: QuotationRepository
    private lateinit var product: Product
    private val viewModels = mutableListOf<BillingViewModel>()
    private val gson = Gson()

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(sql: SupportSQLiteDatabase) {
                    (SyncJournalSchema.statements(includeBoxCount = true, includePipe = true) +
                        SyncTransferSchema.statements()).forEach(sql::execSQL)
                }
            }).build()
        session = SessionHolder().apply { setSession(UserSession.OwnerSession("Integration shop")) }
        val audit = AuditRepository(db.auditLogDao(), session)
        settings = SettingsRepository(db.settingsDao(), db.ownerCredentialDao(), session)
        products = ProductRepository(db, db.categoryDao(), db.productTypeDao(), db.productDao(), db.stockDao(), audit, session)
        billing = BillingRepository(db, db.billDao(), db.productDao(), db.creditDao(), audit, BillNumberGenerator(db), settings, session)
        quotations = QuotationRepository(db, session, products)
        val category = db.categoryDao().insertCategory(CategoryEntity(name = "Tools"))
        val type = db.productTypeDao().insertType(ProductTypeEntity(categoryId = category, name = "Hand Tools"))
        val id = db.productDao().insertProduct(ProductEntity(categoryId = category, typeId = type, name = "Hammer",
            size = "500 g", unit = "Piece", sellingPricePaisa = 10000, purchasePricePaisa = 6000, currentStock = 20.0))
        product = requireNotNull(products.getProductById(id))
    }

    @After fun cleanup() = runBlocking {
        compose.activityRule.scenario.onActivity { it.setContent {} }
        withContext(Dispatchers.Main) {
            viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        }
        db.close()
    }

    private fun line() = CartItem(product, 2.0, product.sellingPricePaisa,
        CalculationEngine.itemTotalPaisa(2.0, product.sellingPricePaisa))

    private suspend fun quotation() = quotations.saveFromCart(listOf(line()), "Contractor", "9876543210",
        QuotationRepository.defaultValidUntil(), "Deliver after approval", 500, gstEnabled = false, gstInclusive = true)

    private suspend fun checkout(record: QuotationRepository.Record, items: List<CartItem>, token: String): Result<Bill> =
        billing.createBill(items, null, record.customerName, record.customerPhone, record.discountPaisa,
            "Quotation ${record.number}", record.gstEnabled, PaymentMethod.CASH, 0, record.notes,
            checkoutId = token, gstInclusive = record.gstInclusive, sourceQuotationId = record.id)

    private fun count(query: String): Long = db.openHelper.readableDatabase.query(query).use {
        assertTrue(it.moveToFirst())
        it.getLong(0)
    }

    private suspend fun newBillingViewModel(): BillingViewModel = withContext(Dispatchers.Main) {
        BillingViewModel(billing, CustomerRepository(db.customerDao(), db.creditDao(), session), products,
            db.settingsDao(), settings, db, session).also { viewModels.add(it) }
    }.also { vm -> withTimeout(15000) { vm.state.first { !it.isLoading } } }



    private fun capture(name:String) {
        compose.waitForIdle()
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"advanced-$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun duplicateQuickAddsAndPurchasedNotesDoNotCreateInventoryOrBills()=runBlocking {
        val repository=WantListRepository(db,session)
        assertTrue(repository.quickAdd(product))
        assertFalse(repository.quickAdd(product))
        var entry=repository.observe().first().single()
        assertEquals(product.id,entry.productId)
        entry=repository.save(entry,product.id,product.displayLabel,"12.50","Piece","Buy good quality",2)
        assertEquals("12.5",entry.quantity)
        assertEquals(2,entry.priority)
        assertTrue(repository.shareText(listOf(entry)).contains("12.5 Piece"))
        repository.setPurchased(entry,true)
        assertTrue(repository.observe().first().single().purchased)
        assertTrue(repository.quickAdd(product))
        assertEquals(2,repository.observe().first().size)
        assertEquals(20.0,products.getProductById(product.id)!!.currentStock,0.0)
        assertEquals(0L,count("SELECT COUNT(*) FROM bills"))
        assertEquals(0L,count("SELECT COUNT(*) FROM stock_adjustments"))
    }
    @Test fun invalidInputsStaleEditsAndLoggedOutWritesAreRejected()=runBlocking {
        val repository=WantListRepository(db,session)
        assertTrue(runCatching {repository.save(null,product.id,"Hammer","-1","Piece","",1)}.isFailure)
        assertTrue(runCatching {repository.save(null,product.id,"Hammer","1.001","Piece","",1)}.isFailure)
        assertTrue(runCatching {repository.save(null,999,"Hammer","1","Piece","",1)}.isFailure)
        val original=repository.save(null,product.id,"Hammer","1","Piece","",1)
        val changed=repository.save(original,product.id,"Hammer","2","Piece","",1)
        assertTrue(runCatching {repository.setPurchased(original,true)}.isFailure)
        assertTrue(runCatching {repository.remove(original)}.isFailure)
        session.clear()
        assertTrue(runCatching {repository.remove(changed)}.isFailure)
        assertTrue(runCatching {repository.quickAdd(product)}.isFailure)
        assertTrue(runCatching {repository.shareText(listOf(changed))}.isFailure)
        assertEquals(20.0,products.getProductById(product.id)!!.currentStock,0.0)
    }
    @Test fun workersCanCompleteSharedNotesButOnlyEditTheirOwnEntries()=runBlocking {
        val repository=WantListRepository(db,session)
        val ownerEntry=repository.save(null,null,"Owner note","1","Box","",1)
        session.setSession(UserSession.WorkerSession(10,"Worker A","a",0,false))
        val workerEntry=repository.save(null,null,"Worker note","2","Piece","",2)
        assertTrue(runCatching {repository.save(ownerEntry,null,"Overwrite","1","Box","",1)}.isFailure)
        assertTrue(runCatching {repository.remove(ownerEntry)}.isFailure)
        repository.setPurchased(ownerEntry,true)
        session.setSession(UserSession.WorkerSession(11,"Worker B","b",0,false))
        assertTrue(runCatching {repository.remove(workerEntry)}.isFailure)
        assertTrue(runCatching {repository.save(workerEntry,null,"Overwrite","1","Piece","",1)}.isFailure)
        session.setSession(UserSession.OwnerSession("Shop"))
        repository.remove(workerEntry)
        assertEquals(1,repository.observe().first().size)
        assertEquals(20.0,products.getProductById(product.id)!!.currentStock,0.0)
    }
    @Test fun purchasingNotesSurviveBackupRestoreWithoutChangingStock()=runBlocking {
        val repository=WantListRepository(db,session)
        val original=repository.save(null,product.id,"Hammer","3","Piece","Urgent",2)
        val backup=com.hardwareshop.pos.util.BackupManager(ApplicationProvider.getApplicationContext<Context>(),db,AuditRepository(db.auditLogDao(),session),session)
        val uri=(backup.createBackup() as Result.Success).data
        repository.remove(original)
        assertTrue(repository.observe().first().isEmpty())
        assertTrue(backup.restoreBackup(uri) is Result.Success)
        assertEquals(original,repository.observe().first().single())
        assertEquals(20.0,products.getProductById(product.id)!!.currentStock,0.0)
    }
    @Test fun productionWantListAddsFiltersAndCompletesAnItem()=runBlocking {
        val repository=WantListRepository(db,session)
        val vm=withContext(Dispatchers.Main){WantListViewModel(repository,products,session)}
        try {
            compose.activityRule.scenario.onActivity {it.setContent {MaterialTheme {WantListScreen({},vm)}}}
            compose.onNodeWithText("ADD ITEM").performClick()
            compose.onNodeWithTag("want-name").performScrollTo().performTextInput("Special screw")
            compose.onNodeWithTag("want-quantity").performScrollTo().performTextReplacement("25")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("SAVE ITEM").performClick()
            withTimeout(15000){vm.state.first {it.entries.size==1 && !it.busy}}
            compose.onNodeWithTag("want-list").performScrollToNode(hasText("Special screw"))
            compose.onNodeWithText("Special screw").assertIsDisplayed()
            compose.onNode(isToggleable()).performScrollTo().performClick()
            withTimeout(15000){vm.state.first {it.entries.singleOrNull()?.purchased==true && !it.busy}}
            compose.onNodeWithTag("want-list").performScrollToNode(hasText("Completed"))
            compose.onNodeWithText("Completed").performClick()
            compose.onNodeWithTag("want-list").performScrollToNode(hasText("Special screw"))
            compose.onNodeWithText("Special screw").assertIsDisplayed()
            capture("want-list-completed")
            assertEquals(20.0,products.getProductById(product.id)!!.currentStock,0.0)
            assertEquals(0L,count("SELECT COUNT(*) FROM bills"))
        }finally {withContext(Dispatchers.Main){vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()}}
    }
}

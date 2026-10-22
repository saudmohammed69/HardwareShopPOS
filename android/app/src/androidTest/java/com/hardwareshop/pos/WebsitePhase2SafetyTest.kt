package com.hardwareshop.pos

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.data.sync.SyncJournalSchema
import com.hardwareshop.pos.data.sync.SyncTransferSchema
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Every database is named phase2-proof-*. Never opens the application's shop DB. */
class WebsitePhase2SafetyTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val opened=mutableListOf<Pair<String,AppDatabase>>()
    private val session=SessionHolder().apply {setSession(UserSession.OwnerSession("Synthetic backup proof"))}
    private fun database():AppDatabase {
        check(context.packageName.endsWith(".debug"))
        val name="phase2-proof-${UUID.randomUUID()}.db"
        return Room.databaseBuilder(context,AppDatabase::class.java,name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object:RoomDatabase.Callback(){override fun onOpen(sql:SupportSQLiteDatabase){
                (SyncJournalSchema.statements(includeBoxCount=true,includePipe=true)+SyncTransferSchema.statements()).forEach(sql::execSQL)
            }}).build().also {opened+=name to it}
    }
    private fun manager(db:AppDatabase)=BackupManager(context,db,AuditRepository(db.auditLogDao(),session),session)
    private fun billing(db:AppDatabase)=BillingRepository(db,db.billDao(),db.productDao(),db.creditDao(),
        AuditRepository(db.auditLogDao(),session),BillNumberGenerator(db),SettingsRepository(db.settingsDao(),db.ownerCredentialDao(),session),session)
    private suspend fun seed(db:AppDatabase):Product {
        val category=db.categoryDao().insertCategory(CategoryEntity(name="Synthetic tools"))
        val type=db.productTypeDao().insertType(ProductTypeEntity(categoryId=category,name="Synthetic variants"))
        val id=db.productDao().insertProduct(ProductEntity(categoryId=category,typeId=type,name="Proof hammer",size="500 g",unit="Piece",
            sellingPricePaisa=12345,purchasePricePaisa=7000,currentStock=20.0))
        return requireNotNull(ProductRepository(db,db.categoryDao(),db.productTypeDao(),db.productDao(),db.stockDao(),
            AuditRepository(db.auditLogDao(),session),session).getProductById(id))
    }
    private suspend fun sale(db:AppDatabase,p:Product,key:String)=billing(db).createBill(listOf(CartItem(p,2.0,12345,24690)),
        null,"Synthetic buyer","",0,"",false,PaymentMethod.CASH,24690,"Phase 2 isolated fixture",checkoutId=key)
    private suspend fun snapshot(db:AppDatabase)=listOf(db.productDao().getAllProducts().toString(),db.billDao().getAllBills().toString(),
        db.billDao().getAllBillItems().toString(),db.billDao().getAllPayments().toString(),db.creditDao().getAllTransactions().toString())
    @After fun close(){opened.forEach {(name,db)->db.close();context.deleteDatabase(name)}}

    @Test fun walSnapshotRestoresIntoAnotherDatabaseAndSurvivesReopen()=runBlocking {
        val source=database();val p=seed(source)
        assertTrue(sale(source,p,UUID.randomUUID().toString()) is Result.Success)
        val before=snapshot(source)
        val sourceName=opened.first().first
        assertTrue("Exercise committed records still present in WAL",File(context.getDatabasePath(sourceName).path+"-wal").length()>0)
        val backup=manager(source).createBackup()
        assertTrue(backup.toString(),backup is Result.Success)
        val uri=(backup as Result.Success).data
        val evidence=File(context.getExternalFilesDir("website-phase2"),"synthetic-recoverable.hwpos.zip")
        evidence.parentFile!!.mkdirs()
        context.contentResolver.openInputStream(uri)!!.use {input->evidence.outputStream().use {input.copyTo(it)}}
        assertEquals(before,snapshot(source))
        val target=database()
        val restored=manager(target).restoreBackup(uri)
        assertTrue(restored.toString(),restored is Result.Success)
        assertEquals(before,snapshot(target))
        assertTrue(manager(target).restoreBackup(uri) is Result.Success)
        assertEquals("Repeat restore must not duplicate financial effects",before,snapshot(target))
        target.close()
        val reopened=android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(opened.last().first).path,null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        reopened.use {sql->sql.rawQuery("SELECT sellingPricePaisa,currentStock FROM products",null).use {
            assertTrue(it.moveToFirst());assertEquals(12345L,it.getLong(0));assertEquals(18.0,it.getDouble(1),0.0)
        };sql.rawQuery("PRAGMA integrity_check",null).use {assertTrue(it.moveToFirst());assertEquals("ok",it.getString(0))}}
    }
    @Test fun linkedShopRestoreIsRejectedWithoutChangingAnyFinancialRows()=runBlocking {
        val source=database();val p=seed(source);sale(source,p,UUID.randomUUID().toString())
        val uri=(manager(source).createBackup() as Result.Success).data
        source.openHelper.writableDatabase.execSQL("UPDATE sync_journal_control SET storeId='synthetic-linked-shop' WHERE id=1")
        val before=snapshot(source)
        assertTrue(manager(source).restoreBackup(uri) is Result.Error)
        assertEquals(before,snapshot(source))
    }
    @Test fun websiteIdentityRetryUsesExistingBillTransactionAndStockExactlyOnce()=runBlocking {
        val db=database();val p=seed(db)
        // Prototype-only table in this isolated DB, not an AppDatabase schema change.
        db.openHelper.writableDatabase.execSQL("CREATE TABLE proof_web_bill(orderId TEXT PRIMARY KEY,billId INTEGER NOT NULL UNIQUE)")
        val order=UUID.randomUUID().toString()
        suspend fun commit():Long=db.withTransaction {
            val bill=(sale(db,p,order) as Result.Success).data
            db.openHelper.writableDatabase.execSQL("INSERT OR IGNORE INTO proof_web_bill(orderId,billId) VALUES(?,?)",arrayOf(order,bill.id))
            bill.id
        }
        val ids=coroutineScope {List(10){async(Dispatchers.Default){commit()}}.awaitAll()}
        assertEquals(1,ids.distinct().size)
        assertEquals(1,db.billDao().getAllBills().size)
        assertEquals(1,db.billDao().getAllPayments().size)
        assertEquals(18.0,db.productDao().getProductById(p.id)!!.currentStock,0.0)
        assertEquals(12345L,db.productDao().getProductById(p.id)!!.sellingPricePaisa)
    }
}

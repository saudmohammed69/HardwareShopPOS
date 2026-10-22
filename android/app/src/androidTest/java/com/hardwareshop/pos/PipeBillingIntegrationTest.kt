package com.hardwareshop.pos
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hardwareshop.pos.data.local.database.*
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.local.database.migrations.MIGRATION_9_10
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class PipeBillingIntegrationTest {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),AppDatabase::class.java)
    @Test fun migrationPreservesHistoricalPricesStockAndAmounts() {
        val name="pipe-migration"
        helper.createDatabase(name,9).apply {
            execSQL("INSERT INTO categories VALUES(1,'PVC',0,1,1,1)")
            execSQL("INSERT INTO product_types VALUES(1,1,'Pipe',0,1,1,1)")
            execSQL("INSERT INTO products(id,categoryId,typeId,name,size,unit,sellingPricePaisa,purchasePricePaisa,gstRateBp,searchAliases,piecesPerBox,currentStock,minimumStock,imagePath,isActive,createdAt,updatedAt) VALUES(1,1,1,'PVC Pipe','1 inch','Length',41234,30000,0,'',0,9.0,0,NULL,1,1,1)")
            execSQL("INSERT INTO bills(id,billNumber,customerId,customerName,customerPhone,workerId,workerName,subtotalPaisa,discountPaisa,discountNote,gstEnabled,gstAmountPaisa,totalPaisa,paymentMethod,paidPaisa,creditPaisa,status,cancellationReason,shopGstNumber,customerGstNumber,notes,isPrinted,createdAt,updatedAt) VALUES(1,'HISTORY',NULL,'','',NULL,'Owner',10000,0,'',0,0,10000,'CASH',10000,0,'ACTIVE','','','','',0,1,1)")
            execSQL("INSERT INTO bill_items(id,billId,productId,productName,productSize,productUnit,quantity,unitPricePaisa,gstRateBp,itemTotalPaisa,gstAmountPaisa,createdAt) VALUES(1,1,1,'Old pipe','1 inch','Length',1,10000,0,10000,0,1)")
            close()
        }
        // Sync auxiliary tables intentionally live outside the Room entity schema.
        helper.runMigrationsAndValidate(name,10,false,MIGRATION_9_10).apply {
            query("SELECT sellingPricePaisa,purchasePricePaisa,currentStock,isPipe FROM products WHERE id=1").use { assertTrue(it.moveToFirst());assertEquals(41234L,it.getLong(0));assertEquals(30000L,it.getLong(1));assertEquals(9.0,it.getDouble(2),0.0);assertEquals(0,it.getInt(3)) }
            query("SELECT itemTotalPaisa,isPipe FROM bill_items WHERE id=1").use { assertTrue(it.moveToFirst());assertEquals(10000L,it.getLong(0));assertEquals(0,it.getInt(1)) }
            query("SELECT totalPaisa FROM bills WHERE id=1").use { assertTrue(it.moveToFirst());assertEquals(10000L,it.getLong(0)) }
            close()
        }
    }
    @Test fun checkoutSnapshotsCancelAndRetryAreAtomicOffline()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).addCallback(object: androidx.room.RoomDatabase.Callback() {
            override fun onOpen(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
                (com.hardwareshop.pos.data.sync.SyncJournalSchema.statements(includeBoxCount=true,includePipe=true) + com.hardwareshop.pos.data.sync.SyncTransferSchema.statements()).forEach { sql.execSQL(it) }
            }
        }).build()
        try {
            val session=SessionHolder().apply { setSession(UserSession.OwnerSession("Test")) }
            val audit=AuditRepository(db.auditLogDao(),session)
            val billing=BillingRepository(db,db.billDao(),db.productDao(),db.creditDao(),audit,BillNumberGenerator(db),SettingsRepository(db.settingsDao(),db.ownerCredentialDao(),session),session)
            val c=db.categoryDao().insertCategory(CategoryEntity(name="PVC"));val t=db.productTypeDao().insertType(ProductTypeEntity(categoryId=c,name="Pipe"))
            val id=db.productDao().insertProduct(ProductEntity(categoryId=c,typeId=t,name="PVC Pipe",size="1 inch",unit="Length",sellingPricePaisa=40000,currentStock=10.0,
                isPipe=true,pipeLengthPricePaisa=40000,pipeFootPricePaisa=3500,pipeFeetPerLength=20))
            val p=db.productDao().getProductById(id)!!.toDomain()
            val line=PipeBilling.item(p,1,10.0)
            suspend fun save()=billing.createBill(listOf(line),null,"","",0,"",false,PaymentMethod.CASH,75000,"",checkoutId="pipe-retry")
            val bill=(save() as Result.Success).data
            assertEquals(75000L,bill.totalPaisa);assertEquals(1L,bill.items.single().pipeLengths);assertEquals(10.0,bill.items.single().pipeFeet,0.0)
            assertEquals(8.5,db.productDao().getProductById(id)!!.currentStock,0.0)
            assertEquals(bill.id,(save() as Result.Success).data.id);assertEquals(8.5,db.productDao().getProductById(id)!!.currentStock,0.0)
            val sql=db.openHelper.writableDatabase
            sql.query("SELECT COUNT(*) FROM sync_stock_movements WHERE kind='DELTA'").use { assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0)) }
            val projector=com.hardwareshop.pos.data.sync.SyncOutboxProjector(db,com.hardwareshop.pos.data.sync.SyncTransferStore(db))
            val spec=com.hardwareshop.pos.data.sync.SyncWireSchema.byName.getValue("bill_items")
            val change=com.hardwareshop.pos.data.sync.SyncJournal.Change("unused_global_001","bill_items",bill.items.single().id.toString(),0,false)
            val fields=projector.readFields(sql,change,spec)
            assertEquals(1L,fields["pipeLengths"]);assertEquals(10.0,fields["pipeFeet"]);assertEquals(3500L,fields["pipeFootPricePaisa"])
            com.hardwareshop.pos.data.sync.SyncProtocol.validate(com.hardwareshop.pos.data.sync.SyncProtocol.Mutation("operation_pipe_00001","record_pipe_0000001","bill_items",spec.scope,"owner",0,false,fields))
            db.productDao().updateProduct(db.productDao().getProductById(id)!!.copy(pipeFootPricePaisa=9999))
            val reopened=billing.getBillById(bill.id)!!;assertEquals(3500L,reopened.items.single().pipeFootPricePaisa)
            val receipt=ReceiptLayout.build(reopened,ReceiptLayout.Shop("Shop","","",""))
            assertTrue(receipt.any { it.text.contains("1 Len x 400") });assertTrue(receipt.any { it.text.contains("10 Ft x 35") });assertTrue(receipt.all { it.text.length<=32 })
            assertTrue(billing.cancelBill(bill.id,"test") is Result.Success);assertEquals(10.0,db.productDao().getProductById(id)!!.currentStock,0.0)
            assertTrue(billing.cancelBill(bill.id,"retry") is Result.Error);assertEquals(10.0,db.productDao().getProductById(id)!!.currentStock,0.0)
        } finally { db.close() }
    }
    @Test fun suppliedCatalogueHasOneCombinedPipePerVariantAfterCleanup()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addCallback(object: androidx.room.RoomDatabase.Callback() {
            override fun onOpen(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
                (com.hardwareshop.pos.data.sync.SyncJournalSchema.statements(includeBoxCount=true,includePipe=true) + com.hardwareshop.pos.data.sync.SyncTransferSchema.statements()).forEach { sql.execSQL(it) }
            }
        }).build()
        try {
            fun rows(asset:String)=context.assets.open(asset).bufferedReader().use {
                com.google.gson.Gson().fromJson(it,Array<FinalProductCatalog.Row>::class.java).toList()
            }
            db.withTransaction { FinalProductCatalog.mergeBoth(FinalProductCatalog.RoomStore(db),rows("final_catalog.json"),rows("store_catalog.json"),1) }
            db.withTransaction { PipeProductCatalog.merge(FinalProductCatalog.RoomStore(db)) }
            val all=db.productDao().getAllProducts()
            assertEquals(27,all.count { it.isActive && it.isPipe })
            PipeProductCatalog.variants.forEach { v ->
                val combined=all.single { it.isActive && it.isPipe && PipeProductCatalog.matches(v,it) }
                assertTrue(combined.pipeLengthPricePaisa>0 && combined.pipeFootPricePaisa>0)
                assertFalse(all.any { it.isActive && !it.isPipe && it.categoryId==combined.categoryId && PipeProductCatalog.matches(v,it) &&
                    it.unit.lowercase() in setOf("length","piece","nos","ft","foot","feet") })
            }
            assertTrue(all.count { !it.isActive && !it.isPipe }>20)
            val snapshot=all
            db.withTransaction { PipeProductCatalog.merge(FinalProductCatalog.RoomStore(db)) }
            assertEquals(snapshot,db.productDao().getAllProducts())
        } finally { db.close() }
    }
    @Test fun catalogImportIsIdempotentAndRetainsCustomizedPrices()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).addCallback(object: androidx.room.RoomDatabase.Callback() {
            override fun onOpen(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
                (com.hardwareshop.pos.data.sync.SyncJournalSchema.statements(includeBoxCount=true,includePipe=true) + com.hardwareshop.pos.data.sync.SyncTransferSchema.statements()).forEach { sql.execSQL(it) }
            }
        }).build()
        try {
            val c=db.categoryDao().insertCategory(CategoryEntity(name="PVC"));val t=db.productTypeDao().insertType(ProductTypeEntity(categoryId=c,name="Pipe"))
            val id=db.productDao().insertProduct(ProductEntity(categoryId=c,typeId=t,name="PVC Pipe",size="1 inch",unit="Length",sellingPricePaisa=45500,purchasePricePaisa=20000,currentStock=7.0))
            val legacy=db.productDao().insertProduct(ProductEntity(categoryId=c,typeId=t,name="PVC Pipe",size="1 inch",unit="Foot",sellingPricePaisa=3500,currentStock=5.0))
            db.withTransaction { PipeProductCatalog.merge(FinalProductCatalog.RoomStore(db)) }
            assertEquals(27,db.productDao().getAllProducts().count { it.isPipe })
            val p=db.productDao().getProductById(id)!!;assertEquals(45500L,p.pipeLengthPricePaisa);assertEquals(7.0,p.currentStock,0.0);assertEquals(20000L,p.purchasePricePaisa)
            db.withTransaction { PipeProductCatalog.merge(FinalProductCatalog.RoomStore(db)) }
            assertEquals(28,db.productDao().getAllProducts().size)
            assertEquals(5.0,db.productDao().getProductById(legacy)!!.currentStock,0.0)
            assertFalse(db.productDao().getProductById(legacy)!!.isActive)
            assertTrue(db.productDao().getProductById(id)!!.isActive)
            assertEquals(27,db.productDao().getAllProducts().count { it.isActive })
            // Upgrading an already-imported v2 catalogue also removes newly restored single-rate rows.
            val extra=db.productDao().insertProduct(ProductEntity(categoryId=c,typeId=t,name="PVC Pipe",size="1 inch",unit="Length",sellingPricePaisa=49900,currentStock=3.0))
            val other=db.productDao().insertProduct(ProductEntity(categoryId=c,typeId=t,name="SDR-11 PVC Pipe",size="1 inch",unit="Length",sellingPricePaisa=55000,currentStock=4.0))
            val differentCategory=db.categoryDao().insertCategory(CategoryEntity(name="Other pipe grade"))
            val distinct=db.productDao().insertProduct(ProductEntity(categoryId=differentCategory,typeId=t,name="PVC Pipe",size="1 inch",unit="Foot",sellingPricePaisa=9999,currentStock=2.0))
            db.withTransaction { PipeProductCatalog.merge(FinalProductCatalog.RoomStore(db)) }
            assertFalse(db.productDao().getProductById(extra)!!.isActive)
            assertEquals(3.0,db.productDao().getProductById(extra)!!.currentStock,0.0)
            assertTrue(db.productDao().getProductById(other)!!.isActive)
            assertTrue(db.productDao().getProductById(distinct)!!.isActive)
            assertEquals(45500L,db.productDao().getProductById(id)!!.pipeLengthPricePaisa)
            assertEquals(3500L,db.productDao().getProductById(id)!!.pipeFootPricePaisa)
            val before=db.productDao().getAllProducts()
            db.withTransaction { PipeProductCatalog.merge(FinalProductCatalog.RoomStore(db)) }
            assertEquals(before,db.productDao().getAllProducts())
            assertTrue(db.settingsDao().getSetting("catalog:pipe-dual-rate:cleanup-v3")!!.value.contains("recorded stock"))
            val session=SessionHolder().apply { setSession(UserSession.OwnerSession("Test")) }
            val repo=ProductRepository(db,db.categoryDao(),db.productTypeDao(),db.productDao(),db.stockDao(),AuditRepository(db.auditLogDao(),session),session)
            val billingProducts=repo.canonicalBillingProducts(listOf(repo.getProductById(id)!!,repo.getProductById(legacy)!!))
            assertEquals(listOf(id),billingProducts.map { it.id })
            assertTrue(billingProducts.single().isPipe)
            assertTrue(db.settingsDao().getSetting(PipeProductCatalog.VERSION)!!.value.contains("kept length"))
        } finally { db.close() }
    }
}

package com.hardwareshop.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.domain.model.Result
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class OffcutCheckoutIntegrationTest {
    private lateinit var db:AppDatabase
    private lateinit var session:SessionHolder
    private lateinit var repository:PipeOffcutRepository
    private lateinit var product:Product
    @Before fun setup()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java)
            .addCallback(object:androidx.room.RoomDatabase.Callback() {
                override fun onOpen(sql:androidx.sqlite.db.SupportSQLiteDatabase) {
                    (com.hardwareshop.pos.data.sync.SyncJournalSchema.statements(includeBoxCount=true,includePipe=true)+
                        com.hardwareshop.pos.data.sync.SyncTransferSchema.statements()).forEach { sql.execSQL(it) }
                }
            }).build()
        session=SessionHolder().apply { setSession(UserSession.OwnerSession("Offcut test")) }
        repository=PipeOffcutRepository(db,session)
        val category=db.categoryDao().insertCategory(CategoryEntity(name="PVC"))
        val type=db.productTypeDao().insertType(ProductTypeEntity(categoryId=category,name="Pipe"))
        val id=db.productDao().insertProduct(ProductEntity(categoryId=category,typeId=type,name="PVC Pipe",size="1 inch",unit="Length",
            sellingPricePaisa=40000,currentStock=10.0,isPipe=true,pipeFeetPerLength=20,pipeLengthPricePaisa=40000,pipeFootPricePaisa=3500))
        product=Product(id,category,"PVC",type,"Pipe","PVC Pipe","1 inch","Length",40000,0,0,10.0,0.0,null,
            isActive=true,isPipe=true,pipeFeetPerLength=20,pipeLengthPricePaisa=40000,pipeFootPricePaisa=3500)
    }
    @After fun close(){db.close()}
    private fun billing():BillingRepository {
        val audit=AuditRepository(db.auditLogDao(),session)
        return BillingRepository(db,db.billDao(),db.productDao(),db.creditDao(),audit,BillNumberGenerator(db),
            SettingsRepository(db.settingsDao(),db.ownerCredentialDao(),session),session)
    }
    private fun line(record:PipeOffcutRepository.Record,feet:Double)=PipeBilling.item(product,0,feet)
        .copy(offcut=OffcutReference(record.id,record.revision,record.feet))
    private suspend fun sale(line:CartItem,token:String)=billing().createBill(listOf(line),null,"Buyer","",0,"",false,PaymentMethod.CASH,0,"",checkoutId=token)
    @Test fun saleAndRetryDeductStockOnceAndConsumeOnlyTheSoldFeet()=runBlocking {
        repository.add(product,"8","Rack A")
        val item=line(repository.snapshot().single(),5.0)
        val first=sale(item,"offcut-sale-a")
        assertTrue("$first",first is Result.Success)
        val bill=(first as Result.Success).data
        assertEquals(17500L,bill.totalPaisa)
        assertTrue(bill.items.single().productName.contains("Offcut"))
        assertEquals(9.75,db.productDao().getProductById(product.id)!!.currentStock,0.0)
        assertEquals(300,repository.snapshot().single().feet)
        assertTrue(repository.snapshot().single().available)
        assertEquals(bill.id,(sale(item,"offcut-sale-a") as Result.Success).data.id)
        assertEquals(9.75,db.productDao().getProductById(product.id)!!.currentStock,0.0)
        assertTrue(sale(item,"offcut-sale-b") is Result.Error)
        assertEquals(1,db.billDao().getAllBills().size)
        assertEquals(9.75,db.productDao().getProductById(product.id)!!.currentStock,0.0)
    }
    @Test fun wholePieceIsUnavailableAfterSaleAndCancellationRestoresItsPhysicalLengthOnce()=runBlocking {
        repository.add(product,"8","Rack A")
        val first=sale(line(repository.snapshot().single(),8.0),"offcut-full") as Result.Success
        assertFalse(repository.snapshot().single().available)
        assertEquals("SOLD",repository.snapshot().single().status)
        assertEquals(first.data.id,repository.snapshot().single().soldBillId)
        assertEquals(9.6,db.productDao().getProductById(product.id)!!.currentStock,0.0000001)
        assertTrue(billing().cancelBill(first.data.id,"Sale cancelled before collection") is Result.Success)
        assertEquals(800,repository.snapshot().filter {it.available}.sumOf {it.feet})
        assertEquals(10.0,db.productDao().getProductById(product.id)!!.currentStock,0.0)
        assertTrue(billing().cancelBill(first.data.id,"Retry") is Result.Error)
        assertEquals(800,repository.snapshot().filter {it.available}.sumOf {it.feet})
    }
    @Test fun editedOrOversizedOffcutRollsBackTheEntireCheckout()=runBlocking {
        repository.add(product,"8","A")
        val old=repository.snapshot().single()
        assertTrue(sale(line(old,9.0),"oversized-offcut") is Result.Error)
        assertTrue(sale(line(old,5.0).copy(pipeLengths=1),"mixed-offcut") is Result.Error)
        val plan=PipeCutPlanner.plan(product.id,listOf(200),listOf(old.piece()))
        repository.recordCuts(plan,product,java.util.UUID.randomUUID().toString())
        assertTrue(sale(line(old,5.0),"stale-offcut") is Result.Error)
        assertTrue(db.billDao().getAllBills().isEmpty())
        assertEquals(600,repository.snapshot().single().feet)
        assertEquals(10.0,db.productDao().getProductById(product.id)!!.currentStock,0.0)
    }
    @Test fun damagedSaleReferenceCannotRestoreAnInventedPhysicalLength()=runBlocking {
        repository.add(product,"8","Rack A")
        val saved=sale(line(repository.snapshot().single(),8.0),"offcut-corrupt-reference") as Result.Success
        val key="pipe-offcut-bill:${saved.data.id}"
        val array=com.google.gson.JsonParser.parseString(db.settingsDao().getSetting(key)!!.value).asJsonArray
        array[0].asJsonObject.addProperty("soldFeet",700)
        db.settingsDao().upsertSetting(SettingsEntity(key,array.toString()))
        assertTrue(billing().cancelBill(saved.data.id,"Invalid reference fixture") is Result.Error)
        assertEquals("ACTIVE",db.billDao().getBillById(saved.data.id)!!.status)
        assertEquals(9.6,db.productDao().getProductById(product.id)!!.currentStock,0.0000001)
        assertTrue(repository.snapshot().none {it.available})
    }
    @Test fun discardCreatesOneStockAdjustmentAndNoSaleAndRequiresOwner()=runBlocking {
        repository.add(product,"8","Damaged")
        val record=repository.snapshot().single()
        session.clear()
        assertTrue(runCatching {repository.discard(record)}.isFailure)
        assertEquals(10.0,db.productDao().getProductById(product.id)!!.currentStock,0.0)
        session.setSession(UserSession.OwnerSession("Offcut test"))
        repository.discard(record)
        assertEquals("DISCARDED",repository.snapshot().single().status)
        assertFalse(repository.snapshot().single().available)
        assertEquals(9.6,db.productDao().getProductById(product.id)!!.currentStock,0.0000001)
        assertTrue(runCatching {repository.discard(record)}.isFailure)
        assertEquals(1,db.stockDao().getAllAdjustments().size)
        assertTrue(db.billDao().getAllBills().isEmpty())
    }
}

package com.hardwareshop.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.PipeOffcutRepository
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.domain.model.Result
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class JobToolsIntegrationTest {
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
    @Test fun recordingCutsIsIdempotentAndDoesNotChangeProductStock()=runBlocking {
        repository.add(product,"8","Rack A")
        val record=repository.snapshot().single()
        val plan=PipeCutPlanner.plan(product.id,listOf(500),listOf(record.piece()))
        val operation=java.util.UUID.randomUUID().toString()
        repository.recordCuts(plan,product,operation)
        assertEquals(300,repository.snapshot().single().feet)
        assertEquals(10.0,db.productDao().getProductById(product.id)!!.currentStock,0.0)
        assertTrue(runCatching { repository.recordCuts(plan,product,operation) }.isFailure)
        assertEquals(300,repository.snapshot().single().feet)
    }
    @Test fun stalePlanRollsBackAllPieceChanges()=runBlocking {
        repository.add(product,"8","A");repository.add(product,"7","B")
        val records=repository.snapshot()
        val plan=PipeCutPlanner.plan(product.id,listOf(700,600),records.map { it.piece() })
        repository.remove(records.first())
        val before=repository.snapshot()
        assertTrue(runCatching { repository.recordCuts(plan,product,java.util.UUID.randomUUID().toString()) }.isFailure)
        assertEquals(before,repository.snapshot())
    }
    @Test fun exactUseRemovesPieceAndNewPipeCreatesOnlyItsLeftover()=runBlocking {
        repository.add(product,"5","A")
        val plan=PipeCutPlanner.plan(product.id,listOf(500,1200),repository.snapshot().map { it.piece() })
        repository.recordCuts(plan,product,java.util.UUID.randomUUID().toString())
        assertEquals(listOf(800),repository.snapshot().map { it.feet })
    }
    @Test fun unauthenticatedEditsAndInvalidMeasurementsCannotChangeRegister()=runBlocking {
        assertTrue(runCatching { repository.add(product,"21","A") }.isFailure)
        session.clear()
        assertTrue(runCatching { repository.add(product,"5","A") }.isFailure)
        assertTrue(repository.snapshot().isEmpty())
    }
    @Test fun cancelledBillsAndDuplicateLinesDoNotInflateBoughtTogetherCounts()=runBlocking {
        suspend fun bill(number:String,status:String) {
            val id=db.billDao().insertBill(BillEntity(billNumber=number,subtotalPaisa=10000,totalPaisa=10000,paymentMethod="CASH",status=status))
            db.billDao().insertBillItems(listOf(product.id,999L,999L).map { productId ->
                BillItemEntity(billId=id,productId=productId,productName="Fixture",productSize="",productUnit="piece",quantity=1.0,
                    unitPricePaisa=1000,itemTotalPaisa=1000)
            })
        }
        bill("ACTIVE","ACTIVE");bill("CANCELLED","CANCELLED")
        val result=db.billDao().getBoughtTogether(listOf(product.id))
        assertEquals(999L,result.single().productId);assertEquals(1L,result.single().saleCount)
    }
    @Test fun malformedRegisterBlocksPlanningSnapshot()=runBlocking {
        db.settingsDao().upsertSetting(SettingsEntity("pipe-offcut:broken","{broken}"))
        assertTrue(runCatching { repository.snapshot() }.isFailure)
    }
    @Test fun offcutsAndCommitMarkersSurviveBackupRestore()=runBlocking {
        repository.add(product,"8","Backup shelf")
        val plan=PipeCutPlanner.plan(product.id,listOf(500),repository.snapshot().map { it.piece() })
        val operation=java.util.UUID.randomUUID().toString()
        repository.recordCuts(plan,product,operation)
        val before=repository.snapshot()
        val audit=com.hardwareshop.pos.data.repository.AuditRepository(db.auditLogDao(),session)
        val backup=BackupManager(ApplicationProvider.getApplicationContext<Context>(),db,audit,session)
        val exported=backup.createBackup()
        assertTrue(exported is Result.Success)
        val restored=backup.restoreBackup((exported as Result.Success).data)
        assertTrue("Backup restore failed: $restored",restored is Result.Success)
        assertEquals(before,repository.snapshot())
        assertNotNull(db.settingsDao().getSetting("pipe-cut-plan:$operation"))
        assertEquals(10.0,db.productDao().getProductById(product.id)!!.currentStock,0.0)
    }
}

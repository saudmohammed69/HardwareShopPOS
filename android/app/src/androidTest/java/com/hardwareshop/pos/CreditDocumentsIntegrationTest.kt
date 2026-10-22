package com.hardwareshop.pos

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.data.sync.SyncJournalSchema
import com.hardwareshop.pos.data.sync.SyncTransferSchema
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

class CreditDocumentsIntegrationTest {
    private lateinit var db:AppDatabase
    private lateinit var session:SessionHolder
    private lateinit var settings:SettingsRepository
    private lateinit var products:ProductRepository
    private lateinit var billing:BillingRepository
    private lateinit var credit:CreditRepository
    private lateinit var reminders:CreditReminderRepository
    private lateinit var product:Product
    private var customerId=0L
    @Before fun setup()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java)
            .addCallback(object:RoomDatabase.Callback(){override fun onOpen(sql:SupportSQLiteDatabase){
                (SyncJournalSchema.statements(includeBoxCount=true,includePipe=true)+SyncTransferSchema.statements()).forEach(sql::execSQL)
            }}).build()
        session=SessionHolder().apply {setSession(UserSession.OwnerSession("Test shop"))}
        val audit=AuditRepository(db.auditLogDao(),session)
        settings=SettingsRepository(db.settingsDao(),db.ownerCredentialDao(),session)
        products=ProductRepository(db,db.categoryDao(),db.productTypeDao(),db.productDao(),db.stockDao(),audit,session)
        billing=BillingRepository(db,db.billDao(),db.productDao(),db.creditDao(),audit,BillNumberGenerator(db),settings,session)
        credit=CreditRepository(db,db.creditDao(),audit,session)
        reminders=CreditReminderRepository(db,settings,session)
        val category=db.categoryDao().insertCategory(CategoryEntity(name="Tools"))
        val type=db.productTypeDao().insertType(ProductTypeEntity(categoryId=category,name="Hand tools"))
        val id=db.productDao().insertProduct(ProductEntity(categoryId=category,typeId=type,name="Hammer",size="500g",unit="Piece",
            sellingPricePaisa=10000,purchasePricePaisa=6000,currentStock=20.0))
        product=products.getProductById(id)!!
        customerId=db.customerDao().insertCustomer(CustomerEntity(name="Contractor",phone="9876543210"))
    }
    @After fun cleanup(){db.close()}
    private suspend fun sale(method:PaymentMethod=PaymentMethod.CREDIT):Bill {
        val line=CartItem(product,2.0,10000,20000)
        return (billing.createBill(listOf(line),customerId,"Contractor","9876543210",0,"",false,method,
            if(method==PaymentMethod.CREDIT)5000 else 20000,"") as Result.Success).data
    }
    private suspend fun financialSnapshot()=listOf(db.billDao().getAllBills().toString(),db.billDao().getAllBillItems().toString(),
        db.billDao().getAllPayments().toString(),db.creditDao().getAllTransactions().toString(),db.productDao().getAllProducts().toString())
    @Test fun remindersReadTheExistingBalanceAndNeverWriteFinancialRows()=runBlocking {
        sale();reminders.setDueDate(customerId,LocalDate.now().minusDays(1).toString())
        val before=financialSnapshot()
        val prepared=reminders.prepare(customerId)
        assertEquals(15000L,prepared.balancePaisa);assertEquals(0,reminders.observe(customerId).first().attempts.size)
        val first=reminders.recordOpening(prepared)
        val retry=reminders.recordOpening(prepared)
        assertEquals(first,retry)
        reminders.markOutcome(first.id,true,"Chooser opened")
        val snapshot=reminders.observe(customerId).first()
        assertEquals(1,snapshot.attempts.size);assertEquals("SHARE_OPENED",snapshot.attempts.single().state)
        assertEquals(before,financialSnapshot())
        assertFalse(snapshot.attempts.single().state.contains("SENT"))
    }
    @Test fun paymentBetweenPreviewAndShareRequiresAFreshReminder()=runBlocking {
        sale();val previous=reminders.prepare(customerId)
        assertTrue(credit.recordPayment(customerId,1000,"Cash",requestId="reminder-test-payment") is Result.Success)
        assertTrue(runCatching {reminders.recordOpening(previous)}.isFailure)
        val current=reminders.prepare(customerId)
        assertEquals(14000L,current.balancePaisa)
        reminders.recordOpening(current);reminders.markOutcome(current.id,false,"No share app")
        assertEquals("OPEN_FAILED",reminders.observe(customerId).first().attempts.single().state)
        assertEquals(14000L,db.creditDao().getBalanceForCustomer(customerId))
        assertTrue(credit.recordPayment(customerId,1000,"Cash",requestId="reminder-retry-payment") is Result.Success)
        assertTrue(runCatching {reminders.recordOpening(current)}.isFailure)
        assertEquals(13000L,db.creditDao().getBalanceForCustomer(customerId))
    }
    @Test fun dueDateChangesNoBalanceAndOwnerPermissionsAreEnforced()=runBlocking {
        sale();val original=financialSnapshot()
        assertTrue(runCatching {reminders.setDueDate(customerId,"2026-02-29")}.isFailure)
        val prepared=reminders.prepare(customerId)
        reminders.setDueDate(customerId,"2026-12-01")
        assertTrue(runCatching {reminders.recordOpening(prepared)}.isFailure)
        reminders.setDueDate(customerId,"")
        assertEquals("",reminders.observe(customerId).first().dueDate)
        session.setSession(UserSession.WorkerSession(10,"Worker","worker",0,false))
        assertTrue(runCatching {reminders.prepare(customerId)}.isFailure)
        assertTrue(runCatching {reminders.setDueDate(customerId,"2026-12-01")}.isFailure)
        session.clear();assertTrue(runCatching {reminders.observe(customerId).first()}.isFailure)
        assertEquals(original,financialSnapshot())
    }
    @Test fun savedReceiptUsesHistoricalValuesAndProducesAReadablePaginatedPdf()=runBlocking {
        settings.set(SettingsRepository.Keys.SHOP_GST_NUMBER,"OLD-GSTIN")
        val bill=sale(PaymentMethod.CASH)
        settings.set(SettingsRepository.Keys.SHOP_GST_NUMBER,"NEW-GSTIN")
        settings.set("receipt_footer","Keep this receipt.\nThank you!")
        val info=settings.getShopInfo()
        val before=financialSnapshot()
        val lines=SavedReceiptPdf.lines(bill,info).map {it.value}
        assertTrue(lines.any {it.contains("OLD-GSTIN")});assertFalse(lines.any {it.contains("NEW-GSTIN")})
        assertTrue(lines.any {it.contains("Keep this receipt.")})
        assertTrue(lines.any {it.contains(bill.billNumber)})
        val context=ApplicationProvider.getApplicationContext<Context>()
        val file=ShopDocumentPdf.create(context,"PAGINATED RECEIPT",SavedReceiptPdf.lines(bill,info)+
            (1..140).map {ShopDocumentPdf.Text("${it}. Long saved product description for pagination and accessible receipt sharing")},"receipt")
        try {
            assertTrue(file.length()>1000)
            android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use {descriptor->
                android.graphics.pdf.PdfRenderer(descriptor).use {renderer->
                    assertTrue(renderer.pageCount>=3)
                    for(index in 0 until renderer.pageCount)renderer.openPage(index).use {page->
                        val bitmap=android.graphics.Bitmap.createBitmap(page.width,page.height,android.graphics.Bitmap.Config.ARGB_8888)
                        try {
                            page.render(bitmap,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            if(index==0)java.io.File(context.getExternalFilesDir(null),"advanced-receipt-pdf-page.png").outputStream().use {
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                            }
                        }finally {bitmap.recycle()}
                    }
                }
            }
            val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
            assertEquals("content",uri.scheme)
        }finally {file.delete()}
        assertEquals(before,financialSnapshot())
    }
    @Test fun customFooterKeepsUpiQrAndSplitAmountInTheExistingPrinterEncoding()=runBlocking {
        val bill=sale(PaymentMethod.CASH).copy(paymentMethod=PaymentMethod.SPLIT,
            payments=listOf(BillPayment(PaymentMethod.CASH,6000),BillPayment(PaymentMethod.UPI,14000)))
        val info=settings.getShopInfo().copy(upiId="shop@upi",upiName="Shop",receiptFooter="Original bill required for returns.\nThank you.")
        val before=financialSnapshot()
        val rows=ReceiptLayout.build(bill,ReceiptLayout.Shop(info.name,info.phone,info.address,info.gstNumber,info.receiptFooter))
        assertEquals(1,rows.count {it.footerStart})
        val bytes=ReceiptBuilder.buildReceipt(bill,info)
        val encoded=String(bytes,Charsets.ISO_8859_1)
        assertTrue(encoded.contains("SCAN & PAY Rs.140.00"))
        assertTrue(encoded.contains("Original bill required"))
        assertTrue(bytes.size<PrinterTransport.MAX_PAYLOAD_BYTES)
        assertEquals(before,financialSnapshot())
    }
}

package com.hardwareshop.pos

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hardwareshop.pos.data.local.database.*
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.data.sync.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.presentation.owner.products.*
import com.hardwareshop.pos.presentation.worker.billing.*
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class Phase2InventoryBillingTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var db:AppDatabase
    private lateinit var products:ProductRepository
    private lateinit var manager:InventoryManagerRepository
    private lateinit var session:SessionHolder
    private lateinit var billing:BillingViewModel
    private lateinit var repository:BillingRepository
    private val managerViewModels=mutableListOf<InventoryManagerViewModel>()
    private var category=0L;private var type=0L
    @Before fun setup():Unit=runBlocking {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java)
            .addCallback(object:androidx.room.RoomDatabase.Callback(){override fun onOpen(sql:androidx.sqlite.db.SupportSQLiteDatabase){
                (SyncJournalSchema.statements(includeBoxCount=true,includePipe=true)+SyncTransferSchema.statements()).forEach(sql::execSQL)
            }}).build()
        session=SessionHolder().apply{setSession(UserSession.OwnerSession("Phase 2"))}
        val audit=AuditRepository(db.auditLogDao(),session)
        products=ProductRepository(db,db.categoryDao(),db.productTypeDao(),db.productDao(),db.stockDao(),audit,session)
        manager=InventoryManagerRepository(db,products,session)
        category=db.categoryDao().insertCategory(CategoryEntity(name="Paints & Coatings"))
        type=db.productTypeDao().insertType(ProductTypeEntity(categoryId=category,name="Colour Paints"))
        val settings=SettingsRepository(db.settingsDao(),db.ownerCredentialDao(),session)
        repository=BillingRepository(db,db.billDao(),db.productDao(),db.creditDao(),audit,BillNumberGenerator(db),settings,session)
        billing=withContext(Dispatchers.Main){BillingViewModel(repository,CustomerRepository(db.customerDao(),db.creditDao(),session),products,db.settingsDao(),settings,db,session)}
        withTimeout(15000){billing.state.first{!it.isLoading}}
    }
    @After fun close():Unit=runBlocking {
        compose.activityRule.scenario.onActivity { it.setContent {} }
        withContext(Dispatchers.Main){
            billing.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            managerViewModels.forEach{it.viewModelScope.coroutineContext[Job]?.cancelAndJoin()}
        }
        db.close()
    }
    private fun request(parent:String="Paint",rows:List<InventoryManagerRepository.Variant>,structure:String="Colour + Size")=
        InventoryManagerRepository.Request(parent,category,type,"Piece","0",variants=rows,structure=structure)
    private fun row(colour:String,size:String,price:String)=InventoryManagerRepository.Variant(colour=colour,size=size,selling=price)
    private suspend fun p(id:Long)=requireNotNull(products.getProductById(id))
    @Test fun addColoursSizesAndFuturePurpleKeepOneParentWithIndependentPrices():Unit=runBlocking {
        val ids=manager.save(request(rows=listOf(row("Red","100 ml","80"),row("Blue","500 ml","250"))))
        val purple=manager.save(request(rows=listOf(row("Purple","100 ml","90"),row("Purple","1 L","500"))))
        val all=(ids+purple).map{p(it)}
        val groups=ProductSizeGroups.groups(all)
        assertEquals(1,groups.size);assertEquals("Paint",groups.single().name)
        assertEquals(4,groups.single().products.size);assertEquals(50000L,p(purple[1]).sellingPricePaisa)
        assertEquals(setOf("100 ml","1 L"),all.filter{ProductVariantMetadata.read(it)?.colour=="Purple"}.map{it.size}.toSet())
        assertEquals(2,products.searchProducts("purple paint").size)
        assertTrue(products.searchProducts("purple paint").all{it.name.contains("Purple")})
    }
    @Test fun simpleSizeTypeSizeAndPipeStructuresUseExistingInventory():Unit=runBlocking {
        val simple=manager.save(request("New Tool",listOf(InventoryManagerRepository.Variant(selling="35")),"Simple")).single()
        assertEquals("",p(simple).size);assertEquals(0.0,p(simple).currentStock,0.0);assertEquals(0L,p(simple).purchasePricePaisa)
        val sizes=manager.save(request("Waterproofing Chemical",listOf(InventoryManagerRepository.Variant(size="1 L",selling="150"),InventoryManagerRepository.Variant(size="5 L",selling="650")),"Size"))
        assertEquals(2,ProductSizeGroups.choices(sizes.map{p(it)},p(sizes[0])).size)
        val models=manager.save(request("Coating",listOf(InventoryManagerRepository.Variant(size="1 L",model="Matt",selling="200")),"Type + Size"))
        assertEquals("Matt",ProductVariantMetadata.read(p(models.single()))!!.model)
        val finished=manager.save(request("Coating",listOf(InventoryManagerRepository.Variant(size="1 L",model="Matt",colour="Purple",selling="220")),"Type + Colour + Size"))
        assertEquals("Purple",ProductVariantMetadata.read(p(finished.single()))!!.colour)
        val fitting=manager.save(request("PVC Elbow",listOf(InventoryManagerRepository.Variant(size="1/2 inch",selling="25")),"Size")).single()
        val newFitting=manager.save(request("PVC Elbow",listOf(InventoryManagerRepository.Variant(size="1¼ inch",selling="72")),"Size")).single()
        assertEquals(2,ProductSizeGroups.choices(listOf(p(fitting),p(newFitting)),p(fitting)).size)
        val pipe=manager.save(InventoryManagerRepository.Request("PVC Pipe",category,type,"Length","0",true,"20",
            listOf(InventoryManagerRepository.Variant(size="1 inch",lengthPrice="850",footPrice="80")),structure="Pipe")).single()
        assertEquals(210000L,PipeBilling.item(p(pipe),2,5.0).itemTotalPaisa)
        val newPipe=manager.save(InventoryManagerRepository.Request("PVC Pipe",category,type,"Length","0",true,"20",
            listOf(InventoryManagerRepository.Variant(size="2 inch",lengthPrice="1200",footPrice="110")),structure="Pipe")).single()
        assertEquals(2,ProductSizeGroups.choices(listOf(p(pipe),p(newPipe)),p(pipe)).size)
        assertEquals(85000L,p(pipe).pipeLengthPricePaisa);assertEquals(120000L,p(newPipe).pipeLengthPricePaisa)
    }
    @Test fun duplicateOrInvalidBatchRollsBackAllVariants():Unit=runBlocking {
        manager.save(request(rows=listOf(row("Red","100 ml","80"))))
        val before=db.productDao().getAllProducts()
        assertTrue(runCatching{manager.save(request(rows=listOf(row("Blue","1 L","500"),row("Red","100 ml","90"))))}.isFailure)
        assertEquals(before,db.productDao().getAllProducts())
        assertTrue(runCatching{manager.save(request(rows=listOf(row("Purple","500 ml","NaN"))))}.isFailure)
    }
    @Test fun addingSizeToLegacyPaintFindsSameParentAndPreventsSemanticDuplicate():Unit=runBlocking {
        db.productDao().insertProduct(ProductEntity(categoryId=category,typeId=type,name="Paint – Black",size="100 ml",unit="Piece",sellingPricePaisa=8000))
        assertTrue(runCatching{manager.save(request(rows=listOf(row("Black","100 ml","85"))))}.isFailure)
        assertTrue(runCatching{manager.save(request(rows=listOf(row("Black","100 ml","85"))).copy(unit=" Piece "))}.isFailure)
        manager.save(request(rows=listOf(row("Black","500 ml","250"))))
        val all=products.observeAllActiveProducts().first()
        assertEquals(1,ProductSizeGroups.groups(all).size);assertEquals(2,all.size)
    }
    @Test fun ownerOnlyCreationAndWorkerCannotOverrideThroughSubmission():Unit=runBlocking {
        val id=manager.save(request(rows=listOf(row("Red","100 ml","80")))).single();val product=p(id)
        session.setSession(UserSession.WorkerSession(1,"Worker","worker",0,false))
        assertTrue(runCatching{manager.save(request(rows=listOf(row("Purple","1 L","500"))))}.isFailure)
        withContext(Dispatchers.Main){assertFalse(billing.addSelection("worker",listOf(CartItem(product,2.0,7000,14000))))}
        assertTrue(billing.state.value.cart.isEmpty())
    }
    @Test fun batchSubmitIsAtomicAndTokenPreventsTripleTapButNewOperationWorks():Unit=runBlocking {
        val ids=manager.save(request(rows=listOf(row("Red","100 ml","80"),row("Red","500 ml","250"))))
        val a=p(ids[0]);val b=p(ids[1]);val lines=listOf(CartItem(a,5.0,8000,40000),CartItem(b,3.0,25000,75000))
        withContext(Dispatchers.Main){assertTrue(billing.addSelection("one",lines));repeat(2){assertFalse(billing.addSelection("one",lines))}}
        assertEquals(2,billing.state.value.cart.size);assertEquals(115000L,billing.state.value.calculation!!.totalPaisa)
        withContext(Dispatchers.Main){assertTrue(billing.addSelection("intentional",listOf(lines[0])))}
        assertEquals(10.0,billing.state.value.cart.first{it.product.id==a.id}.quantity,0.0)
        withContext(Dispatchers.Main) {
            billing.setItemPrice(a.id,"70")
            val before=billing.state.value.cart
            assertFalse(billing.addSelection("conflicting-batch",listOf(lines[1],lines[0])))
            assertEquals(before,billing.state.value.cart)
        }
    }
    @Test fun multipleRemovalUndoRestoresOverridesAndPositionOnlyOnce():Unit=runBlocking {
        val ids=manager.save(request(rows=listOf(row("Red","100 ml","80"),row("Blue","100 ml","90"))))
        val a=p(ids[0]);val b=p(ids[1])
        withContext(Dispatchers.Main){billing.addSelection("start",listOf(CartItem(a,2.0,7000,14000),CartItem(b,3.0,9000,27000)))}
        val before=billing.state.value.cart
        withContext(Dispatchers.Main){billing.removeFromCart(a.id);billing.removeFromCart(b.id)}
        val removed=billing.state.value.removedLines
        withContext(Dispatchers.Main){billing.undoRemoved(removed[1].token);billing.undoRemoved(removed[0].token);billing.undoRemoved(removed[0].token)}
        assertEquals(before,billing.state.value.cart);assertEquals(41000L,billing.state.value.calculation!!.totalPaisa)
    }
    @Test fun savedOverrideAuditsOriginalRateWithoutChangingMasterOrOldBills():Unit=runBlocking {
        val id=manager.save(request(rows=listOf(row("Red","100 ml","80")))).single();val product=p(id)
        withContext(Dispatchers.Main){billing.addSelection("override",listOf(CartItem(product,2.0,7000,14000)));billing.saveBill()}
        withTimeout(15000){billing.state.first{!it.isLoading}}
        val bill=repository.getBillById(requireNotNull(billing.state.value.savedBillId))!!
        assertEquals(14000L,bill.totalPaisa);assertEquals(8000L,p(id).sellingPricePaisa)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM audit_logs WHERE actionType='BILL_PRICE_OVERRIDDEN'").use{assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))}
        val changed=product.copy(sellingPricePaisa=10000);assertTrue(products.updateProduct(changed) is Result.Success)
        assertEquals(7000L,repository.getBillById(bill.id)!!.items.single().unitPricePaisa)
        assertTrue(ReceiptLayout.build(bill,ReceiptLayout.Shop("Shop","","","")).any{it.text.contains("Red")})
    }
    @Test fun metadataTravelsThroughExistingSyncedProductField():Unit=runBlocking {
        val id=manager.save(request(rows=listOf(row("Purple","500 ml","250")))).single()
        val transfer=SyncTransferStore(db);val projector=SyncOutboxProjector(db,transfer)
        val sql=db.openHelper.writableDatabase
        val spec=SyncWireSchema.byName.getValue("products")
        val fields=projector.readFields(sql,SyncJournal.Change("product_variant_test_0001","products",id.toString(),0,false),spec)
        val aliases=fields["searchAliases"] as String
        val decoded=ProductPackagingWire.decode(aliases,"Piece")
        assertEquals("Purple",ProductVariantMetadata.read(p(id).copy(searchAliases=decoded.aliases))!!.colour)
        SyncProtocol.validate(SyncProtocol.Mutation("operation_variant_test_0001","product_variant_test_0001","products",spec.scope,"owner",0,false,fields))
    }
    @Test fun ownerManagerCanCreateSimpleProductUsingItsActualForm():Unit=runBlocking {
        val vm=withContext(Dispatchers.Main){InventoryManagerViewModel(products,manager,session).also{managerViewModels.add(it)}}
        compose.activityRule.scenario.onActivity{it.setContent{MaterialTheme{InventoryManagerScreen({}, {}, {},vm)}}}
        compose.waitUntil(15000){vm.categories.value.isNotEmpty()}
        compose.onNodeWithText("Add New Product").performClick()
        compose.onNodeWithText("Parent product / family name").performTextInput("Future Simple Tool")
        compose.onNodeWithText("Structure: Size").performScrollTo().performClick()
        compose.onNodeWithText("Simple").performClick()
        compose.onNodeWithText("Category: Choose").performScrollTo().performClick()
        compose.onNodeWithText("Paints & Coatings").performClick()
        compose.waitUntil(15000){vm.types.value.isNotEmpty()}
        compose.onNodeWithText("Product type: Choose").performScrollTo().performClick()
        compose.onNodeWithText("Colour Paints").performClick()
        compose.onNodeWithTag("inventory-manager-list").performScrollToNode(hasText("Selling price ₹"))
        compose.onNodeWithText("Selling price ₹").performTextInput("35")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("inventory-manager-list").performScrollToNode(hasText("SAVE ALL VARIANTS"))
        compose.onNodeWithText("SAVE ALL VARIANTS").performClick()
        compose.waitUntil(15000){!vm.busy.value && vm.catalogue.value.any{it.name=="Future Simple Tool"}}
        val saved=vm.catalogue.value.single{it.name=="Future Simple Tool"}
        assertEquals(3500L,saved.sellingPricePaisa);assertEquals("",saved.size);assertEquals(0.0,saved.currentStock,0.0)
        compose.onNodeWithTag("inventory-manager-list").performScrollToIndex(0)
        compose.waitForIdle()
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"inventory-manager-1419.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            };image.recycle()
        }
    }
    @Test fun cartRemovalShowsUndoAndRestoresTheSameLine():Unit=runBlocking {
        val id=manager.save(request(rows=listOf(row("Red","100 ml","80")))).single();val product=p(id)
        withContext(Dispatchers.Main){billing.addSelection("undo-ui",listOf(CartItem(product,2.0,7000,14000)))}
        compose.activityRule.scenario.onActivity{it.setContent{MaterialTheme{CartScreen({}, {}, {}, {},billing)}}}
        compose.onNodeWithContentDescription("Remove").performClick()
        compose.onNodeWithText("UNDO").performClick()
        compose.runOnIdle{assertEquals(7000L,billing.state.value.cart.single().unitPricePaisa);assertEquals(2.0,billing.state.value.cart.single().quantity,0.0)}
    }
    @Test fun editingOneCombinationPreservesOtherRatesAndRejectsDuplicates():Unit=runBlocking {
        val ids=manager.save(request(rows=listOf(row("Red","100 ml","80"),row("Purple","100 ml","90"))))
        val red=p(ids[0]);val purple=p(ids[1])
        assertTrue(products.updateProduct(red.copy(sellingPricePaisa=8500)) is Result.Success)
        assertEquals(9000L,p(purple.id).sellingPricePaisa)
        val duplicate=purple.copy(name=red.name,searchAliases=red.searchAliases)
        assertTrue(products.updateProduct(duplicate) is Result.Error)
        assertEquals(purple,p(purple.id))
        val attrs=ProductVariantMetadata.Attributes("Paint","Blue")
        assertTrue(products.updateProduct(purple.copy(name=ProductVariantMetadata.displayName(attrs),
            searchAliases=ProductVariantMetadata.aliases(attrs,purple.searchAliases))) is Result.Success)
        assertEquals("Blue",ProductVariantMetadata.read(p(purple.id))!!.colour)
        assertEquals(8500L,p(red.id).sellingPricePaisa)
    }
    @Test fun conflictingPipeFootOverrideRejectsWithoutConsumingRetryToken():Unit=runBlocking {
        val id=manager.save(InventoryManagerRepository.Request("PVC Pipe",category,type,"Length","0",true,"20",
            listOf(InventoryManagerRepository.Variant(size="1 inch",lengthPrice="850",footPrice="80")),structure="Pipe")).single()
        val product=p(id)
        withContext(Dispatchers.Main) {
            assertTrue(billing.addSelection("pipe-first",listOf(PipeBilling.item(product,1,2.0,85000,7000))))
            val before=billing.state.value.cart
            assertFalse(billing.addSelection("retry",listOf(PipeBilling.item(product,0,3.0,85000,6000))))
            assertEquals(before,billing.state.value.cart)
            assertTrue(billing.addSelection("retry",listOf(PipeBilling.item(product,0,3.0,85000,7000))))
            assertEquals(5.0,billing.state.value.cart.single().pipeFeet,0.0)
            assertEquals(7000L,billing.state.value.cart.single().pipeFootPricePaisa)
            assertNull(billing.state.value.error)
        }
    }
}

package com.hardwareshop.pos

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.hardwareshop.pos.data.local.database.*
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.presentation.worker.billing.*
import com.hardwareshop.pos.presentation.worker.product.*
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class ProductSizeSelectionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var db: AppDatabase
    private lateinit var picker: ProductSelectionViewModel
    private lateinit var billing: BillingViewModel
    private lateinit var products: ProductRepository
    private lateinit var black: List<Product>
    private lateinit var elbows: List<Product>
    private lateinit var pipes: List<Product>
    private lateinit var hammer: Product
    private var upCalls=0
    @Before fun setup(): Unit = runBlocking {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).build()
        val session=SessionHolder().apply { setSession(UserSession.OwnerSession("Size selection test")) }
        val audit=AuditRepository(db.auditLogDao(),session)
        products=ProductRepository(db,db.categoryDao(),db.productTypeDao(),db.productDao(),db.stockDao(),audit,session)
        val settings=SettingsRepository(db.settingsDao(),db.ownerCredentialDao(),session)
        val repository=BillingRepository(db,db.billDao(),db.productDao(),db.creditDao(),audit,BillNumberGenerator(db),settings,session)
        suspend fun family(categoryName:String,typeName:String,name:String,sizes:List<String>,pipe:Boolean=false):List<Product> {
            val c=db.categoryDao().getAllCategories().firstOrNull { it.name==categoryName }?.id ?: db.categoryDao().insertCategory(CategoryEntity(name=categoryName))
            val t=db.productTypeDao().insertType(ProductTypeEntity(categoryId=c,name=typeName))
            return sizes.mapIndexed { index,size ->
                val id=db.productDao().insertProduct(ProductEntity(categoryId=c,typeId=t,name=name,size=size,
                    unit=if(pipe) "Length" else "Piece",sellingPricePaisa=10000L*(index+1),currentStock=100.0,
                    isPipe=pipe,pipeLengthPricePaisa=if(pipe) 85000 else 0,pipeFootPricePaisa=if(pipe) 8000 else 0,
                    pipeFeetPerLength=if(pipe) 20 else 0))
                requireNotNull(products.getProductById(id))
            }
        }
        black=family("Paints & Coatings","Colour Paints","Paint – Black",listOf("100 ml","250 ml","500 ml","1 L"))
        elbows=family("PVC","Elbow","PVC Elbow",listOf("1/2 inch","3/4 inch","1 inch"))
        pipes=family("PVC","Pipe","PVC Pipe",listOf("1/2 inch","1 inch"),true)
        hammer=family("Tools","Hammers","Hammer",listOf("Standard")).single()
        withContext(Dispatchers.Main) {
            picker=ProductSelectionViewModel(products,db.settingsDao(),PipeOffcutRepository(db,session))
            billing=BillingViewModel(repository,CustomerRepository(db.customerDao(),db.creditDao(),session),products,db.settingsDao(),settings,db,session)
        }
        withTimeout(15000) { picker.state.first { it.availableProducts.size==10 } }
        withTimeout(15000) { billing.state.first { !it.isLoading } }
    }
    @After fun cleanup(): Unit = runBlocking {
        compose.activityRule.scenario.onActivity { it.setContent {} }
        withContext(Dispatchers.Main) {
            picker.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            billing.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        db.close()
    }
    private fun screen()=compose.activityRule.scenario.onActivity { it.setContent {
        MaterialTheme { ProductSelectionScreen({ upCalls++ },{},billing,picker) }
    } }
    private fun chooseFamily(product:Product) {
        screen();compose.runOnIdle { picker.selectProduct(product) }
        compose.onNodeWithTag("inline-billing-entry").assertIsDisplayed()
    }
    private fun chooseSize(product:Product) {
        settleKeyboard()
        compose.onNodeWithTag("inline-size-choices").performScrollToNode(hasTestTag("size-option-${product.id}"))
        val option=compose.onNodeWithTag("size-option-${product.id}").performScrollTo()
        if(runCatching { option.assertIsDisplayed() }.isFailure)screenshot("failed-size-panel-1419.png")
        option.assertIsDisplayed().performClick()
        compose.waitUntil(15000) { picker.state.value.pendingProduct?.id==product.id }
        compose.waitForIdle()
    }
    private fun settleKeyboard() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitUntil(15000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true
        }
        compose.waitForIdle()
    }
    private fun addSelected() {
        if(compose.onAllNodesWithTag("inline-billing-entry").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText("ADD TO CART"))
        compose.onNodeWithText("ADD TO CART").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(billing.state.value.error ?: "Add did not create the selected cart line",billing.state.value.cart.isNotEmpty()) }
    }
    private fun quantityKey(key:String) {
        compose.onNodeWithContentDescription("Quantity key $key").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(key,picker.state.value.pendingQuantity) }
    }
    private fun assertChoicesReachable(products:List<Product>) {
        products.forEach {
            compose.onNodeWithTag("inline-size-choices").performScrollToNode(hasTestTag("size-option-${it.id}"))
            compose.onNodeWithTag("size-option-${it.id}").assertIsDisplayed()
        }
    }
    private fun screenshot(name:String) {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            };image.recycle()
        }
    }
    @Test fun blackPaintThenCapacityThenContainerCountBillsExactSavedVariant(): Unit = runBlocking {
        chooseFamily(black.first())
        compose.onNodeWithText("Search products, size, aliases…").assertIsDisplayed()
        compose.onNodeWithTag("billing-picker").assertIsDisplayed()
        assertChoicesReachable(black)
        compose.onNodeWithText("ADD TO CART").assertDoesNotExist()
        assertTrue(billing.state.value.cart.isEmpty())
        screenshot("black-paint-sizes-1418.png")
        chooseSize(black[2])
        compose.onNodeWithText("Number of containers (500 ml)").assertIsDisplayed()
        screenshot("selected-quantity-entry-1419.png")
        quantityKey("2")
        addSelected()
        compose.runOnIdle {
            val line=billing.state.value.cart.single()
            assertEquals(black[2].id,line.product.id);assertEquals(2.0,line.quantity,0.0)
            assertEquals(60000L,line.itemTotalPaisa)
            assertNull(picker.state.value.sizeChoiceProduct)
        }
        assertEquals(black[2].sellingPricePaisa,products.getProductById(black[2].id)!!.sellingPricePaisa)
    }
    @Test fun pvcElbowSizeThenPieceQuantityDoesNotUsePipeLengthFields() {
        chooseFamily(elbows.first());screenshot("pvc-elbow-sizes-1418.png")
        chooseSize(elbows[1])
        compose.onAllNodes(hasTestTag("billing-pipe-fields")).assertCountEquals(0)
        quantityKey("5")
        addSelected()
        compose.runOnIdle {
            assertEquals(elbows[1].id,billing.state.value.cart.single().product.id)
            assertEquals(5.0,billing.state.value.cart.single().quantity,0.0)
            assertEquals(100000L,billing.state.value.cart.single().itemTotalPaisa)
        }
    }
    @Test fun fullPipeSizeThenExistingLengthFeetBillingIsPreserved() {
        chooseFamily(pipes.first());chooseSize(pipes[1])
        compose.onNodeWithTag("pipe-length-${pipes[1].id}").performTextInput("2")
        compose.onNodeWithTag("pipe-feet-${pipes[1].id}").performTextInput("5")
        compose.runOnIdle { assertTrue(billing.state.value.cart.isEmpty()) }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText("ADD TO CART"))
        addSelected()
        compose.runOnIdle {
            val line=billing.state.value.cart.single()
            assertEquals(pipes[1].id,line.product.id);assertEquals(2L,line.pipeLengths)
            assertEquals(5.0,line.pipeFeet,0.0);assertEquals(210000L,line.itemTotalPaisa)
        }
    }
    @Test fun backReturnsFromQuantityToSizeThenToItemsWithoutAddingPaint() {
        chooseFamily(black.first());chooseSize(black[1])
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("inline-billing-entry").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("inline-billing-entry").assertDoesNotExist()
        compose.runOnIdle { assertTrue(billing.state.value.cart.isEmpty());assertEquals(0,upCalls) }
    }
    @Test fun searchCollapsesFourBlackPacksAndFavouriteStillOffersAllSizes() {
        screen();compose.runOnIdle { picker.onSearchQueryChange("black") }
        compose.waitUntil(15000) { !picker.state.value.isLoading && picker.state.value.searchResults.size==4 }
        compose.onAllNodesWithTag("billing-family-${ProductSizeGroups.key(black.first())}").assertCountEquals(1)
        compose.onNodeWithText("CHOOSE SIZE").performClick()
        assertChoicesReachable(black)
        compose.runOnIdle { picker.dismissQuantityDialog();picker.clearSearch();picker.toggleFavourite(black.first()) }
        compose.waitUntil(15000) { picker.state.value.favourites.size==1 }
        compose.onNodeWithTag("billing-family-${ProductSizeGroups.key(black.first())}").performClick()
        assertChoicesReachable(black)
    }
    @Test fun unrelatedItemsKeepQuantityFlowAndOtherFamilyCannotBeChosenAsSize() {
        chooseFamily(black.first())
        compose.runOnIdle { picker.selectSize(elbows.first());assertNull(picker.state.value.pendingProduct) }
        compose.runOnIdle { picker.selectProduct(hammer) }
        compose.onNodeWithTag("inline-billing-entry").assertDoesNotExist()
        compose.onNodeWithText("ADD TO CART").assertIsDisplayed()
        addSelected()
        compose.runOnIdle { assertEquals(hammer.id,billing.state.value.cart.single().product.id) }
    }
    @Test fun ownerAddedAndDeactivatedSizesUpdateTheLiveChooser(): Unit = runBlocking {
        chooseFamily(black.first())
        val original=db.productDao().getProductById(black.first().id)!!
        val newId=db.productDao().insertProduct(original.copy(id=0,size="2 L",sellingPricePaisa=70000))
        compose.waitUntil(15000) { picker.state.value.sizeChoices.any { it.id==newId } }
        compose.onNodeWithTag("inline-size-choices").performScrollToNode(hasTestTag("size-option-$newId"))
        compose.onNodeWithTag("size-option-$newId").assertIsDisplayed()
        db.productDao().updateProduct(original.copy(isActive=false))
        compose.waitUntil(15000) { picker.state.value.sizeChoices.none { it.id==original.id } }
        compose.onNodeWithTag("size-option-${original.id}").assertDoesNotExist()
    }
    @Test fun searchNextItemWithoutLeavingInlineEntry() {
        chooseFamily(black.first())
        compose.onNodeWithText("Search products, size, aliases…").performTextReplacement("PVC Elbow")
        compose.waitUntil(15000) { !picker.state.value.isLoading && picker.state.value.searchResults.size==3 }
        compose.onNodeWithTag("inline-billing-entry").assertDoesNotExist()
        compose.onNodeWithText("CHOOSE SIZE").performClick()
        compose.onNodeWithTag("inline-billing-entry").assertIsDisplayed()
        chooseSize(elbows[1])
        quantityKey("2")
        addSelected()
        compose.runOnIdle { assertEquals(elbows[1].id,billing.state.value.cart.single().product.id) }
    }
    @Test fun specificSizeSearchPrioritizesItAndStillRequiresSelection() {
        screen()
        compose.onNodeWithText("Search products, size, aliases…").performTextReplacement("3/4 elbow pvc")
        compose.waitUntil(15000) { !picker.state.value.isLoading && picker.state.value.searchResults.size==1 }
        settleKeyboard()
        compose.onNodeWithText("CHOOSE SIZE").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("size-option-${elbows[1].id}").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(elbows[1].id,picker.state.value.sizeChoices.first().id)
            assertNull(picker.state.value.pendingProduct);assertTrue(billing.state.value.cart.isEmpty())
        }
        compose.onNodeWithText("ADD TO CART").assertDoesNotExist()
    }
    @Test fun repeatedSizesMergeAndCartInlineEditResolvesTargetVariant() {
        compose.runOnIdle { billing.addToCart(elbows[0],2.0);billing.addToCart(elbows[0],3.0);billing.addToCart(elbows[1],1.0) }
        compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme { CartScreen({}, {}, {}, {}, billing) } } }
        settleKeyboard()
        compose.onAllNodesWithText("PVC Elbow").onFirst().performScrollTo().assertIsDisplayed().performClick()
        try {
            compose.waitUntil(15000) { compose.onAllNodesWithTag("cart-variant-editor").fetchSemanticsNodes().any { it.boundsInRoot.height>0 } }
        } finally { screenshot("cart-inline-editor-1419.png") }
        compose.onNodeWithTag("cart-variant-editor").assertIsDisplayed()
        compose.onNodeWithTag("inline-size-choices").performScrollToNode(hasTestTag("size-option-${elbows[1].id}"))
        compose.onNodeWithTag("size-option-${elbows[1].id}").performClick()
        compose.onNodeWithTag("edit-quantity").performTextReplacement("5")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("cart-variant-editor").performScrollToNode(hasText("APPLY CHANGES"))
        compose.onNodeWithText("APPLY CHANGES").performClick()
        compose.waitUntil(15000) { !billing.state.value.isLoading && billing.state.value.cart.size==1 }
        compose.runOnIdle {
            val line=billing.state.value.cart.single();assertEquals(elbows[1].id,line.product.id)
            assertEquals(6.0,line.quantity,0.0);assertEquals(120000L,line.itemTotalPaisa)
        }
        compose.onNodeWithTag("cart-variant-editor").assertDoesNotExist()
    }
    @Test fun multipleSizesCanBeAddedInOneInlineOperation() {
        chooseFamily(elbows.first())
        compose.onNodeWithText("ADD MULTIPLE SIZES").performClick()
        compose.onNodeWithTag("batch-size-fields").performScrollToNode(hasTestTag("batch-quantity-${elbows[0].id}"))
        compose.onNodeWithTag("batch-quantity-${elbows[0].id}").performTextInput("5")
        compose.onNodeWithTag("batch-size-fields").performScrollToNode(hasTestTag("batch-quantity-${elbows[1].id}"))
        compose.onNodeWithTag("batch-quantity-${elbows[1].id}").performTextInput("3")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText("ADD SELECTED TO CART"))
        compose.onNodeWithText("ADD SELECTED TO CART").performClick()
        compose.runOnIdle {
            assertEquals(2,billing.state.value.cart.size)
            assertEquals(5.0,billing.state.value.cart.first{it.product.id==elbows[0].id}.quantity,0.0)
            assertEquals(3.0,billing.state.value.cart.first{it.product.id==elbows[1].id}.quantity,0.0)
            assertEquals(110000L,billing.state.value.calculation!!.totalPaisa)
        }
    }
    @Test fun stagedPipesCombineLengthsAndFeetWhileCancelAddsNothing() {
        chooseFamily(pipes.first());chooseSize(pipes[1])
        compose.onNodeWithTag("pipe-length-${pipes[1].id}").performTextInput("1")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertTrue(billing.state.value.cart.isEmpty()) }
        compose.runOnIdle {
            assertTrue(billing.addPipe(pipes[1],"2",""))
            assertTrue(billing.addPipe(pipes[1],"","5"))
            assertTrue(billing.addPipe(pipes[1],"1","2"))
            val line=billing.state.value.cart.single()
            assertEquals(3L,line.pipeLengths);assertEquals(7.0,line.pipeFeet,0.0)
            assertEquals(311000L,line.itemTotalPaisa)
            assertFalse(billing.addPipe(pipes[1],"-1","2"))
            assertEquals(line,billing.state.value.cart.single())
        }
    }
    @Test fun multipleSizesDisplayAndUseTheExistingBillOverride() {
        compose.runOnIdle { billing.addToCart(elbows[0],1.0);billing.setItemPrice(elbows[0].id,"90") }
        chooseFamily(elbows.first())
        compose.onNodeWithText("ADD MULTIPLE SIZES").performClick()
        compose.onNodeWithTag("batch-size-fields").performScrollToNode(hasTestTag("batch-quantity-${elbows[0].id}"))
        compose.onNodeWithTag("batch-quantity-${elbows[0].id}").performTextInput("5")
        compose.onNodeWithTag("batch-size-fields").performScrollToNode(hasTestTag("batch-quantity-${elbows[1].id}"))
        compose.onNodeWithTag("batch-quantity-${elbows[1].id}").performTextInput("3")
        settleKeyboard()
        val total="Selected total: ${CalculationEngine.formatRupees(105000)}"
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText(total))
        compose.onNodeWithText(total).assertIsDisplayed()
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText("ADD SELECTED TO CART"))
        compose.onNodeWithText("ADD SELECTED TO CART").performClick()
        compose.runOnIdle {
            assertEquals(9000L,billing.state.value.cart.first{it.product.id==elbows[0].id}.unitPricePaisa)
            assertEquals(6.0,billing.state.value.cart.first{it.product.id==elbows[0].id}.quantity,0.0)
            assertEquals(114000L,billing.state.value.calculation!!.totalPaisa);assertNull(billing.state.value.error)
        }
    }
    @Test fun batchPipesKeepEachDiameterMeasurementsAndBillRates() {
        compose.runOnIdle { assertTrue(billing.addSelection("initial-pipe",listOf(PipeBilling.item(pipes[0],1,0.0,80000,7000)))) }
        chooseFamily(pipes.first())
        compose.onNodeWithText("ADD MULTIPLE SIZES").performClick()
        fun enter(id:Long,kind:String,value:String) {
            compose.onNodeWithTag("batch-size-fields").performScrollToNode(hasTestTag("batch-$kind-$id"))
            compose.onNodeWithTag("batch-$kind-$id").performTextInput(value)
        }
        enter(pipes[0].id,"lengths","2");enter(pipes[0].id,"feet","3")
        enter(pipes[1].id,"lengths","1");enter(pipes[1].id,"feet","5")
        settleKeyboard()
        val total="Selected total: ${CalculationEngine.formatRupees(306000)}"
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText(total))
        compose.onNodeWithText(total).assertIsDisplayed()
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText("ADD SELECTED TO CART"))
        compose.onNodeWithText("ADD SELECTED TO CART").performClick()
        compose.runOnIdle {
            val first=billing.state.value.cart.single{it.product.id==pipes[0].id}
            val second=billing.state.value.cart.single{it.product.id==pipes[1].id}
            assertEquals(3L,first.pipeLengths);assertEquals(3.0,first.pipeFeet,0.0);assertEquals(7000L,first.pipeFootPricePaisa)
            assertEquals(1L,second.pipeLengths);assertEquals(5.0,second.pipeFeet,0.0);assertEquals(8000L,second.pipeFootPricePaisa)
            assertEquals(386000L,billing.state.value.calculation!!.totalPaisa);assertNull(billing.state.value.error)
        }
    }
    @Test fun includedCatalogueGroupsAllPaintAndPipeSizesWithoutChangingInventory(): Unit = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val catalogueDb=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addCallback(object: androidx.room.RoomDatabase.Callback() {
            override fun onOpen(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
                (com.hardwareshop.pos.data.sync.SyncJournalSchema.statements(includeBoxCount=true,includePipe=true) +
                    com.hardwareshop.pos.data.sync.SyncTransferSchema.statements()).forEach(sql::execSQL)
            }
        }).build()
        try {
            fun rows(name:String)=context.assets.open(name).bufferedReader().use {
                com.google.gson.Gson().fromJson(it,Array<FinalProductCatalog.Row>::class.java).toList()
            }
            val store=FinalProductCatalog.RoomStore(catalogueDb)
            catalogueDb.withTransaction {
                FinalProductCatalog.mergeBoth(store,rows("final_catalog.json"),rows("store_catalog.json"),1)
                PipeProductCatalog.merge(store)
            }
            val categories=catalogueDb.categoryDao().getAllCategories().associate { it.id to it.name }
            val types=catalogueDb.productTypeDao().getAllTypes().associate { it.id to it.name }
            val before=catalogueDb.productDao().getAllProducts()
            val active=before.filter { it.isActive }.map { it.toDomain(categories[it.categoryId].orEmpty(),types[it.typeId].orEmpty()) }
            val groups=ProductSizeGroups.groups(active)
            assertEquals(active.map { it.id }.toSet(),groups.flatMap { it.products }.map { it.id }.toSet())
            val paintGroup=groups.single { it.name=="Paint" && it.products.first().typeName=="Colour Paints" }
            val black=paintGroup.products.filter{ProductVariantMetadata.read(it)?.colour=="Black"}
            assertEquals(setOf("100 ml","250 ml","500 ml","1 L"),black.map { it.size }.toSet())
            assertTrue(groups.any { it.name=="PVC Elbow" && it.products.size>1 })
            assertEquals(before,catalogueDb.productDao().getAllProducts())
            val grouped=groups.filter { it.sizeFirst }
            assertFalse(grouped.any { it.name=="TMT Bar" && it.products.size==1 })
            val lines=mutableListOf("# Paint and pipe size list — included catalogue","",
                "This list is generated from the catalogue included in APK 1.4.19 after its existing import and pipe cleanup rules. The app builds choices from active inventory at runtime, so owner changes appear automatically.","",
                "${grouped.size} item families; ${grouped.sumOf { it.products.size }} saved variants.","",
                "| Category | Item | Selling unit | Saved sizes |","|---|---|---|---|")
            grouped.forEach { group ->
                fun clean(value:String)=value.replace("|","/")
                group.products.groupBy { product -> ProductVariantMetadata.read(product)?.let { attrs ->
                    listOf(attrs.model,attrs.colour,attrs.extra).filter { it.isNotBlank() }.joinToString(" · ")
                }.orEmpty() }.forEach { (attributes,variants) ->
                    val label=listOf(group.name,attributes).filter { it.isNotBlank() }.joinToString(" · ")
                    lines.add("| ${clean(variants.first().categoryName)} | ${clean(label)} | ${clean(variants.first().unit)} | ${clean(variants.map { it.size.ifBlank { "Size not recorded" } }.distinct().joinToString(" · "))} |")
                }
            }
            java.io.File(context.getExternalFilesDir(null),"paint-pipe-size-list-1419.md").writeText(lines.joinToString("\n"))
        } finally { catalogueDb.close() }
    }
}

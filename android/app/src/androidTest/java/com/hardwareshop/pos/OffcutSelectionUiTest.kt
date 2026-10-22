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

class OffcutSelectionUiTest {
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
    @Test fun matchingOffcutActionUsesTheSelectedPipeAndKeepsNormalStockUntilCheckout():Unit=runBlocking {
        val record=PipeOffcutRepository.Record(java.util.UUID.randomUUID().toString(),pipes.first().id,pipes.first().displayLabel,800,"Rack A")
        val other=PipeOffcutRepository.Record(java.util.UUID.randomUUID().toString(),pipes.last().id,pipes.last().displayLabel,1200,"Rack B")
        listOf(record,other).forEach {db.settingsDao().upsertSetting(SettingsEntity("pipe-offcut:${it.id}",com.google.gson.Gson().toJson(it)))}
        chooseFamily(pipes.first());chooseSize(pipes.first())
        compose.waitUntil(15000){picker.recordedOffcuts.value.size==2}
        val action=hasText("USE OFFCUT:",substring=true) and hasText("Rack A",substring=true)
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(action)
        compose.onNode(action).assertIsDisplayed()
        compose.onAllNodes(hasText("Rack B",substring=true)).assertCountEquals(0)
        screenshot("offcut-selected-size.png")
        compose.onNode(action).performClick()
        val line=billing.state.value.cart.single()
        assertEquals(pipes.first().id,line.product.id)
        assertEquals(record.id,line.offcut!!.id)
        assertEquals(8.0,line.pipeFeet,0.0)
        assertEquals(0.4,line.quantity,0.0)
        assertEquals(64000L,line.itemTotalPaisa)
        assertEquals(100.0,products.getProductById(pipes.first().id)!!.currentStock,0.0)
        assertTrue(db.billDao().getAllBills().isEmpty())
        withContext(Dispatchers.Main){billing.updatePipe(pipes.first(),"0","5")}
        assertEquals(record.id,billing.state.value.cart.single().offcut!!.id)
        assertEquals(5.0,billing.state.value.cart.single().pipeFeet,0.0)
        withContext(Dispatchers.Main){billing.updatePipe(pipes.first(),"0","9")}
        assertEquals(5.0,billing.state.value.cart.single().pipeFeet,0.0)
        assertNotNull(billing.state.value.error)
        withContext(Dispatchers.Main){billing.updatePipe(pipes.first(),"0","5")}
        withContext(Dispatchers.Main){billing.removeFromCart(pipes.first().id)}
        val removed=billing.state.value.removedLines.single()
        withContext(Dispatchers.Main){billing.undoRemoved(removed.token)}
        assertEquals(record.id,billing.state.value.cart.single().offcut!!.id)
        assertEquals(100.0,products.getProductById(pipes.first().id)!!.currentStock,0.0)
    }
}

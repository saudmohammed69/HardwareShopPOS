package com.hardwareshop.pos

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.di.SessionEntryPoint
import com.hardwareshop.pos.presentation.navigation.*
import com.hardwareshop.pos.util.UserSession
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.*

class JobToolsUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val session get()=EntryPointAccessors.fromApplication(compose.activity.applicationContext,SessionEntryPoint::class.java).sessionHolder()
    private fun waitFor(text:String)=compose.waitUntil(20_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun screen(route:String) { compose.activityRule.scenario.onActivity {
        session.setSession(UserSession.OwnerSession("Job tools"));it.setContent { MaterialTheme { AppNavGraph(route) } }
    } }
    @After fun clear(){session.clear()}
    private fun seed():List<Long> = runBlocking {
        val db=AppDatabase.getInstance(compose.activity.applicationContext)
        db.withTransaction {
            val cat=db.categoryDao().insertCategory(CategoryEntity(name="PVC job fixture ${System.nanoTime()}"))
            val type=db.productTypeDao().insertType(ProductTypeEntity(categoryId=cat,name="Pipe fixtures"))
            listOf(
                db.productDao().insertProduct(ProductEntity(categoryId=cat,typeId=type,name="Workshop pipe test",size="1 inch",unit="Length",
                    sellingPricePaisa=40000,currentStock=20.0,isPipe=true,pipeLengthPricePaisa=40000,pipeFootPricePaisa=3500,pipeFeetPerLength=20)),
                db.productDao().insertProduct(ProductEntity(categoryId=cat,typeId=type,name="PVC Workshop elbow",size="1 inch",unit="piece",
                    sellingPricePaisa=1000,currentStock=20.0))
            )
        }
    }
    private fun select(id:Long) {
        compose.onNodeWithText("Find pipe by material or size").performTextReplacement("Workshop pipe test")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("pipe-tools-product-$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pipe-tools-product-$id").performScrollTo().performClick()
    }
    @Test fun offcutRegisterAndCutPlannerAreReachableAndPersistReviewedCuts() {
        val id=seed()[0]
        screen(Routes.OWNER_HOME)
        waitFor("New / Resume Bill")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Pipe Offcuts"))
        compose.onNodeWithText("Pipe Offcuts").performClick()
        waitFor("SAVE OFFCUT")
        select(id)
        compose.onNodeWithTag("pipe-tools-list").performScrollToNode(hasTestTag("offcut-length"))
        compose.onNodeWithTag("offcut-length").performTextInput("8")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("pipe-tools-list").performScrollToNode(hasText("SAVE OFFCUT"))
        compose.onNodeWithText("SAVE OFFCUT").performClick()
        waitFor("Offcut saved")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Cut Planner"))
        compose.onNodeWithText("Cut Planner").performClick()
        waitFor("CALCULATE CUT PLAN")
        select(id)
        compose.onNodeWithTag("pipe-tools-list").performScrollToNode(hasTestTag("cut-requests"))
        compose.onNodeWithTag("cut-requests").performTextInput("5, 12")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("pipe-tools-list").performScrollToNode(hasText("CALCULATE CUT PLAN"))
        compose.onNodeWithText("CALCULATE CUT PLAN").performClick()
        waitFor("Plan ready — check the pieces before cutting")
        compose.onNodeWithTag("pipe-tools-list").performScrollToNode(hasText("1 offcuts first · 1 new 20-ft pipes"))
        compose.onNodeWithText("1 offcuts first · 1 new 20-ft pipes").assertIsDisplayed()
        val db=AppDatabase.getInstance(compose.activity.applicationContext)
        compose.runOnIdle { runBlocking { org.junit.Assert.assertEquals(1,db.settingsDao().getPipeOffcuts().count { it.value.contains("\"productId\":$id,") }) } }
        compose.onNodeWithTag("pipe-tools-list").performScrollToNode(hasText("CUTS MADE — UPDATE OFFCUTS"))
        compose.onNodeWithText("CUTS MADE — UPDATE OFFCUTS").performClick()
        compose.onNodeWithText("KEEP PLAN ONLY").performClick()
        compose.onNodeWithText("CUTS MADE — UPDATE OFFCUTS").performClick()
        compose.onNodeWithText("RECORD CUTS").performClick()
        waitFor("Cuts recorded and leftovers saved")
        compose.runOnIdle { runBlocking {
            org.junit.Assert.assertEquals(20.0,db.productDao().getProductById(id)!!.currentStock,0.0)
            org.junit.Assert.assertEquals(2,db.settingsDao().getPipeOffcuts().count { it.value.contains("\"productId\":$id,") })
        } }
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"cut-planner.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            };bitmap.recycle()
        }
    }
    @Test fun addingPipeThenSearchingShowsOptionalFittingsWithoutAddingThem() {
        val ids=seed()
        val db=AppDatabase.getInstance(compose.activity.applicationContext)
        val entity=runBlocking { requireNotNull(db.productDao().getProductById(ids[0])) }
        runBlocking {
            val bill=db.billDao().insertBill(BillEntity(billNumber="Suggestions ${System.nanoTime()}",subtotalPaisa=41000,totalPaisa=41000,paymentMethod="CASH"))
            db.billDao().insertBillItems(ids.map { id->BillItemEntity(billId=bill,productId=id,productName="Suggestion fixture",
                productSize="1 inch",productUnit="piece",quantity=1.0,unitPricePaisa=1000,itemTotalPaisa=1000) })
        }
        val product=com.hardwareshop.pos.domain.model.Product(entity.id,entity.categoryId,"PVC",entity.typeId,"Pipe",
            entity.name,entity.size,"Length",40000,0,0,20.0,0.0,null,isActive=true,isPipe=true,pipeLengthPricePaisa=40000,
            pipeFootPricePaisa=3500,pipeFeetPerLength=20)
        var billing:com.hardwareshop.pos.presentation.worker.billing.BillingViewModel?=null
        var selector:com.hardwareshop.pos.presentation.worker.product.ProductSelectionViewModel?=null
        compose.activityRule.scenario.onActivity { activity ->
            session.setSession(UserSession.OwnerSession("Suggestion test"))
            activity.setContent { MaterialTheme {
                val vm:com.hardwareshop.pos.presentation.worker.billing.BillingViewModel=androidx.hilt.navigation.compose.hiltViewModel()
                val selection:com.hardwareshop.pos.presentation.worker.product.ProductSelectionViewModel=androidx.hilt.navigation.compose.hiltViewModel()
                androidx.compose.runtime.SideEffect { billing=vm;selector=selection }
                com.hardwareshop.pos.presentation.worker.billing.ProductSelectionScreen({}, {},viewModel=selection,billingViewModel=vm)
            } }
        }
        compose.waitUntil(20_000) { billing!=null && billing!!.state.value.isLoading.not() }
        compose.runOnIdle { billing!!.updatePipe(product,"1","0") }
        compose.onNodeWithText("Search products, size, aliases…").performTextInput("elbow")
        try {
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("job-suggestions").fetchSemanticsNodes().isNotEmpty() }
        } catch(failure:Throwable) {
            val selection=selector!!.state.value
            println("SUGGESTION DEBUG: cart=${billing!!.state.value.cart.map { it.product.id }}; error=${billing!!.state.value.error}; suggestions=${selection.suggestions.map { it.product.id }}; pipes=${selection.availablePipes.size}; searchOpened=${selection.suggestionSearchOpened}; pending=${selection.pendingProduct?.id}; query=${selection.searchQuery}")
            val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"job-suggestion-timeout.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                };bitmap.recycle()
            }
            throw failure
        }
        compose.onNodeWithTag("billing-picker").performScrollToNode(hasTestTag("billing-product-${ids[1]}"))
        compose.onNodeWithTag("billing-product-${ids[1]}").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertFalse(billing!!.state.value.cart.any { it.product.id==ids[1] }) }
        compose.onNodeWithTag("billing-product-${ids[1]}").performClick()
        compose.onNodeWithText("ADD TO CART").assertIsDisplayed()
        compose.onNodeWithText("ADD TO CART").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1,billing!!.state.value.cart.count { it.product.id==ids[1] }) }
    }
}


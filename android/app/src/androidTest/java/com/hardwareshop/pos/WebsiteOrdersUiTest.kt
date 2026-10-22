package com.hardwareshop.pos

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.hardwareshop.pos.data.local.entity.WorkerEntity
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.presentation.website.*
import com.hardwareshop.pos.presentation.worker.billing.*
import com.hardwareshop.pos.service.WebsiteOrderNotifications
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Real website/backend -> isolated native cache -> existing cart UI/transaction. */
class WebsiteOrdersUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var fixture:WebsiteNativeFixture
    private val store=ViewModelStore()
    @After fun close(){compose.activityRule.scenario.onActivity{it.setContent{};store.clear()};compose.waitForIdle();if(::fixture.isInitialized)fixture.close()}
    @Test fun workerReviewsPreparesAndLoadsWebsiteOrderWhileParkingAnExistingCounterBill(){
        fixture=WebsiteNativeFixture(compose.activity)
        val repo=fixture.repository()
        val (id,product)=runBlocking{
            val products=fixture.seed();repo.publishCatalog();repo.publishSettings(0,1440,false)
            val m=fixture.cache.orders().products().first{it.localId==products[0].id};val id=fixture.createOrder(m.publicId);repo.refresh()
            val worker=fixture.db.workerDao().insertWorker(WorkerEntity(name="UI worker",username="website-ui-worker",passwordHash="PRIVATE",passwordSalt="PRIVATE"))
            fixture.session.setSession(UserSession.WorkerSession(worker,"UI worker","website-ui-worker",0,true));id to products[2]
        }
        lateinit var billing:BillingViewModel;lateinit var orders:WebsiteOrdersViewModel
        compose.activityRule.scenario.onActivity{
            billing=BillingViewModel(fixture.billing(),CustomerRepository(fixture.db.customerDao(),fixture.db.creditDao(),fixture.session),fixture.products(),fixture.db.settingsDao(),SettingsRepository(fixture.db.settingsDao(),fixture.db.ownerCredentialDao(),fixture.session),fixture.db,fixture.session,javax.inject.Provider { repo })
            orders=WebsiteOrdersViewModel(repo,fixture.session,WebsiteOrderNotifications(compose.activity))
            store.put("billing",billing);store.put("orders",orders)
        }
        compose.waitUntil(15_000){!billing.state.value.isLoading}
        compose.runOnIdle{billing.addSelection("existing-counter-fixture",listOf(com.hardwareshop.pos.domain.model.CartItem(product,1.0,product.sellingPricePaisa,product.sellingPricePaisa)))}
        compose.activityRule.scenario.onActivity{activity->activity.setContent{MaterialTheme{
            var screen by remember{mutableStateOf("orders")}
            when(screen){
                "orders"->WebsiteOrdersScreen({}, {screen="review"},{},orders)
                "review"->WebsiteBillingReview(id,billing,{screen="orders"},{screen="cart"},orders)
                else->CartScreen({}, {}, {}, {},billing)
            }
        }}}
        compose.waitUntil(15_000){compose.onAllNodesWithTag("website-order-$id").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("website-order-$id").performClick()
        compose.onNodeWithTag("website-accept").assertIsNotEnabled()
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onNodeWithTag("website-accept").performClick()
        compose.waitUntil(15_000){runBlocking{repo.row(id).status}=="ACCEPTED"&&!orders.state.value.busy&&
            compose.onAllNodes(hasText("Start preparing") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Start preparing"))
        compose.onNodeWithText("Start preparing").performClick()
        compose.waitUntil(15_000){runBlocking{repo.row(id).status}=="PREPARING"&&!orders.state.value.busy&&
            compose.onAllNodesWithText("Prepared / checked").fetchSemanticsNodes().isNotEmpty()&&
            compose.onAllNodes(isToggleable() and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodes(isToggleable() and isEnabled()).onFirst().performClick()
        compose.waitUntil(15_000){runBlocking{repo.row(id).checklist}!="[]"&&!orders.state.value.busy&&
            compose.onAllNodes(hasText("Ready for pickup") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Ready for pickup"))
        compose.onNodeWithText("Ready for pickup").performClick()
        // Room commits before the Flow/UI and the previous action's finally.
        // Retain each original deadline; never click disabled stale controls.
        compose.waitUntil(15_000){runBlocking{repo.row(id).status}=="READY_FOR_PICKUP"&&!orders.state.value.busy&&
            compose.onAllNodes(hasTestTag("website-open-billing") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasTestTag("website-open-billing"))
        compose.onNodeWithTag("website-open-billing").performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithText("Availability and current rates confirmed with customer").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Park current bill and load order"))
        compose.onNodeWithText("Park current bill and load order").performClick()
        compose.waitUntil(15_000){billing.state.value.sourceWebsiteOrderId==id&&!billing.state.value.isLoading}
        assertEquals(2.0,billing.state.value.cart.single().quantity,0.0)
        assertEquals("Synthetic pickup customer",billing.state.value.customerName)
        assertEquals(1,runBlocking{fixture.db.settingsDao().getFeatureRecords("cart:").count{it.key.contains(":parked:")}})
        assertTrue(runBlocking{fixture.db.billDao().getAllBills().isEmpty()})
        val output=File(compose.activity.getExternalFilesDir("website-phase3b"),"native-website-cart.png");output.parentFile!!.mkdirs()
        output.outputStream().use{compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        compose.runOnIdle{billing.saveBill(false)}
        compose.waitUntil(15_000){billing.state.value.savedBillId!=null||billing.state.value.error!=null}
        assertNull(billing.state.value.error);assertNotNull(billing.state.value.savedBillId)
        assertEquals(1,runBlocking{fixture.db.billDao().getAllBills().size});assertEquals("COMPLETED",runBlocking{repo.row(id).status})
    }
}

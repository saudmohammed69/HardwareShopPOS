package com.hardwareshop.pos

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.database.toDomain
import com.hardwareshop.pos.data.local.entity.CategoryEntity
import com.hardwareshop.pos.data.local.entity.ProductEntity
import com.hardwareshop.pos.data.local.entity.ProductTypeEntity
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import com.hardwareshop.pos.di.SessionEntryPoint
import com.hardwareshop.pos.domain.model.CartItem
import com.hardwareshop.pos.domain.model.PaymentMethod
import com.hardwareshop.pos.domain.model.SavedCart
import com.hardwareshop.pos.presentation.navigation.AppNavGraph
import com.hardwareshop.pos.presentation.navigation.Routes
import com.hardwareshop.pos.util.UserSession
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Production navigation on an isolated test emulator, using only uniquely named fixtures. */
class NewFeatureUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val session get() = EntryPointAccessors.fromApplication(compose.activity.applicationContext,
        SessionEntryPoint::class.java).sessionHolder()
    private val db get() = AppDatabase.getInstance(compose.activity.applicationContext)
    private val token = UUID.randomUUID().toString().take(8)
    private var productId = 0L
    private var originalCart: SettingsEntity? = null
    private var screenSequence = 0

    @Before fun setup(): Unit = runBlocking {
        session.setSession(UserSession.OwnerSession("New feature UI"))
        originalCart = db.settingsDao().getSetting("cart:owner")
        val category = db.categoryDao().insertCategory(CategoryEntity(name = "UI feature $token"))
        val type = db.productTypeDao().insertType(ProductTypeEntity(categoryId = category, name = "Tools"))
        productId = db.productDao().insertProduct(ProductEntity(categoryId = category, typeId = type,
            name = "Warranty pump $token", size = "Standard", unit = "Piece", sellingPricePaisa = 12500,
            gstRateBp = 1800, currentStock = 20.0))
    }

    @After fun cleanup(): Unit = runBlocking {
        compose.activityRule.scenario.onActivity { it.setContent {} }
        session.clear()
        db.settingsDao().getAllSettings().filter {
            (it.key.startsWith("quotation:") || it.key.startsWith("warranty:")) && it.value.contains(token)
        }.forEach { db.settingsDao().deleteSetting(it.key) }
        if (originalCart == null) db.settingsDao().deleteSetting("cart:owner")
        else db.settingsDao().upsertSetting(originalCart!!)
        db.productDao().getProductById(productId)?.let { db.productDao().updateProduct(it.copy(isActive = false)) }
        Unit
    }

    private fun screen(route: String) {
        val generation = ++screenSequence
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { MaterialTheme { key(generation) { AppNavGraph(route) } } }
        }
    }
    private fun waitText(text: String) = compose.waitUntil(25_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitTag(tag: String) = compose.waitUntil(25_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun ownerMenu(label: String) {
        waitText("New / Resume Bill")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
    }
    private fun hideKeyboard() { androidx.test.espresso.Espresso.closeSoftKeyboard(); compose.waitForIdle() }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "new-features-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun quotationsSaveConvertAndAddItemsUseProductionRoutesAndOptionalTopSort() {
        runBlocking {
            val product = db.productDao().getProductById(productId)!!.toDomain("UI feature $token", "Tools")
            val line = CartItem(product, 2.0, 12500, 25000)
            val draft = SavedCart(UUID.randomUUID().toString(), listOf(line), null, 0, "", false,
                customerName = "UI customer $token", paymentMethod = PaymentMethod.CASH, paidPaisa = 25000, notes = "")
            db.settingsDao().upsertSetting(SettingsEntity("cart:owner", Gson().toJson(draft)))
        }
        screen(Routes.OWNER_HOME)
        ownerMenu("Quotations")
        waitTag("quotation-new")
        compose.onNodeWithTag("quotation-new").performClick()
        waitTag("product-sort-control")
        compose.onNodeWithTag("product-sort-control").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sort products: Default").assertIsDisplayed()
        compose.onNodeWithTag("product-sort-PRICE_ASC").assertDoesNotExist()
        screenshot("top-sort")
        compose.onNodeWithTag("product-sort-control").performClick()
        compose.onNodeWithTag("product-sort-PRICE_ASC").performClick()
        compose.onNodeWithContentDescription("Sort products: Price low → high").assertIsDisplayed()
        compose.onNodeWithTag("product-sort-control").performClick()
        compose.onNodeWithTag("product-sort-DEFAULT").performClick()
        compose.onNodeWithTag("billing-header-cart").performClick()
        waitTag("save-quotation-action")
        compose.waitUntil(25_000) { !compose.onNodeWithTag("save-quotation-action").fetchSemanticsNode().config.contains(
            androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        compose.onNodeWithTag("save-quotation-action").assertIsEnabled().performClick()
        waitTag("quotation-customer")
        compose.onNodeWithTag("quotation-customer").performTextReplacement("UI customer $token")
        hideKeyboard()
        compose.onNodeWithTag("save-quotation-confirm").performClick()
        waitText("Quotation saved")
        screenshot("quotation-saved")
        compose.onNodeWithText("OK").performClick()
        val quoteId = runBlocking {
            db.settingsDao().getAllSettings().single { it.key.startsWith("quotation:") && it.value.contains("UI customer $token") }
                .let { JsonParser.parseString(it.value).asJsonObject.get("id").asString }
        }
        assertEquals(20.0, runBlocking { db.productDao().getProductById(productId)!!.currentStock }, 0.0)
        // Starting again at the owner menu makes the conversion graph lack a product-selection entry.
        screen(Routes.OWNER_HOME)
        ownerMenu("Quotations")
        waitTag("quotation-search")
        compose.onNodeWithTag("quotation-search").performTextReplacement("UI customer $token")
        hideKeyboard()
        compose.onNodeWithTag("quotation-list").performScrollToNode(hasTestTag("quotation-open-$quoteId"))
        compose.onNodeWithTag("quotation-open-$quoteId").performClick()
        compose.onNodeWithTag("quotation-list").performScrollToNode(hasTestTag("quotation-convert"))
        screenshot("quotation-details")
        compose.onNodeWithTag("quotation-convert").performClick()
        waitText("REVIEW IN BILLING")
        compose.onNodeWithText("REVIEW IN BILLING").performClick()
        waitTag("import-quotation-confirm")
        screenshot("quotation-review")
        compose.onNodeWithTag("import-quotation-confirm").performScrollTo().assertIsEnabled().performClick()
        waitText("Add Items")
        screenshot("quotation-cart")
        compose.onNodeWithText("Add Items").performClick()
        waitTag("billing-header-cart")
        compose.onNodeWithTag("product-sort-control").assertIsDisplayed()
        compose.onNodeWithText("Select Product").assertIsDisplayed()
        assertEquals(20.0, runBlocking { db.productDao().getProductById(productId)!!.currentStock }, 0.0)
        assertNotNull(runBlocking { db.settingsDao().getSetting("quotation:$quoteId") })
        assertNull(runBlocking { db.settingsDao().getSetting("quotation-bill:$quoteId") })
    }

    @Test fun productSearchSurvivesRepeatedProductionCartReturns() {
        screen(Routes.BILLING_GRAPH)
        waitTag("billing-header-cart")
        val query="Warranty pump $token"
        compose.onNodeWithText("Search products, size, aliases…").performTextInput(query)
        hideKeyboard()
        waitTag("billing-product-$productId")
        repeat(5) {
            compose.onNodeWithTag("billing-header-cart").performClick()
            waitText("Cart — 0 items")
            compose.onNodeWithContentDescription("Back").performClick()
            waitTag("billing-header-cart")
            compose.onNode(hasSetTextAction() and hasText(query)).assertTextContains(query)
            compose.onNodeWithTag("billing-product-$productId").assertIsDisplayed()
        }
    }

    @Test fun warrantySerialAndServiceHistoryAreSavedThroughTheProductionForm() {
        screen(Routes.OWNER_HOME)
        ownerMenu("Warranty & Serial Register")
        waitTag("warranty-new")
        compose.waitUntil(25_000) { !compose.onNodeWithTag("warranty-new").fetchSemanticsNode().config.contains(
            androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        compose.onNodeWithTag("warranty-new").performClick()
        waitTag("warranty-product-search")
        compose.onNodeWithTag("warranty-product-search").performTextInput("Warranty pump $token")
        hideKeyboard()
        compose.onNodeWithTag("warranty-product-$productId").performScrollTo().performClick()
        compose.onNodeWithTag("warranty-serial").performScrollTo().performTextInput("PUMP-$token")
        compose.onNodeWithTag("warranty-customer").performScrollTo().performTextInput("UI owner $token")
        compose.onNodeWithTag("warranty-months").performScrollTo().performTextReplacement("12")
        hideKeyboard()
        compose.onNodeWithTag("warranty-save").performScrollTo().performClick()
        waitText("Warranty record saved")
        screenshot("warranty-details")
        compose.onNodeWithTag("warranty-add-service").performScrollTo().performClick()
        waitTag("warranty-service-problem")
        compose.onNodeWithTag("warranty-service-problem").performScrollTo().performTextInput("Pump does not start")
        compose.onNodeWithTag("warranty-service-action").performScrollTo().performTextInput("Replaced start capacitor")
        hideKeyboard()
        compose.onNodeWithTag("warranty-service-save").performScrollTo().performClick()
        waitText("Service entry saved")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Service history (1)"))
        compose.onNodeWithText("Service history (1)").assertIsDisplayed()
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Action / notes: Replaced start capacitor"))
        compose.onNodeWithText("Action / notes: Replaced start capacitor").assertIsDisplayed()
        screenshot("warranty-service")
        val row = runBlocking {
            db.settingsDao().getAllSettings().single { it.key.startsWith("warranty:") && it.value.contains("PUMP-$token") }
        }
        assertTrue(row.value.contains("Replaced start capacitor"))
        assertEquals(20.0, runBlocking { db.productDao().getProductById(productId)!!.currentStock }, 0.0)
    }
}

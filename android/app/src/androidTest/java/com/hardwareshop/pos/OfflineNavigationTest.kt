package com.hardwareshop.pos

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.getOrNull
import com.hardwareshop.pos.di.SessionEntryPoint
import com.hardwareshop.pos.presentation.navigation.AppNavGraph
import com.hardwareshop.pos.presentation.navigation.Routes
import com.hardwareshop.pos.presentation.common.AppColors
import com.hardwareshop.pos.util.UserSession
import dagger.hilt.android.EntryPointAccessors
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Runs only on the isolated Codex emulator; no cloud sign-in or shop data is used. */
class OfflineNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val session get() = EntryPointAccessors.fromApplication(
        compose.activity.applicationContext, SessionEntryPoint::class.java).sessionHolder()
    private fun screen(route: String, owner: Boolean = false) {
        compose.activityRule.scenario.onActivity {
            if (owner) session.setSession(UserSession.OwnerSession("Audit test")) else session.clear()
            it.setContent {
                MaterialTheme(colorScheme = lightColorScheme(
                    primary = AppColors.Primary, secondary = AppColors.Secondary,
                    background = AppColors.Surface, surface = AppColors.SurfaceCard, error = AppColors.Error,
                    onPrimary = Color.White, onSecondary = Color.White,
                    onBackground = AppColors.TextPrimary, onSurface = AppColors.TextPrimary
                )) { AppNavGraph(startDestination = route) }
            }
        }
    }
    private fun waitText(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitEnabled(matcher: SemanticsMatcher) {
        compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        } }
    }
    private fun captureDebug(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val root = automation.rootInActiveWindow
        android.util.Log.i("AuditUi", "$name: window=${root?.packageName}")
        automation.takeScreenshot()?.let { bitmap ->
            try {
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
                    .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }
    private fun openVisibleCart() {
        // Measured cart anchors can keep scheduling animation frames on API 36.
        // Control the clock while locating and pressing the semantic button.
        compose.mainClock.autoAdvance = false
        try {
            compose.mainClock.advanceTimeBy(1_000)
            val cart = hasContentDescription("Open cart,", substring = true)
            compose.waitUntil(30_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodes(cart).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(cart).assertIsDisplayed().performClick()
            compose.mainClock.advanceTimeBy(1_000)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
    @After fun releaseSession() { session.clear() }
    @Test fun cloudLoginUsesOnlyEmailPasswordAndFreshStartCanBeCancelled() {
        val db = com.hardwareshop.pos.data.local.database.AppDatabase.getInstance(compose.activity.applicationContext)
        val original = kotlinx.coroutines.runBlocking { db.ownerCredentialDao().getOwnerCredential() }
        try {
            kotlinx.coroutines.runBlocking {
                if (original == null) db.ownerCredentialDao().upsertCredential(
                    com.hardwareshop.pos.data.local.entity.OwnerCredentialEntity(passwordHash = "audit", passwordSalt = "audit", shopName = "Old local shop"))
            }
            screen(Routes.CLOUD_LOGIN)
            waitText("Email")
            waitEnabled(hasText("Start fresh on this phone"))
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
            compose.onNodeWithText("Existing owner password").assertDoesNotExist()
            compose.onNodeWithText("Email").performTextInput("audit@example.com")
            compose.onNodeWithText("Create a shop account").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
            compose.onNodeWithText("Existing owner password").assertDoesNotExist()
            compose.onNodeWithText("Shop name").assertDoesNotExist()
            compose.onNodeWithText("Start fresh on this phone").performScrollTo().performClick()
            waitText("Start fresh on this phone?")
            compose.onNodeWithText("CLEAR LOCAL DATA").assertIsEnabled()
            compose.onNodeWithText("KEEP DATA").performClick()
            compose.onNodeWithText("Start fresh on this phone?").assertDoesNotExist()
            compose.onNodeWithText("audit@example.com").assertExists()
            kotlinx.coroutines.runBlocking {
                org.junit.Assert.assertNotNull(db.ownerCredentialDao().getOwnerCredential())
            }
            compose.onNodeWithText("Already have an account? Sign in").performScrollTo().performClick()
            compose.onNodeWithText("Email").performScrollTo()
            captureDebug("email-password-only-login")
        } finally {
            kotlinx.coroutines.runBlocking {
                if (original == null) db.openHelper.writableDatabase.execSQL("DELETE FROM owner_credentials")
                else db.ownerCredentialDao().upsertCredential(original)
            }
        }
    }
    @Test fun freshStartConfirmationInvokesLocalResetOnlyAfterConfirmation() {
        var resetCalls = 0
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    com.hardwareshop.pos.presentation.auth.FreshStartDialog(onDismiss = {},
                        onClearLocalData = { resetCalls++; false })
                }
            }
        }
        compose.onNodeWithText("CLEAR LOCAL DATA").assertIsEnabled()
        org.junit.Assert.assertEquals(0, resetCalls)
        compose.onNodeWithText("CLEAR LOCAL DATA").performClick()
        org.junit.Assert.assertEquals(1, resetCalls)
        compose.onNodeWithText("Could not reset the app. Open Android App info → Storage → Clear data, then reopen the app.").assertExists()
    }
    @Test fun billingPickerShowsTwoProductsPerRowAndKeepsQuantityActions() {
        val db = com.hardwareshop.pos.data.local.database.AppDatabase.getInstance(compose.activity.applicationContext)
        val categoryLabel = "Grid audit ${System.nanoTime()}"
        val ids = kotlinx.coroutines.runBlocking {
            val category = db.categoryDao().insertCategory(
                com.hardwareshop.pos.data.local.entity.CategoryEntity(name = categoryLabel, displayOrder = 100000))
            val type = db.productTypeDao().insertType(
                com.hardwareshop.pos.data.local.entity.ProductTypeEntity(categoryId = category, name = "Grid fittings"))
            listOf("A", "B", "C").mapIndexed { index, suffix ->
                db.productDao().insertProduct(com.hardwareshop.pos.data.local.entity.ProductEntity(
                    categoryId = category, typeId = type, name = "Grid audit fitting $suffix", size = "${index + 1} inch",
                    unit = "piece", sellingPricePaisa = 1250, currentStock = 10.0))
            }
        }
        screen(Routes.OWNER_HOME, owner = true)
        waitText("New / Resume Bill")
        compose.onNodeWithText("New / Resume Bill").performScrollTo().performClick()
        openVisibleCart()
        waitText("ADD ITEM MANUALLY")
        compose.onNodeWithText("CLEAR").performClick()
        compose.onNodeWithText("DISCARD").performClick()
        compose.onNodeWithText("Add Items").performClick()
        compose.onNodeWithTag("billing-picker").performScrollToNode(hasText(categoryLabel))
        waitText(categoryLabel)
        compose.onNodeWithContentDescription("Pin $categoryLabel").performClick()
        compose.waitUntil(30_000) {
            compose.onNodeWithTag("billing-picker").performScrollToIndex(0)
            compose.onAllNodesWithContentDescription("Unpin $categoryLabel").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(categoryLabel).assertIsDisplayed()
        compose.onNodeWithContentDescription("Unpin $categoryLabel").assertExists()
        kotlinx.coroutines.runBlocking {
            org.junit.Assert.assertTrue(db.settingsDao().getAllSettings().any { it.key.startsWith("billing-category-pin:") })
        }
        compose.onNodeWithText(categoryLabel).performClick()
        waitText("Grid fittings")
        compose.onNodeWithText("Grid fittings").performClick()
        waitText("Grid audit fitting A")
        val first = compose.onNodeWithTag("billing-product-${ids[0]}").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("billing-product-${ids[1]}").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(first.top, second.top, 1f)
        org.junit.Assert.assertTrue(first.right <= second.left)
        compose.onNode(hasContentDescription("Add one piece") and hasAnyAncestor(hasTestTag("billing-product-${ids[0]}")),
            useUnmergedTree = true).performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasContentDescription("Open cart, 1 items,", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Grid audit fitting B").performClick()
        waitText("ADD TO CART")
        compose.onNodeWithContentDescription("Quantity key 2").performClick()
        compose.onNodeWithContentDescription("Quantity key .").performClick()
        compose.onNodeWithContentDescription("Quantity key 5").performClick()
        compose.onNodeWithTag("billing-quantity-value").assertTextEquals("2.5")
        captureDebug("billing-inline-quantity")
        compose.onNodeWithText("ADD TO CART").performClick()
        compose.onNodeWithTag("billing-quantity-keypad").assertDoesNotExist()
        compose.onNodeWithText("Grid audit fitting B").assertExists()
        compose.onNodeWithText("Grid audit fitting B").performClick()
        compose.onNodeWithContentDescription("Quantity key 0").performClick()
        compose.onNodeWithText("ADD TO CART").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        captureDebug("billing-two-column-grid")
        compose.onNodeWithTag("billing-picker").performScrollToNode(hasTestTag("billing-product-${ids[2]}"))
        compose.onNodeWithText("Grid audit fitting C").assertIsDisplayed()
        openVisibleCart()
        waitText("2.5 piece")
        compose.onNodeWithText("2.5 piece").assertExists()
        kotlinx.coroutines.runBlocking {
            repeat(3) { index ->
                val bill = db.billDao().insertBill(com.hardwareshop.pos.data.local.entity.BillEntity(
                    billNumber = "grid-popular-${System.nanoTime()}-$index", subtotalPaisa = 1250,
                    totalPaisa = 1250, paymentMethod = "CASH"))
                db.billDao().insertBillItems(listOf(com.hardwareshop.pos.data.local.entity.BillItemEntity(
                    billId = bill, productId = ids[2], productName = "Grid audit fitting C", productSize = "3 inch",
                    productUnit = "piece", quantity = 1.0, unitPricePaisa = 1250, itemTotalPaisa = 1250)))
            }
        }
        compose.onNodeWithText("Add Items").performClick()
        compose.onNodeWithText("Search products, size, aliases…").performTextInput(categoryLabel)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Best matches first; frequently sold items lead matching groups.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("billing-picker").performScrollToIndex(0)
        val productTile = SemanticsMatcher("Billing product tile") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
                ?.startsWith("billing-product-") == true
        }
        val topTile = compose.onAllNodes(productTile).fetchSemanticsNodes()
            .minWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
        org.junit.Assert.assertEquals("billing-product-${ids[2]}",
            topTile.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag])
        captureDebug("billing-best-sellers")
        androidx.test.espresso.Espresso.pressBack()
        waitText("New / Resume Bill")
        compose.onNodeWithText("New / Resume Bill").performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("Unpin $categoryLabel").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(categoryLabel).assertIsDisplayed()
        captureDebug("billing-pinned-categories")
    }
    @Test fun historyResetClearsQueryAndBothFilterGroups() {
        screen(Routes.BILL_HISTORY, owner = true)
        waitText("Bill number, customer or phone")
        compose.onNodeWithText("Cash").performClick()
        compose.onNodeWithText("Cancelled").performClick()
        compose.onNodeWithText("Bill number, customer or phone").performTextInput("unmatched audit search")
        compose.onNodeWithText("RESET SEARCH & FILTERS").performClick()
        compose.onNodeWithText("unmatched audit search").assertDoesNotExist()
        compose.onAllNodesWithText("All").assertAll(isSelected())
        compose.onNodeWithText("RESET SEARCH & FILTERS").assertDoesNotExist()
        compose.onNodeWithText("Bill number, customer or phone").performTextInput("clear this audit search")
        compose.onNodeWithContentDescription("Clear bill search").performClick()
        compose.onNodeWithText("clear this audit search").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear bill search").assertDoesNotExist()
    }
    @Test fun cloudLoginOffersOfflineSetupWithoutSendingCredentials() {
        screen(Routes.CLOUD_LOGIN)
        waitText("Use offline / local login")
        compose.waitUntil(30_000) { compose.onNodeWithText("Use offline / local login")
            .fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
        compose.onNodeWithText("Use offline / local login").performScrollTo().performClick()
        waitText("First-Time Setup")
        compose.onNodeWithText("Shop Name *").assertExists()
    }
    private fun cartWithManualItem(name: String, price: String) {
        screen(Routes.OWNER_HOME, owner = true)
        waitText("New / Resume Bill")
        compose.onNodeWithText("New / Resume Bill").performScrollTo().performClick()
        openVisibleCart()
        waitText("ADD ITEM MANUALLY")
        waitEnabled(hasText("ADD ITEM MANUALLY"))
        // This emulator contains audit fixtures only; begin with an empty unfinished cart.
        compose.onNodeWithText("CLEAR").performClick()
        compose.onNodeWithText("DISCARD").performClick()
        compose.onNodeWithText("ADD ITEM MANUALLY").performClick()
        compose.onNodeWithText("Item name *").performTextInput(name)
        compose.onNodeWithText("Price per piece ₹ *").performTextInput(price)
        compose.onNodeWithText("ADD TO CART").performClick()
        waitText(name)
        waitEnabled(hasContentDescription("Direct bill: Cash or UPI"))
    }
    @Test fun ownerCanNavigateToCartAndAddBillOnlyManualItemOffline() {
        cartWithManualItem("Audit loose fitting", "12.50")
        compose.onNodeWithContentDescription("Direct bill: Cash or UPI").performClick()
        compose.onNodeWithText("Direct bill — Cash or UPI?").assertExists()
        compose.onNodeWithText("Direct bill discount ₹ (optional)").performTextInput("1.25")
        val expectedCollect = "Collect ${com.hardwareshop.pos.util.CalculationEngine.formatRupees(1125)}"
        try { waitText(expectedCollect) } catch (failure: Throwable) {
            compose.onRoot(useUnmergedTree = true).printToLog("DiscountRegression")
            captureDebug("discount-regression-failure")
            throw failure
        }
        compose.onNodeWithText(expectedCollect).assertExists()
        compose.onNodeWithText("Direct bill discount ₹ (optional)").performTextReplacement("13")
        compose.onNodeWithText("CASH — SAVE & PRINT").assertIsNotEnabled()
        compose.onNodeWithText("Direct bill discount ₹ (optional)").performTextReplacement("1.25")
        compose.onNodeWithText("CASH — SAVE & PRINT").assertIsEnabled()
        compose.onNodeWithText("UPI RECEIVED — SAVE & PRINT").assertIsEnabled()
        compose.onNodeWithText("Cash received ₹ (optional)").performTextInput("10")
        compose.onNodeWithText("Still due: ${com.hardwareshop.pos.util.CalculationEngine.formatRupees(125)}").assertExists()
        compose.onNodeWithText("CASH — SAVE & PRINT").assertIsNotEnabled()
        compose.onNodeWithText("UPI RECEIVED — SAVE & PRINT").assertIsEnabled()
        // Scroll the dialog vertically first. A chip's closest scroll parent is the
        // horizontal amount row, so its own performScrollTo cannot reveal that row vertically.
        compose.onNodeWithText("The bill records its total as paid. Cash handed over and change do not increase sales.")
            .performScrollTo()
        compose.onNodeWithText("Exact amount").performClick()
        captureDebug("direct-cash-exact")
        waitText("Change: ${com.hardwareshop.pos.util.CalculationEngine.formatRupees(0)}")
        compose.onNodeWithText("CASH — SAVE & PRINT").assertIsEnabled()
        compose.onNodeWithText("₹100", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Change: ${com.hardwareshop.pos.util.CalculationEngine.formatRupees(8875)}").assertExists()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "direct-bill-discount.png")
                    .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
        compose.onNodeWithText("CANCEL").performClick()
        compose.onNodeWithText("Audit loose fitting").assertExists()
        compose.onNodeWithText("PROCEED").assertIsEnabled().performClick()
        waitText("Payment")
        compose.onNodeWithText("Payment").assertExists()
    }
    @Test fun directCashDiscountSavesWithoutPreviewAndKeepsTheBillWhenPrinterIsUnavailable() {
        // Run on the isolated emulator with Bluetooth disabled and CONNECT permission granted.
        cartWithManualItem("Audit direct cash", "50.00")
        compose.onNodeWithContentDescription("Direct bill: Cash or UPI").performClick()
        compose.onNodeWithText("Direct bill discount ₹ (optional)").performTextInput("5")
        compose.onNodeWithText("Collect ₹45.00").assertExists()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.activityRule.scenario.onActivity { session.setSession(UserSession.OwnerSession("Audit test")) }
        compose.onNodeWithText("CASH — SAVE & PRINT")
            .assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        // BluetoothGate correctly asks Android to enable Bluetooth after saving. Decline that
        // system screen through UiAutomation before querying the app's Compose hierarchy.
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val serviceInfo = automation.serviceInfo
        serviceInfo.flags = serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = serviceInfo
        // Wait for the native negative button itself, without Compose idleness or
        // assuming the dialog's package/translated button label is always Settings/Deny.
        val promptDeadline = android.os.SystemClock.elapsedRealtime() + 30_000
        var declined = false
        while (!declined && android.os.SystemClock.elapsedRealtime() < promptDeadline) {
            // Native polling does not auto-advance the Compose test clock. Allow the
            // asynchronous save result to recompose and navigate to the print gate.
            compose.mainClock.advanceTimeByFrame()
            val root = automation.rootInActiveWindow
            val nativePackage = root?.packageName?.toString().orEmpty()
            if (nativePackage in setOf("com.android.settings", "com.android.bluetooth", "com.android.systemui",
                    "com.android.permissioncontroller", "com.google.android.permissioncontroller")) {
                val negative = root?.findAccessibilityNodeInfosByViewId("android:id/button2")?.firstOrNull()
                    ?: listOf("Deny", "Cancel", "Don't allow", "Don’t allow").firstNotNullOfOrNull { label ->
                        root?.findAccessibilityNodeInfosByText(label)?.firstOrNull()
                    }
                declined = negative?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true ||
                    negative?.parent?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            if (!declined) android.os.SystemClock.sleep(100)
        }
        if (!declined) {
            captureDebug("direct-bluetooth-prompt")
            compose.onRoot(useUnmergedTree = true).printToLog("AuditUi")
        }
        check(declined) { "Could not decline Android's Bluetooth-enable prompt" }
        compose.waitUntil(30_000) { automation.rootInActiveWindow?.packageName?.toString() == "com.hardwareshop.pos.debug" }
        waitText("Bill Saved Successfully!")
        waitText("Payment: Cash")
        compose.onNodeWithText("₹45.00").assertExists()
        val unavailable = hasText("Bluetooth must be turned on to print.")
        waitEnabled(unavailable)
        waitEnabled(hasText("NEW BILL"))
        compose.onNodeWithText("NEW BILL").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
    }
}


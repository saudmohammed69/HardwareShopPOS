package com.hardwareshop.pos

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.room.withTransaction
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.di.SessionEntryPoint
import com.hardwareshop.pos.presentation.navigation.*
import com.hardwareshop.pos.util.UserSession
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.*

/** Isolated offline emulator fixtures only; never run against a shop phone. */
class MenuKeyboardRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var preserveFailedFixtures = false
    private inline fun captureNavigationFailure(block: () -> Unit) {
        val probe = NavigationFailureEvidence.startMainThreadProbe()
        try { block() }
        catch (failure: Throwable) {
            // Capture inside the test, BEFORE @After or ActivityScenario teardown.
            preserveFailedFixtures = true
            runCatching {
                NavigationFailureEvidence.capture(compose.activity, failure) {
                    compose.onRoot(useUnmergedTree=true).printToString()
                }
            }.onFailure { captureFailure ->
                if (captureFailure !== failure) failure.addSuppressed(captureFailure)
                android.util.Log.e("NavigationProbe", "Pre-teardown evidence capture failed", captureFailure)
            }
            throw failure
        } finally { probe.close() }
    }
    private val session get() = EntryPointAccessors.fromApplication(compose.activity.applicationContext,
        SessionEntryPoint::class.java).sessionHolder()
    private fun home() = compose.activityRule.scenario.onActivity {
        session.setSession(UserSession.OwnerSession("Navigation regression"))
        it.setContent { MaterialTheme { AppNavGraph(Routes.OWNER_HOME) } }
    }
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(15_000) {
        try {compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()}
        catch(e:IllegalStateException) {
            // A native permission window or Activity transition may temporarily detach Compose.
            if(e.message?.contains("No compose hierarchies found")==true)false else throw e
        }
    }
    private fun cleanLoadFixtures(db: AppDatabase)=runBlocking {
        check(compose.activity.packageName.endsWith(".debug") &&
            (android.os.Build.HARDWARE=="ranchu" || android.os.Build.HARDWARE=="goldfish")) {
            "Navigation fixture cleanup is restricted to a disposable debug emulator"
        }
        db.withTransaction {
            val sql=db.openHelper.writableDatabase
            val fixtureIds="SELECT id FROM categories WHERE name GLOB 'Load test [0-9]*'"
            sql.query("SELECT COUNT(*) FROM bill_items WHERE productId IN (SELECT id FROM products WHERE categoryId IN ($fixtureIds))").use {
                check(it.moveToFirst() && it.getLong(0)==0L) {"A navigation fixture has financial history; leave it intact"}
            }
            sql.execSQL("DELETE FROM products WHERE categoryId IN ($fixtureIds)")
            sql.execSQL("DELETE FROM product_types WHERE categoryId IN ($fixtureIds)")
            sql.execSQL("DELETE FROM categories WHERE id IN ($fixtureIds)")
        }
    }
    @After fun clearSession() {
        compose.activityRule.scenario.onActivity {it.setContent {}}
        if (!preserveFailedFixtures) cleanLoadFixtures(AppDatabase.getInstance(compose.activity.applicationContext))
        session.clear()
    }
    @Test fun largeCatalogueCanRepeatedlyOpenBillingAndOwnerMenus() = captureNavigationFailure {
        val db = AppDatabase.getInstance(compose.activity.applicationContext)
        // Prior runs leaked 3,000 rows each, changing the workload every retry.
        // Remove only this test's named, unbilled fixtures on our disposable emulator.
        cleanLoadFixtures(db)
        val preserved=runBlocking {db.productDao().getAllProducts()}
        runBlocking { db.withTransaction {
            val category = db.categoryDao().insertCategory(CategoryEntity(name="Load test ${System.nanoTime()}"))
            val type = db.productTypeDao().insertType(ProductTypeEntity(categoryId=category,name="Load fittings"))
            repeat(3000) { index -> db.productDao().insertProduct(ProductEntity(categoryId=category,typeId=type,
                name="Load fitting ${index+1}",size="${index%20+1} inch",unit="piece",sellingPricePaisa=1250,currentStock=100.0)) }
        } }
        home()
        repeat(5) { index ->
            // LazyColumn restores its scroll position on return. Offscreen menu rows
            // need not be in the semantics tree until scrolled into view.
            waitFor(hasContentDescription("Logout"))
            compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("New / Resume Bill"))
            val start = android.os.SystemClock.elapsedRealtime()
            compose.onNodeWithText("New / Resume Bill").performClick()
            waitFor(hasTestTag("billing-header-cart"))
            compose.onNodeWithTag("billing-header-cart").assertIsDisplayed()
            // The spinner alone only covers draft recovery outside search mode.
            // Require the real catalog AND draft state to be ready on every open.
            waitFor(hasTestTag("billing-catalog-ready"))
            compose.waitUntil(15_000) { compose.onAllNodes(SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isEmpty() }
            compose.waitForIdle()
            val elapsed = android.os.SystemClock.elapsedRealtime()-start
            android.util.Log.i("MenuPerformance", "Home to Billing with 3000 extra products, pass $index: ${elapsed}ms")
            Assert.assertTrue("Billing navigation took ${elapsed}ms",elapsed<15_000)
            compose.onNodeWithContentDescription("Back").performClick()
        }
        listOf("Orders","Products & Prices","Settings","Stock Management","Bill History","Dashboard & Reports",
            "Manually Added Bills","Purchases & Supplier Dues","Returns & Exchanges","Reorder List",
            "GST Bills 🧾","Customers & Credit","Workers","Reports","Audit Log","Remote Printing 🌐","Day Closing",
            "Pending Item Details").forEach { menu ->
            waitFor(hasContentDescription("Logout"))
            val menuMatcher=if(menu=="Orders")hasText("Orders (",substring=true) else if(menu=="Pending Item Details") hasText("Pending Item Details (",substring=true)
                else hasText(menu)
            compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(menuMatcher)
            val menuNode=compose.onNode(menuMatcher)
            val start = android.os.SystemClock.elapsedRealtime()
            android.util.Log.i("MenuPerformance", "Opening $menu")
            menuNode.performClick()
            waitFor(hasContentDescription("Back"))
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.waitForIdle()
            val elapsed=android.os.SystemClock.elapsedRealtime()-start
            android.util.Log.i("MenuPerformance", "Home to $menu: ${elapsed}ms")
            Assert.assertTrue("$menu navigation took ${elapsed}ms",elapsed<15_000)
            compose.onNodeWithContentDescription("Back").performClick()
        }
        waitFor(hasContentDescription("Logout"))
        Assert.assertTrue("Navigation must preserve the existing owner session",session.current is UserSession.OwnerSession)
        cleanLoadFixtures(db)
        Assert.assertEquals("Existing product names/prices/stock must be unchanged",preserved,
            runBlocking {db.productDao().getAllProducts()})
    }
    @Test fun pipeFeetAndPinnedCartRemainVisibleWithNumericKeyboard() = captureNavigationFailure {
        val db=AppDatabase.getInstance(compose.activity.applicationContext)
        val name="Keyboard pipes ${System.nanoTime()}"
        val id=runBlocking { db.withTransaction {
            val category=db.categoryDao().insertCategory(CategoryEntity(name=name))
            val type=db.productTypeDao().insertType(ProductTypeEntity(categoryId=category,name="Keyboard pipe type"))
            db.productDao().insertProduct(ProductEntity(categoryId=category,typeId=type,name="Keyboard test pipe",size="1 inch",
                unit="Length",sellingPricePaisa=40000,currentStock=100.0,isPipe=true,pipeLengthPricePaisa=40000,
                pipeFootPricePaisa=3500,pipeFeetPerLength=20))
        } }
        val entity=runBlocking { requireNotNull(db.productDao().getProductById(id)) }
        val product=com.hardwareshop.pos.domain.model.Product(id,entity.categoryId,name,entity.typeId,"Keyboard pipe type",
            "Keyboard test pipe","1 inch","Length",40000,0,0,100.0,0.0,null,isActive=true,
            isPipe=true,pipeLengthPricePaisa=40000,pipeFootPricePaisa=3500,pipeFeetPerLength=20)
        runBlocking {
            val fixtureSession=com.hardwareshop.pos.util.SessionHolder().apply {
                setSession(UserSession.OwnerSession("Offcut fixture"))
            }
            com.hardwareshop.pos.data.repository.PipeOffcutRepository(db,fixtureSession).add(product,"15","Keyboard shelf")
        }
        var cartCalls=0
        // Exercise the actual production screen and Hilt view models. Catalogue navigation
        // is covered separately; select the real database fixture through the same action
        // as a tile tap so this regression can isolate IME geometry and focus behavior.
        compose.activityRule.scenario.onActivity { activity ->
            session.setSession(UserSession.OwnerSession("Keyboard regression"))
            activity.setContent { MaterialTheme {
                val picker: com.hardwareshop.pos.presentation.worker.product.ProductSelectionViewModel =
                    androidx.hilt.navigation.compose.hiltViewModel()
                androidx.compose.runtime.LaunchedEffect(id) { picker.selectProductForQuantity(product) }
                com.hardwareshop.pos.presentation.worker.billing.ProductSelectionScreen(
                    onNavigateUp={},onGoToCart={cartCalls++},viewModel=picker)
            } }
        }
        waitFor(hasTestTag("pipe-feet-$id"))
        val feet=compose.onNodeWithTag("pipe-feet-$id")
        compose.waitUntil(15_000) { !feet.fetchSemanticsNode().config.contains(
            androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        val cart=compose.onNodeWithTag("billing-header-cart")
        cart.assertIsDisplayed()
        val headerBefore=cart.fetchSemanticsNode().boundsInRoot
        feet.assertIsDisplayed().performClick()
        feet.assertIsFocused()
        compose.waitUntil(15_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime())==true }
        compose.runOnIdle {
            val editor=android.view.inputmethod.EditorInfo()
            compose.activity.currentFocus?.onCreateInputConnection(editor)
            Assert.assertEquals("Length must use a numeric keyboard",android.text.InputType.TYPE_CLASS_NUMBER,
                editor.inputType and android.text.InputType.TYPE_MASK_CLASS)
        }
        feet.performTextInput("12.5")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("offcut-billing-hint").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("offcut-billing-hint").assertTextContains("15 ft offcut available",substring=true)
        feet.assertIsDisplayed().assertTextContains("12.5")
        compose.onNodeWithText("ft").assertIsDisplayed()
        cart.assertIsDisplayed()
        val headerAfter=cart.fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(headerBefore.top,headerAfter.top,1f)
        Assert.assertEquals(headerBefore.left,headerAfter.left,1f)
        val visible=android.graphics.Rect()
        compose.activity.window.decorView.getWindowVisibleDisplayFrame(visible)
        val location=IntArray(2)
        compose.activity.window.decorView.getLocationOnScreen(location)
        Assert.assertTrue("Feet input intersects keyboard",feet.fetchSemanticsNode().boundsInRoot.bottom+location[1]<=visible.bottom+1)
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"keyboard-pinned-cart.png").outputStream()
                .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitUntil(15_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime())!=true }
        feet.assertIsDisplayed().assertTextContains("12.5")
        cart.assertIsDisplayed()
        Assert.assertEquals(headerBefore.top,cart.fetchSemanticsNode().boundsInRoot.top,1f)
        feet.performClick()
        compose.waitUntil(15_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime())==true }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("pipe-feet-$id").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Search products, size, aliases…").assertIsDisplayed()
        cart.assertIsDisplayed()
        cart.performClick()
        compose.runOnIdle { Assert.assertEquals(1,cartCalls) }
    }
}


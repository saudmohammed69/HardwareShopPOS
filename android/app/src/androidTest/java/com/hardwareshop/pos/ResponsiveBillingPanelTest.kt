package com.hardwareshop.pos

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.presentation.worker.billing.*
import com.hardwareshop.pos.util.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Runner changes emulator display to phone/tablet portrait/tablet landscape between runs. */
class ResponsiveBillingPanelTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val pipes=(1..28).map{id->Product(id.toLong(),1,"PVC",1,"Pipe","PVC Pipe","$id/2 inch","Length",
        40000,0,1800,100.0,0.0,null,isActive=true,isPipe=true,pipeLengthPricePaisa=40000,pipeFootPricePaisa=3500,pipeFeetPerLength=20)}
    private fun capture(name:String){
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val config=InstrumentationRegistry.getArguments().getString("displayName") ?: "display"
        instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->
            File(instrumentation.targetContext.getExternalFilesDir(null),"panel-$config-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
    private fun assertRegions(){
        val entry=compose.onNodeWithTag("billing-entry-region").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("billing-entry-region").assertIsDisplayed()
        val roots=compose.onNodeWithTag("panel-test-root").fetchSemanticsNode().boundsInRoot
        assertTrue("Panel outside root: $entry / $roots",entry.left>=roots.left && entry.top>=roots.top && entry.right<=roots.right+1 && entry.bottom<=roots.bottom+1)
        val grids=compose.onAllNodesWithTag("billing-grid-region").fetchSemanticsNodes()
        if(grids.isNotEmpty()){
            val grid=grids.single().boundsInRoot
            assertTrue("Grid overlaps entry: $grid / $entry",grid.right<=entry.left+1 || grid.bottom<=entry.top+1)
            if(compose.activity.resources.configuration.screenWidthDp>=600){
                assertEquals(grid.top,entry.top,1f);assertEquals(grid.bottom,entry.bottom,1f)
            } else {assertEquals(roots.left,entry.left,1f);assertEquals(roots.right,entry.right,1f)}
        }
    }
    private fun awaitSettledKeyboard(){
        var previous: androidx.compose.ui.geometry.Rect?=null
        var stableSince=android.os.SystemClock.uptimeMillis()
        compose.waitUntil(15000){
            val insets=ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            val root=compose.onNodeWithTag("panel-test-root").fetchSemanticsNode().boundsInRoot
            val now=android.os.SystemClock.uptimeMillis()
            if(root!=previous){previous=root;stableSince=now}
            insets?.isVisible(WindowInsetsCompat.Type.ime())==true && now-stableSince>=300
        }
        compose.waitForIdle()
    }
    @Test fun allSizesBatchFooterAndSinglePipeStayReachable():Unit {
        compose.activityRule.scenario.onActivity{activity->activity.setContent {MaterialTheme {
            var chosen by remember{mutableStateOf<Product?>(null)}
            var anchor by remember{mutableStateOf<Product?>(pipes.first())}
            Box(Modifier.fillMaxSize().safeDrawingPadding().testTag("panel-test-root")) {
                BillingPickerLayout(chosen,"1",{}, {},{chosen=null;anchor=null},{},
                    sizeChoiceProduct=anchor,sizeChoices=pipes,onSize={chosen=it},entryRates=40000L to 3500L,
                    onAddItems={_,_->true},onAddPipe={_,_,_->},content={modifier->Box(modifier.testTag("panel-grid-content"))})
            }
        }}}
        assertRegions()
        compose.onNodeWithTag("inline-size-choices").performScrollToNode(hasTestTag("size-option-${pipes.last().id}"))
        compose.onNodeWithTag("size-option-${pipes.last().id}").assertIsDisplayed()
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText("Cart"))
        compose.onNodeWithText("Cart").assertIsDisplayed()
        capture("many-sizes")
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText("ADD MULTIPLE SIZES"))
        compose.onNodeWithText("ADD MULTIPLE SIZES").performClick()
        compose.onNodeWithTag("batch-size-fields").performScrollToNode(hasTestTag("batch-lengths-${pipes.last().id}"))
        compose.onNodeWithTag("batch-lengths-${pipes.last().id}").performTextInput("2")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText("ADD SELECTED TO CART"))
        compose.onNodeWithText("ADD SELECTED TO CART").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText("Cart"))
        compose.onNodeWithText("CANCEL").assertIsDisplayed()
        capture("batch-actions")
        compose.onNodeWithText("SINGLE SIZE").performScrollTo().performClick()
        compose.onNodeWithTag("inline-size-choices").performScrollToNode(hasTestTag("size-option-${pipes.first().id}"))
        compose.onNodeWithTag("size-option-${pipes.first().id}").performClick()
        compose.onNodeWithTag("pipe-feet-${pipes.first().id}").performScrollTo().performTextInput("3.5")
        compose.waitUntil(15000){ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true}
        awaitSettledKeyboard()
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText("ADD TO CART"))
        compose.onNodeWithText("ADD TO CART").assertIsDisplayed().assertIsEnabled()
        capture("keyboard");assertRegions()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText("Cart"))
        compose.onNodeWithText("Cart").assertIsDisplayed()
    }
    @Test fun singleSavedPipeStillHasCompleteEntryAndCartActions():Unit {
        val single=pipes.first()
        compose.activityRule.scenario.onActivity{it.setContent {MaterialTheme {
            Box(Modifier.fillMaxSize().safeDrawingPadding().testTag("panel-test-root")) {
                BillingPickerLayout(single,"1",{}, {},{},{},sizeChoiceProduct=single,sizeChoices=listOf(single),
                    entryRates=40000L to 3500L,content={modifier->Box(modifier)})
            }
        }}}
        assertRegions()
        compose.onNodeWithTag("pipe-length-${single.id}").performScrollTo().performTextInput("2")
        compose.onNodeWithTag("pipe-feet-${single.id}").performScrollTo().performTextInput("3.5")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        val expectedAmount="Amount: ${CalculationEngine.formatRupees(92250)}"
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText(expectedAmount))
        compose.onNodeWithText(expectedAmount).assertIsDisplayed()
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText("ADD TO CART"))
        compose.onNodeWithText("ADD TO CART").assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithTag("inline-billing-entry").performScrollToNode(hasText("Cart"))
        compose.onNodeWithText("Cart").assertIsDisplayed()
        capture("single-size-pipe")
    }

    @Test fun cartVariantEditorAndOrdinaryQuantityUseTheSameSeparateRegion():Unit {
        val fittings=pipes.map{it.copy(name="PVC Elbow",unit="Piece",isPipe=false,pipeLengthPricePaisa=0,pipeFootPricePaisa=0,pipeFeetPerLength=0)}
        val item=CartItem(fittings.first(),2.0,40000,80000)
        compose.activityRule.scenario.onActivity{it.setContent {MaterialTheme {
            Box(Modifier.fillMaxSize().safeDrawingPadding().testTag("panel-test-root")) {
                BillingPickerLayout(null,"",{}, {},{},{},entryContent={modifier->
                    CartVariantEditor(item,fittings,listOf(item),true,null,{_,_,_,_->},{},modifier)
                },content={modifier->Box(modifier)})
            }
        }}}
        assertRegions()
        compose.onNodeWithTag("inline-size-choices").performScrollToNode(hasTestTag("size-option-${fittings.last().id}"))
        compose.onNodeWithTag("size-option-${fittings.last().id}").performClick()
        compose.onNodeWithTag("cart-variant-editor").performScrollToNode(hasTestTag("edit-quantity"))
        compose.onNodeWithTag("edit-quantity").performTextReplacement("4")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("cart-variant-editor").performScrollToNode(hasText("APPLY CHANGES"))
        compose.onNodeWithText("APPLY CHANGES").assertIsDisplayed().assertIsEnabled()
        capture("cart-editor")
        compose.activityRule.scenario.onActivity{it.setContent {MaterialTheme {
            Box(Modifier.fillMaxSize().safeDrawingPadding().testTag("panel-test-root")) {
                BillingPickerLayout(fittings.first(),"2",{}, {},{},{},content={modifier->Box(modifier)})
            }
        }}}
        assertRegions()
        compose.onNodeWithText("ADD TO CART").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cart").performScrollTo().assertIsDisplayed()
        capture("ordinary-quantity")
    }
}

package com.hardwareshop.pos
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.presentation.worker.billing.*
import com.hardwareshop.pos.util.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class PipeBillingUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun pipe(id: Long)=Product(id,1,"PVC",1,"Pipe","PVC Pipe","1 inch","Length",40000,0,0,10.0,0.0,null,isActive=true,
        isPipe=true,pipeLengthPricePaisa=40000,pipeFootPricePaisa=3500,pipeFeetPerLength=20)
    @Test fun typingSwitchingRemovingAndRecompositionNeverDuplicateTheBillLine() {
        var cart by mutableStateOf(emptyList<CartItem>())
        var selected by mutableStateOf(pipe(1))
        var tick by mutableStateOf(0)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme { Column {
                Text("Total: ${cart.sumOf { it.itemTotalPaisa }}")
                Text("Tick: $tick")
                BillingPickerLayout(selected,"1",{}, {}, {}, {},
                    pipeItem=cart.firstOrNull { it.product.id==selected.id },
                    onPipe={ l,f -> cart=PipeBilling.replace(cart,selected,l.toLongOrNull() ?: 0,f.toDoubleOrNull() ?: 0.0) }) { modifier ->
                    Column(modifier) {
                        Button({ selected=pipe(2) }) { Text("Second pipe") }
                        Button({ selected=pipe(1) }) { Text("First pipe") }
                        Button({ cart=cart.filterNot { it.product.id==selected.id } }) { Text("Remove selected") }
                        Button({ tick++ }) { Text("Recompose") }
                    }
                }
            } }
        } }
        compose.onNodeWithText("ADD TO CART").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithTag("pipe-length-1").performTextInput("1")
        compose.onNodeWithTag("pipe-feet-1").performTextInput("10")
        compose.onNodeWithText("Total: 75000").assertExists()
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "pipe-input.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        }
        // On phones the grid yields its space to entry while the IME is open.
        // Trigger recomposition directly so this still exercises keyboard-open typing.
        compose.runOnIdle { tick++ }
        compose.runOnIdle { assertEquals(1,cart.size) }
        compose.onNodeWithTag("pipe-length-1").performTextReplacement("2")
        compose.onNodeWithTag("pipe-feet-1").performTextReplacement("5")
        compose.onNodeWithText("Total: 97500").assertExists()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Second pipe").performClick()
        compose.onNodeWithTag("pipe-feet-2").performTextInput("10")
        compose.runOnIdle { assertEquals(2,cart.size) }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("First pipe").performClick()
        compose.onNodeWithTag("pipe-length-1").assertTextContains("2")
        compose.onNodeWithTag("pipe-feet-1").assertTextContains("5")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Remove selected").performClick()
        compose.onNodeWithTag("pipe-length-1").assertTextEquals("Length No.", "")
        compose.runOnIdle { assertEquals(listOf(2L),cart.map { it.product.id }) }
    }
    @Test fun ordinaryProductKeepsItsAddButtonAndPipeHasOnlyTwoFields() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme { BillingPickerLayout(pipe(1).copy(isPipe=false),"1",{}, {}, {}, {}) { modifier -> Text("Products",modifier) } }
        } }
        compose.onNodeWithText("ADD TO CART").assertIsDisplayed()
    }
}

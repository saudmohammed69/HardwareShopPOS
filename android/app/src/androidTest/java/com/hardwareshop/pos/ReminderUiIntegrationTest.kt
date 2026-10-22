package com.hardwareshop.pos

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.presentation.owner.misc.*
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

class ReminderUiIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var db:AppDatabase
    private lateinit var vm:CreditReminderViewModel
    private lateinit var repository:CreditReminderRepository
    private var customerId=0L
    @Before fun setup()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).build()
        val session=SessionHolder().apply {setSession(UserSession.OwnerSession("Shop"))}
        val settings=SettingsRepository(db.settingsDao(),db.ownerCredentialDao(),session)
        customerId=db.customerDao().insertCustomer(CustomerEntity(name="Contractor"))
        db.creditDao().insertTransaction(CreditTransactionEntity(customerId=customerId,billId=null,type="ADJUSTMENT",
            amountPaisa=123450,previousBalancePaisa=0,newBalancePaisa=123450,notes="Test opening balance"))
        repository=CreditReminderRepository(db,settings,session)
        vm=withContext(Dispatchers.Main){CreditReminderViewModel(repository)}
    }
    @After fun cleanup()=runBlocking {
        compose.activityRule.scenario.onActivity {it.setContent {}}
        withContext(Dispatchers.Main){vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()}
        db.close()
    }
    @Test fun ownerReviewsAnOverdueReminderWithoutSendingOrChangingTheLedger()=runBlocking {
        compose.activityRule.scenario.onActivity {activity->activity.setContent {MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()){CreditReminderControls(customerId,vm)}
        }}}
        // LaunchedEffect starts observation on Compose's frame clock. Pump that clock
        // while waiting; blocking on Flow.first alone can prevent the effect starting.
        compose.waitUntil(15000){vm.state.value.snapshot!=null || vm.state.value.error!=null}
        assertNull(vm.state.value.error)
        assertNotNull(vm.state.value.snapshot)
        val date=LocalDate.now().minusDays(1).toString()
        compose.onNodeWithTag("reminder-due-date").performScrollTo().performTextInput(date)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("SAVE DUE DATE").performScrollTo().performClick()
        compose.waitUntil(15000){vm.state.value.error!=null || (vm.state.value.snapshot?.dueDate==date && !vm.state.value.busy)}
        assertNull(vm.state.value.error)
        assertEquals(date,vm.state.value.snapshot?.dueDate)
        compose.onNodeWithText("PREPARE REMINDER").performScrollTo().performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("Review Udhar reminder").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Review Udhar reminder").assertIsDisplayed()
        compose.onNodeWithText("Hello Contractor",substring=true).assertTextContains("1,234.50",substring=true)
        compose.onNodeWithText("OPEN WHATSAPP").assertIsDisplayed()
        assertTrue(repository.observe(customerId).first().attempts.isEmpty())
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let {bitmap->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"advanced-reminder-preview.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            };bitmap.recycle()
        }
        compose.onNodeWithText("CANCEL").performClick()
        assertEquals(123450L,db.creditDao().getBalanceForCustomer(customerId))
        assertEquals(1,db.creditDao().getAllTransactions().size)
        assertTrue(repository.observe(customerId).first().attempts.isEmpty())
    }
}

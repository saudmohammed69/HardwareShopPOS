package com.hardwareshop.pos

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import com.hardwareshop.pos.di.SessionEntryPoint
import com.hardwareshop.pos.presentation.owner.misc.SettingsScreen
import com.hardwareshop.pos.presentation.owner.misc.SettingsViewModel
import com.hardwareshop.pos.util.ReceiptFooter
import com.hardwareshop.pos.util.UserSession
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** Exercises the production Settings screen; no printer connection or message is opened. */
class ReceiptFooterUiIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun settingsFooterEditsPreviewsPersistsAndResetsWithoutBluetoothPrompt()=runBlocking {
        val session=EntryPointAccessors.fromApplication(compose.activity.applicationContext,SessionEntryPoint::class.java).sessionHolder()
        val db=AppDatabase.getInstance(compose.activity.applicationContext)
        val previous=db.settingsDao().getSetting("receipt_footer")
        var vm:SettingsViewModel?=null
        try {
            db.settingsDao().upsertSetting(SettingsEntity("receipt_footer","Previous footer"))
            compose.activityRule.scenario.onActivity {activity->
                session.setSession(UserSession.OwnerSession("Footer test"))
                activity.setContent {MaterialTheme {
                    val settings:SettingsViewModel=hiltViewModel()
                    SideEffect {vm=settings}
                    SettingsScreen(onNavigateUp={},viewModel=settings)
                }}
            }
            compose.waitUntil(15000){vm!=null}
            withTimeout(15000){vm!!.state.first {it.receiptFooter=="Previous footer"}}
            val field=compose.onNodeWithText("58mm receipt footer")
            field.performScrollTo().performTextReplacement("Original bill required.\nThank you!")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("SAVE FOOTER").performScrollTo().performClick()
            withTimeout(15000){vm!!.state.first {!it.isLoading && it.message?.startsWith("Receipt footer saved")==true}}
            assertEquals("Original bill required.\nThank you!",db.settingsDao().getSetting("receipt_footer")!!.value)
            compose.onNodeWithText("PREVIEW").performScrollTo().performClick()
            compose.onNodeWithText("Original bill required.").performScrollTo().assertIsDisplayed()
            val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.takeScreenshot()?.let {bitmap->
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"advanced-footer-preview.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                };bitmap.recycle()
            }
            compose.onNodeWithText("RESET TO DEFAULT").performScrollTo().performClick()
            assertEquals("Original bill required.\nThank you!",db.settingsDao().getSetting("receipt_footer")!!.value)
            compose.onNodeWithText("SAVE FOOTER").performScrollTo().performClick()
            withTimeout(15000){vm!!.state.first {!it.isLoading && it.receiptFooter==ReceiptFooter.DEFAULT && it.message?.startsWith("Receipt footer saved")==true}}
            assertEquals(ReceiptFooter.DEFAULT,db.settingsDao().getSetting("receipt_footer")!!.value)
        }finally {
            compose.activityRule.scenario.onActivity {it.setContent {}}
            withContext(Dispatchers.Main){vm?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()}
            if(previous==null)db.settingsDao().deleteSetting("receipt_footer") else db.settingsDao().upsertSetting(previous)
            session.clear()
        }
    }
}

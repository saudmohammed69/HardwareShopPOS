package com.hardwareshop.pos.data.remote

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.sync.*
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** No live Firebase account or business data is used by these tests. */
@RunWith(AndroidJUnit4::class)
class CloudSessionInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var store: CloudSessionStore
    private lateinit var session: SessionHolder
    private lateinit var context: Context
    private lateinit var base: Context
    private val prefsName="session_test_${java.util.UUID.randomUUID()}"
    @Before fun setup() = runBlocking {
        base=ApplicationProvider.getApplicationContext()
        context=object:ContextWrapper(base) {
            override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences(prefsName,mode)
        }
        db=Room.inMemoryDatabaseBuilder(base,AppDatabase::class.java).addCallback(object:RoomDatabase.Callback() {
            override fun onOpen(db:SupportSQLiteDatabase) {
                SyncJournalSchema.statements(includeBoxCount=true).forEach { db.execSQL(it) }
            }
        }).build()
        val journal=SyncJournal(db)
        journal.bindVerifiedStore("test-shop")
        db.ownerCredentialDao().upsertCredential(OwnerCredentialEntity(passwordHash="hash",passwordSalt="salt",shopName="Hardware"))
        session=SessionHolder()
        store=CloudSessionStore(context,db,journal,session)
    }
    @After fun close() { db.close(); base.deleteSharedPreferences(prefsName) }
    @Test fun ownerSurvivesRestartButCannotSwitchAccountOrDevice()=runBlocking {
        session.setSession(UserSession.OwnerSession("Hardware"))
        store.rememberAccount("owner")
        session.clear()
        assertTrue(store.restoreAccount("owner") is UserSession.OwnerSession)
        assertNull(store.restoreAccount("different-owner"))
        assertNull(store.restoreAccount("owner")) // rejected metadata stays cleared
        store.rememberAccount("owner")
        context.getSharedPreferences("cloud_login_session",0).edit().putString("device","another-phone").commit()
        assertNull(store.restoreAccount("owner"))
    }
    @Test fun logoutAndMissingAuthenticationNeverRestore()=runBlocking {
        session.setSession(UserSession.OwnerSession("Hardware"))
        store.rememberAccount("owner")
        store.clear(); session.clear()
        assertNull(store.restoreAccount("owner"))
        store.rememberAccount("owner") // None cannot create a remembered worker role
        assertNull(store.restoreAccount("owner"))
        session.setSession(UserSession.OwnerSession("Hardware")); store.rememberAccount("owner")
        assertNull(store.restoreAccount(null))
    }
    @Test fun workerKeepsCurrentPermissionsAndDisabledWorkerCannotRestore()=runBlocking {
        val worker=WorkerEntity(id=7,name="Cashier",username="cashier",passwordHash="hash",passwordSalt="salt",maxDiscountPaisa=50)
        db.workerDao().insertWorker(worker)
        session.setSession(UserSession.WorkerSession(7,"Cashier","cashier",50,true))
        store.rememberAccount("owner"); session.clear()
        db.workerDao().updateWorker(worker.copy(maxDiscountPaisa=20))
        val restored=store.restoreAccount("owner") as UserSession.WorkerSession
        assertEquals(20L,restored.maxDiscountPaisa)
        assertFalse(session.isOwner)
        db.workerDao().updateWorker(worker.copy(isActive=false))
        assertNull(store.restoreAccount("owner"))
    }
    @Test fun syncedReceiptHeaderOverridesOldLocalHeader()=runBlocking {
        db.settingsDao().upsertSetting(SettingsEntity("shop_name","ALI HARDWARE"))
        val repository=com.hardwareshop.pos.data.repository.SettingsRepository(db.settingsDao(),db.ownerCredentialDao(),session)
        assertEquals("ALI HARDWARE",repository.getShopInfo().name)
        session.setSession(UserSession.OwnerSession("ALI HARDWARE"))
        val result=repository.updateShopInfo(repository.getShopInfo().copy(phone="12345"))
        assertTrue(result is com.hardwareshop.pos.domain.model.Result.Success)
        assertEquals("12345",db.settingsDao().getSetting("shop_phone")!!.value)
    }
}

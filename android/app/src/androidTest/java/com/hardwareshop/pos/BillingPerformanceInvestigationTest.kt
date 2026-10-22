package com.hardwareshop.pos

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.data.sync.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.presentation.worker.billing.*
import com.hardwareshop.pos.presentation.worker.product.ProductSelectionViewModel
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Same test and fixture on the before/after APK; no timing promises baked into assertions. */
class BillingPerformanceInvestigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var db:AppDatabase
    private lateinit var picker:ProductSelectionViewModel
    private lateinit var billing:BillingViewModel
    private lateinit var products:ProductRepository
    private val queries=ConcurrentLinkedQueue<String>()
    private val recording=AtomicBoolean(false)
    private val frameTimes=ConcurrentLinkedQueue<Double>()
    private val frameHandler=HandlerThread("performance-frame-observer")
    private val frameListener=Window.OnFrameMetricsAvailableListener { _,metrics,_ ->
        if(recording.get()) frameTimes.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION)/1_000_000.0)
    }
    @Before fun setup():Unit=runBlocking {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java)
            .setQueryCallback(object:RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery:String,bindArgs:List<Any?>) { if(recording.get())queries.add(sqlQuery) }
            },Executor { it.run() })
            .addCallback(object:RoomDatabase.Callback(){override fun onOpen(sql:SupportSQLiteDatabase){
                (SyncJournalSchema.statements(true,true)+SyncTransferSchema.statements()).forEach(sql::execSQL)
            }}).build()
        val actor=SessionHolder().apply{setSession(UserSession.OwnerSession("Performance fixture"))}
        val audit=AuditRepository(db.auditLogDao(),actor)
        products=ProductRepository(db,db.categoryDao(),db.productTypeDao(),db.productDao(),db.stockDao(),audit,actor)
        val settings=SettingsRepository(db.settingsDao(),db.ownerCredentialDao(),actor)
        val repository=BillingRepository(db,db.billDao(),db.productDao(),db.creditDao(),audit,BillNumberGenerator(db),settings,actor)
        db.withTransaction {
            for(material in listOf("PVC","CPVC","UPVC")) {
                val category=db.categoryDao().insertCategory(CategoryEntity(name=material))
                for(kind in listOf("Pipe","Elbow","Tee","Socket","Valve")) {
                    val type=db.productTypeDao().insertType(ProductTypeEntity(categoryId=category,name=kind))
                    repeat(20){family -> repeat(10){size ->
                        val pipe=kind=="Pipe"
                        db.productDao().insertProduct(ProductEntity(categoryId=category,typeId=type,
                            name="$material $kind Series $family",size="${size+1}/2 inch",unit=if(pipe)"Length" else "Piece",
                            sellingPricePaisa=10000L+size*1000,currentStock=1000.0,
                            isPipe=pipe,pipeLengthPricePaisa=if(pipe)10000L+size*1000 else 0,
                            pipeFootPricePaisa=if(pipe)1500L+size*100 else 0,pipeFeetPerLength=if(pipe)20 else 0))
                    }}
                }
            }
        }
        frameHandler.start()
        compose.activityRule.scenario.onActivity {it.window.addOnFrameMetricsAvailableListener(frameListener,Handler(frameHandler.looper))}
        // Construction timed in the test, with fixture writes excluded.
        withContext(Dispatchers.Main) {
            billing=BillingViewModel(repository,CustomerRepository(db.customerDao(),db.creditDao(),actor),products,db.settingsDao(),settings,db,actor)
        }
        withTimeout(30000){billing.state.first{!it.isLoading}}
    }
    @After fun cleanup():Unit=runBlocking {
        recording.set(false)
        compose.activityRule.scenario.onActivity {it.window.removeOnFrameMetricsAvailableListener(frameListener);it.setContent{}}
        frameHandler.quitSafely()
        withContext(Dispatchers.Main){
            if(::picker.isInitialized)picker.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            billing.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        db.close()
    }
    @Test fun comparableOfflineBillingWorkload():Unit=runBlocking {
        val result=linkedMapOf<String,Any>("version" to BuildConfig.VERSION_NAME,"fixtureProducts" to 3000,
            "network" to "emulator Wi-Fi disabled by runner; local Room fixture",
            "navigationMeasurement" to "retained-ViewModel product/cart composable harness, not cold app startup")
        val heapBefore=Runtime.getRuntime().let{it.totalMemory()-it.freeMemory()}
        val pssBefore=Debug.MemoryInfo().apply{Debug.getMemoryInfo(this)}.totalPss
        queries.clear();recording.set(true)
        val opening=SystemClock.elapsedRealtimeNanos()
        withContext(Dispatchers.Main) {
            val actor=SessionHolder().apply{setSession(UserSession.OwnerSession("Performance fixture"))}
            picker=ProductSelectionViewModel(products,db.settingsDao(),PipeOffcutRepository(db,actor))
        }
        withTimeout(30000){picker.state.first{it.availableProducts.size==3000}}
        var showingCart by mutableStateOf(false)
        compose.activityRule.scenario.onActivity {it.setContent {MaterialTheme {
            if(showingCart)CartScreen({showingCart=false},{showingCart=false},{},{},billing)
            else ProductSelectionScreen({}, {showingCart=true},billing,picker)
        }}}
        compose.waitForIdle()
        result["openSelectProductMs"]=(SystemClock.elapsedRealtimeNanos()-opening)/1_000_000.0
        delay(600);queries.clear()
        val searchRows=mutableListOf<Map<String,Any>>()
        for(query in listOf("pvc pipe","cpvc pipe","elbow","tee","socket","pvc","pvc pipe","pvc pipe 3/2","upvc pipe","cpvc elbow")) {
            val countBefore=queries.size;val start=SystemClock.elapsedRealtimeNanos()
            var blankDuringLoading=false
            withContext(Dispatchers.Main){picker.onSearchQueryChange(query);blankDuringLoading=picker.state.value.isLoading && picker.state.value.searchResults.isEmpty()}
            withTimeout(30000){picker.state.first{state->
                val pending=state.javaClass.methods.firstOrNull{it.name=="getSearchPending"}?.invoke(state) as? Boolean ?: false
                state.searchQuery==query && !state.isLoading && !pending && state.searchResults.isNotEmpty()
            }}
            val stateMs=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
            compose.waitForIdle()
            searchRows+=mapOf("query" to query,"stateMs" to stateMs,"renderIdleMs" to (SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0,
                "sqlStatements" to (queries.size-countBefore),"clearedForSpinner" to blankDuringLoading)
        }
        result["searches"]=searchRows;result["searchSql"]=queries.toList()
        val groupMs=mutableListOf<Double>()
        repeat(5){compose.runOnIdle{
            val start=SystemClock.elapsedRealtimeNanos()
            ProductSizeGroups.groups(picker.state.value.availableProducts,picker.state.value.availableProducts)
            groupMs+=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
        }}
        result["wholeCatalogueGroupingMainThreadMs"]=groupMs
        val catalogue=picker.state.value.availableProducts
        val ordinary=catalogue.filterNot{it.isPipe}.take(20)
        val addStart=SystemClock.elapsedRealtimeNanos()
        withContext(Dispatchers.Main){ordinary.forEach{assertTrue(billing.addSelection("perf-${it.id}",listOf(CartItem(it,2.0,it.sellingPricePaisa,it.sellingPricePaisa*2))))}}
        assertEquals(20,billing.state.value.cart.size)
        result["twentyAddsMs"]=(SystemClock.elapsedRealtimeNanos()-addStart)/1_000_000.0
        val pipes=catalogue.filter{it.isPipe}.take(3)
        withContext(Dispatchers.Main){pipes.forEach{
            picker.selectProduct(it);picker.selectSize(it)
            assertTrue(billing.addSelection("perf-pipe-${it.id}",listOf(PipeBilling.item(it,2,3.5))))
        };picker.dismissQuantityDialog()}
        pipes.forEach{p->val line=billing.state.value.cart.single{it.product.id==p.id};assertEquals(2L,line.pipeLengths);assertEquals(3.5,line.pipeFeet,0.0);assertEquals(PipeBilling.item(p,2,3.5).itemTotalPaisa,line.itemTotalPaisa)}
        result["pipeLineCount"]=pipes.size
        delay(700);val navSql=queries.size
        val returns=mutableListOf<Double>()
        repeat(5){
            compose.runOnIdle{showingCart=true};compose.waitForIdle()
            val start=SystemClock.elapsedRealtimeNanos()
            compose.runOnIdle{showingCart=false};compose.waitForIdle()
            returns+=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
            assertEquals("cpvc elbow",picker.state.value.searchQuery)
        }
        result["returnFromCartMs"]=returns;result["navigationSqlStatements"]=queries.size-navSql
        result["queryPlans"]=listOf("SELECT * FROM products WHERE isActive=1 ORDER BY name ASC","SELECT * FROM products WHERE typeId=1 AND isActive=1 ORDER BY size ASC,name ASC").associateWith{sql->
            db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use{cursor->buildList{while(cursor.moveToNext())add(cursor.getString(3))}}
        }
        recording.set(false)
        val frames=frameTimes.toList().sorted()
        result["framesObserved"]=frames.size
        if(frames.isNotEmpty()) {
            result["frameP50Ms"]=frames[frames.size/2];result["frameP95Ms"]=frames[((frames.size-1)*0.95).toInt()]
            result["framesOver16_67ms"]=frames.count{it>16.67};result["framesOver700ms"]=frames.count{it>700}
        }
        result["pssBeforeKiB"]=pssBefore;result["pssAfterKiB"]=Debug.MemoryInfo().apply{Debug.getMemoryInfo(this)}.totalPss
        result["heapBeforeBytes"]=heapBefore;result["heapAfterBytes"]=Runtime.getRuntime().let{it.totalMemory()-it.freeMemory()}
        result["firebaseListenersCreatedByPicker"]=0
        result["firebaseEvidence"]="ProductSelectionViewModel dependencies are Room repositories; FirebaseManager listeners are outside this picker. Live-account listeners not measured."
        File(compose.activity.getExternalFilesDir(null),"billing-performance.json").writeText(Gson().toJson(result))
        println("PERFORMANCE_RESULT "+Gson().toJson(result))
    }
}

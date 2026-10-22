package com.hardwareshop.pos

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.presentation.worker.product.ProductSelectionViewModel
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

class SearchLiveCatalogueTest {
    private lateinit var db:AppDatabase
    private lateinit var vm:ProductSelectionViewModel
    private val sql=ConcurrentLinkedQueue<String>()
    private var elbow=0L;private var socket=0L
    @Before fun setup():Unit=runBlocking {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java)
            .setQueryCallback(object:RoomDatabase.QueryCallback {override fun onQuery(sqlQuery:String,bindArgs:List<Any?>){sql.add(sqlQuery)}},Executor{it.run()}).build()
        val category=db.categoryDao().insertCategory(CategoryEntity(name="PVC"))
        val type=db.productTypeDao().insertType(ProductTypeEntity(categoryId=category,name="Fittings"))
        elbow=db.productDao().insertProduct(ProductEntity(categoryId=category,typeId=type,name="PVC Elbow",size="3/4 inch",unit="Piece",sellingPricePaisa=10000,currentStock=100.0))
        socket=db.productDao().insertProduct(ProductEntity(categoryId=category,typeId=type,name="PVC Socket",size="1/2 inch",unit="Piece",sellingPricePaisa=5000,currentStock=100.0))
        val actor=SessionHolder().apply{setSession(UserSession.OwnerSession("Live catalogue"))}
        val repo=ProductRepository(db,db.categoryDao(),db.productTypeDao(),db.productDao(),db.stockDao(),AuditRepository(db.auditLogDao(),actor),actor)
        withContext(Dispatchers.Main){vm=ProductSelectionViewModel(repo,db.settingsDao(),PipeOffcutRepository(db,actor))}
        withTimeout(15000){vm.state.first{it.catalogueIndex!=null && it.availableProducts.size==2}}
        delay(150);sql.clear()
    }
    @After fun cleanup():Unit=runBlocking{withContext(Dispatchers.Main){vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()};db.close()}
    private suspend fun query(value:String){withContext(Dispatchers.Main){vm.onSearchQueryChange(value)};withTimeout(15000){vm.state.first{it.searchQuery==value && !it.searchPending}}}
    @Test fun rapidChangesNeverPublishOlderResultsAndTypingDoesNotReadRoom():Unit=runBlocking {
        withContext(Dispatchers.Main){repeat(30){vm.onSearchQueryChange(if(it%2==0)"elbow" else "socket")};vm.onSearchQueryChange("3/4 elbow pvc")}
        withTimeout(15000){vm.state.first{!it.searchPending && it.searchQuery=="3/4 elbow pvc"}}
        assertEquals(listOf(elbow),vm.state.value.searchResults.map{it.id})
        assertFalse(vm.state.value.isLoading)
        assertTrue("Typing unexpectedly queried Room: $sql",sql.isEmpty())
        query("socket")
        assertEquals(listOf(socket),vm.state.value.searchResults.map{it.id})
        assertTrue(sql.isEmpty())
    }
    @Test fun currentQueryUpdatesAfterPriceEditDeactivationAndNewProductWithoutRetyping():Unit=runBlocking {
        query("elbow")
        val old=db.productDao().getProductById(elbow)!!
        db.productDao().updateProduct(old.copy(sellingPricePaisa=12500))
        withTimeout(15000){vm.state.first{!it.searchPending && it.searchResults.singleOrNull()?.sellingPricePaisa==12500L}}
        db.productDao().updateProduct(old.copy(isActive=false))
        withTimeout(15000){vm.state.first{!it.searchPending && it.searchResults.isEmpty() && it.availableProducts.none{p->p.id==elbow}}}
        val replacement=db.productDao().insertProduct(old.copy(id=0,sellingPricePaisa=15000))
        withTimeout(15000){vm.state.first{!it.searchPending && it.searchResults.singleOrNull()?.id==replacement}}
        assertEquals("elbow",vm.state.value.searchQuery)
    }
    @Test fun favouritesAndDraftSettingsDoNotReloadThePreparedCatalogue():Unit=runBlocking {
        val index=vm.state.value.catalogueIndex
        db.settingsDao().upsertSetting(SettingsEntity("cart:owner","test draft"))
        db.settingsDao().upsertSetting(SettingsEntity("billing-favourite:$elbow","1"))
        withTimeout(15000){vm.state.first{it.favouriteIds.contains(elbow)}}
        assertSame(index,vm.state.value.catalogueIndex)
        query("elbow");assertEquals(elbow,vm.state.value.searchResults.single().id)
    }
}

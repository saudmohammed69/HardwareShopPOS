package com.hardwareshop.pos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.*
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.sync.SyncJournal
import com.hardwareshop.pos.data.website.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.service.WebsiteOrderNotifications
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.zip.ZipInputStream

class WebsiteNativeIntegrationTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val fixtures=mutableListOf<WebsiteNativeFixture>()
    private fun fixture(device:String="device-a")=WebsiteNativeFixture(context,device).also{fixtures+=it}
    @After fun close(){fixtures.forEach{it.close()}}
    private suspend fun caught(block:suspend()->Unit):Boolean=try{block();false}catch(_:Exception){true}
    private suspend fun ready(repo:WebsiteOrdersRepository,id:String){repo.action(id,"prepare");val lines=JsonParser.parseString(repo.row(id).document).asJsonObject.getAsJsonArray("items");for(l in lines)repo.checkLine(id,l.asJsonObject.string("productId"),true);repo.action(id,"ready")}
    private suspend fun prepared(f:WebsiteNativeFixture):Pair<String,WebsiteHandoff>{
        f.seed();val repo=f.repository();repo.publishCatalog();repo.publishSettings(0,1440,false)
        val product=f.cache.orders().products().first{f.products().getProductById(it.localId)?.isPipe==false}
        val id=f.createOrder(product.publicId);repo.refresh();repo.action(id,"accept",availabilityChecked=true)
        ready(repo,id);return id to repo.prepareHandoff(id)
    }

    @Test fun publicProjectionHideShowAndCurrentPermissionsPreservePrivateFinancialRows()=runBlocking {
        val f=fixture();val original=f.seed();val repo=f.repository();assertEquals(3,repo.publishCatalog());repo.publishSettings(0,1440,false)
        val sent=f.transport.sentBodies.filter{it.first=="catalog/publish-batch"}.flatMap{it.second!!.getAsJsonArray("products").map{p->p.asJsonObject.getAsJsonObject("product")}}
        assertEquals(3,sent.size);for(p in sent){assertFalse(p.has("purchasePricePaisa"));assertFalse(p.has("currentStock"));assertFalse(p.has("passwordHash"));assertFalse(p.toString().contains("hidden-marker"))}
        val m=f.cache.orders().products().first{f.products().getProductById(it.localId)?.isPipe==false};repo.setPublished(m.publicId,false);assertFalse(f.cache.orders().product(m.publicId)!!.published)
        assertTrue(caught{f.createOrder(m.publicId)})
        repo.publishCatalog();assertFalse(f.cache.orders().product(m.publicId)!!.published)
        repo.setPublished(m.publicId,true);assertNotNull(f.createOrder(m.publicId))
        val worker=f.db.workerDao().insertWorker(WorkerEntity(name="Synthetic worker",username="native-worker",passwordHash="PRIVATE",passwordSalt="PRIVATE",maxDiscountPaisa=0))
        f.session.setSession(UserSession.WorkerSession(worker,"Synthetic worker","native-worker",0,true))
        assertTrue(caught{repo.publishCatalog()});assertTrue(caught{repo.setPublished(m.publicId,false)});assertTrue(caught{repo.publishSettings(0,1440,false)})
        repo.refresh();assertTrue(repo.snapshot().isNotEmpty())
        f.db.workerDao().updateWorker(f.db.workerDao().getWorkerById(worker)!!.copy(isActive=false));assertTrue(caught{repo.refresh()})
        f.session.clear();assertTrue(caught{repo.snapshot()})
        assertEquals(original.map{it.sellingPricePaisa to it.currentStock},f.db.productDao().getAllProducts().map{it.sellingPricePaisa to it.currentStock});assertTrue(f.db.billDao().getAllBills().isEmpty())
    }
    @Test fun incrementalCursorAndChecklistRollbackSurviveRestartAndNotificationsAreDeduplicated()=runBlocking {
        val f=fixture();f.seed();val repo=f.repository();repo.publishCatalog();repo.publishSettings(0,1440,false);val id=f.createOrder(f.cache.orders().products().first{f.products().getProductById(it.localId)?.isPipe==false}.publicId)
        val page=f.transport.request("pos/events?cursor=0",null,true)
        assertTrue(caught{repo.applyPage(page){throw IllegalStateException("Injected before cursor commit")}})
        assertNull(f.cache.orders().order(id));assertNull(f.cache.orders().cursor())
        repo.refresh();val first=repo.row(id);assertTrue(first.unread);assertTrue(WebsiteOrderNotifications.shouldAlert(first));repo.markAlerted(id);repo.markRead(id)
        repo.applyPage(page);assertFalse(repo.row(id).unread);assertFalse(WebsiteOrderNotifications.shouldAlert(repo.row(id)))
        assertTrue(caught{repo.action(id,"accept")});repo.action(id,"accept",availabilityChecked=true);repo.action(id,"prepare")
        assertTrue(caught{repo.action(id,"ready")});val product=JsonParser.parseString(repo.row(id).document).asJsonObject.getAsJsonArray("items")[0].asJsonObject.string("productId")
        repo.checkLine(id,product,true);val cursor=f.cache.orders().cursor()!!.sequence;f.reopen();val restarted=f.repository();assertTrue(restarted.row(id).checklist.contains(product));assertEquals(cursor,f.cache.orders().cursor()!!.sequence)
        f.transport.offline=true;assertTrue(caught{restarted.refresh()});assertNotNull(restarted.row(id));f.transport.offline=false;restarted.action(id,"ready");assertEquals("READY_FOR_PICKUP",restarted.row(id).status)
    }
    @Test fun concurrentInstallationsAndTenRealBillingRetriesProduceOneBillPaymentStockEffectAndHistoryLink()=runBlocking {
        val a=fixture("device-a");val b=fixture("device-b");val ap=a.seed();val bp=b.seed();val ar=a.repository();val br=b.repository();ar.publishCatalog();ar.publishSettings(0,1440,false)
        val mapping=a.cache.orders().products().first{it.localId==ap[0].id};b.replicateProduct(a,mapping.publicId,bp[0])
        val id=a.createOrder(mapping.publicId);ar.refresh();br.refresh();ar.action(id,"accept",availabilityChecked=true);ready(ar,id);br.refresh()
        val candidates=coroutineScope{listOf(a to ar,b to br).map{(f,r)->async{runCatching{r.prepareHandoff(id)}.getOrNull()?.let{Triple(f,r,it)}}}.awaitAll()}.filterNotNull()
        assertEquals(1,candidates.size);val (winner,repo,handoff)=candidates.single();val loser=if(winner===a)b else a
        val ids=coroutineScope{List(10){async{repo.commit(id,handoff.items,{checkout->winner.save(handoff.items,checkout)})}}.awaitAll()}.map{assertTrue(it.toString(),it is Result.Success);(it as Result.Success).data.id}
        assertEquals(1,ids.distinct().size);assertEquals(1,winner.db.billDao().getAllBills().size);assertEquals(1,winner.db.billDao().getAllPayments().size);assertEquals(18.0,winner.db.productDao().getProductById(handoff.items[0].product.id)!!.currentStock,0.0)
        assertEquals("COMPLETED",repo.row(id).status);assertTrue(repo.origin(id)!!.pendingCompletion);assertEquals(ids.singleOrNull()?:ids.first(),repo.billLink(id))
        winner.transport.loseComplete=true;assertTrue(caught{repo.flushCompletions()});assertTrue(repo.origin(id)!!.pendingCompletion)
        winner.reopen();val restarted=winner.repository();restarted.flushCompletions();restarted.refresh();assertFalse(restarted.origin(id)!!.pendingCompletion)
        val again=restarted.commit(id,handoff.items,{checkout->winner.save(handoff.items,checkout)});assertEquals(ids.first(),(again as Result.Success).data.id)
        val losing=loser.repository();losing.refresh();assertEquals("COMPLETED",losing.row(id).status);assertTrue(caught{losing.prepareHandoff(id)});assertNull(losing.billLink(id));assertTrue(loser.db.billDao().getAllBills().isEmpty())
        assertEquals(1,winner.billing().getBillsFiltered().size);assertEquals(handoff.items[0].product.sellingPricePaisa,winner.db.productDao().getProductById(handoff.items[0].product.id)!!.sellingPricePaisa)
    }
    @Test fun lostClaimResponseAndInjectedTransactionFailureAreRecoverableWithoutFinancialSideEffects()=runBlocking {
        val f=fixture();f.seed();val r=f.repository();r.publishCatalog();r.publishSettings(0,1440,false);val m=f.cache.orders().products().first{f.products().getProductById(it.localId)?.isPipe==false};val id=f.createOrder(m.publicId);r.refresh();r.action(id,"accept",availabilityChecked=true);ready(r,id)
        f.transport.loseClaim=true;assertTrue(caught{r.prepareHandoff(id)});val token=f.cache.orders().claim(id)!!.token
        f.reopen();val repo=f.repository();val h=repo.prepareHandoff(id);assertEquals(token,f.cache.orders().claim(id)!!.token)
        val failed=repo.commit(id,h.items,{key->f.save(h.items,key)},{throw IllegalStateException("Crash before origin/outbox commit")})
        assertTrue(failed is Result.Error);assertTrue(f.db.billDao().getAllBills().isEmpty());assertTrue(f.db.billDao().getAllPayments().isEmpty());assertEquals(20.0,f.db.productDao().getProductById(h.items.first().product.id)!!.currentStock,0.0);assertNull(repo.origin(id));assertEquals("BILLING_IN_PROGRESS",repo.row(id).status)
        f.transport.offline=true
        assertTrue(repo.commit(id,h.items,{key->f.save(h.items,key)}) is Result.Success);assertEquals("COMPLETED",repo.row(id).status);assertTrue(repo.origin(id)!!.pendingCompletion);assertTrue(caught{repo.flushCompletions()})
        f.transport.offline=false;repo.flushCompletions();assertFalse(repo.origin(id)!!.pendingCompletion)
    }
    @Test fun quantityChangesRequireCustomerConfirmationAndCurrentWorkerDiscountPermissionIsRetained()=runBlocking {
        val f=fixture();val (id,h)=prepared(f);val r=f.repository();val original=h.items.first()
        assertTrue(r.commit(id,listOf(original.copy(quantity=9.0)),{key->f.save(listOf(original.copy(quantity=9.0)),key)}) is Result.Error)
        val worker=f.db.workerDao().insertWorker(WorkerEntity(name="Worker",username="discount-worker",passwordHash="PRIVATE",passwordSalt="PRIVATE"))
        f.session.setSession(UserSession.WorkerSession(worker,"Worker","discount-worker",0,true))
        assertTrue(r.commit(id,h.items,{key->f.save(h.items,key,100)}) is Result.Error);assertTrue(f.db.billDao().getAllBills().isEmpty());assertEquals(20.0,f.db.productDao().getProductById(original.product.id)!!.currentStock,0.0)
        assertTrue(r.commit(id,h.items,{key->f.save(h.items,key)}) is Result.Success)
    }
    @Test fun accountingBackupIncludesOriginButNotClaimsAndRollbackRefusesToEraseAnExistingWebsiteBill()=runBlocking {
        val f=fixture();val (id,h)=prepared(f);val repo=f.repository();assertTrue(repo.commit(id,h.items,{key->f.save(h.items,key)}) is Result.Success)
        val uri=(f.backup().createBackup() as Result.Success).data
        val text=ZipInputStream(context.contentResolver.openInputStream(uri)).use{zip->var result="";while(true){val e=zip.nextEntry?:break;if(e.name=="backup.json")result=zip.readBytes().toString(Charsets.UTF_8)};result}
        assertTrue(text.contains(WebsiteOrdersRepository.originKey(id)));assertFalse(text.contains(f.cache.orders().claim(id)!!.token));assertFalse(text.contains("website-local-fixture"))
        val target=fixture("device-b");val restored=target.backup().restoreBackup(uri);assertTrue(restored.toString(),restored is Result.Success)
        val saved=target.repository().origin(id)!!;assertEquals(1,target.db.billDao().getAllBills().size);assertEquals(saved.billId,target.db.billDao().getAllBills().single().id)
        val before=f.db.billDao().getAllBills();assertTrue(f.backup().restoreBackup(uri) is Result.Error);assertEquals(before,f.db.billDao().getAllBills())
        assertEquals(11,f.db.openHelper.readableDatabase.version);assertEquals(1,f.cache.openHelper.readableDatabase.version)
    }
    @Test fun fixtureAndTenantGuardsFailBeforeNetworkAndArchivedCheckpointsCannotCreateBills()=runBlocking {
        val f=fixture();f.seed();val repo=f.repository();val before=f.transport.requests
        f.db.settingsDao().upsertSetting(SettingsEntity("website-local-fixture","WRONG"));assertTrue(caught{repo.refresh()});assertEquals(before,f.transport.requests)
        f.db.settingsDao().upsertSetting(SettingsEntity("website-local-fixture",f.fixtureId));val id=UUID.randomUUID().toString()
        repo.applyPage(json("events" to JsonArray().apply{add(json("id" to id,"seq" to 1,"revision" to 6,"status" to "COMPLETED","doc" to json("estimatedSubtotalPaisa" to "12345","archiveRef" to "a".repeat(64)),"updated_at" to 1))},"cursor" to 1))
        assertEquals("COMPLETED",repo.row(id).status);assertTrue(caught{repo.prepareHandoff(id)})
        SyncJournal(f.db).bindVerifiedStore("synthetic-other-shop");assertTrue(caught{repo.refresh()});assertEquals(before,f.transport.requests);assertTrue(f.db.billDao().getAllBills().isEmpty())
    }
    @Test fun nativePipeUsesPosRatesAndGstUpiWhileOrdinaryOfflineCounterBillingRemainsIndependent()=runBlocking {
        val f=fixture();val products=f.seed();val repo=f.repository();repo.publishCatalog();repo.publishSettings(0,1440,false)
        val p=products.first{it.isPipe};val m=f.cache.orders().products().first{it.localId==p.id}
        val body=json("submissionId" to UUID.randomUUID().toString(),"capability" to "c".repeat(64),"customer" to json("name" to "Pipe pickup","phone" to "9876543210"),"items" to JsonArray().apply{add(json("productId" to m.publicId,"fullLengths" to "1","extraFeet" to "3.5"))})
        val id=f.transport.request("fixture/orders",body,true).string("id");repo.refresh();repo.action(id,"accept",availabilityChecked=true);ready(repo,id);val h=repo.prepareHandoff(id)
        assertEquals(52250L,h.items.single().itemTotalPaisa);assertEquals(1.175,h.items.single().quantity,0.00000001)
        f.transport.offline=true
        val result=repo.commit(id,h.items,{checkout->f.billing().createBill(h.items,null,h.customerName,h.phone,0,"",true,PaymentMethod.UPI,52250,"",checkoutId=checkout)})
        assertTrue(result.toString(),result is Result.Success);assertEquals(52250L,(result as Result.Success).data.totalPaisa);assertTrue(result.data.gstAmountPaisa>0);assertEquals("UPI",f.db.billDao().getAllPayments().single().method);assertEquals(18.825,f.db.productDao().getProductById(p.id)!!.currentStock,0.00000001)
        val normal=products.first{!it.isPipe};val item=CartItem(normal,1.0,normal.sellingPricePaisa,normal.sellingPricePaisa)
        assertTrue(f.save(listOf(item),UUID.randomUUID().toString()) is Result.Success);assertEquals(2,f.db.billDao().getAllBills().size)
        f.transport.offline=false;repo.flushCompletions();assertFalse(repo.origin(id)!!.pendingCompletion)
    }
    @Test fun localNotificationFixtureHasIndependentChannelPersistentDedupAndNoCustomerData()=runBlocking {
        val f=fixture();f.seed();val repo=f.repository();repo.publishCatalog();repo.publishSettings(0,1440,false)
        val id=f.createOrder(f.cache.orders().products().first{f.products().getProductById(it.localId)?.isPipe==false}.publicId);repo.refresh();val row=repo.row(id)
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val beforePrint=manager.activeNotifications.filter{it.notification.channelId=="remote_print"}.map{it.key}
        val alerts=WebsiteOrderNotifications(context);assertTrue("Disposable emulator grants notifications for this fixture",alerts.permitted)
        try{
            assertTrue(alerts.show(row));repo.markAlerted(id);assertFalse(WebsiteOrderNotifications.shouldAlert(repo.row(id)))
            val posted=manager.activeNotifications.single{it.tag==id};assertEquals(WebsiteOrderNotifications.CHANNEL,posted.notification.channelId)
            assertFalse(posted.notification.extras.toString().contains("9876543210"));assertEquals(beforePrint,manager.activeNotifications.filter{it.notification.channelId=="remote_print"}.map{it.key})
        }finally{manager.cancel(id,0)}
    }
    @Test fun nativeConfirmedAdjustmentRecordsAuditClearsChecklistAndBillsOnlyTheConfirmedQuantity()=runBlocking {
        val f=fixture();val products=f.seed();val repo=f.repository();repo.publishCatalog();repo.publishSettings(0,1440,false)
        val m=f.cache.orders().products().first{it.localId==products[0].id};val id=f.createOrder(m.publicId);repo.refresh();repo.action(id,"accept",availabilityChecked=true);repo.action(id,"prepare");repo.checkLine(id,m.publicId,true)
        val items=JsonArray().apply{add(json("productId" to m.publicId,"quantity" to "3"))}
        assertTrue(caught{repo.adjust(id,items,"Customer requested three",false)})
        repo.adjust(id,items,"Customer requested three",true);val row=repo.row(id);assertEquals("ACCEPTED",row.status);assertEquals("[]",row.checklist)
        val doc=JsonParser.parseString(row.document).asJsonObject;assertEquals("3",doc.getAsJsonArray("items")[0].asJsonObject.string("quantity"));assertEquals("2",doc.getAsJsonArray("adjustments")[0].asJsonObject.getAsJsonArray("beforeItems")[0].asJsonObject.string("quantity"))
        ready(repo,id);val h=repo.prepareHandoff(id);assertEquals(3.0,h.items.single().quantity,0.0);assertTrue(repo.commit(id,h.items,{key->f.save(h.items,key)}) is Result.Success)
        assertEquals(17.0,f.db.productDao().getProductById(products[0].id)!!.currentStock,0.0)
    }
}

package com.hardwareshop.pos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.*
import com.hardwareshop.pos.data.website.*
import com.hardwareshop.pos.domain.model.Result
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Existing main repository and BillingRepository over the new signed shop
 * adapter. Only disposable Room databases and a private synthetic authority. */
class WebsitePairedIntegrationTest {
    @Test fun pairedOrdersUseExistingBillTransactionAndDurableOperationRecovery()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".debug")&&android.os.Build.HARDWARE in listOf("ranchu","goldfish"))
        val f=WebsiteNativeFixture(context);val key=WebsiteDeviceKey("hardwareshop.website.test.integrated.${UUID.randomUUID()}")
        val device=UUID.randomUUID().toString();val epoch=UUID.randomUUID().toString();key.ensureCreated()
        val rotationFile="website-test-paired-$device.json";var stagedAlias:String?=null;var succeeded=false
        fun raw(route:String,body:JsonObject,headers:Map<String,String> = emptyMap()):JsonObject {
            val c=URL("http://127.0.0.1:4318$route").openConnection() as HttpURLConnection
            try{c.requestMethod="POST";c.connectTimeout=3000;c.readTimeout=8000;c.doOutput=true;c.setRequestProperty("Content-Type","application/json");headers.forEach{(k,v)->c.setRequestProperty(k,v)};c.outputStream.use{it.write(body.toString().toByteArray())};assertTrue("HTTP ${c.responseCode}",c.responseCode in 200..299);return JsonParser.parseString(c.inputStream.bufferedReader().use{it.readText()}).asJsonObject}finally{c.disconnect()}
        }
        try{
            val control=JsonParser.parseString(File(context.noBackupFilesDir,"website-phase4g-control.json").readText()).asJsonObject.string("control")
            val grant=raw("/__fixture/bootstrap",json("deviceId" to device,"databaseEpoch" to epoch,"publicKey" to key.publicJwk()),mapOf("X-Synthetic-Control" to control))["ticket"]
            val redeem=json("ticket" to grant,"publicKey" to key.publicJwk());val time=(System.currentTimeMillis()/1000).toString();val nonce=UUID.randomUUID().toString();val operation=UUID.randomUUID().toString()
            val message=listOf("HWPOS-SHOP-DEVICE-V1","POST","https://proof.invalid/shops/proof-shop/pair/redeem",WebsiteDeviceKey.sha256(redeem.toString().toByteArray()),device,epoch,time,nonce,operation).joinToString("\n")
            raw("/shops/proof-shop/pair/redeem",redeem,mapOf("x-device-id" to device,"x-database-epoch" to epoch,"x-device-time" to time,"x-device-nonce" to nonce,"x-operation-id" to operation,"x-device-signature" to key.sign(message)))
            val record=PairedLocalWebsiteTransport.Record(device,epoch,f.fixtureId,key.fingerprint())
            var transport=PairedLocalWebsiteTransport(context,record,key)
            val source=f.seed();var repo=WebsiteOrdersRepository(f.db,f.cache,f.products(),f.billing(),f.session,transport)
            assertEquals(device,transport.request("pair/session").string("deviceId"))
            assertEquals(3,repo.publishCatalog());repo.publishSettings(0,1440,false)
            val product=f.cache.orders().products().first{it.localId==source.first().id}
            // A confirmed receipt closes one intent, not all future equal bodies.
            repo.setPublished(product.publicId,true);repo.setPublished(product.publicId,false);repo.setPublished(product.publicId,true)
            val visible=transport.request("catalog?after=").getAsJsonArray("products").first{it.asJsonObject.string("id")==product.publicId}.asJsonObject
            val beforePublish=visible.long("revision")
            assertEquals(3,repo.publishCatalog());assertEquals(3,repo.publishCatalog())
            fun revisionOf(page:JsonObject)=page.getAsJsonArray("products").first{it.asJsonObject.string("id")==product.publicId}.asJsonObject.long("revision")
            assertEquals(beforePublish+2,revisionOf(transport.request("catalog?after=")))
            // A committed response lost before journaling remains the SAME intent
            // when both repository and transport are recreated after restart.
            transport.afterResponseForLocalTest={ route -> if(route=="catalog/publish")throw java.io.IOException("Synthetic lost committed response") }
            assertTrue(runCatching{repo.setPublished(product.publicId,false)}.isFailure)
            val journalDirectory=File(context.noBackupFilesDir,"website-paired-operations-$device")
            val pending=journalDirectory.listFiles().orEmpty().first{file->runCatching{val j=JsonParser.parseString(file.readText()).asJsonObject;j.string("state")=="PENDING"&&j.string("route")=="catalog/publish"}.getOrDefault(false)}
            val pendingId=JsonParser.parseString(pending.readText()).asJsonObject.string("operation")
            val head=journalDirectory.listFiles().orEmpty().first{file->runCatching{JsonParser.parseString(file.readText()).asJsonObject.string("activeOperation")==pendingId}.getOrDefault(false)}
            assertTrue(pending.renameTo(File(pending.path+".bak")));assertTrue(head.renameTo(File(head.path+".bak")))
            val hiddenRevision=beforePublish+3
            transport=PairedLocalWebsiteTransport(context,record,key)
            repo=WebsiteOrdersRepository(f.db,f.cache,f.products(),f.billing(),f.session,transport)
            repo.setPublished(product.publicId,false);repo.setPublished(product.publicId,true)
            assertEquals(hiddenRevision+1,revisionOf(transport.request("catalog?after=")))
            coroutineScope {listOf(async{repo.setPublished(product.publicId,true)},async{repo.setPublished(product.publicId,true)}).awaitAll()}
            assertEquals(hiddenRevision+3,revisionOf(transport.request("catalog?after=")))
            val body=json("submissionId" to UUID.randomUUID().toString(),"capability" to "e".repeat(64),"customer" to json("name" to "Synthetic paired customer","phone" to "9876543210"),"items" to JsonArray().apply{add(json("productId" to product.publicId,"quantity" to "2"))})
            val id=raw("/shops/proof-shop/orders",body).string("id")
            repo.refresh();repo.action(id,"accept",availabilityChecked=true);repo.action(id,"prepare");repo.checkLine(id,product.publicId,true);repo.action(id,"ready")
            transport.afterResponseForLocalTest={route->if(route.endsWith("/billing-claim"))throw java.io.IOException("Synthetic lost committed claim response")}
            assertTrue(runCatching{repo.prepareHandoff(id)}.isFailure);assertEquals(0,f.db.billDao().getAllBills().size)
            f.reopen();transport=PairedLocalWebsiteTransport(context,record,key);repo=WebsiteOrdersRepository(f.db,f.cache,f.products(),f.billing(),f.session,transport)
            repo.refresh();val originalClaim=repo.row(id).serverBillId;assertNotNull(originalClaim)
            val h=repo.prepareHandoff(id);assertEquals(originalClaim,h.row.serverBillId)
            val bill=repo.commit(id,h.items,{checkout->f.save(h.items,checkout)})
            assertTrue(bill is Result.Success);assertEquals(1,f.db.billDao().getAllBills().size);assertEquals(1,f.db.billDao().getAllPayments().size)
            // Reopen both DBs and transport journal, then retry completion and
            // local commit. Original bill, payment and stock effect must survive.
            f.reopen();transport=PairedLocalWebsiteTransport(context,record,key);repo=WebsiteOrdersRepository(f.db,f.cache,f.products(),f.billing(),f.session,transport)
            repo.flushCompletions();repo.flushCompletions();repo.refresh()
            assertTrue(repo.commit(id,h.items,{checkout->f.save(h.items,checkout)}) is Result.Success)
            assertEquals("COMPLETED",repo.row(id).status);assertEquals(1,f.db.billDao().getAllBills().size);assertEquals(1,f.db.billDao().getAllPayments().size)
            assertEquals(18.0,f.db.productDao().getProductById(source.first().id)!!.currentStock,0.0);assertNotNull(repo.billLink(id))
            val worker=f.db.workerDao().insertWorker(com.hardwareshop.pos.data.local.entity.WorkerEntity(name="Paired local worker",username="paired-worker",passwordHash="SYNTHETIC",passwordSalt="SYNTHETIC"))
            f.session.setSession(com.hardwareshop.pos.util.UserSession.WorkerSession(worker,"Paired local worker","paired-worker",0,true))
            assertTrue(runCatching{repo.publishCatalog()}.isFailure);repo.refresh()
            f.db.workerDao().updateWorker(f.db.workerDao().getWorkerById(worker)!!.copy(isActive=false))
            assertTrue(runCatching{repo.refresh()}.isFailure)
            f.session.setSession(com.hardwareshop.pos.util.UserSession.OwnerSession("Synthetic paired owner"))
            val delegate=transport
            val switched=object:WebsiteTransport {
                override fun connection()=delegate.connection()
                override suspend fun request(route:String,body:JsonObject?,owner:Boolean):JsonObject {
                    val reply=delegate.request(route,body,owner)
                    if(route.startsWith("pos/events"))f.session.clear()
                    return reply
                }
            }
            val guarded=WebsiteOrdersRepository(f.db,f.cache,f.products(),f.billing(),f.session,switched)
            assertTrue(runCatching{guarded.refresh()}.isFailure)
            assertEquals(1,f.db.billDao().getAllBills().size)
            f.session.setSession(com.hardwareshop.pos.util.UserSession.OwnerSession("Synthetic paired owner"))
            assertTrue(runCatching{transport.request("pair/approve",json())}.isFailure)
            assertNull(PairedLocalWebsiteTransport(context,record.copy(fingerprint="0".repeat(64)),key).connection())
            var lifecycle=PairedWebsiteKeyLifecycle(context,rotationFile);lifecycle.initialize(record,key);stagedAlias=lifecycle.stage().stagedAlias
            assertNull(transport.connection())
            assertTrue(runCatching{lifecycle.recover{throw java.io.IOException("Synthetic process death after server rotation")}}.isFailure)
            lifecycle=PairedWebsiteKeyLifecycle(context,rotationFile);val recovered=lifecycle.recover()
            assertNull(recovered.stagedAlias);assertEquals(device,lifecycle.transport().request("pair/session").string("deviceId"))
            assertTrue(runCatching{transport.request("pair/session")}.isFailure)
            val current=lifecycle.transport();current.afterResponseForLocalTest={route->if(route=="catalog/publish")throw java.io.IOException("Synthetic uncertain publication before revocation")}
            val projection=json("product" to visible,"published" to true)
            assertTrue(runCatching{current.request("catalog/publish",projection)}.isFailure)
            raw("/__fixture/revoke",json("deviceId" to device),mapOf("X-Synthetic-Control" to control));current.afterResponseForLocalTest=null
            val revoked=runCatching{current.request("catalog/publish",projection)}.exceptionOrNull()
            assertTrue(revoked is WebsiteAuthorizationException&&(revoked as WebsiteAuthorizationException).status==403)
            assertTrue(journalDirectory.listFiles().orEmpty().any{file->runCatching{JsonParser.parseString(file.readText()).asJsonObject.string("state")=="PENDING"}.getOrDefault(false)})
            assertEquals(1,f.db.billDao().getAllBills().size);assertEquals(18.0,f.db.productDao().getProductById(source.first().id)!!.currentStock,0.0)
            succeeded=true
        }finally{if(succeeded){stagedAlias?.let{WebsiteDeviceKey(it).deleteForLocalTest()};key.deleteForLocalTest();File(context.noBackupFilesDir,rotationFile).delete();File(context.noBackupFilesDir,"website-pair-uncertain-$device.json").delete();val journal=File(context.noBackupFilesDir,"website-paired-operations-$device");journal.listFiles().orEmpty().forEach{it.delete()};journal.delete()};f.close(preserve=!succeeded)}
    }
}

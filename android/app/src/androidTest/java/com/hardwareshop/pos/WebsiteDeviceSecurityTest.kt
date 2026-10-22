package com.hardwareshop.pos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.*
import com.hardwareshop.pos.data.website.*
import com.hardwareshop.pos.domain.model.CartItem
import com.hardwareshop.pos.domain.model.Result
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.Signature
import java.util.UUID

class WebsiteDeviceSecurityTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val keys=mutableListOf<WebsiteDeviceKey>()
    private val files=mutableListOf<String>()
    private val fixtures=mutableListOf<WebsiteNativeFixture>()
    private fun key():Pair<String,WebsiteDeviceKey> {
        val alias="hardwareshop.website.test.${UUID.randomUUID()}"
        return alias to WebsiteDeviceKey(alias).also{keys+=it;it.ensureCreated()}
    }
    private fun store(key:WebsiteDeviceKey):Pair<String,WebsiteEnrollmentStore> {
        val name="website-test-${UUID.randomUUID()}.json";files+=name
        return name to WebsiteEnrollmentStore(context,key,name)
    }
    @After fun close(){fixtures.forEach{it.close()};keys.forEach{it.deleteForLocalTest()};files.forEach{File(context.noBackupFilesDir,it).delete()}}
    @Test fun keystoreKeyIsNonExportableAndProofSignatureInteroperates() {
        val (alias,key)=key();val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        assertNull(ks.getKey(alias,null).encoded)
        val message=WebsiteDeviceKey.proofMessage("POST","http://127.0.0.1:4318/test","{}".toByteArray(),"synthetic-token",UUID.randomUUID().toString(),1,1234567890L,UUID.randomUUID().toString())
        val raw=android.util.Base64.decode(key.sign(message),android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        assertEquals(64,raw.size)
        fun integer(bytes:ByteArray):ByteArray {val value=bytes.dropWhile{it==0.toByte()}.toByteArray().let{if(it.isEmpty())byteArrayOf(0)else it};val positive=if(value[0].toInt()<0)byteArrayOf(0)+value else value;return byteArrayOf(2,positive.size.toByte())+positive}
        val pair=integer(raw.copyOfRange(0,32))+integer(raw.copyOfRange(32,64))
        val der=byteArrayOf(0x30,pair.size.toByte())+pair
        assertTrue(Signature.getInstance("SHA256withECDSA").apply{initVerify(ks.getCertificate(alias).publicKey);update(message.toByteArray())}.verify(der))
        assertEquals(key.fingerprint(),WebsiteDeviceKey(alias).fingerprint())
    }
    @Test fun interruptedEnrollmentMetadataSurvivesRestartButRestoredMetadataCannotAuthorizeANewInstallation() {
        val (_,key)=key();val (name,store)=store(key);val pending=store.begin()
        assertEquals(pending,WebsiteEnrollmentStore(context,key,name).begin())
        val device=UUID.randomUUID().toString();store.rememberResponse(pending.requestId,device,1)
        assertEquals(device,WebsiteEnrollmentStore(context,key,name).load()!!.deviceId)
        key.deleteForLocalTest();assertNull(store.load());key.ensureCreated();assertNull(store.load())
        val reinstalled=store.begin();assertNotEquals(pending.requestId,reinstalled.requestId);assertNotEquals(pending.fingerprint,reinstalled.fingerprint);assertNull(reinstalled.deviceId)
        val contents=File(context.noBackupFilesDir,name).readText();assertFalse(contents.contains("token"));assertFalse(contents.contains("PRIVATE"))
    }
    private fun control(route:String,body:JsonObject):JsonObject {
        val secret=JsonParser.parseString(File(context.noBackupFilesDir,"website-phase4b-control.json").readText()).asJsonObject["control"].asString
        val c=URL("http://127.0.0.1:4318/__fixture/$route").openConnection() as HttpURLConnection
        try {c.requestMethod="POST";c.connectTimeout=3000;c.readTimeout=8000;c.doOutput=true;c.setRequestProperty("X-Synthetic-Control",secret);c.setRequestProperty("Content-Type","application/json")
            c.outputStream.use{it.write(body.toString().toByteArray())};check(c.responseCode in 200..299){"Synthetic control fixture failed"}
            return JsonParser.parseString(c.inputStream.bufferedReader().use{it.readText()}).asJsonObject
        }finally{c.disconnect()}
    }
    @Test fun stagedRotationIsDurableAndReinstallCannotReuseItsMetadata()=runBlocking {
        val (_,key)=key();val name="website-test-${UUID.randomUUID()}.json";files+=name
        val store=WebsiteKeyRotationStore(context,name);val id=UUID.randomUUID().toString()
        store.initialize(id,key);val staged=store.stage();keys+=WebsiteDeviceKey(staged.stagedAlias!!)
        assertEquals(staged,WebsiteKeyRotationStore(context,name).load())
        assertTrue(runCatching{store.transport(UUID.randomUUID().toString()){"synthetic"}}.isFailure)
        assertTrue(runCatching{store.recover(UUID.randomUUID().toString(),{throw java.io.IOException("Synthetic network loss before send")})}.isFailure)
        assertEquals(staged,store.load());assertTrue(key.exists())
        WebsiteDeviceKey(staged.stagedAlias!!).deleteForLocalTest()
        assertTrue(runCatching{WebsiteKeyRotationStore(context,name).load()}.isFailure)
        assertTrue(key.exists()) // No automatic key deletion, new identity or fallback.
    }
    @Test fun signedWorkerRotationRecoversLostResponseAndRestartThenRevocationPreservesUncertainty()=runBlocking {
        val (_,key)=key();val (_,enrollment)=store(key);val request=enrollment.begin()
        val jwt=withContext(Dispatchers.IO){control("token",json("uid" to "synthetic-worker")).string("token")}
        val pending=SignedLocalWebsiteTransport.enroll(key,request.requestId,jwt)
        val id=pending.string("deviceId")
        withContext(Dispatchers.IO){control("approve",json("deviceId" to id,"expectedVersion" to 1))}
        val name="website-test-${UUID.randomUUID()}.json";files+=name
        val store=WebsiteKeyRotationStore(context,name);store.initialize(id,key)
        val first=store.stage();keys+=WebsiteDeviceKey(first.stagedAlias!!)
        val fixture=UUID.randomUUID().toString()
        assertTrue(runCatching{store.recover(fixture,{jwt}){throw java.io.IOException("Synthetic lost rotation response before promotion")}}.isFailure)
        assertEquals(first,store.load());assertTrue(key.exists())
        assertTrue(runCatching{SignedLocalWebsiteTransport(fixture,id,key){jwt}.request("auth/session")}.isFailure)
        val restarted=WebsiteKeyRotationStore(context,name)
        val recovered=restarted.recover(fixture,{jwt});assertEquals(2,recovered.version);assertNull(recovered.stagedAlias)
        assertEquals("WORKER",restarted.transport(fixture){jwt}.request("auth/session").string("role"))
        assertTrue(runCatching{restarted.transport(fixture){jwt}.request("owner/public-config",json("name" to "Unauthorized"),true)}.isFailure)
        val second=restarted.stage();keys+=WebsiteDeviceKey(second.stagedAlias!!)
        assertTrue(runCatching{restarted.recover(fixture,{throw java.io.IOException("Synthetic offline before rotation")})}.isFailure)
        assertEquals(3,restarted.recover(fixture,{jwt}).version)
        val third=restarted.stage();keys+=WebsiteDeviceKey(third.stagedAlias!!)
        withContext(Dispatchers.IO){control("revoke",json("deviceId" to id))}
        assertTrue(runCatching{WebsiteKeyRotationStore(context,name).recover(fixture,{jwt})}.isFailure)
        assertEquals(third,WebsiteKeyRotationStore(context,name).load())
        assertTrue(WebsiteDeviceKey(third.alias).exists());assertTrue(WebsiteDeviceKey(third.stagedAlias!!).exists())
    }
    @Test fun beginLostRotationForSeparateProcessRecovery()=runBlocking {
        val name="website-test-phase4c-process-rotation.json"
        check(!File(context.noBackupFilesDir,name).exists()){"Previous process-recovery fixture requires review"}
        // Deliberately retained only for the next isolated instrumentation process.
        val key=WebsiteDeviceKey("hardwareshop.website.test.process."+UUID.randomUUID());key.ensureCreated()
        val jwt=withContext(Dispatchers.IO){control("token",json("uid" to "synthetic-worker")).string("token")}
        val pending=SignedLocalWebsiteTransport.enroll(key,UUID.randomUUID().toString(),jwt)
        val id=pending.string("deviceId");withContext(Dispatchers.IO){control("approve",json("deviceId" to id,"expectedVersion" to 1))}
        val rotation=WebsiteKeyRotationStore(context,name);rotation.initialize(id,key);val staged=rotation.stage()
        assertTrue(runCatching{rotation.recover(UUID.randomUUID().toString(),{jwt}){throw java.io.IOException("Synthetic lost response before durable promotion")}}.isFailure)
        assertEquals(staged,rotation.load())
    }
    @Test fun finishLostRotationFromPreviousInstrumentationProcess()=runBlocking {
        val name="website-test-phase4c-process-rotation.json";files+=name
        val store=WebsiteKeyRotationStore(context,name);val staged=store.load()
        keys+=WebsiteDeviceKey(staged.alias);keys+=WebsiteDeviceKey(staged.stagedAlias!!)
        val jwt=withContext(Dispatchers.IO){control("token",json("uid" to "synthetic-worker")).string("token")}
        val recovered=store.recover(UUID.randomUUID().toString(),{jwt});assertEquals(2,recovered.version);assertNull(recovered.stagedAlias)
        assertEquals(staged.deviceId,recovered.deviceId)
        withContext(Dispatchers.IO){control("revoke",json("deviceId" to recovered.deviceId))}
        assertEquals(2,store.load().version)
    }
    @Test fun individualWorkerBindingGuardsHandoffAndRejectsSwitchRestoreAndRevocation()=runBlocking {
        val (_,key)=key();val (_,enrollment)=store(key);val request=enrollment.begin()
        val jwt=withContext(Dispatchers.IO){control("token",json("uid" to "synthetic-worker")).string("token")}
        val pending=SignedLocalWebsiteTransport.enroll(key,request.requestId,jwt);val id=pending.string("deviceId")
        withContext(Dispatchers.IO){control("approve",json("deviceId" to id,"expectedVersion" to 1))}
        val signed=SignedLocalWebsiteTransport(UUID.randomUUID().toString(),id,key){jwt}
        var account=com.hardwareshop.pos.data.local.entity.WorkerEntity(id=77,name="Synthetic worker",username="fixture-individual",passwordHash="unused",passwordSalt="unused",createdAt=100,updatedAt=200)
        val local=com.hardwareshop.pos.util.SessionHolder()
        fun select(){local.setSession(com.hardwareshop.pos.util.UserSession.WorkerSession(account.id,account.name,account.username,account.maxDiscountPaisa,account.requireOwnerApprovalForDiscount))}
        select();val epoch=UUID.randomUUID().toString();var handoffs=0
        val gate=WebsiteWorkerIdentityGate(local,signed,epoch){workerId->account.takeIf{it.id==workerId}}
        assertTrue(runCatching{gate.handoff{handoffs++}}.isFailure);assertEquals(0,handoffs)
        withContext(Dispatchers.IO){control("bind-worker",json("deviceId" to id,"uid" to "synthetic-worker","personId" to "person-synthetic-worker","workerRef" to "worker-synthetic-worker","databaseEpoch" to epoch,"accountHash" to WebsiteWorkerIdentityGate.accountHash(account),"expectedMembershipVersion" to 1))}
        gate.handoff{handoffs++};assertEquals(1,handoffs)
        account=account.copy(updatedAt=201);assertTrue(runCatching{gate.handoff{handoffs++}}.isFailure)
        account=account.copy(updatedAt=200)
        assertTrue(runCatching{WebsiteWorkerIdentityGate(local,signed,UUID.randomUUID().toString()){account}.handoff{handoffs++}}.isFailure)
        val switched=object:WebsiteTransport{
            override fun connection()=signed.connection()
            override suspend fun request(route:String,body:JsonObject?,owner:Boolean):JsonObject = signed.request(route,body,owner).also{local.clear()}
        }
        assertTrue(runCatching{WebsiteWorkerIdentityGate(local,switched,epoch){account}.handoff{handoffs++}}.isFailure)
        select();withContext(Dispatchers.IO){control("revoke",json("deviceId" to id))}
        assertTrue(runCatching{gate.handoff{handoffs++}}.isFailure);assertEquals(1,handoffs)
        // This method verifies the gate callback, not a production sign-in change.
        assertTrue(local.isLoggedIn)
    }
    @Test fun androidSignedEnrollmentApprovalBillingRetriesRevocationAndOrdinaryOfflineBilling()=runBlocking {
        val (_,key)=key();val (_,store)=store(key);val request=store.begin()
        val jwt=withContext(Dispatchers.IO){control("token",json("uid" to "synthetic-owner")).string("token")}
        val first=SignedLocalWebsiteTransport.enroll(key,request.requestId,jwt)
        val repeated=SignedLocalWebsiteTransport.enroll(key,request.requestId,jwt)
        assertEquals("PENDING",first.string("status"));assertEquals(first.string("deviceId"),repeated.string("deviceId"))
        val record=store.rememberResponse(request.requestId,first.string("deviceId"),1)
        val f=WebsiteNativeFixture(context).also{fixtures+=it};val products=f.seed()
        val signed=SignedLocalWebsiteTransport(f.fixtureId,record.deviceId!!,key){jwt}
        assertTrue(runCatching{signed.request("auth/session")}.isFailure)
        withContext(Dispatchers.IO){control("approve",json("deviceId" to record.deviceId,"expectedVersion" to 1))}
        assertEquals("OWNER",signed.request("auth/session").string("role"))
        val wrapper=object:WebsiteTransport {
            var offline=false;var loseComplete=true
            override fun connection()=signed.connection()
            override suspend fun request(route:String,body:JsonObject?,owner:Boolean):JsonObject {
                check(!offline){"Synthetic disconnected"};val result=signed.request(route,body,owner)
                if(route.endsWith("complete")&&loseComplete){loseComplete=false;throw java.io.IOException("Synthetic lost response")};return result
            }
        }
        fun repository()=WebsiteOrdersRepository(f.db,f.cache,f.products(),f.billing(),f.session,wrapper)
        val repo=repository();assertEquals(3,repo.publishCatalog());repo.publishSettings(0,1440,false)
        val mapping=f.cache.orders().products().first{it.localId==products[0].id}
        val body=json("submissionId" to UUID.randomUUID().toString(),"capability" to "d".repeat(64),"customer" to json("name" to "Signed synthetic customer","phone" to "9876543210"),"items" to JsonArray().apply{add(json("productId" to mapping.publicId,"quantity" to "2"))})
        val id=withContext(Dispatchers.IO){control("orders",body).string("id")}
        repo.refresh();repo.action(id,"accept",availabilityChecked=true);repo.action(id,"prepare");repo.checkLine(id,mapping.publicId,true);repo.action(id,"ready")
        val h=repo.prepareHandoff(id)
        wrapper.offline=true
        val bills=List(3){repo.commit(id,h.items,{checkout->f.save(h.items,checkout)})}
        assertTrue(bills.all{it is Result.Success});assertEquals(1,f.db.billDao().getAllBills().size);assertEquals(1,f.db.billDao().getAllPayments().size)
        assertEquals(18.0,f.db.productDao().getProductById(products[0].id)!!.currentStock,0.0)
        wrapper.offline=false;assertTrue(runCatching{repo.flushCompletions()}.isFailure)
        f.reopen();val restarted=repository();restarted.flushCompletions();restarted.refresh()
        assertFalse(restarted.origin(id)!!.pendingCompletion);assertEquals("COMPLETED",restarted.row(id).status);assertEquals(1,f.billing().getBillsFiltered().size)
        withContext(Dispatchers.IO){control("revoke",json("deviceId" to record.deviceId))}
        assertTrue(runCatching{restarted.refresh()}.isFailure)
        val normal=products[0];val item=CartItem(normal,1.0,normal.sellingPricePaisa,normal.sellingPricePaisa)
        assertTrue(f.save(listOf(item),UUID.randomUUID().toString()) is Result.Success);assertEquals(2,f.db.billDao().getAllBills().size)
        assertEquals(11,f.db.openHelper.readableDatabase.version)
    }
}

package com.hardwareshop.pos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.*
import com.hardwareshop.pos.data.website.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Select each method in a separate instrumentation process, with force-stop
 * between them. Never reset uncertain metadata to manufacture a pass. */
class WebsitePairedProcessRecoveryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>().also{check(it.packageName.endsWith(".debug")&&android.os.Build.HARDWARE in listOf("ranchu","goldfish"))}
    private val fileName="website-test-paired-process.json"
    @Test fun beginCommittedRotationBeforeProcessDeath()=runBlocking {
        check(!WebsiteRestoreQuarantine.hasAtomicState(context,fileName)){"Uncertain previous rotation requires review"}
        val key=WebsiteDeviceKey("hardwareshop.website.test.process.${UUID.randomUUID()}");key.ensureCreated()
        val device=UUID.randomUUID().toString();val epoch=UUID.randomUUID().toString();val fixture=UUID.randomUUID().toString()
        fun post(route:String,body:JsonObject,headers:Map<String,String>):JsonObject {
            val c=URL("http://127.0.0.1:4318$route").openConnection() as HttpURLConnection
            try{c.requestMethod="POST";c.connectTimeout=3000;c.readTimeout=8000;c.doOutput=true;c.setRequestProperty("Content-Type","application/json");headers.forEach{(k,v)->c.setRequestProperty(k,v)};c.outputStream.use{it.write(body.toString().toByteArray())};assertEquals(200,c.responseCode);return JsonParser.parseString(c.inputStream.bufferedReader().use{it.readText()}).asJsonObject}finally{c.disconnect()}
        }
        val control=JsonParser.parseString(File(context.noBackupFilesDir,"website-phase4g-control.json").readText()).asJsonObject.string("control")
        val ticket=post("/__fixture/bootstrap",json("deviceId" to device,"databaseEpoch" to epoch,"publicKey" to key.publicJwk()),mapOf("X-Synthetic-Control" to control))["ticket"]
        val body=json("ticket" to ticket,"publicKey" to key.publicJwk())
        val nonce=UUID.randomUUID().toString();val operation=UUID.randomUUID().toString();val time=(System.currentTimeMillis()/1000).toString()
        val message=listOf("HWPOS-SHOP-DEVICE-V1","POST","https://proof.invalid/shops/proof-shop/pair/redeem",WebsiteDeviceKey.sha256(body.toString().toByteArray()),device,epoch,time,nonce,operation).joinToString("\n")
        post("/shops/proof-shop/pair/redeem",body,mapOf("x-device-id" to device,"x-database-epoch" to epoch,"x-device-time" to time,"x-device-nonce" to nonce,"x-operation-id" to operation,"x-device-signature" to key.sign(message)))
        val record=PairedLocalWebsiteTransport.Record(device,epoch,fixture,key.fingerprint(),keyAlias=key.alias)
        val lifecycle=PairedWebsiteKeyLifecycle(context,fileName);lifecycle.initialize(record,key)
        val publication=json("product" to json("id" to "process-recovery-product","name" to "Synthetic process recovery","category" to "Test","type" to "Fixture","size" to "one","unit" to "Piece","sellingPricePaisa" to "100","gstRateBp" to 0),"published" to true)
        val transport=lifecycle.transport();transport.afterResponseForLocalTest={route->if(route=="catalog/publish")throw java.io.IOException("Synthetic death after committed publication")}
        assertTrue(runCatching{transport.request("catalog/publish",publication)}.isFailure)
        lifecycle.stage()
        assertTrue(runCatching{lifecycle.recover{throw java.io.IOException("Synthetic death boundary after committed rotation")}}.isFailure)
        assertNotNull(lifecycle.load().stagedAlias)
    }
    @Test fun recoverCommittedRotationInAnotherProcess()=runBlocking {
        val lifecycle=PairedWebsiteKeyLifecycle(context,fileName);val old=lifecycle.load();assertNotNull(old.stagedAlias)
        var succeeded=false
        try{val recovered=lifecycle.recover();assertNull(recovered.stagedAlias);val transport=lifecycle.transport();assertEquals(old.record.deviceId,transport.request("pair/session").string("deviceId"))
            val publication=json("product" to json("id" to "process-recovery-product","name" to "Synthetic process recovery","category" to "Test","type" to "Fixture","size" to "one","unit" to "Piece","sellingPricePaisa" to "100","gstRateBp" to 0),"published" to true)
            transport.request("catalog/publish",publication)
            fun revision(page:JsonObject)=page.getAsJsonArray("products").first{it.asJsonObject.string("id")=="process-recovery-product"}.asJsonObject.long("revision")
            assertEquals(1L,revision(transport.request("catalog?after=")))
            transport.request("catalog/publish",publication);assertEquals(2L,revision(transport.request("catalog?after=")))
            succeeded=true
        }
        finally{
          if(succeeded){
            old.stagedAlias?.let{WebsiteDeviceKey(it).deleteForLocalTest()};WebsiteDeviceKey(old.alias).deleteForLocalTest()
            File(context.noBackupFilesDir,fileName).delete();File(context.noBackupFilesDir,"website-pair-uncertain-${old.record.deviceId}.json").delete()
            val journal=File(context.noBackupFilesDir,"website-paired-operations-${old.record.deviceId}");journal.listFiles().orEmpty().forEach{it.delete()};journal.delete()
          }
        }
    }
}

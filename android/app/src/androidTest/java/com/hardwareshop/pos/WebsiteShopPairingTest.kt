package com.hardwareshop.pos

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.google.gson.*
import com.hardwareshop.pos.data.website.WebsiteDeviceKey
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID

/** Synthetic emulator-only protocol acceptance; no app login or live DB access. */
class WebsiteShopPairingTest {
    @Test fun keystoreShopPairingApprovalRevocationAndReinstallProof() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".debug") && android.os.Build.HARDWARE in listOf("ranchu","goldfish"))
        val owner=WebsiteDeviceKey("hardwareshop.website.test.pair.owner.${UUID.randomUUID()}")
        val worker=WebsiteDeviceKey("hardwareshop.website.test.pair.worker.${UUID.randomUUID()}")
        val ownerId=UUID.randomUUID().toString();val workerId=UUID.randomUUID().toString()
        val ownerEpoch=UUID.randomUUID().toString();val workerEpoch=UUID.randomUUID().toString()
        owner.ensureCreated();worker.ensureCreated()
        fun json(vararg pairs:Pair<String,Any>)=JsonObject().apply{pairs.forEach{(k,v)->when(v){is JsonElement->add(k,v);else->addProperty(k,v.toString())}}}
        fun request(route:String,body:JsonObject?,key:WebsiteDeviceKey?,id:String="",epoch:String="",control:String?=null):Pair<Int,JsonObject> {
            val method=if(body==null)"GET" else "POST";val bytes=body?.toString()?.toByteArray()?:byteArrayOf()
            val connection=URL("http://127.0.0.1:4318$route").openConnection() as HttpURLConnection
            try {
                connection.requestMethod=method;connection.connectTimeout=3000;connection.readTimeout=8000
                if(control!=null)connection.setRequestProperty("X-Synthetic-Control",control)
                if(key!=null){
                    val time=(System.currentTimeMillis()/1000).toString();val nonce=UUID.randomUUID().toString();val operation=UUID.randomUUID().toString()
                    val message=listOf("HWPOS-SHOP-DEVICE-V1",method,"https://proof.invalid$route",WebsiteDeviceKey.sha256(bytes),id,epoch,time,nonce,operation).joinToString("\n")
                    mapOf("x-device-id" to id,"x-database-epoch" to epoch,"x-device-time" to time,"x-device-nonce" to nonce,"x-operation-id" to operation,"x-device-signature" to key.sign(message)).forEach{(k,v)->connection.setRequestProperty(k,v)}
                }
                if(body!=null){connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use{it.write(bytes)}}
                val status=connection.responseCode;val stream=if(status<400)connection.inputStream else connection.errorStream
                return status to JsonParser.parseString(stream.bufferedReader().use{it.readText()}).asJsonObject
            }finally{connection.disconnect()}
        }
        try {
            val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};assertNull(ks.getKey(owner.alias,null).encoded)
            val control=JsonParser.parseString(File(context.noBackupFilesDir,"website-phase4g-control.json").readText()).asJsonObject["control"].asString
            val grant=request("/__fixture/bootstrap",json("deviceId" to ownerId,"databaseEpoch" to ownerEpoch,"publicKey" to owner.publicJwk()),null,control=control).second["ticket"]
            assertEquals(200,request("/shops/proof-shop/pair/redeem",json("ticket" to grant,"publicKey" to owner.publicJwk()),owner,ownerId,ownerEpoch).first)
            val permissions=JsonArray().apply{add("ORDERS_READ");add("ORDERS_WRITE");add("BILLING")}
            val approved=request("/shops/proof-shop/pair/approve",json("deviceId" to workerId,"databaseEpoch" to workerEpoch,"publicKey" to worker.publicJwk(),"permissions" to permissions),owner,ownerId,ownerEpoch)
            assertEquals(200,approved.first)
            val redeem=json("ticket" to approved.second["ticket"],"publicKey" to worker.publicJwk())
            assertEquals(200,request("/shops/proof-shop/pair/redeem",redeem,worker,workerId,workerEpoch).first)
            assertEquals(403,request("/shops/proof-shop/pair/redeem",redeem,worker,workerId,workerEpoch).first)
            assertEquals("WORKER",request("/shops/proof-shop/pair/session",null,worker,workerId,workerEpoch).second["role"].asString)
            assertEquals(200,request("/shops/proof-shop/pair/revoke",json("deviceId" to workerId),owner,ownerId,ownerEpoch).first)
            assertEquals(403,request("/shops/proof-shop/pair/session",null,worker,workerId,workerEpoch).first)
            worker.deleteForLocalTest();worker.ensureCreated()
            assertEquals(403,request("/shops/proof-shop/pair/session",null,worker,workerId,workerEpoch).first)
        } finally {owner.deleteForLocalTest();worker.deleteForLocalTest()}
    }
}

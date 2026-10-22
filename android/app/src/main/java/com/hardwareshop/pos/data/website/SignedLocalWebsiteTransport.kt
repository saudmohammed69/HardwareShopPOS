package com.hardwareshop.pos.data.website

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hardwareshop.pos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Local-only adapter for the production-oriented signature protocol. It is
 * never selected by Hilt, never reads Firebase credentials, and cannot contact
 * an arbitrary host. The server, not the local owner boolean, decides roles. */
class SignedLocalWebsiteTransport(
    private val fixtureDatabaseId:String,
    private val deviceId:String,
    private val key:WebsiteDeviceKey,
    private val keyVersion:Int=1,
    private val tokenSource:suspend ()->String
):WebsiteTransport {
    init { UUID.fromString(fixtureDatabaseId);UUID.fromString(deviceId);require(keyVersion>0) }
    override fun connection():WebsiteFixtureConnection? = if(BuildConfig.DEBUG&&key.exists())
        WebsiteFixtureConnection(deviceId,fixtureDatabaseId,"","") else null
    override suspend fun request(route:String,body:JsonObject?,owner:Boolean):JsonObject = withContext(Dispatchers.IO) {
        checkNotNull(connection()){"Signed local website connection is unavailable"}
        require(route.matches(Regex("[A-Za-z0-9/?=&_-]{1,200}")))
        send(route,body,key,deviceId,keyVersion,tokenSource())
    }
    companion object {
        suspend fun rotate(oldKey:WebsiteDeviceKey,newKey:WebsiteDeviceKey,device:String,version:Int,idToken:String):JsonObject=withContext(Dispatchers.IO){
            UUID.fromString(device);require(version>0)
            val nonce=UUID.randomUUID().toString()
            val body=JsonObject().apply{
                add("publicKey",newKey.publicJwk())
                addProperty("rotationSignature",newKey.sign(listOf("HWPOS-ROTATE-V1",device,(version+1).toString(),newKey.fingerprint(),nonce).joinToString("\n")))
            }
            send("auth/rotate",body,oldKey,device,version,idToken,nonce)
        }
        /** Explicit test enrollment. Persist requestId/key before sending; retry
         * with the same requestId and a fresh proof after a lost response. */
        suspend fun enroll(key:WebsiteDeviceKey,requestId:String,idToken:String):JsonObject=withContext(Dispatchers.IO){
            UUID.fromString(requestId);check(BuildConfig.DEBUG);key.ensureCreated()
            val body=JsonObject().apply{addProperty("requestId",requestId);add("publicKey",key.publicJwk())}
            send("auth/enroll",body,key,"enroll:"+key.fingerprint(),1,idToken)
        }
        private fun send(route:String,body:JsonObject?,key:WebsiteDeviceKey,device:String,keyVersion:Int,idToken:String,nonce:String=UUID.randomUUID().toString()):JsonObject {
            check(BuildConfig.DEBUG);require(idToken.length in 1..8192)
            val method=if(body==null)"GET" else "POST"
            val bytes=(body?.toString()?:"").toByteArray(Charsets.UTF_8)
            require(bytes.size<=32768)
            val url="http://127.0.0.1:4318/shops/proof-shop/$route"
            val time=System.currentTimeMillis()/1000
            val signature=key.sign(WebsiteDeviceKey.proofMessage(method,url,bytes,idToken,device,keyVersion,time,nonce))
            val connection=URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod=method;connection.instanceFollowRedirects=false
                connection.connectTimeout=3000;connection.readTimeout=8000
                connection.setRequestProperty("Authorization","Bearer $idToken")
                connection.setRequestProperty("X-Device-Id",device);connection.setRequestProperty("X-Device-Key-Version",keyVersion.toString())
                connection.setRequestProperty("X-Device-Time",time.toString());connection.setRequestProperty("X-Device-Nonce",nonce)
                connection.setRequestProperty("X-Device-Signature",signature)
                if(body!=null){connection.setRequestProperty("Content-Type","application/json");connection.doOutput=true;connection.outputStream.use{it.write(bytes)}}
                val code=connection.responseCode
                val stream=if(code in 200..299)connection.inputStream else connection.errorStream
                val response=stream?.use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=2*1024*1024);out.write(buffer,0,n)};out.toByteArray()}?:byteArrayOf()
                // Do not include raw response bodies, tokens or signature material in exceptions/logs.
                if(code !in 200..299)throw WebsiteAuthorizationException(code)
                return JsonParser.parseString(response.toString(Charsets.UTF_8)).asJsonObject
            }finally{connection.disconnect()}
        }
    }
}

class WebsiteAuthorizationException(val status:Int):java.io.IOException("Website authentication or operation failed ($status); recover the original operation")

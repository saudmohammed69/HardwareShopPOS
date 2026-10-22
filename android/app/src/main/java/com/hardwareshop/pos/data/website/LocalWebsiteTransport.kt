package com.hardwareshop.pos.data.website

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hardwareshop.pos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class WebsiteFixtureConnection(val deviceId:String,val fixtureDatabaseId:String,val ownerToken:String,val workerToken:String)
interface WebsiteTransport {
    fun connection():WebsiteFixtureConnection?
    suspend fun request(route:String,body:JsonObject?=null,owner:Boolean=false):JsonObject
}
/** Fixed loopback only, debug only, explicitly provisioned synthetic DB only.
 * No Firebase token, arbitrary host, production mode or automatic enrollment. */
open class LocalWebsiteTransport(private val context:Context):WebsiteTransport {
    override fun connection():WebsiteFixtureConnection? = runCatching {
        if(!BuildConfig.DEBUG)return null
        val f=File(context.noBackupFilesDir,"website-local-proof.json");if(!f.isFile||f.length()>4096)return null
        Gson().fromJson(f.readText(),WebsiteFixtureConnection::class.java).also {
            require(it.deviceId in listOf("device-a","device-b"))
            require(it.fixtureDatabaseId.matches(Regex("[a-f0-9-]{36}")))
            val suffix=it.deviceId.last();require(it.ownerToken=="fixture-owner-$suffix"&&it.workerToken=="fixture-worker-$suffix")
        }
    }.getOrNull()
    override suspend fun request(route:String,body:JsonObject?,owner:Boolean):JsonObject = withContext(Dispatchers.IO) {
        val c=checkNotNull(connection()){ "Local website fixture is not configured. Production connection is disabled." }
        require(route.matches(Regex("[A-Za-z0-9/?=&_-]{1,200}")))
        val connection=URL("http://127.0.0.1:4316/$route").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=3000;connection.readTimeout=8000;connection.instanceFollowRedirects=false
            connection.requestMethod=if(body==null)"GET" else "POST"
            connection.setRequestProperty("Authorization","Bearer ${if(owner)c.ownerToken else c.workerToken}")
            if(body!=null){val bytes=body.toString().toByteArray();require(bytes.size<=32768);connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use{it.write(bytes)}}
            val code=connection.responseCode
            val stream=if(code in 200..299)connection.inputStream else connection.errorStream
            val bytes=stream?.use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n);require(out.size()<=2*1024*1024){"Order page too large; cursor kept"}};out.toByteArray()} ?: byteArrayOf()
            require(bytes.size<=2*1024*1024){"Order page too large; cursor has been kept"}
            val result=runCatching{JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject}.getOrNull()
            check(code in 200..299){result?.get("error")?.asString?:"Local website unavailable ($code). Refresh before retrying."}
            checkNotNull(result){"Incomplete local response; retry original operation"}
        }finally{connection.disconnect()}
    }
}

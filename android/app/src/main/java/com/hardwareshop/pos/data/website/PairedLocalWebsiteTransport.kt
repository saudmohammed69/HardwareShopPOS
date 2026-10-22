package com.hardwareshop.pos.data.website

import android.content.Context
import android.util.AtomicFile
import com.google.gson.*
import com.hardwareshop.pos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Explicit synthetic installation adapter. Existing POS login and Firebase
 * sessions are untouched. Release builds cannot open this local gateway.
 * No public bootstrap or device-admin signing from a shared local OWNER label. */
class PairedLocalWebsiteTransport(
    private val context:Context, private val record:Record, private val key:WebsiteDeviceKey,
    private val recoveryProbe:Boolean=false
):WebsiteTransport {
    data class Record(val deviceId:String,val databaseEpoch:String,val fixtureDatabaseId:String,
        val fingerprint:String,val authorityEpoch:Long=1,val shop:String="proof-shop",val keyAlias:String="hardwareshop.website.installation.v1")
    private val mutex=locks.getOrPut(record.deviceId){Mutex()}
    private val journalDirectory=File(context.noBackupFilesDir,"website-paired-operations-${record.deviceId}")
    // Synthetic response-loss seam; invoked before a response is durably confirmed.
    internal var afterResponseForLocalTest:((String)->Unit)?=null
    init { UUID.fromString(record.deviceId);UUID.fromString(record.databaseEpoch);UUID.fromString(record.fixtureDatabaseId);require(record.shop=="proof-shop"&&record.authorityEpoch>0) }
    override fun connection():WebsiteFixtureConnection?=if(BuildConfig.DEBUG&&!WebsiteRestoreQuarantine.blocked(context)&&key.exists()&&key.fingerprint()==record.fingerprint&&
        (recoveryProbe||!WebsiteRestoreQuarantine.hasAtomicState(context,"website-pair-uncertain-${record.deviceId}.json")))
        WebsiteFixtureConnection(record.deviceId,record.fixtureDatabaseId,"","") else null
    private fun journal(identity:String):AtomicFile {require(identity.matches(Regex("[a-f0-9]{64}")));check(journalDirectory.isDirectory||journalDirectory.mkdirs());return AtomicFile(File(journalDirectory,"$identity.json"))}
    private fun load(identity:String):JsonObject {val journal=journal(identity);return try{journal.openRead().use{input->
        require(journal.baseFile.length()<=2*1024*1024);JsonParser.parseString(input.readBytes().toString(Charsets.UTF_8)).asJsonObject
    }}catch(e:java.io.FileNotFoundException){
        // openRead restores an AtomicFile .bak first. A .new/.bak remnant is
        // uncertainty, never permission to manufacture a new operation ID.
        if(listOf("",".bak",".new").any{File(journal.baseFile.path+it).exists()})throw e
        JsonObject()
    }
    }
    private fun save(identity:String,j:JsonObject){val journal=journal(identity);val bytes=j.toString().toByteArray(Charsets.UTF_8);check(bytes.size<=2*1024*1024){"Operation journal needs owner review; no operation was discarded"};val out=journal.startWrite();try{out.write(bytes);journal.finishWrite(out)}catch(e:Exception){journal.failWrite(out);throw e}}
    private fun intentKey(operation:String):String {UUID.fromString(operation);return WebsiteDeviceKey.sha256("intent:$operation".toByteArray())}
    private fun beginIntent(identity:String,route:String,body:JsonObject):Pair<String,JsonObject> {
        val head=load(identity)
        head.string("activeOperation").takeIf{it.isNotBlank()}?.let { operation ->
            val existing=load(intentKey(operation))
            check(existing.string("operation")==operation&&existing.string("route")==route&&existing.string("body")==body.toString()){"Operation journal mismatch; owner review required"}
            if(existing.string("state")=="PENDING")return operation to existing
            check(existing.string("state")=="RECEIPT"){"Unknown operation state; owner review required"}
        }
        // Migrate an uncertain old-format intent without changing its identity.
        // Confirmed legacy evidence stays on disk but cannot suppress new intent.
        val legacyRecord=head.getAsJsonObject(identity)?.also{check(it.string("state") in setOf("PENDING","RECEIPT")&&it.string("route")==route&&it.string("body")==body.toString()){"Invalid legacy operation evidence; owner review required"};UUID.fromString(it.string("operation"))}
        val legacy=legacyRecord?.takeIf{head.string("activeOperation").isBlank()&&it.string("state")=="PENDING"}
        val operation=legacy?.string("operation")?:UUID.randomUUID().toString()
        val intent=legacy?.deepCopy()?:json("operation" to operation,"route" to route,"body" to body.toString(),"state" to "PENDING")
        check(intent.string("route")==route&&intent.string("body")==body.toString())
        save(intentKey(operation),intent)
        head.addProperty("activeOperation",operation);head.addProperty("format",2);save(identity,head)
        // Both durable writes precede signing/transmission. An orphan created
        // before the head commit has never been sent; prior receipts are retained.
        return operation to intent
    }
    override suspend fun request(route:String,body:JsonObject?,owner:Boolean):JsonObject=exchange(route,body,false)
    internal suspend fun rotate(staged:WebsiteDeviceKey,rotationId:String):JsonObject {
        UUID.fromString(rotationId)
        val message=listOf("HWPOS-PAIR-ROTATE-V1",record.shop,record.deviceId,record.databaseEpoch,record.authorityEpoch.toString(),rotationId,staged.fingerprint()).joinToString("\n")
        return exchange("pair/rotate",json("rotationId" to rotationId,"publicKey" to staged.publicJwk(),"newKeySignature" to staged.sign(message)),true)
    }
    private suspend fun exchange(route:String,body:JsonObject?,lifecycle:Boolean):JsonObject=mutex.withLock{withContext(Dispatchers.IO){
        checkNotNull(connection()){"Pairing unavailable; new owner approval required"}
        require(route.matches(Regex("[A-Za-z0-9/?=&_-]{1,200}")))
        // Administration belongs to the independently controlled owner channel.
        check(!route.startsWith("pair/")||route=="pair/session"||(lifecycle&&route=="pair/rotate")){"Device administration requires independent owner approval"}
        val bytes=(body?.toString()?:"").toByteArray(Charsets.UTF_8);require(bytes.size<=32768)
        val method=if(body==null)"GET" else "POST"
        // Rotation changes the key, never the durable business-operation ID.
        val identity=WebsiteDeviceKey.sha256((record.deviceId+record.databaseEpoch+route+"\n").toByteArray()+bytes)
        val intent=body?.let{beginIntent(identity,route,it)}
        val operation=intent?.first?:UUID.randomUUID().toString()
        // Sign after acquiring the transport mutex, immediately before I/O.
        val nonce=UUID.randomUUID().toString();val time=(System.currentTimeMillis()/1000).toString()
        val canonical="https://proof.invalid/shops/${record.shop}/$route"
        val message=listOf("HWPOS-SHOP-DEVICE-V1",method,canonical,WebsiteDeviceKey.sha256(bytes),record.deviceId,record.databaseEpoch,time,nonce,operation).joinToString("\n")
        val connection=URL("http://127.0.0.1:4318/shops/${record.shop}/$route").openConnection() as HttpURLConnection
        try{
            connection.requestMethod=method;connection.instanceFollowRedirects=false;connection.connectTimeout=3000;connection.readTimeout=8000
            mapOf("x-device-id" to record.deviceId,"x-database-epoch" to record.databaseEpoch,"x-device-time" to time,"x-device-nonce" to nonce,"x-operation-id" to operation,"x-device-signature" to key.sign(message)).forEach{(k,v)->connection.setRequestProperty(k,v)}
            if(body!=null){connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use{it.write(bytes)}}
            val status=connection.responseCode;val input=if(status<400)connection.inputStream else connection.errorStream
            val response=input?.use{stream->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=stream.read(buffer);if(n<0)break;require(out.size()+n<=2*1024*1024);out.write(buffer,0,n)};out.toByteArray()}?:byteArrayOf()
            if(status !in 200..299)throw WebsiteAuthorizationException(status)
            val result=JsonParser.parseString(response.toString(Charsets.UTF_8)).asJsonObject
            if(route=="pair/session"){check(result.string("deviceId")==record.deviceId&&result.string("databaseEpoch")==record.databaseEpoch&&result.long("authorityEpoch")==record.authorityEpoch)}
            afterResponseForLocalTest?.invoke(route)
            if(intent!=null){intent.second.apply{addProperty("state","RECEIPT");add("receipt",result)};save(intentKey(operation),intent.second)}
            result
        }finally{connection.disconnect()}
    }}
    companion object {
        private val locks=java.util.concurrent.ConcurrentHashMap<String,Mutex>()
        fun open(context:Context):PairedLocalWebsiteTransport?=runCatching{
            check(BuildConfig.DEBUG);val file=File(context.noBackupFilesDir,"website-pairing-local.json")
            if(WebsiteRestoreQuarantine.hasAtomicState(context,"website-paired-rotation.json"))return@runCatching PairedWebsiteKeyLifecycle(context).transport()
            val record=AtomicFile(file).openRead().use{require(file.length() in 1L..4096L);Gson().fromJson(it.reader(),Record::class.java)}
            PairedLocalWebsiteTransport(context,record,WebsiteDeviceKey(record.keyAlias)).also{checkNotNull(it.connection())}
        }.getOrNull()
    }
}

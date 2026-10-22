package com.hardwareshop.pos.data.website

import android.content.Context
import android.util.AtomicFile
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID

/** No tokens/private keys. Excluded from Android backup and custom format 8.
 * Metadata alone never grants authority; the server verifies the installation key. */
class WebsiteEnrollmentStore(context:Context,private val key:WebsiteDeviceKey,name:String="website-enrollment.json") {
    init { require(name.matches(Regex("website-[a-zA-Z0-9.-]+\\.json"))) }
    private val file=AtomicFile(File(context.noBackupFilesDir,name))
    data class Record(val requestId:String,val fingerprint:String,val deviceId:String?=null,val keyVersion:Int=1)
    @Synchronized fun load():Record? {
        if(!key.exists())return null
        return try {
            val j=file.openRead().use{JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8)).asJsonObject}
            val request= j["requestId"].asString.also{UUID.fromString(it)}
            val fingerprint=j["fingerprint"].asString
            if(fingerprint!=key.fingerprint())return null
            val device=j.get("deviceId")?.takeUnless{it.isJsonNull}?.asString?.also{UUID.fromString(it)}
            val version=j["keyVersion"].asInt.also{require(it>0)}
            Record(request,fingerprint,device,version)
        }catch(_:Exception){null}
    }
    @Synchronized fun begin():Record {
        load()?.let{return it}
        key.ensureCreated()
        return Record(UUID.randomUUID().toString(),key.fingerprint()).also(::save)
    }
    @Synchronized fun rememberResponse(requestId:String,deviceId:String,keyVersion:Int):Record {
        val previous=requireNotNull(load());require(previous.requestId==requestId)
        UUID.fromString(deviceId);require(keyVersion>0)
        require(previous.deviceId==null||previous.deviceId==deviceId)
        return previous.copy(deviceId=deviceId,keyVersion=keyVersion).also(::save)
    }
    private fun save(record:Record) {
        val json=com.google.gson.JsonObject().apply {
            addProperty("requestId",record.requestId);addProperty("fingerprint",record.fingerprint)
            addProperty("deviceId",record.deviceId);addProperty("keyVersion",record.keyVersion)
        }
        val output=file.startWrite()
        try{output.write(json.toString().toByteArray(Charsets.UTF_8));file.finishWrite(output)}
        catch(e:Exception){file.failWrite(output);throw e}
    }
}

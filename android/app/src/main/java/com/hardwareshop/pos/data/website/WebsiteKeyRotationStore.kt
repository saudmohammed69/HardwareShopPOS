package com.hardwareshop.pos.data.website

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.hardwareshop.pos.BuildConfig
import java.io.File
import java.util.UUID

/** Local-only durable key lifecycle. No Room, billing, tokens or printer identity.
 * Uncertain rotation blocks new signed operations until online reconciliation.
 * Old keys are retained; an unacknowledged rotation never deletes either key. */
class WebsiteKeyRotationStore(context:Context,name:String="website-rotation.json") {
    init{require(name.matches(Regex("website-[a-zA-Z0-9.-]+\\.json")))}
    private val file=AtomicFile(File(context.noBackupFilesDir,name))
    data class Record(val deviceId:String,val alias:String,val fingerprint:String,val version:Int,
        val stagedAlias:String?=null,val stagedFingerprint:String?=null)
    @Synchronized fun load():Record {
        val record=file.openRead().use{Gson().fromJson(it.reader(),Record::class.java)}
        UUID.fromString(record.deviceId);require(record.version>0&&record.version<Int.MAX_VALUE)
        val active=WebsiteDeviceKey(record.alias);check(active.exists()&&active.fingerprint()==record.fingerprint){"Installation key missing; owner recovery required"}
        if(record.stagedAlias!=null){val staged=WebsiteDeviceKey(record.stagedAlias);check(staged.exists()&&staged.fingerprint()==record.stagedFingerprint){"Staged key missing; owner recovery required"}}
        else require(record.stagedFingerprint==null)
        return record
    }
    @Synchronized fun initialize(deviceId:String,key:WebsiteDeviceKey,version:Int=1):Record {
        check(BuildConfig.DEBUG);UUID.fromString(deviceId);require(version>0&&version<Int.MAX_VALUE&&key.exists())
        if(file.baseFile.exists()){val previous=load();require(previous.deviceId==deviceId);return previous}
        return Record(deviceId,key.alias,key.fingerprint(),version).also(::save)
    }
    @Synchronized fun stage():Record {
        check(BuildConfig.DEBUG);val previous=load();if(previous.stagedAlias!=null)return previous
        val key=WebsiteDeviceKey("hardwareshop.website."+(if(previous.alias.contains(".test."))"test." else "")+"rotation."+UUID.randomUUID());key.ensureCreated()
        return previous.copy(stagedAlias=key.alias,stagedFingerprint=key.fingerprint()).also(::save)
    }
    @Synchronized private fun promote(expected:Record):Record {
        check(load()==expected&&expected.stagedAlias!=null)
        return Record(expected.deviceId,expected.stagedAlias!!,expected.stagedFingerprint!!,expected.version+1).also(::save)
    }
    /** Probe new key first after restart/lost response. Only an explicit 403
     * permits probing the old key; timeouts/503 retain uncertainty. No revoked
     * key, reinstalled key or corrupt metadata can fall back to a new identity. */
    suspend fun recover(fixtureId:String,tokenSource:suspend ()->String,
        afterRotationResponse:()->Unit={}):Record {
        val record=load();if(record.stagedAlias==null)return record
        val staged=WebsiteDeviceKey(record.stagedAlias)
        val next=SignedLocalWebsiteTransport(fixtureId,record.deviceId,staged,record.version+1,tokenSource)
        try {
            val session=next.request("auth/session")
            check(session.string("deviceId")==record.deviceId&&session["keyVersion"].asInt==record.version+1)
            return promote(record)
        }catch(e:WebsiteAuthorizationException){if(e.status!=403)throw e}
        val old=WebsiteDeviceKey(record.alias)
        val session=SignedLocalWebsiteTransport(fixtureId,record.deviceId,old,record.version,tokenSource).request("auth/session")
        check(session.string("deviceId")==record.deviceId&&session["keyVersion"].asInt==record.version)
        val result=SignedLocalWebsiteTransport.rotate(old,staged,record.deviceId,record.version,tokenSource())
        check(result.string("deviceId")==record.deviceId&&result["keyVersion"].asInt==record.version+1)
        afterRotationResponse() // Testable process-death boundary before durable promotion.
        return promote(record)
    }
    fun transport(fixtureId:String,tokenSource:suspend ()->String):SignedLocalWebsiteTransport {
        val record=load();check(record.stagedAlias==null){"Reconcile staged website key before new online operations"}
        return SignedLocalWebsiteTransport(fixtureId,record.deviceId,WebsiteDeviceKey(record.alias),record.version,tokenSource)
    }
    private fun save(record:Record){
        val output=file.startWrite()
        try{output.write(Gson().toJson(record).toByteArray(Charsets.UTF_8));file.finishWrite(output)}
        catch(e:Exception){file.failWrite(output);throw e}
    }
}

package com.hardwareshop.pos.data.website

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.hardwareshop.pos.BuildConfig
import java.io.File
import java.util.UUID

/** Durable staged rotation. A lost response never deletes keys or creates a
 * replacement device identity. Both keys denied means owner review, not reset. */
class PairedWebsiteKeyLifecycle(private val context:Context,private val fileName:String="website-paired-rotation.json") {
    data class State(val record:PairedLocalWebsiteTransport.Record,val alias:String,
        val stagedAlias:String?=null,val rotationId:String?=null)
    private val file:AtomicFile
    init{require(fileName.matches(Regex("website-[a-zA-Z0-9.-]+\\.json")));file=AtomicFile(File(context.noBackupFilesDir,fileName))}
    @Synchronized fun load():State=file.openRead().use{Gson().fromJson(it.reader(),State::class.java)}.also{state->
        val key=WebsiteDeviceKey(state.alias);check(key.exists()&&key.fingerprint()==state.record.fingerprint)
        if(state.stagedAlias!=null){check(WebsiteDeviceKey(state.stagedAlias).exists());UUID.fromString(state.rotationId)}
    }
    @Synchronized fun initialize(record:PairedLocalWebsiteTransport.Record,key:WebsiteDeviceKey):State {
        check(BuildConfig.DEBUG&&key.fingerprint()==record.fingerprint)
        if(WebsiteRestoreQuarantine.hasAtomicState(context,fileName))return load().also{check(it.record.deviceId==record.deviceId)}
        return State(record.copy(keyAlias=key.alias),key.alias).also(::save)
    }
    @Synchronized fun stage():State {
        check(BuildConfig.DEBUG);val old=load();if(old.stagedAlias!=null)return old
        val next=WebsiteDeviceKey("hardwareshop.website."+(if(old.alias.contains(".test."))"test." else "")+"paired.rotation."+UUID.randomUUID());next.ensureCreated()
        // Block even previously retained transport objects before staging.
        val fence=AtomicFile(File(context.noBackupFilesDir,"website-pair-uncertain-${old.record.deviceId}.json"));val out=fence.startWrite();try{out.write("uncertain rotation; reconcile online".toByteArray());fence.finishWrite(out)}catch(e:Exception){fence.failWrite(out);throw e}
        return old.copy(stagedAlias=next.alias,rotationId=UUID.randomUUID().toString()).also(::save)
    }
    suspend fun recover(afterServerResponse:()->Unit={}):State {
        check(BuildConfig.DEBUG);val old=load()
        val fence=File(context.noBackupFilesDir,"website-pair-uncertain-${old.record.deviceId}.json")
        val alias=old.stagedAlias?:run{
            // Crash before staging or after durable promotion but before fence
            // cleanup: fresh server verification is required before unblocking.
            if(WebsiteRestoreQuarantine.hasAtomicState(context,fence.name)){
                PairedLocalWebsiteTransport(context,old.record,WebsiteDeviceKey(old.alias),true).request("pair/session")
                synchronized(this){check(load()==old);AtomicFile(fence).delete();check(!WebsiteRestoreQuarantine.hasAtomicState(context,fence.name))}
            }
            return old
        }
        val next=WebsiteDeviceKey(alias);val record=old.record.copy(fingerprint=next.fingerprint(),keyAlias=alias)
        val newTransport=PairedLocalWebsiteTransport(context,record,next,true)
        try{newTransport.request("pair/session")}
        catch(e:WebsiteAuthorizationException){if(e.status!=403)throw e
            val active=PairedLocalWebsiteTransport(context,old.record,WebsiteDeviceKey(old.alias),true)
            active.request("pair/session");active.rotate(next,requireNotNull(old.rotationId));afterServerResponse()
            newTransport.request("pair/session")
        }
        synchronized(this){check(load()==old){"Key lifecycle changed; owner review required"};return State(record,alias).also{save(it);AtomicFile(fence).delete();check(!WebsiteRestoreQuarantine.hasAtomicState(context,fence.name))}}
    }
    fun transport():PairedLocalWebsiteTransport {val state=load();check(state.stagedAlias==null){"Reconcile staged rotation before online Orders"};return PairedLocalWebsiteTransport(context,state.record,WebsiteDeviceKey(state.alias))}
    private fun save(s:State){val out=file.startWrite();try{out.write(Gson().toJson(s).toByteArray());file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}}
}

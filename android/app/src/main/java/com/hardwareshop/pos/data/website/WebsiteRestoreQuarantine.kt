package com.hardwareshop.pos.data.website

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** External authority fence; no Room schema or backup payload/version change.
 * Written BEFORE replacement, so even failed/interrupted restore cannot make
 * old device metadata authorize a new online operation. Offline POS is intact. */
object WebsiteRestoreQuarantine {
    fun beforeRestore(context:Context){
        val dir=context.noBackupFilesDir
        if(dir.listFiles().orEmpty().none{it.name.startsWith("website-pair")})return
        val file=AtomicFile(File(dir,"website-pairing-restore-quarantine.json"));val out=file.startWrite()
        try{out.write("Owner-reviewed pairing recovery required after database restore".toByteArray());file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
    }
    fun hasAtomicState(context:Context,name:String)=listOf("",".bak",".new").any{File(context.noBackupFilesDir,name+it).exists()}
    fun blocked(context:Context)=hasAtomicState(context,"website-pairing-restore-quarantine.json")
}

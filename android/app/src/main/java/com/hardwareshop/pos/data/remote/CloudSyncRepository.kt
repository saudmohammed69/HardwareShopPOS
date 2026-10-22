package com.hardwareshop.pos.data.remote

import com.hardwareshop.pos.data.sync.SyncReleaseGate
import com.hardwareshop.pos.data.sync.SyncEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud transfer is withheld until the journal transport passes acceptance tests.
 * The previous snapshot prototype reused remote local IDs and overwrote absolute
 * stock; retrying it could corrupt multi-device data. Local billing/printing and
 * the durable SQLite journal work independently of this transport.
 */
@Singleton
class CloudSyncRepository @Inject constructor(private val engine: SyncEngine) {
    suspend fun runOnce(): SyncResult {
        if(!SyncReleaseGate.READY) return SyncResult.Failed(SyncReleaseGate.MESSAGE)
        return try {
            val result=engine.runLinkedStore()
            SyncResult.Success(result.uploaded,result.received,result.reviewRequired,result.moreWork)
        } catch(e: IllegalStateException) {
            // Local configuration that retrying cannot fix: background work skips it
            // instead of burning retry attempts until the owner links the phone.
            val message=e.message.orEmpty()
            if("Link this phone" in message || "not authorized" in message ||
                "OWNER or WORKER role" in message) SyncResult.NotLinked
            else throw e
        }
    }

    sealed interface SyncResult {
        data class Success(val uploaded: Int, val downloaded: Int, val reviewRequired: Int, val moreWork: Boolean = false) : SyncResult
        data class Failed(val message: String) : SyncResult
        data object NotLinked : SyncResult
    }
}

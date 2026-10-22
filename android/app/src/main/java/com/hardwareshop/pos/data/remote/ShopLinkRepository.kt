package com.hardwareshop.pos.data.remote

import android.content.Context
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.sync.FirestoreSyncTransport
import com.hardwareshop.pos.data.sync.SyncJournal
import com.hardwareshop.pos.service.CloudSyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** One server-verified shop for both the billing journal and the printer target. */
@Singleton
class ShopLinkRepository @Inject constructor(
    private val db: AppDatabase,
    private val journal: SyncJournal,
    private val transport: FirestoreSyncTransport,
    @param:ApplicationContext private val context: Context
) {
    suspend fun link(shopId: String, schedule: Boolean = true, requireOwner: Boolean = false): FirestoreSyncTransport.Membership {
        val printerShop = db.shopDeviceDao().getRegisteredDevice()?.shopId
        check(printerShop == null || printerShop == shopId) {
            "The printer is linked to a different shop. Unlink that printer before linking this shop."
        }
        val member = transport.verifyMembership(shopId)
        check(!requireOwner || member.actor.role == com.hardwareshop.pos.data.sync.SyncProtocol.Role.OWNER) {
            "Use the shop owner's cloud account to set up this phone"
        }
        journal.bindVerifiedStore(shopId)
        if (schedule) CloudSyncScheduler.requestNow(context)
        return member
    }
}

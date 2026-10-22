package com.hardwareshop.pos.data.remote

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.sync.SyncJournal
import com.hardwareshop.pos.util.UserSession
import com.hardwareshop.pos.util.SessionHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Device-bound local role; Firebase persists the credential, never our password. */
@Singleton
class CloudSessionStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: AppDatabase, private val journal: SyncJournal, private val session: SessionHolder
) {
    private val prefs = context.getSharedPreferences("cloud_login_session",Context.MODE_PRIVATE)
    fun clear() { prefs.edit().clear().commit() }
    suspend fun remember() {
        val user=FirebaseAuth.getInstance().currentUser ?: return
        if (user.isAnonymous || user.email == null) return
        rememberAccount(user.uid)
    }
    internal suspend fun rememberAccount(uid: String) {
        val shop=journal.linkedStore() ?: return
        val current=session.current
        if (current is UserSession.None) { clear(); return }
        val device=com.hardwareshop.pos.util.printerDeviceId(context) ?: return
        prefs.edit().putString("uid",uid).putString("shop",shop)
            .putString("device",device)
            .putString("role",if(current is UserSession.OwnerSession) "OWNER" else "WORKER")
            .putLong("worker",(current as? UserSession.WorkerSession)?.workerId ?: 0).commit()
    }
    suspend fun restore(): UserSession? {
        val user=FirebaseAuth.getInstance().currentUser
        return restoreAccount(user?.takeIf { !it.isAnonymous && it.email != null }?.uid)
    }
    internal suspend fun restoreAccount(uid: String?): UserSession? {
        val shop=journal.linkedStore()
        val device=com.hardwareshop.pos.util.printerDeviceId(context)
        if (uid == null || shop == null || device == null || prefs.getString("uid",null)!=uid ||
            prefs.getString("shop",null)!=shop || prefs.getString("device",null)!=device) {
            clear(); return null
        }
        val restored=when(prefs.getString("role",null)) {
            "OWNER" -> db.ownerCredentialDao().getOwnerCredential()?.let {
                UserSession.OwnerSession(db.settingsDao().getSetting("shop_name")?.value ?: it.shopName)
            }
            "WORKER" -> db.workerDao().getWorkerById(prefs.getLong("worker",0))?.takeIf {
                it.isActive && it.passwordHash.isNotBlank() && it.passwordSalt.isNotBlank()
            }?.let { UserSession.WorkerSession(it.id,it.name,it.username,it.maxDiscountPaisa,it.requireOwnerApprovalForDiscount) }
            else -> null
        }
        if(restored == null) clear() else session.setSession(restored)
        return restored
    }
}

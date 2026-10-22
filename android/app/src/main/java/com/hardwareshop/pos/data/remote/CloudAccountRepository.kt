package com.hardwareshop.pos.data.remote

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.hardwareshop.pos.data.sync.SyncJournal
import com.hardwareshop.pos.util.PermissionChecker
import com.hardwareshop.pos.util.SessionHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Passwords stay with Firebase Auth. Existing anonymous accounts are upgraded in place. */
@Singleton
class CloudAccountRepository @Inject constructor(
    private val db: com.hardwareshop.pos.data.local.database.AppDatabase,
    private val journal: SyncJournal,
    private val link: ShopLinkRepository,
    private val session: SessionHolder,
    private val cataloguePolicy: CloudCataloguePolicy,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) {
    private val auth get() = FirebaseAuth.getInstance()
    private val cloud get() = FirebaseFirestore.getInstance()
    private val lock = Mutex()
    val email: String? get() = auth.currentUser?.email

    suspend fun connect(email: String, password: String, create: Boolean): String {
        PermissionChecker.enforceOwner(session.current)
        return connectAccount(email,password,create)
    }

    /** Login grants no local role until an enabled OWNER membership is verified. */
    suspend fun signInFromLogin(email: String, password: String): String {
        CloudLoginAccess.checkSignIn(db.ownerCredentialDao().getOwnerCredential() != null,
            journal.linkedStore() != null || db.shopDeviceDao().getRegisteredDevice() != null,
            session.current is com.hardwareshop.pos.util.UserSession.OwnerSession)
        return connectAccount(email,password,false)
    }

    suspend fun createFromLogin(email: String, password: String): String {
        CloudLoginAccess.checkCreate(db.ownerCredentialDao().getOwnerCredential() != null,
            journal.linkedStore() != null || db.shopDeviceDao().getRegisteredDevice() != null)
        return connectAccount(email, password, true)
    }

    private suspend fun connectAccount(email: String, password: String, create: Boolean): String = lock.withLock {
        require(email.trim().contains('@')) { "Enter a valid email address" }
        require(password.length >= 6) { "Password must have at least 6 characters" }
        val previous = auth.currentUser
        var canRestorePrevious = true
        var newAccount = false
        try {
            withTimeout(60_000) {
                val local = journal.linkedStore() ?: db.shopDeviceDao().getRegisteredDevice()?.shopId
                val user = if (create) {
                    val credential = EmailAuthProvider.getCredential(email.trim(), password)
                    CloudSignUpRecovery.createOrResume(
                        previous = previous?.let { CloudSignUpRecovery.ExistingAccount(it.isAnonymous, it.email) },
                        email = email, hasLinkedShop = local != null,
                        linkAnonymous = { checkNotNull(previous).linkWithCredential(credential).await().user.also { newAccount = it != null } },
                        createAccount = { auth.createUserWithEmailAndPassword(email.trim(), password).await().user.also { newAccount = it != null } },
                        signIn = { auth.signInWithEmailAndPassword(email.trim(), password).await().user },
                        signOut = { canRestorePrevious = false; auth.signOut() },
                        errorCode = { (it as? FirebaseAuthException)?.errorCode }
                    )
                } else auth.signInWithEmailAndPassword(email.trim(), password).await().user
                val uid = checkNotNull(user).uid
                if (newAccount) cataloguePolicy.recordPrimary(uid)
                val profile = cloud.collection("cloudAccounts").document(uid)
                val existing = profile.get(Source.SERVER).await().getString("shopId")
                if (existing != null) {
                    check(local == null || local == existing) {
                        "This phone contains a different shop. Use Start fresh on this phone to clear its local data before signing in to this account."
                    }
                    link.link(existing, schedule = false, requireOwner = true)
                    cataloguePolicy.connected(uid, newAccount)
                    com.hardwareshop.pos.service.CloudSyncScheduler.requestNow(context)
                    existing
                } else {
                    // Upgrade an approved legacy link without replacing its shop or identity.
                    val shopId = local ?: "account_$uid"
                    if (local == null) {
                        val shop = cloud.collection("shops").document(shopId)
                        val membership = shop.collection("members").document(uid)
                        cloud.runTransaction { tx ->
                            val root = tx.get(shop)
                            val member = tx.get(membership)
                            if (!root.exists()) tx.set(shop, mapOf("ownerUid" to uid))
                            if (!member.exists()) tx.set(membership, mapOf("enabled" to true, "role" to "OWNER", "printer" to true))
                            tx.set(profile, mapOf("shopId" to shopId))
                        }.await()
                    } else {
                        link.link(shopId, schedule = false, requireOwner = true)
                        profile.set(mapOf("shopId" to shopId)).await()
                    }
                    link.link(shopId, schedule = false, requireOwner = true)
                    cataloguePolicy.connected(uid, newAccount)
                    com.hardwareshop.pos.service.CloudSyncScheduler.requestNow(context)
                    shopId
                }
            }
        } catch (e: Exception) {
            val authCode = (e as? FirebaseAuthException)?.errorCode
            val failedPreviousAccount = create || previous?.email?.equals(email.trim(), ignoreCase = true) == true
            if (failedPreviousAccount && (CloudSignUpRecovery.isExpired(authCode) || authCode == "ERROR_USER_DISABLED")) {
                canRestorePrevious = false
                auth.signOut()
            }
            // A rejected account switch must not leave another shop's identity active.
            if (canRestorePrevious && previous != null && auth.currentUser?.uid != previous.uid) {
                try { auth.updateCurrentUser(previous).await() } catch (_: Exception) { auth.signOut() }
            }
            if (previous == null && !create) auth.signOut()
            if (e is kotlinx.coroutines.TimeoutCancellationException)
                throw IllegalStateException("Connection timed out. Check internet and try signing in again.", e)
            if (e is CancellationException) throw e
            val networkFailure = generateSequence<Throwable>(e) { it.cause }.take(8).any {
                it is FirebaseNetworkException || it is java.io.IOException ||
                    it.message?.contains("Software caused connection abort", ignoreCase = true) == true
            }
            val explanation = CloudConnectionError.message(authCode,
                (e as? FirebaseFirestoreException)?.code?.name, networkFailure)
                ?: if (e is IllegalArgumentException || e is IllegalStateException) e.message
                else "Unable to connect your cloud account. Please try again."
            throw IllegalStateException(explanation, e)
        }
    }
}

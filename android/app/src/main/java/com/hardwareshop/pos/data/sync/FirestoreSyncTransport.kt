package com.hardwareshop.pos.data.sync

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** Transactional cloud adapter. Only authenticated, server-verified memberships are used.
 * This adapter is not scheduled/enabled until its rules and local receiver are integrated.
 */
@Singleton
class FirestoreSyncTransport @Inject constructor() {
    private val auth get() = FirebaseAuth.getInstance()
    private val cloud get() = FirebaseFirestore.getInstance()

    data class Membership(val storeId: String, val actor: SyncProtocol.Actor)
    data class Cursor(val seconds: Long, val nanos: Int, val operationId: String)
    data class Delivery(val receipt: SyncProtocol.Receipt, val cursor: Cursor)
    data class BundleDelivery(val manifest: SyncFinancialBundle.Manifest,val cursor: Cursor)

    suspend fun verifyMembership(storeId: String): Membership = withTimeout(20_000) {
        require(storeId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid Shop ID" }
        val user = auth.currentUser ?: auth.signInAnonymously().await().user
            ?: error("Firebase did not return a signed-in user")
        val member = cloud.collection("shops").document(storeId).collection("members")
            .document(user.uid).get(Source.SERVER).await()
        check(member.exists() && member.getBoolean("enabled") == true) { "This phone is not authorized for this shop" }
        val role = when (member.getString("role")) {
            "OWNER" -> SyncProtocol.Role.OWNER
            "WORKER" -> SyncProtocol.Role.WORKER
            else -> error("The Firebase member document needs an OWNER or WORKER role")
        }
        Membership(storeId, SyncProtocol.Actor(user.uid, role))
    }

    /** Receipt and record head commit atomically. A retry never emits a second change.
     * A stale version throws Conflict; callers must retain and surface that local edit.
     */
    suspend fun upload(member: Membership, mutation: SyncProtocol.Mutation): SyncProtocol.Receipt = withTimeout(20_000) {
        check(auth.currentUser?.uid == member.actor.uid) { "Firebase account changed during synchronization" }
        SyncProtocol.validate(mutation)
        val shop = cloud.collection("shops").document(member.storeId)
        val head = shop.collection(mutation.scope.collection).document(mutation.recordId)
        val operation = shop.collection(mutation.scope.collection + "_changes").document(mutation.operationId)
        cloud.runTransaction { transaction ->
            val recorded = transaction.get(operation)
            if (recorded.exists()) {
                SyncProtocol.decide(member.actor, mutation, null, decodeReceipt(recorded, mutation.scope))
            } else {
                val existing = transaction.get(head)
                val current = if (existing.exists()) decodeRecord(existing, mutation.scope) else null
                val receipt = SyncProtocol.decide(member.actor, mutation, current, null)
                val fields = envelope(receipt)
                transaction.set(head, fields + ("lastOperationId" to mutation.operationId))
                transaction.set(operation, fields + ("committedAt" to FieldValue.serverTimestamp()))
                receipt
            }
        }.await()
    }

    /** At a new run, replay the last server timestamp (including ties). Local receipt IDs
     * deduplicate it. Within a page sequence, use the timestamp+document ID cursor.
     * Persist a cursor only in the same local transaction that saves its complete inbox page.
     */
    suspend fun fetchPage(member: Membership, scope: SyncProtocol.Scope, after: Cursor? = null,
                          replayTimestamp: Boolean = false, limit: Long = 100): List<Delivery> = withTimeout(20_000) {
        require(limit in 1..500)
        check(auth.currentUser?.uid == member.actor.uid) { "Firebase account changed during synchronization" }
        if (scope == SyncProtocol.Scope.PRIVATE) check(member.actor.role == SyncProtocol.Role.OWNER)
        var query: Query = cloud.collection("shops").document(member.storeId)
            .collection(scope.collection + "_changes")
            .orderBy("committedAt").orderBy(FieldPath.documentId()).limit(limit)
        if (after != null) {
            val timestamp = Timestamp(after.seconds, after.nanos)
            query = if (replayTimestamp) query.startAt(timestamp) else query.startAfter(timestamp, after.operationId)
        }
        query.get(Source.SERVER).await().documents.map { doc ->
            val timestamp = requireNotNull(doc.getTimestamp("committedAt")) { "Uncommitted server record" }
            val receipt = decodeReceipt(doc, scope)
            check(receipt.mutation.operationId == doc.id) { "Mismatched operation identity" }
            Delivery(receipt, Cursor(timestamp.seconds, timestamp.nanoseconds, doc.id))
        }
    }

    private fun envelope(receipt: SyncProtocol.Receipt): Map<String, Any?> = receipt.mutation.let {
        mapOf("schemaVersion" to 2L, "operationId" to it.operationId, "recordId" to it.recordId,
            "entity" to it.entity, "authorUid" to it.authorUid, "baseVersion" to it.baseVersion,
            "version" to receipt.version, "deleted" to it.deleted, "fields" to it.fields)
    }

    suspend fun publishBundle(member: Membership, manifest: SyncFinancialBundle.Manifest) = withTimeout(20_000) {
        check(auth.currentUser?.uid==member.actor.uid)
        if(manifest.scope==SyncProtocol.Scope.PRIVATE) require(member.actor.role==SyncProtocol.Role.OWNER)
        SyncBundleJson.decode(SyncBundleJson.encode(manifest))
        val shop=cloud.collection("shops").document(member.storeId)
        val target=shop.collection(manifest.scope.collection+"_bundles").document(manifest.bundleId)
        val root=shop.collection(manifest.scope.collection+"_changes").document(manifest.bundleId)
        cloud.runTransaction { tx ->
            val existing=tx.get(target)
            if(existing.exists()) check(decodeBundle(existing,manifest.scope)==manifest) { "Bundle identity was reused" }
            else {
                val rootReceipt=decodeReceipt(tx.get(root),manifest.scope)
                check(rootReceipt.mutation.authorUid==member.actor.uid && rootReceipt.mutation.recordId==manifest.rootId)
                check(rootReceipt.mutation.entity==manifest.rootEntity)
                tx.set(target,mapOf("schemaVersion" to 2L,"bundleId" to manifest.bundleId,"rootId" to manifest.rootId,
                    "rootEntity" to manifest.rootEntity,"authorUid" to member.actor.uid,"committedAt" to FieldValue.serverTimestamp(),
                    "members" to SyncBundleJson.wireMembers(manifest)))
            }
            Unit
        }.await()
    }

    suspend fun fetchBundlePage(member: Membership,scope: SyncProtocol.Scope,after: Cursor?=null,
                                replayTimestamp: Boolean=false,limit: Long=100): List<BundleDelivery> = withTimeout(20_000) {
        require(limit in 1..500)
        check(auth.currentUser?.uid==member.actor.uid)
        if(scope==SyncProtocol.Scope.PRIVATE) require(member.actor.role==SyncProtocol.Role.OWNER)
        var query: Query=cloud.collection("shops").document(member.storeId).collection(scope.collection+"_bundles")
            .orderBy("committedAt").orderBy(FieldPath.documentId()).limit(limit)
        after?.let { query=if(replayTimestamp) query.startAt(Timestamp(it.seconds,it.nanos))
            else query.startAfter(Timestamp(it.seconds,it.nanos),it.operationId) }
        query.get(Source.SERVER).await().documents.map { doc ->
            val time=requireNotNull(doc.getTimestamp("committedAt"))
            BundleDelivery(decodeBundle(doc,scope),Cursor(time.seconds,time.nanoseconds,doc.id))
        }
    }

    /** Fetch only missing immutable dependencies; callers save these without moving a page cursor. */
    suspend fun fetchReceipt(member: Membership,scope: SyncProtocol.Scope,operationId: String): Delivery? = withTimeout(20_000) {
        check(auth.currentUser?.uid==member.actor.uid)
        require(operationId.matches(Regex("[A-Za-z0-9_-]{16,120}")))
        if(scope==SyncProtocol.Scope.PRIVATE) require(member.actor.role==SyncProtocol.Role.OWNER)
        val doc=cloud.collection("shops").document(member.storeId).collection(scope.collection+"_changes")
            .document(operationId).get(Source.SERVER).await()
        if(!doc.exists()) return@withTimeout null
        val receipt=decodeReceipt(doc,scope)
        check(receipt.mutation.operationId==operationId)
        val time=requireNotNull(doc.getTimestamp("committedAt"))
        Delivery(receipt,Cursor(time.seconds,time.nanoseconds,operationId))
    }

    private fun decodeBundle(doc: DocumentSnapshot,scope: SyncProtocol.Scope): SyncFinancialBundle.Manifest {
        check(doc.getLong("schemaVersion")==2L)
        val rows=requireNotNull(doc.get("members") as? List<*>)
        val manifest=SyncFinancialBundle.Manifest(requireNotNull(doc.getString("bundleId")),requireNotNull(doc.getString("rootId")),
            requireNotNull(doc.getString("rootEntity")),scope,SyncBundleJson.readWireMembers(rows))
        check(doc.id==manifest.bundleId)
        return SyncBundleJson.decode(SyncBundleJson.encode(manifest))
    }

    private fun decodeReceipt(doc: DocumentSnapshot, scope: SyncProtocol.Scope): SyncProtocol.Receipt {
        check(doc.getLong("schemaVersion") == 2L) { "Unsupported sync record version" }
        val raw = requireNotNull(doc.get("fields") as? Map<*, *>) { "Missing structured fields" }
        val fields = raw.entries.associate { (key, value) -> requireNotNull(key as? String) to value }
        val mutation = SyncProtocol.Mutation(
            requireNotNull(doc.getString("operationId")), requireNotNull(doc.getString("recordId")),
            requireNotNull(doc.getString("entity")), scope, requireNotNull(doc.getString("authorUid")),
            requireNotNull(doc.getLong("baseVersion")), requireNotNull(doc.getBoolean("deleted")), fields)
        SyncProtocol.validate(mutation)
        val version = requireNotNull(doc.getLong("version"))
        check(version == mutation.baseVersion + 1) { "Invalid server version" }
        return SyncProtocol.Receipt(mutation, version)
    }

    private fun decodeRecord(doc: DocumentSnapshot, scope: SyncProtocol.Scope): SyncProtocol.Record =
        SyncProtocol.nextRecord(decodeReceipt(doc, scope)).also {
            check(it.recordId == doc.id) { "Mismatched server record identity" }
        }
}

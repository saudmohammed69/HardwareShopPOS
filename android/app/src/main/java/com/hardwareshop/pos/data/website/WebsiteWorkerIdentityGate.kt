package com.hardwareshop.pos.data.website

import com.hardwareshop.pos.BuildConfig
import com.hardwareshop.pos.data.local.entity.WorkerEntity
import com.hardwareshop.pos.util.SessionHolder
import com.hardwareshop.pos.util.UserSession
import java.security.MessageDigest
import java.util.UUID

/** Local proof, outside Hilt/login/billing. A binding is an owner-approved account
 * association, never authentication. A fresh signed server session is required.
 * Production database epoch/restore lifecycle and isolated provider sign-in need
 * approval before wiring this to the existing Orders handoff. */
class WebsiteWorkerIdentityGate(
    private val session:SessionHolder,
    private val transport:WebsiteTransport,
    private val databaseEpoch:String,
    private val localWorker:suspend(Long)->WorkerEntity?
) {
    init { UUID.fromString(databaseEpoch) }
    suspend fun <T> handoff(action:suspend()->T):T {
        check(BuildConfig.DEBUG) { "Local identity integration is disabled" }
        val selected=session.current
        val worker=(selected as? UserSession.WorkerSession)?.let { localWorker(it.workerId) }
        check(selected is UserSession.OwnerSession || (worker!=null&&worker.isActive&&
            worker.username==(selected as UserSession.WorkerSession).username&&
            worker.maxDiscountPaisa==selected.maxDiscountPaisa&&
            worker.requireOwnerApprovalForDiscount==selected.requireOwnerApprovalForDiscount)) { "Selected worker account requires reauthentication" }
        val remote=transport.request("auth/session") // No cached provider authority.
        check(remote.string("shop")=="proof-shop"&&remote.string("personId").isNotBlank()&&remote.string("uid").isNotBlank())
        check(remote.string("deviceId")==transport.connection()?.deviceId)
        if(selected is UserSession.OwnerSession)check(remote.string("role")=="OWNER")
        else {
            check(remote.string("role")=="WORKER"&&remote.string("workerRef").isNotBlank())
            val binding=remote.getAsJsonObject("localBinding")?:error("Owner approval of the individual/local account binding is required")
            check(binding.string("databaseEpoch")==databaseEpoch&&binding.string("accountHash")==accountHash(requireNotNull(worker))) { "Individual identity does not match this local worker/database" }
        }
        // A worker/account change while awaiting network cannot authorize handoff.
        check(session.current==selected&& (worker==null||localWorker(worker.id)==worker)) { "Worker changed during verification; authenticate again" }
        return action()
    }
    companion object {
        fun accountHash(worker:WorkerEntity):String {
            // Length-prefixed fields avoid ambiguous concatenation. No credential
            // hash/salt or customer/financial data leaves this installation.
            val parts=listOf(worker.id.toString(),worker.username,worker.createdAt.toString(),worker.updatedAt.toString(),
                worker.maxDiscountPaisa.toString(),worker.requireOwnerApprovalForDiscount.toString())
            val bytes=parts.joinToString(""){"${it.length}:$it"}.toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        }
    }
}

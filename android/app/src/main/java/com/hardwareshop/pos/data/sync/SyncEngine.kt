package com.hardwareshop.pos.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** One bounded resumable pass. No network wait holds a Room transaction.
 * Automatic activation remains gated until first-link reconciliation is integrated.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val cloud: FirestoreSyncTransport,
    private val journal: SyncJournal,
    private val transfer: SyncTransferStore,
    private val projector: SyncOutboxProjector,
    private val financialProjector: SyncFinancialProjector,
    private val catalogue: SyncCatalogueReceiver,
    private val financial: SyncFinancialReceiver,
    private val stock: SyncStockReceiver,
    private val bundles: SyncBundleStore,
    private val scanner: SyncInboxScanner
) {
    data class Result(val uploaded: Int,val received: Int,val reviewRequired: Int,val moreWork: Boolean = false)
    private val runLock=Mutex()

    suspend fun runLinkedStore(): Result = runLock.withLock {
        // Waiting for another pass must not consume this pass's network budget.
        withTimeout(90_000) {
        check(SyncReleaseGate.READY) { SyncReleaseGate.MESSAGE }
            val storeId=checkNotNull(journal.linkedStore()) { "Link this phone to your shop first" }
            val member=cloud.verifyMembership(storeId)
            journal.retryCatalogueParents()
            var uploaded=0
            var received=0
            var review=0
            var fullDownloadPage=false
            val scopes=if(member.actor.role==SyncProtocol.Role.OWNER) SyncProtocol.Scope.entries.toList()
                else listOf(SyncProtocol.Scope.PUBLIC)

            // Freeze local edits before receiving; a concurrent remote edit must be
            // retained as a conflict instead of silently replacing this phone's work.
            projector.prepare(member, limit = 100)
            financialProjector.prepare(member)
            val uploadStarted = System.nanoTime()
            for(queued in transfer.pending(storeId,100)) {
                // Leave time for downloads and local reconciliation on slow connections.
                if (System.nanoTime() - uploadStarted >= 25_000_000_000L) break
                if(queued.mutation.authorUid!=member.actor.uid) {
                    transfer.needsReview(storeId,queued,"The phone's cloud identity changed; owner review required")
                    review++;continue
                }
                if(queued.mutation.scope==SyncProtocol.Scope.PRIVATE && member.actor.role!=SyncProtocol.Role.OWNER) continue
                try {
                    val receipt=cloud.upload(member,queued.mutation)
                    transfer.acknowledge(storeId,receipt)
                    uploaded++
                } catch(e: SyncProtocol.Conflict) {
                    transfer.conflict(storeId,queued,e.remote);review++
                } catch(_: IllegalArgumentException) {
                    transfer.needsReview(storeId,queued,"This saved change requires owner review before upload")
                    review++
                }
                // Cancellation, connectivity and server authorization failures propagate.
                // Their frozen payload remains queued for an identical retry.
            }
            for(manifest in bundles.readyToPublish(storeId)) {
                if(manifest.scope !in scopes) continue
                cloud.publishBundle(member,manifest)
                bundles.published(storeId,manifest)
            }
            for(scope in scopes) {
                var cursor=transfer.cursor(storeId,scope)
                for(page in 0 until 2) {
                    val deliveries=cloud.fetchPage(member,scope,cursor,replayTimestamp=page==0)
                    transfer.saveInboxPage(storeId,scope,deliveries)
                    if(deliveries.isEmpty()) break
                    if (page == 1 && deliveries.size == 100) fullDownloadPage = true
                    cursor=deliveries.last().cursor
                    if(deliveries.size<100) break
                }
                var bundleCursor=bundles.cursor(storeId,scope)
                for(page in 0 until 2) {
                    val deliveries=cloud.fetchBundlePage(member,scope,bundleCursor,replayTimestamp=page==0)
                    bundles.savePage(storeId,scope,deliveries)
                    if(deliveries.isEmpty()) break
                    if (page == 1 && deliveries.size == 100) fullDownloadPage = true
                    bundleCursor=deliveries.last().cursor
                    if(deliveries.size<100) break
                }
            }
            // Bounded scans resume durably next time, including waiting rows. A large
            // invoice backlog cannot prevent later catalogue/customer records arriving.
            for(pass in 0 until 10) {
                val page=scanner.records(member)
                for(row in page.rows) {
                    val m=row.mutation
                    val stockRecord=m.entity in setOf("stock_movements","stock_baselines")
                    val independent=m.entity in setOf("stock_adjustments","audit_logs","day_closings") ||
                        (m.entity=="credit_transactions" && m.fields["billId"]==null)
                    when {
                        stockRecord -> when(stock.apply(member,row)) {
                            SyncStockReceiver.Outcome.APPLIED -> received++
                            SyncStockReceiver.Outcome.CONFLICT -> review++
                            SyncStockReceiver.Outcome.WAITING -> Unit
                        }
                        independent -> when(financial.applyStandalone(member,row)) {
                            SyncFinancialReceiver.Outcome.APPLIED -> received++
                            SyncFinancialReceiver.Outcome.CONFLICT -> review++
                            SyncFinancialReceiver.Outcome.WAITING -> Unit
                        }
                        else -> when(catalogue.apply(member,row)) {
                            SyncCatalogueReceiver.Outcome.APPLIED -> received++
                            SyncCatalogueReceiver.Outcome.CONFLICT -> review++
                            SyncCatalogueReceiver.Outcome.WAITING -> Unit
                            SyncCatalogueReceiver.Outcome.UNSUPPORTED -> transfer.defer(storeId,row,
                                if(m.entity in SyncFinancialBundle.entities) "Waiting for the complete invoice"
                                else "Waiting for first-link stock/profile reconciliation")
                        }
                    }
                }
                scanner.complete(storeId,page)
                if(page.rows.isEmpty()) break
            }
            val bundlePage=scanner.bundles(member)
            for(manifest in bundlePage.rows) {
                var receipts=bundles.receipts(storeId,manifest)
                val saved=receipts.map { it.mutation.operationId }.toSet()
                var missing=false
                for(reference in manifest.members.filter { it.operationId !in saved }) {
                    val delivery=cloud.fetchReceipt(member,manifest.scope,reference.operationId)
                    if(delivery==null) missing=true
                    else transfer.saveInboxPage(storeId,manifest.scope,listOf(delivery),advanceCursor=false)
                }
                receipts=bundles.receipts(storeId,manifest)
                val outcome=if(missing) SyncFinancialReceiver.Outcome.WAITING else try {
                    financial.apply(member,manifest,receipts)
                } catch(_: IllegalArgumentException) {
                    // Invalid totals remain available for review without stopping later bills.
                    SyncFinancialReceiver.Outcome.CONFLICT
                }
                bundles.result(storeId,manifest,outcome)
                if(outcome==SyncFinancialReceiver.Outcome.APPLIED) received+=receipts.size
                if(outcome==SyncFinancialReceiver.Outcome.CONFLICT) review++
            }
            scanner.complete(storeId,bundlePage)
            val counts = journal.pendingCounts()
            Result(uploaded,received,review, SyncContinuationPolicy.shouldContinue(
                uploaded,received,counts.first,counts.second,journal.hasWaitingDownloads(),fullDownloadPage))
        }
    }
}

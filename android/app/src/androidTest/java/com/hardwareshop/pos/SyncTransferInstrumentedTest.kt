package com.hardwareshop.pos.data.sync

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncTransferInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var store: SyncTransferStore
    private lateinit var journal: SyncJournal
    private val member=FirestoreSyncTransport.Membership("test-shop",SyncProtocol.Actor("owner-device",SyncProtocol.Role.OWNER))

    @Before fun setup() = runBlocking {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AppDatabase::class.java)
            .addCallback(object: RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    (SyncJournalSchema.statements(includeBoxCount = true)+SyncTransferSchema.statements()).forEach { db.execSQL(it) }
                }
            }).build()
        store=SyncTransferStore(db)
        journal=SyncJournal(db)
        journal.bindVerifiedStore(member.storeId)
        val category=db.categoryDao().insertCategory(CategoryEntity(name="Local category"))
        val type=db.productTypeDao().insertType(ProductTypeEntity(categoryId=category,name="Elbow"))
        db.productDao().insertProduct(ProductEntity(id=1,categoryId=category,typeId=type,name="PVC Elbow",
            unit="Piece",sellingPricePaisa=1500,purchasePricePaisa=900,currentStock=100.0))
        Unit
    }
    @After fun close() { db.close() }
    @Test fun normalizedIncomingVariantLinksOriginalIdWithoutDuplicateOrPriceOverwrite() = runBlocking {
        val original=db.productDao().getProductById(1)!!
        db.productDao().updateProduct(original.copy(size="3/4 inch"))
        SyncOutboxProjector(db,store).prepare(member)
        val pending=store.pending(member.storeId)
        val local=pending.single { it.mutation.entity=="products" }.mutation
        pending.forEach { store.acknowledge(member.storeId,SyncProtocol.Receipt(it.mutation,it.mutation.baseVersion+1)) }
        val remote=local.copy(operationId="operation_normalized_variant_001",recordId="record_normalized_variant_001",
            fields=local.fields+mapOf("name" to "pvc   elbow","size" to "¾ inch","unit" to "piece","sellingPricePaisa" to 9999L))
        val receiver=SyncCatalogueReceiver(db,store)
        assertEquals(SyncCatalogueReceiver.Outcome.APPLIED,receiver.apply(member,deliver(remote)))
        assertEquals(1,db.productDao().getAllProducts().size)
        assertEquals(1500L,db.productDao().getProductById(1)!!.sellingPricePaisa)
        db.openHelper.readableDatabase.query("SELECT localKey FROM sync_foreign_identity WHERE syncId=?",arrayOf<Any>(remote.recordId)).use {
            assertTrue(it.moveToFirst());assertEquals("1",it.getString(0))
        }
        assertEquals(SyncCatalogueReceiver.Outcome.APPLIED,receiver.apply(member,deliver(remote)))
        assertEquals(1,db.productDao().getAllProducts().size)
    }
    @Test fun secondaryCloudCatalogueUpdatesMatchedProductsAndRetainsUnsentEdits() = runBlocking {
        val base: android.content.Context = ApplicationProvider.getApplicationContext()
        val file = "catalogue_test_${java.util.UUID.randomUUID()}"
        val isolated = object : android.content.ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                base.getSharedPreferences(file,mode)
        }
        try {
            val policy = com.hardwareshop.pos.data.remote.CloudCataloguePolicy(isolated)
            policy.recordPrimary(member.actor.uid)
            policy.connected(member.actor.uid,true)
            policy.connected(member.actor.uid,false)
            assertFalse(policy.usesCloud(member.actor.uid))
            isolated.getSharedPreferences("cloud_catalogue",0).edit().clear().commit()
            policy.connected(member.actor.uid,false)
            assertTrue(policy.usesCloud(member.actor.uid))
            SyncOutboxProjector(db,store).prepare(member)
            val all=store.pending(member.storeId)
            val product=all.single { it.mutation.entity=="products" }.mutation
            all.forEach { store.acknowledge(member.storeId,SyncProtocol.Receipt(it.mutation,it.mutation.baseVersion+1)) }
            val remote=product.copy(operationId="cloud_catalogue_first_change",recordId="cloud_primary_product_0001",
                fields=product.fields + mapOf("sellingPricePaisa" to 2100L,
                    "searchAliases" to ProductPackagingWire.encode("bend",100,"Piece")))
            val receiver=SyncCatalogueReceiver(db,store,policy)
            assertEquals(SyncCatalogueReceiver.Outcome.APPLIED,receiver.apply(member,deliver(remote)))
            val received=db.productDao().getProductById(1)!!
            assertEquals(1,db.productDao().getAllProducts().size)
            assertEquals(2100L,received.sellingPricePaisa);assertEquals(100,received.piecesPerBox)
            assertEquals(100.0,received.currentStock,0.0)
            val next=remote.copy(operationId="cloud_catalogue_next_change",baseVersion=1,
                fields=remote.fields + ("sellingPricePaisa" to 2200L))
            assertEquals(SyncCatalogueReceiver.Outcome.APPLIED,receiver.apply(member,deliver(next)))
            assertEquals(2200L,db.productDao().getProductById(1)!!.sellingPricePaisa)
            db.productDao().updateProduct(db.productDao().getProductById(1)!!.copy(sellingPricePaisa=2300L))
            val conflicting=next.copy(operationId="cloud_catalogue_conflicting_change",baseVersion=2,
                fields=next.fields + ("sellingPricePaisa" to 2400L))
            assertEquals(SyncCatalogueReceiver.Outcome.CONFLICT,receiver.apply(member,deliver(conflicting)))
            assertEquals(2300L,db.productDao().getProductById(1)!!.sellingPricePaisa)
            assertEquals(100.0,db.productDao().getProductById(1)!!.currentStock,0.0)
        } finally { base.deleteSharedPreferences(file) }
        Unit
    }
    @Test fun anotherPhoneUsingSameAccountStillReceivesStockBaseline() = runBlocking {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DELETE FROM sync_dirty")
        sql.execSQL("DELETE FROM sync_stock_movements")
        val syncId = sql.query("SELECT syncId FROM sync_identity WHERE tableName='products' AND localKey='1'").use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        val mutation = SyncProtocol.Mutation("same_account_baseline_operation", "base_$syncId",
            "stock_baselines", SyncProtocol.Scope.PUBLIC, member.actor.uid, 0, false,
            mapOf("productSyncId" to syncId, "quantity" to 250.0))
        val incoming = deliver(mutation)
        val receiver = SyncStockReceiver(db,journal,store)
        assertEquals(SyncStockReceiver.Outcome.APPLIED,receiver.apply(member,incoming))
        assertEquals(250.0,db.productDao().getProductById(1)!!.currentStock,0.0)
        db.productDao().updateProduct(db.productDao().getProductById(1)!!.copy(currentStock=245.0))
        assertEquals(SyncStockReceiver.Outcome.APPLIED,receiver.apply(member,incoming))
        assertEquals(245.0,db.productDao().getProductById(1)!!.currentStock,0.0)
        Unit
    }
    @Test fun boxCountTravelsThroughExistingProductFieldsAndDoesNotTouchStock() = runBlocking {
        val product = db.productDao().getProductById(1)!!
        db.productDao().updateProduct(product.copy(piecesPerBox = 100, searchAliases = "bend"))
        SyncOutboxProjector(db,store).prepare(member)
        val projected = store.pending(member.storeId).single { it.mutation.entity == "products" }.mutation
        assertFalse(projected.fields.containsKey("piecesPerBox"))
        val metadata = ProductPackagingWire.decode(projected.fields.getValue("searchAliases") as String, "Piece")
        assertEquals(100,metadata.piecesPerBox); assertEquals("bend",metadata.aliases)
        val incoming = projected.copy(operationId = "operation_box_received", recordId = "product_box_received",
            fields = projected.fields + ("name" to "New boxed screw"))
        assertEquals(SyncCatalogueReceiver.Outcome.APPLIED,SyncCatalogueReceiver(db,store).apply(member,deliver(incoming)))
        val received = db.productDao().getAllProducts().single { it.name == "New boxed screw" }
        assertEquals(100,received.piecesPerBox); assertEquals("bend",received.searchAliases)
        assertEquals(0.0,received.currentStock,0.0)
        assertEquals(100.0,db.productDao().getProductById(1)!!.currentStock,0.0)
        Unit
    }

    @Test fun pricePrivacyAndRevisionAwareAcknowledgement() = runBlocking {
        SyncOutboxProjector(db,store).prepare(member)
        val all=store.pending(member.storeId)
        val product=all.single { it.mutation.entity=="products" }
        val cost=all.single { it.mutation.entity=="product_costs" }
        assertFalse(product.mutation.fields.containsKey("purchasePricePaisa"))
        assertFalse(product.mutation.fields.containsKey("currentStock"))
        assertEquals(900L,cost.mutation.fields["purchasePricePaisa"])
        store.acknowledge(member.storeId,SyncProtocol.Receipt(product.mutation,1))
        assertTrue(journal.pending().any { it.table=="products" })
        db.openHelper.writableDatabase.execSQL("UPDATE products SET sellingPricePaisa=1600 WHERE id=1")
        store.acknowledge(member.storeId,SyncProtocol.Receipt(cost.mutation,1))
        assertTrue(journal.pending().single { it.table=="products" }.revision>product.localRevision)
        Unit
    }

    @Test fun interruptedUploadKeepsIdenticalPayloadAndOperationId() = runBlocking {
        val projector=SyncOutboxProjector(db,store)
        projector.prepare(member)
        val before=store.pending(member.storeId)
        db.productDao().decrementStock(1,1.0)
        projector.prepare(member)
        val after=store.pending(member.storeId)
        val originalIds=before.map { it.mutation.operationId }.toSet()
        assertEquals(before,after.filter { it.mutation.operationId in originalIds })
        assertEquals(-1.0,after.single { it.mutation.operationId !in originalIds }.mutation.fields["delta"])
        Unit
    }

    @Test fun workerPublishesUnpricedProposalWithoutChangingLocalPrice() = runBlocking {
        val worker=member.copy(actor=SyncProtocol.Actor("worker-device",SyncProtocol.Role.WORKER))
        SyncOutboxProjector(db,store).prepare(worker)
        val product=store.pending(member.storeId).single { it.mutation.entity=="products" }.mutation
        assertEquals(0L,product.fields["sellingPricePaisa"])
        SyncProtocol.decide(worker.actor,product,null,null)
        assertEquals(1500L,db.productDao().getProductById(1)!!.sellingPricePaisa)
        assertEquals(900L,db.productDao().getProductById(1)!!.purchasePricePaisa)
        Unit
    }

    private fun category(name: String)=SyncProtocol.Mutation("operation_remote_0001","category_remote_0001","categories",
        SyncProtocol.Scope.PUBLIC,"remote-owner",0,false,mapOf("name" to name,"displayOrder" to 0L,
            "isActive" to 1L,"createdAt" to 1L,"updatedAt" to 1L))

    private suspend fun deliver(m: SyncProtocol.Mutation): SyncTransferStore.Incoming {
        val version=m.baseVersion+1
        store.saveInboxPage(member.storeId,m.scope,listOf(FirestoreSyncTransport.Delivery(SyncProtocol.Receipt(m,version),
            FirestoreSyncTransport.Cursor(100,0,m.operationId))))
        return SyncTransferStore.Incoming(m,version)
    }

    @Test fun incomingCategoryAllocatesNewLocalKeyAndDoesNotEcho() = runBlocking {
        val incoming=deliver(category("Remote category"))
        val receiver=SyncCatalogueReceiver(db,store)
        assertEquals(SyncCatalogueReceiver.Outcome.APPLIED,receiver.apply(member,incoming))
        assertEquals(SyncCatalogueReceiver.Outcome.APPLIED,receiver.apply(member,incoming))
        db.openHelper.readableDatabase.query("SELECT localKey FROM sync_identity WHERE syncId=?",
            arrayOf<Any>(incoming.mutation.recordId)).use {
            assertTrue(it.moveToFirst()); assertNotEquals("1",it.getString(0))
        }
        assertFalse(journal.pending().any { it.syncId==incoming.mutation.recordId })
        Unit
    }

    @Test fun identicalCategoryMapsForeignIdentityAndPreservesOriginal() = runBlocking {
        val result=SyncCatalogueReceiver(db,store).apply(member,deliver(category("Local category")))
        assertEquals(SyncCatalogueReceiver.Outcome.APPLIED,result)
        assertEquals(100.0,db.productDao().getProductById(1)!!.currentStock,0.0)
        assertTrue(journal.pending().any { it.table=="categories" })
        Unit
    }

    @Test fun repeatedOperationWithDifferentContentsCannotAdvanceCursor() = runBlocking {
        val original=category("Original")
        deliver(original)
        try {
            deliver(original.copy(fields=original.fields+("name" to "Different")))
            fail("Changed retry should be rejected")
        } catch (_: IllegalStateException) { }
        assertEquals(original,store.incoming(member.storeId).single().mutation)
        Unit
    }

    private fun financialRow(entity: String,id: String,overrides: Map<String,Any?>): SyncProtocol.Receipt {
        val spec=SyncWireSchema.byName.getValue(entity)
        val fields=spec.fields.associateWith { field -> when {
            field in spec.references -> if(field in spec.required) "parent_reference_001" else null
            SyncWireSchema.kind(entity,field)=="TEXT" -> ""
            SyncWireSchema.kind(entity,field)=="REAL" -> 0.0
            else -> 0L
        } }+overrides
        return SyncProtocol.Receipt(SyncProtocol.Mutation("operation_$id",id,entity,spec.scope,"remote-owner",0,false,fields),1)
    }

    private suspend fun incomingSale(id: String,product: String): Pair<SyncFinancialBundle.Manifest,List<SyncProtocol.Receipt>> {
        val bill=financialRow("bills",id,mapOf("billNumber" to "000001","subtotalPaisa" to 1500L,"totalPaisa" to 1500L,
            "paidPaisa" to 1500L,"creditPaisa" to 0L,"status" to "ACTIVE","paymentMethod" to "CASH"))
        val line=financialRow("bill_items","line_$id",mapOf("billId" to id,"productId" to product,"quantity" to 1.0,
            "unitPricePaisa" to 1500L,"itemTotalPaisa" to 1500L))
        val payment=financialRow("payments","payment_$id",mapOf("billId" to id,"amountPaisa" to 1500L,"method" to "CASH"))
        val rows=listOf(bill,line,payment)
        store.saveInboxPage(member.storeId,SyncProtocol.Scope.PUBLIC,rows.map {
            FirestoreSyncTransport.Delivery(it,FirestoreSyncTransport.Cursor(100,0,it.mutation.operationId))
        },advanceCursor=false)
        return SyncFinancialBundle.manifest(id,rows) to rows
    }

    @Test fun missingFinancialParentRollsBackTheEntireBillAndRemainsRetryable() = runBlocking {
        val (manifest,rows)=incomingSale("remote_bill_missing_01","missing_product_001")
        assertEquals(SyncFinancialReceiver.Outcome.WAITING,SyncFinancialReceiver(db,store).apply(member,manifest,rows))
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM bills").use { it.moveToFirst();assertEquals(0,it.getInt(0)) }
        assertEquals(0L,store.version(member.storeId,SyncProtocol.Scope.PUBLIC,manifest.rootId))
        assertEquals(3,store.incoming(member.storeId).size)
        assertNull(store.cursor(member.storeId,SyncProtocol.Scope.PUBLIC))
        Unit
    }

    @Test fun manualInvoiceImportsWithoutProductDependencyOrStockMovement() = runBlocking {
        val (manifest, rows) = incomingSale("remote_manual_bill_01", SyncWireSchema.MANUAL_PRODUCT_REFERENCE)
        val receiver = SyncFinancialReceiver(db, store)
        assertEquals(SyncFinancialReceiver.Outcome.APPLIED, receiver.apply(member, manifest, rows))
        assertEquals(SyncFinancialReceiver.Outcome.APPLIED, receiver.apply(member, manifest, rows))
        assertEquals(0L, db.billDao().getAllBillItems().single().productId)
        assertEquals(1, db.billDao().getAllBills().size)
        assertEquals(100.0, db.productDao().getProductById(1)!!.currentStock, 0.0)
        Unit
    }

    @Test fun manualLineProjectsReservedReferenceWithoutCatalogueRow() = runBlocking {
        val bill = db.billDao().insertBill(BillEntity(billNumber = "MANUAL-1", subtotalPaisa = 1000,
            totalPaisa = 1000, paymentMethod = "CASH", paidPaisa = 1000))
        db.billDao().insertBillItem(BillItemEntity(billId = bill, productId = 0, productName = "Loose item",
            productSize = "", productUnit = "Piece", quantity = 1.0, unitPricePaisa = 1000,
            itemTotalPaisa = 1000))
        val change = journal.pending().single { it.table == "bill_items" }
        val fields = SyncOutboxProjector(db, store).readFields(db.openHelper.readableDatabase,
            change, SyncWireSchema.byName.getValue("bill_items"))
        assertEquals(SyncWireSchema.MANUAL_PRODUCT_REFERENCE, fields["productId"])
        Unit
    }

    @Test fun independentBillNumbersSurviveWithoutChangingStockOrPrinting() = runBlocking {
        val product=journal.pending().single { it.table=="products" }.syncId
        val receiver=SyncFinancialReceiver(db,store)
        for(id in listOf("remote_bill_phone_a_01","remote_bill_phone_b_01")) {
            val (manifest,rows)=incomingSale(id,product)
            assertEquals(SyncFinancialReceiver.Outcome.APPLIED,receiver.apply(member,manifest,rows))
            assertEquals(SyncFinancialReceiver.Outcome.APPLIED,receiver.apply(member,manifest,rows))
        }
        db.openHelper.readableDatabase.query("SELECT COUNT(*),COUNT(DISTINCT billNumber),SUM(isPrinted) FROM bills").use {
            it.moveToFirst();assertEquals(2,it.getInt(0));assertEquals(2,it.getInt(1));assertEquals(0,it.getInt(2))
        }
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM payments").use { it.moveToFirst();assertEquals(2,it.getInt(0)) }
        assertEquals(100.0,db.productDao().getProductById(1)!!.currentStock,0.0)
        assertFalse(journal.pending().any { it.table in SyncFinancialBundle.entities })
        Unit
    }

    @Test fun independentCreditPaymentsCombineOnceWithoutOverwritingBalance() = runBlocking {
        val customer=financialRow("customers","remote_customer_0001",mapOf("name" to "Customer","isActive" to 1L))
        SyncCatalogueReceiver(db,store).apply(member,deliver(customer.mutation))
        val receiver=SyncFinancialReceiver(db,store)
        for((id,amount) in listOf("credit_opening_0001" to 1000L,"credit_payment_a_01" to -200L,"credit_payment_b_01" to -300L)) {
            val row=financialRow("credit_transactions",id,mapOf("customerId" to customer.mutation.recordId,
                "billId" to null,"type" to if(amount>0) "CREDIT_CREATED" else "PARTIAL_PAYMENT",
                "amountPaisa" to amount,"previousBalancePaisa" to if(amount>0) 0L else 1000L,
                "newBalancePaisa" to if(amount>0) amount else 1000L+amount))
            val incoming=deliver(row.mutation)
            assertEquals(SyncFinancialReceiver.Outcome.APPLIED,receiver.applyStandalone(member,incoming))
            assertEquals(SyncFinancialReceiver.Outcome.APPLIED,receiver.applyStandalone(member,incoming))
        }
        db.openHelper.readableDatabase.query("SELECT COUNT(*),SUM(amountPaisa) FROM credit_transactions").use {
            assertTrue(it.moveToFirst());assertEquals(3,it.getInt(0));assertEquals(500L,it.getLong(1))
        }
        assertFalse(journal.pending().any { it.table=="credit_transactions" })
        Unit
    }

    @Test fun waitingRecordsDoNotStarveLaterPagesAndInterruptedScansReplay() = runBlocking {
        val first=deliver(category("Needs review"))
        val second=deliver(category("Later record").copy(operationId="operation_remote_0002",recordId="category_remote_0002"))
        val scanner=SyncInboxScanner(db,store)
        val page=scanner.records(member,1)
        assertEquals(first,page.rows.single())
        store.defer(member.storeId,first,"Waiting for parent")
        assertEquals(page,scanner.records(member,1)) // Interrupted before checkpoint.
        scanner.complete(member.storeId,page)
        val next=scanner.records(member,1)
        assertEquals(second,next.rows.single())
        scanner.complete(member.storeId,next)
        val end=scanner.records(member,1)
        assertTrue(end.rows.isEmpty())
        scanner.complete(member.storeId,end)
        assertEquals(first,scanner.records(member,1).rows.single())
        Unit
    }

    @Test fun reviewedProductAliasPreservesRemoteBillAndLocalProduct() = runBlocking {
        val alias="reviewed_product_alias_01"
        journal.mapIncoming(alias,"products","1")
        val (manifest,rows)=incomingSale("remote_alias_bill_0001",alias)
        assertEquals(SyncFinancialReceiver.Outcome.APPLIED,SyncFinancialReceiver(db,store).apply(member,manifest,rows))
        db.openHelper.readableDatabase.query("SELECT productId FROM bill_items").use {
            assertTrue(it.moveToFirst());assertEquals(1L,it.getLong(0))
        }
        assertEquals(100.0,db.productDao().getProductById(1)!!.currentStock,0.0)
        Unit
    }

    @Test fun firstLinkSnapshotBecomesStaleEvenWhenQuantityReturnsToOriginal() = runBlocking {
        val review=SyncFirstLinkSnapshot(db)
        val before=review.capture()
        review.requireUnchanged(before)
        db.productDao().decrementStock(1,1.0)
        db.openHelper.writableDatabase.execSQL("UPDATE products SET currentStock=currentStock+1 WHERE id=1")
        assertEquals(before.products.single().stock,review.capture().products.single().stock,0.0)
        try { review.requireUnchanged(before);fail("A stale review must not authorize a merge") }
        catch(_: IllegalStateException) { }
        Unit
    }
}

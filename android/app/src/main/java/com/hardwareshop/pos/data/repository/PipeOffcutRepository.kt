package com.hardwareshop.pos.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.domain.model.CartItem
import com.hardwareshop.pos.data.local.entity.StockAdjustmentEntity
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/** Physical piece register for this phone; backups include these settings, cloud sync does not. */
@Singleton
class PipeOffcutRepository @Inject constructor(private val db: AppDatabase, private val session: SessionHolder) {
    data class Record(val id:String,val productId:Long,val productLabel:String,val feet:Int,val location:String,
        val revision:String=java.util.UUID.randomUUID().toString(),val status:String?=null,val soldBillId:Long?=null) {
        val available:Boolean get()=status==null || status=="AVAILABLE"
        fun piece()=PipeCutPlanner.Piece(id,productId,feet,location,revision)
    }
    private val gson=Gson()
    private fun decode(row:SettingsEntity): Record {
        val record=gson.fromJson(row.value,Record::class.java)
        require(record.id.isNotBlank() && row.key=="pipe-offcut:${record.id}" && record.productId>0 && record.feet in 1..2000 &&
            !record.revision.isNullOrBlank() && (record.status==null || record.status in setOf("AVAILABLE","SOLD","DISCARDED"))) { "An offcut record is damaged. Restore a valid backup before editing it." }
        return record
    }
    fun observe(): Flow<List<Record>> = db.settingsDao().observePipeOffcuts().map { rows -> rows.map(::decode) }.flowOn(Dispatchers.Default)
    suspend fun snapshot():List<Record> = db.settingsDao().getPipeOffcuts().map(::decode)
    suspend fun add(product: Product, feet: String, location: String) = db.withTransaction {
        PermissionChecker.enforceOwner(session.current)
        snapshot() // Never append to a register with unreadable existing pieces.
        val current=db.productDao().getProductById(product.id)
        require(current?.isActive==true && current.isPipe) { "Choose an active configured pipe" }
        val row=Record(java.util.UUID.randomUUID().toString(),product.id,"${product.categoryName} · ${product.displayLabel}",PipeCutPlanner.feet(feet),location.trim().take(100))
        db.settingsDao().upsertSetting(SettingsEntity("pipe-offcut:${row.id}",gson.toJson(row)))
    }
    suspend fun remove(record: Record) = db.withTransaction {
        PermissionChecker.enforceOwner(session.current)
        val existing=db.settingsDao().getSetting("pipe-offcut:${record.id}")?.let(::decode)
        require(existing?.revision==record.revision && existing.available) { "This piece changed. Refresh the list before removing it." }
        db.settingsDao().deleteSetting("pipe-offcut:${record.id}")
    }
    data class Sale(val original:Record,val soldFeet:Int)
    /** Called by checkout inside its Room transaction. Normal billing is the ONLY stock decrement. */
    suspend fun consumeForBill(items:List<CartItem>,billId:Long) {
        PermissionChecker.enforceLogin(session.current)
        val sales=items.mapNotNull { item->item.offcut?.let { reference->
            require(item.product.isPipe && item.pipeLengths==0L) { "An offcut must be billed in feet only" }
            val feet=PipeCutPlanner.feet(java.math.BigDecimal.valueOf(item.pipeFeet).stripTrailingZeros().toPlainString())
            val current=requireNotNull(db.settingsDao().getSetting("pipe-offcut:${reference.id}")?.let(::decode)) { "This offcut is no longer available" }
            require(current.available && current.productId==item.product.id && current.revision==reference.revision && current.feet==reference.capacityFeet && feet<=current.feet) {
                "The selected offcut changed or was sold. Remove it and choose an available piece."
            }
            Sale(current,feet)
        } }
        require(sales.map {it.original.id}.distinct().size==sales.size) { "Duplicate offcut selection" }
        sales.forEach {sale->
            val record=sale.original
            val remaining=record.feet-sale.soldFeet
            val updated=if(remaining==0)record.copy(status="SOLD",soldBillId=billId,revision=java.util.UUID.randomUUID().toString())
                else record.copy(feet=remaining,status="AVAILABLE",revision=java.util.UUID.randomUUID().toString())
            db.settingsDao().upsertSetting(SettingsEntity("pipe-offcut:${record.id}",gson.toJson(updated)))
        }
        if(sales.isNotEmpty())db.settingsDao().upsertSetting(SettingsEntity("pipe-offcut-bill:$billId",gson.toJson(sales)))
    }
    /** Cancellation restores the sold length as its own available piece; never undo later cuts. */
    suspend fun restoreCancelledBill(billId:Long) {
        PermissionChecker.enforceOwner(session.current)
        val json=db.settingsDao().getSetting("pipe-offcut-bill:$billId")?.value ?: return
        val sales=gson.fromJson(json,Array<Sale>::class.java).toList()
        val lines=db.billDao().getBillItems(billId)
        require(sales.map {it.original.id}.distinct().size==sales.size && sales.map {it.original.productId}.distinct().size==sales.size) { "Duplicate offcut sale references" }
        sales.forEach { sale->
            val source=lines.singleOrNull {it.productId==sale.original.productId}
            require(sale.soldFeet in 1..2000 && sale.original.feet in sale.soldFeet..2000 && source?.isPipe==true && source.pipeLengths==0L &&
                PipeCutPlanner.feet(java.math.BigDecimal.valueOf(source.pipeFeet).stripTrailingZeros().toPlainString())==sale.soldFeet) { "Offcut sale reference does not match the original bill. Review the register before cancelling." }
            val id=java.util.UUID.nameUUIDFromBytes("cancelled-offcut:$billId:${sale.original.id}".toByteArray()).toString()
            require(db.settingsDao().getSetting("pipe-offcut:$id")==null) { "Offcut cancellation was already restored" }
            val record=Record(id,sale.original.productId,sale.original.productLabel,sale.soldFeet,
                "Cancelled bill $billId · ${sale.original.location}".take(100))
            db.settingsDao().upsertSetting(SettingsEntity("pipe-offcut:$id",gson.toJson(record)))
        }
    }
    suspend fun discard(record:Record)=db.withTransaction {
        PermissionChecker.enforceOwner(session.current)
        val current=requireNotNull(db.settingsDao().getSetting("pipe-offcut:${record.id}")?.let(::decode)) { "Piece is unavailable" }
        require(current.available && current.revision==record.revision) { "This piece changed. Refresh before discarding." }
        val product=requireNotNull(db.productDao().getProductById(record.productId)) { "Product is unavailable" }
        require(product.isActive && product.isPipe) { "Review this pipe configuration before adjusting stock" }
        val domain=Product(product.id,product.categoryId,"",product.typeId,"",product.name,product.size,product.unit,
            product.sellingPricePaisa,product.purchasePricePaisa,product.gstRateBp,product.currentStock,product.minimumStock,product.imagePath,
            isActive=product.isActive,isPipe=true,pipeFeetPerLength=product.pipeFeetPerLength,
            pipeLengthPricePaisa=product.pipeLengthPricePaisa,pipeFootPricePaisa=product.pipeFootPricePaisa)
        val quantity=PipeBilling.stockQuantity(domain,0,record.feet/100.0)
        val next=java.math.BigDecimal.valueOf(product.currentStock).subtract(java.math.BigDecimal.valueOf(quantity)).toDouble()
        require(next>=0) { "Recorded stock is too low. Reconcile stock before discarding this piece." }
        db.productDao().setStock(product.id,next)
        db.stockDao().insertAdjustment(StockAdjustmentEntity(productId=product.id,productName=product.name,oldStock=product.currentStock,
            newStock=next,difference=-quantity,reason="Damaged goods",notes="Discarded offcut ${record.id}: ${PipeCutPlanner.format(record.feet)} ft",adjustedByOwnerId=1))
        db.settingsDao().upsertSetting(SettingsEntity("pipe-offcut:${record.id}",gson.toJson(current.copy(status="DISCARDED",revision=java.util.UUID.randomUUID().toString()))))
    }

    /** Compare revision and length inside the write transaction. Each reviewed plan commits once. */
    suspend fun recordCuts(plan: PipeCutPlanner.Plan, product:Product, operationId:String) = db.withTransaction {
        PermissionChecker.enforceOwner(session.current)
        require(operationId.matches(Regex("[a-f0-9-]{36}")) && plan.productId==product.id) { "Invalid cutting plan" }
        val marker="pipe-cut-plan:$operationId"
        require(db.settingsDao().getSetting(marker)==null) { "This plan was already recorded" }
        val current=db.productDao().getProductById(product.id)
        require(current?.isPipe==true && current.isActive) { "Pipe is no longer active" }
        require(plan.kerf in 0..100 && plan.sources.isNotEmpty() && plan.sources.size<=100) { "Invalid cutting plan" }
        require(plan.sources.mapNotNull { it.piece?.id }.distinct().size==plan.sources.count { it.piece!=null }) { "Duplicate source pieces" }
        plan.sources.forEach { source ->
            require(source.capacity==(source.piece?.length ?: PipeCutPlanner.FULL) && source.capacity in 1..2000 &&
                source.cuts.isNotEmpty() && source.cuts.all { it in 1..2000 }) { "Invalid source lengths" }
            var remaining=source.capacity
            source.cuts.forEach { length->remaining-=if(remaining==length) length else length+plan.kerf }
            require(remaining>=0 && remaining==source.remaining) { "Cut lengths exceed available material" }
        }
        plan.sources.forEach { source -> source.piece?.let { piece ->
            val record=db.settingsDao().getSetting("pipe-offcut:${piece.id}")?.let(::decode)
            require(record!=null && record.available && record.revision==piece.revision && record.feet==piece.length && record.productId==product.id) {
                "An offcut changed after planning. Calculate a new plan." }
        } }
        plan.sources.forEachIndexed { index,source ->
            source.piece?.let { db.settingsDao().deleteSetting("pipe-offcut:${it.id}") }
            if(source.remaining>0) {
                val record=Record(java.util.UUID.randomUUID().toString(),product.id,"${product.categoryName} · ${product.displayLabel}",source.remaining,
                    source.piece?.label?.ifBlank { "Plan leftover ${index+1}" } ?: "Plan leftover ${index+1}")
                db.settingsDao().upsertSetting(SettingsEntity("pipe-offcut:${record.id}",gson.toJson(record)))
            }
        }
        db.settingsDao().upsertSetting(SettingsEntity(marker,"recorded"))
    }
}

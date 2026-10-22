package com.hardwareshop.pos.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Local shop purchasing notes, included in backups. Never writes product stock or financial records. */
@Singleton class WantListRepository @Inject constructor(private val db:AppDatabase,private val session:SessionHolder) {
    data class Entry(val id:String,val productId:Long?,val name:String,val quantity:String,val unit:String,val notes:String,
        val priority:Int,val purchased:Boolean,val createdBy:String,val revision:String,val createdAt:Long,val updatedAt:Long)
    private val gson=Gson()
    private fun actor():String {
        PermissionChecker.enforceLogin(session.current)
        return (session.current as? UserSession.WorkerSession)?.workerId?.let {"worker:$it"} ?: "owner"
    }
    private fun decode(row:SettingsEntity):Entry {
        val entry=gson.fromJson(row.value,Entry::class.java)
        require(row.key=="want-list:${entry.id}" && entry.id.matches(Regex("[a-f0-9-]{36}")) && entry.name.isNotBlank() &&
            entry.quantity.toBigDecimalOrNull()?.signum()==1 && entry.unit.isNotBlank() && entry.priority in 0..2 && entry.revision.isNotBlank()) {
            "A Want List entry is damaged. Restore a valid backup before editing it."
        }
        return entry
    }
    fun observe():Flow<List<Entry>> = flow {
        actor()
        emitAll(db.settingsDao().observeFeatureRecords("want-list:").map {rows->rows.map(::decode)
            .sortedWith(compareBy<Entry> {it.purchased}.thenByDescending {it.priority}.thenBy {it.createdAt})})
    }.flowOn(Dispatchers.Default)
    private suspend fun current(id:String)=requireNotNull(db.settingsDao().getSetting("want-list:$id")?.let(::decode)) {"Entry is no longer available"}
    suspend fun save(previous:Entry?,productId:Long?,name:String,quantity:String,unit:String,notes:String,priority:Int):Entry=db.withTransaction {
        val actor=actor()
        snapshot() // Never append to an unreadable register.
        val number=WeightCalculator.decimal(quantity,true)
        require(number.scale()<=2 && number<=BigDecimal("999999999")) {"Quantity supports up to two decimals"}
        require(name.trim().length in 1..160 && unit.trim().length in 1..24 && notes.length<=500 && priority in 0..2) {"Check item, unit, notes and priority"}
        if(productId!=null)require(db.productDao().getProductById(productId)?.isActive==true) {"Choose an active product or enter an unlinked item"}
        previous?.let {
            val old=current(it.id)
            require(old.revision==it.revision) {"This entry changed. Refresh before editing."}
            require(actor=="owner" || old.createdBy==actor) {"Only the owner or creator can edit this entry"}
        }
        val now=System.currentTimeMillis()
        val entry=Entry(previous?.id ?: UUID.randomUUID().toString(),productId,name.trim(),number.stripTrailingZeros().toPlainString(),
            unit.trim(),notes.trim(),priority,previous?.purchased ?: false,previous?.createdBy ?: actor,
            UUID.randomUUID().toString(),previous?.createdAt ?: now,now)
        db.settingsDao().upsertSetting(SettingsEntity("want-list:${entry.id}",gson.toJson(entry)))
        entry
    }
    suspend fun setPurchased(entry:Entry,purchased:Boolean)=db.withTransaction {
        actor()
        val old=current(entry.id)
        require(old.revision==entry.revision) {"This entry changed. Refresh before updating it."}
        db.settingsDao().upsertSetting(SettingsEntity("want-list:${old.id}",gson.toJson(old.copy(purchased=purchased,
            revision=UUID.randomUUID().toString(),updatedAt=System.currentTimeMillis()))))
    }
    suspend fun remove(entry:Entry)=db.withTransaction {
        val actor=actor();val old=current(entry.id)
        require(old.revision==entry.revision && (actor=="owner" || old.createdBy==actor)) {"Only the owner or creator can remove an unchanged entry"}
        db.settingsDao().deleteSetting("want-list:${old.id}")
    }
    private suspend fun snapshot()=db.settingsDao().getFeatureRecords("want-list:").map(::decode)
    suspend fun quickAdd(product:Product):Boolean=db.withTransaction {
        actor()
        val existing=snapshot()
        if(existing.any {!it.purchased && it.productId==product.id})return@withTransaction false
        save(null,product.id,product.displayLabel,"1",product.unit,"",1)
        true
    }
    fun shareText(entries:List<Entry>):String {
        actor()
        require(entries.size<=1000) {"Filter the list before sharing more than 1,000 entries"}
        val text="Want List / Kharidi List\n"+entries.joinToString("\n") {entry->
            "${if(entry.purchased)"✓" else "□"} ${entry.name} — ${entry.quantity} ${entry.unit} [${listOf("Low","Normal","High")[entry.priority]}]"+
                if(entry.notes.isBlank())"" else " · ${entry.notes}"
        }
        require(text.length<=200000) {"The list is too large to share at once. Filter it and share fewer entries."}
        return text
    }
}

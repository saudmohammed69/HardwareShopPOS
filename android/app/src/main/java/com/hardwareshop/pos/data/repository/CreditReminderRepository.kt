package com.hardwareshop.pos.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Reminder metadata only. The existing credit ledger is never changed by a reminder. */
@Singleton class CreditReminderRepository @Inject constructor(private val db:AppDatabase,
    private val settings:SettingsRepository,private val session:SessionHolder) {
    data class Attempt(val id:String,val customerId:Long,val balancePaisa:Long,val dueDate:String,val text:String,
        val createdAt:Long,val state:String,val detail:String="")
    data class Snapshot(val balancePaisa:Long=0,val dueDate:String="",val attempts:List<Attempt> = emptyList())
    private val gson=Gson()
    private fun owner()=PermissionChecker.enforceOwner(session.current)
    fun observe(customerId:Long):Flow<Snapshot> = flow {
        owner()
        emitAll(combine(db.creditDao().observeAllBalances(),db.settingsDao().observeFeatureRecords("credit-reminder")) {balances,rows->
            val due=rows.firstOrNull {it.key=="credit-reminder-due:$customerId"}?.value.orEmpty()
            CreditReminders.parseDate(due)
            val attempts=rows.filter {it.key.startsWith("credit-reminder-attempt:")}.map {gson.fromJson(it.value,Attempt::class.java)}
                .filter {it.customerId==customerId}.sortedByDescending {it.createdAt}
            Snapshot(balances.firstOrNull {it.customerId==customerId}?.balance ?: 0,due,attempts)
        })
    }.flowOn(Dispatchers.Default)
    suspend fun setDueDate(customerId:Long,date:String)=db.withTransaction {
        owner()
        require(db.customerDao().getCustomerById(customerId)?.isActive==true) {"Customer is unavailable"}
        val value=CreditReminders.parseDate(date.trim())?.toString().orEmpty()
        db.settingsDao().upsertSetting(SettingsEntity("credit-reminder-due:$customerId",value))
    }
    suspend fun prepare(customerId:Long,today:LocalDate=LocalDate.now()):Attempt=db.withTransaction {
        owner()
        val customer=requireNotNull(db.customerDao().getCustomerById(customerId)) {"Customer is unavailable"}
        require(customer.isActive) {"Customer is unavailable"}
        val balance=db.creditDao().getBalanceForCustomer(customerId)
        val date=db.settingsDao().getSetting("credit-reminder-due:$customerId")?.value.orEmpty()
        Attempt(UUID.randomUUID().toString(),customerId,balance,date,
            CreditReminders.message(customer.name,settings.getShopInfo().name,balance,CreditReminders.parseDate(date),today),
            System.currentTimeMillis(),"PREPARED")
    }
    suspend fun recordOpening(attempt:Attempt)=db.withTransaction {
        owner()
        require(attempt.id.matches(Regex("[a-f0-9-]{36}")) && attempt.balancePaisa>0 && attempt.text.length<=4000) {"Invalid reminder"}
        require(db.customerDao().getCustomerById(attempt.customerId)?.isActive==true) {"Customer is unavailable"}
        require(db.creditDao().getBalanceForCustomer(attempt.customerId)==attempt.balancePaisa &&
            db.settingsDao().getSetting("credit-reminder-due:${attempt.customerId}")?.value.orEmpty()==attempt.dueDate) {
            "Balance or due date changed. Prepare a fresh reminder before sharing."
        }
        val key="credit-reminder-attempt:${attempt.id}"
        val previous=db.settingsDao().getSetting(key)
        if(previous!=null) {
            val old=gson.fromJson(previous.value,Attempt::class.java)
            require(old.customerId==attempt.customerId && old.balancePaisa==attempt.balancePaisa && old.text==attempt.text) {"Reminder reference already used"}
            return@withTransaction old
        }
        val recorded=attempt.copy(state="OPEN_ATTEMPTED")
        db.settingsDao().upsertSetting(SettingsEntity(key,gson.toJson(recorded)))
        recorded
    }
    suspend fun markOutcome(id:String,opened:Boolean,detail:String="")=db.withTransaction {
        owner()
        val row=requireNotNull(db.settingsDao().getSetting("credit-reminder-attempt:$id")) {"Reminder attempt is unavailable"}
        val record=gson.fromJson(row.value,Attempt::class.java)
        db.settingsDao().upsertSetting(row.copy(value=gson.toJson(record.copy(state=if(opened)"SHARE_OPENED" else "OPEN_FAILED",detail=detail.take(200)))))
    }
}

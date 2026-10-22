package com.hardwareshop.pos.util

import androidx.room.withTransaction
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Room transactions serialize reservations across generator instances. */
@Singleton
class BillNumberGenerator @Inject constructor(private val db: AppDatabase) {
    companion object {
        const val SEQUENCE_KEY = "bill_sequence"
    }

    // Joins the billing transaction; a failed sale also rolls back its number.
    suspend fun next(): String = db.withTransaction {
        val stored = db.settingsDao().getSetting(SEQUENCE_KEY)?.value?.toLongOrNull() ?: 0L
        val existing = db.billDao().getMaxNumericBillNumber() ?: 0L
        val next = Math.addExact(maxOf(0L, stored, existing), 1L)
        db.settingsDao().upsertSetting(SettingsEntity(SEQUENCE_KEY, next.toString()))
        String.format(Locale.ROOT, "%06d", next)
    }
}

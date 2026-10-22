package com.hardwareshop.pos.data.repository

import androidx.room.withTransaction
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.DayClosingEntity
import com.hardwareshop.pos.data.local.dao.*
import com.hardwareshop.pos.util.*
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class SalesReport(val totals: SalesTotals, val products: List<TopProductSales>)

class OwnerToolsRepository @Inject constructor(private val db: AppDatabase, private val session: SessionHolder) {
    suspend fun report(days: Long): SalesReport {
        PermissionChecker.enforceOwner(session.current)
        val from = LocalDate.now().minusDays(days - 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val to = System.currentTimeMillis() + 1
        return db.withTransaction { SalesReport(db.netSales(from, to), db.billDao().getTopProducts(from, to)) }
    }

    fun observeClosings() = db.billDao().observeRecentDayClosings()

    suspend fun closeToday(): DayClosingEntity {
        PermissionChecker.enforceOwner(session.current)
        return db.withTransaction {
            val day = LocalDate.now()
            val existing = db.billDao().getClosing(day.toString())
            val from = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val to = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val totals = db.netSales(from, to)
            val closing = DayClosingEntity(id = existing?.id ?: 0, closingDate = day.toString(),
                totalSalesPaisa = totals.sales, cashSalesPaisa = totals.cash, upiSalesPaisa = totals.upi,
                cardSalesPaisa = totals.card, creditSalesPaisa = totals.credit, totalDiscountsPaisa = totals.discounts,
                totalGstPaisa = totals.tax, totalBillCount = totals.billCount,
                cancelledBillCount = db.billDao().getBillsForDay(from, to).count { it.status == "CANCELLED" },
                expectedCashPaisa = totals.cash,
                notes = "Sales snapshot only. Cash includes recorded cash refunds; excludes credit recoveries, expenses and opening cash.", closedByOwnerId = 1)
            val id = db.billDao().insertDayClosing(closing)
            closing.copy(id = id)
        }
    }
}

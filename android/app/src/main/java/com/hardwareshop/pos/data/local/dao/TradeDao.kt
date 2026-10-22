package com.hardwareshop.pos.data.local.dao

import androidx.room.*
import com.hardwareshop.pos.data.local.entity.*

@Dao
interface TradeDao {
    @Insert suspend fun insertPayment(row: SupplierPaymentEntity)
    @Query("SELECT * FROM supplier_payments WHERE id = :id") suspend fun payment(id: String): SupplierPaymentEntity?
    @Query("SELECT * FROM supplier_payments ORDER BY createdAt DESC") suspend fun payments(): List<SupplierPaymentEntity>
    @Query("SELECT COALESCE(SUM(amountPaisa),0) FROM supplier_payments WHERE purchaseBillId = :id") suspend fun paid(id: Long): Long
    @Insert suspend fun insertReturn(row: SaleReturnEntity)
    @Insert suspend fun insertReturnItems(rows: List<SaleReturnItemEntity>)
    @Query("SELECT * FROM sale_returns WHERE id = :id") suspend fun returnById(id: String): SaleReturnEntity?
    @Query("SELECT * FROM sale_returns ORDER BY createdAt DESC") suspend fun returns(): List<SaleReturnEntity>
    @Query("SELECT * FROM sale_return_items ORDER BY id") suspend fun returnItems(): List<SaleReturnItemEntity>
    @Query("SELECT * FROM sale_return_items WHERE billItemId = :id") suspend fun returnedLine(id: Long): List<SaleReturnItemEntity>
    @Query("SELECT COUNT(*) FROM sale_returns WHERE billId = :id OR exchangeBillId = :id") suspend fun linkedReturns(id: Long): Int
    @Query("SELECT COALESCE(SUM(totalPaisa),0) AS total, COALESCE(SUM(discountPaisa),0) AS discount, COALESCE(SUM(taxPaisa),0) AS tax, COALESCE(SUM(creditReductionPaisa),0) AS credit, COALESCE(SUM(CASE WHEN method = 'CASH' THEN refundPaisa ELSE 0 END),0) AS cash, COALESCE(SUM(CASE WHEN method = 'UPI' THEN refundPaisa ELSE 0 END),0) AS upi, COALESCE(SUM(CASE WHEN method = 'CARD' THEN refundPaisa ELSE 0 END),0) AS card FROM sale_returns WHERE createdAt >= :from AND createdAt < :to")
    suspend fun totals(from: Long, to: Long): ReturnTotals
    @Query("DELETE FROM sale_return_items") suspend fun clearReturnItems()
    @Query("DELETE FROM sale_returns") suspend fun clearReturns()
    @Query("DELETE FROM supplier_payments") suspend fun clearPayments()
}
data class ReturnTotals(val total: Long, val discount: Long, val tax: Long, val credit: Long, val cash: Long, val upi: Long, val card: Long)

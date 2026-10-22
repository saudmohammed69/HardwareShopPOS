// data/local/dao/PurchaseBillItemDao.kt
package com.hardwareshop.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hardwareshop.pos.data.local.entity.PurchaseBillItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PurchaseBillItemDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: PurchaseBillItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<PurchaseBillItemEntity>): List<Long>

    @Query("SELECT * FROM purchase_bill_items WHERE purchaseBillId = :billId ORDER BY id ASC")
    fun observeByBill(billId: Long): Flow<List<PurchaseBillItemEntity>>

    @Query("SELECT * FROM purchase_bill_items WHERE purchaseBillId = :billId ORDER BY id ASC")
    suspend fun getByBill(billId: Long): List<PurchaseBillItemEntity>

    @Query("SELECT * FROM purchase_bill_items WHERE productId = :productId ORDER BY createdAt DESC")
    fun observeByProduct(productId: Long): Flow<List<PurchaseBillItemEntity>>

    @Query("SELECT * FROM purchase_bill_items WHERE productId = :productId ORDER BY createdAt DESC")
    suspend fun getByProduct(productId: Long): List<PurchaseBillItemEntity>

    @Query("""
        SELECT * FROM purchase_bill_items
        WHERE purchaseBillId IN (
            SELECT id FROM purchase_bills WHERE billDate BETWEEN :fromEpoch AND :toEpoch
        )
        ORDER BY createdAt DESC
    """)
    suspend fun getByDateRange(fromEpoch: Long, toEpoch: Long): List<PurchaseBillItemEntity>

    @Query("DELETE FROM purchase_bill_items WHERE purchaseBillId = :billId")
    suspend fun deleteByBill(billId: Long)

    @Query("SELECT SUM(itemTotalPaisa) FROM purchase_bill_items WHERE purchaseBillId = :billId")
    suspend fun subtotalForBill(billId: Long): Long?

    @Query("""
        SELECT SUM(pbi.itemTotalPaisa)
        FROM purchase_bill_items pbi
        INNER JOIN purchase_bills pb ON pbi.purchaseBillId = pb.id
        WHERE pb.billDate BETWEEN :fromEpoch AND :toEpoch
    """)
    suspend fun totalPurchaseValueInRange(fromEpoch: Long, toEpoch: Long): Long?
    @Query("SELECT * FROM purchase_bill_items ORDER BY id")
    suspend fun getAll(): List<PurchaseBillItemEntity>

    @Query("DELETE FROM purchase_bill_items")
    suspend fun deleteAll()
}
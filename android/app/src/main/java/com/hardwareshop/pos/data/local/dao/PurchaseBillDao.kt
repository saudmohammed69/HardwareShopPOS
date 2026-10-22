package com.hardwareshop.pos.data.local.dao

import androidx.room.*
import com.hardwareshop.pos.data.local.entity.PurchaseBillEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PurchaseBillDao {
    @Query("SELECT * FROM purchase_bills ORDER BY billDate DESC, id DESC")
    fun observePurchaseBills(): Flow<List<PurchaseBillEntity>>
    @Query("SELECT * FROM purchase_bills ORDER BY id")
    suspend fun getAllPurchaseBills(): List<PurchaseBillEntity>
    @Query("SELECT * FROM purchase_bills WHERE id = :id")
    suspend fun getPurchaseBillById(id: Long): PurchaseBillEntity?
    @Query("SELECT * FROM purchase_bills WHERE supplierId = :supplierId ORDER BY billDate DESC")
    suspend fun getBySupplier(supplierId: Long): List<PurchaseBillEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPurchaseBill(bill: PurchaseBillEntity): Long
    @Update
    suspend fun updatePurchaseBill(bill: PurchaseBillEntity)
    @Query("DELETE FROM purchase_bills")
    suspend fun deleteAll()
}

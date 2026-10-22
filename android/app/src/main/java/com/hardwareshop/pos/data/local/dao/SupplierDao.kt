package com.hardwareshop.pos.data.local.dao

import androidx.room.*
import com.hardwareshop.pos.data.local.entity.SupplierEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplierDao {
    @Query("SELECT * FROM suppliers WHERE isActive = 1 ORDER BY name")
    fun observeActiveSuppliers(): Flow<List<SupplierEntity>>
    @Query("SELECT * FROM suppliers ORDER BY id")
    suspend fun getAllSuppliers(): List<SupplierEntity>
    @Query("SELECT * FROM suppliers WHERE id = :id")
    suspend fun getSupplierById(id: Long): SupplierEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSupplier(supplier: SupplierEntity): Long
    @Update
    suspend fun updateSupplier(supplier: SupplierEntity)
    @Query("DELETE FROM suppliers")
    suspend fun deleteAll()
}

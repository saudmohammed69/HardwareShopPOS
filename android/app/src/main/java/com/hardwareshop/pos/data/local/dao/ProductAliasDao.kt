package com.hardwareshop.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.hardwareshop.pos.data.local.entity.ProductAliasEntity
import kotlinx.coroutines.flow.Flow

@Dao interface ProductAliasDao {
    @Query("SELECT * FROM product_aliases WHERE productId=:productId ORDER BY normalizedAlias")
    fun observe(productId:Long):Flow<List<ProductAliasEntity>>
    @Query("SELECT * FROM product_aliases WHERE productId=:productId ORDER BY normalizedAlias")
    suspend fun get(productId:Long):List<ProductAliasEntity>
}

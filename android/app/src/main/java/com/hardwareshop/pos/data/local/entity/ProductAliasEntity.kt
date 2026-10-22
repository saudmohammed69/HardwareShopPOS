package com.hardwareshop.pos.data.local.entity

import androidx.room.*

/** Indexed projection of owner-managed aliases, linked to the unchanged product ID. */
@Entity(tableName="product_aliases",primaryKeys=["productId","alias"],foreignKeys=[ForeignKey(
    entity=ProductEntity::class,parentColumns=["id"],childColumns=["productId"],onDelete=ForeignKey.CASCADE)],
    indices=[Index("productId"),Index("normalizedAlias")])
data class ProductAliasEntity(val productId:Long,val alias:String,val normalizedAlias:String)

package com.hardwareshop.pos.data.website

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Additive, device-local cache. The existing accounting Room database stays v11. */
@Entity(tableName="website_orders")
data class WebsiteOrderRow(@PrimaryKey val id:String,val revision:Long,val status:String,val document:String,
    val eventSequence:Long,val updatedAt:Long,val unread:Boolean=false,val alerted:Boolean=false,
    val checklist:String="[]",val claimDevice:String?=null,val serverBillId:String?=null)
@Entity(tableName="website_cursor")
data class WebsiteCursor(@PrimaryKey val shop:String="proof-shop",val sequence:Long=0)
@Entity(tableName="website_claims")
data class WebsiteClaim(@PrimaryKey val orderId:String,val device:String,val token:String,
    val serverBillId:String?=null,val revision:Long=0,val orderRevision:Long=0)
@Entity(tableName="website_products",indices=[Index(value=["localId"],unique=true)])
data class WebsiteProductRow(@PrimaryKey val publicId:String,val localId:Long,val published:Boolean=true)

@Dao
interface WebsiteOrdersDao {
    @Query("SELECT * FROM website_orders ORDER BY updatedAt DESC") fun observe():Flow<List<WebsiteOrderRow>>
    @Query("SELECT * FROM website_orders ORDER BY updatedAt DESC") suspend fun all():List<WebsiteOrderRow>
    @Query("SELECT * FROM website_orders WHERE id=:id") suspend fun order(id:String):WebsiteOrderRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(row:WebsiteOrderRow)
    @Query("SELECT * FROM website_cursor WHERE shop='proof-shop'") suspend fun cursor():WebsiteCursor?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putCursor(row:WebsiteCursor)
    @Query("SELECT * FROM website_claims WHERE orderId=:id") suspend fun claim(id:String):WebsiteClaim?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putClaim(row:WebsiteClaim)
    @Query("SELECT * FROM website_products") suspend fun products():List<WebsiteProductRow>
    @Query("SELECT * FROM website_products WHERE publicId=:id") suspend fun product(id:String):WebsiteProductRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putProduct(row:WebsiteProductRow)
}

@Database(entities=[WebsiteOrderRow::class,WebsiteCursor::class,WebsiteClaim::class,WebsiteProductRow::class],version=1,exportSchema=true)
abstract class WebsiteOrdersDatabase:RoomDatabase(){abstract fun orders():WebsiteOrdersDao}

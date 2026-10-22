package com.hardwareshop.pos.data.local.entity

import androidx.room.*

@Entity(tableName = "supplier_payments", foreignKeys = [ForeignKey(entity = PurchaseBillEntity::class,
    parentColumns = ["id"], childColumns = ["purchaseBillId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("purchaseBillId")])
data class SupplierPaymentEntity(@PrimaryKey val id: String, val purchaseBillId: Long,
    val amountPaisa: Long, val method: String, val reference: String, val createdAt: Long)

@Entity(tableName = "sale_returns", foreignKeys = [
    ForeignKey(entity = BillEntity::class, parentColumns = ["id"], childColumns = ["billId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = BillEntity::class, parentColumns = ["id"], childColumns = ["exchangeBillId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("billId"), Index("exchangeBillId"), Index("createdAt")])
data class SaleReturnEntity(@PrimaryKey val id: String, val billId: Long, val exchangeBillId: Long?,
    val totalPaisa: Long, val discountPaisa: Long, val taxPaisa: Long,
    val creditReductionPaisa: Long, val refundPaisa: Long, val method: String,
    val reason: String, val createdAt: Long)

@Entity(tableName = "sale_return_items", foreignKeys = [
    ForeignKey(entity = SaleReturnEntity::class, parentColumns = ["id"], childColumns = ["returnId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = BillItemEntity::class, parentColumns = ["id"], childColumns = ["billItemId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("returnId"), Index("billItemId")])
data class SaleReturnItemEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val returnId: String, val billItemId: Long, val quantity: Double,
    val totalPaisa: Long, val discountPaisa: Long, val taxPaisa: Long, val restock: Boolean)

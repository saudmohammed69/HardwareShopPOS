// data/local/entity/PurchaseBillItemEntity.kt
package com.hardwareshop.pos.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "purchase_bill_items",
    foreignKeys = [
        ForeignKey(
            entity = PurchaseBillEntity::class,
            parentColumns = ["id"],
            childColumns = ["purchaseBillId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index("purchaseBillId"),
        Index("productId")
    ]
)
data class PurchaseBillItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** FK → purchase_bills.id */
    val purchaseBillId: Long,

    /** FK → products.id  (live reference for stock ops) */
    val productId: Long,

    /** Snapshot at time of purchase — survives product renames */
    val productName: String,
    val productSize: String,
    val productUnit: String,

    val quantity: Double,

    /** Price paid per unit in paisa */
    val purchasePricePaisa: Long,

    /** GST rate in basis points (e.g. 1800 = 18 %) */
    val gstRateBp: Int,

    /** quantity × purchasePricePaisa, excluding GST */
    val itemTotalPaisa: Long,

    /** GST component in paisa */
    val gstAmountPaisa: Long,

    val createdAt: Long
)
package com.hardwareshop.pos.util

import com.hardwareshop.pos.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode

/** Pure cart replacement. Quantity is the inventory-unit equivalent, never a pricing multiplier. */
object PipeBilling {
    fun total(lengths: Long, feet: Double, lengthPrice: Long, footPrice: Long): Long {
        require(lengths >= 0 && feet.isFinite() && feet >= 0 && lengthPrice >= 0 && footPrice >= 0)
        return Math.addExact(Math.multiplyExact(lengths, lengthPrice), CalculationEngine.itemTotalPaisa(feet, footPrice))
    }
    fun stockQuantity(product: Product, lengths: Long, feet: Double): Double {
        require(product.pipeFeetPerLength > 0) { "Set the physical feet per full pipe in Products" }
        val allFeet = BigDecimal.valueOf(lengths).multiply(BigDecimal(product.pipeFeetPerLength)).add(BigDecimal.valueOf(feet))
        val quantity = when(product.unit.trim().lowercase()) {
            "foot", "feet", "ft" -> allFeet
            "length", "piece", "nos" -> allFeet.divide(BigDecimal(product.pipeFeetPerLength), 8, RoundingMode.UNNECESSARY)
            else -> throw IllegalArgumentException("Pipe stock unit must be Length, Piece or Foot")
        }
        return quantity.toDouble().also { require(it.isFinite()) }
    }
    fun item(product: Product, lengths: Long, feet: Double,
             lengthPrice: Long = product.pipeLengthPricePaisa, footPrice: Long = product.pipeFootPricePaisa): CartItem {
        require(product.isPipe && (lengths > 0 || feet > 0))
        require(lengthPrice > 0 && footPrice > 0) { "Set both pipe selling prices in Products" }
        return CartItem(product, stockQuantity(product, lengths, feet), lengthPrice,
            total(lengths, feet, lengthPrice, footPrice), pipeLengths = lengths,
            pipeFeet = feet, pipeFootPricePaisa = footPrice)
    }
    fun replace(cart: List<CartItem>, product: Product, lengths: Long, feet: Double): List<CartItem> {
        require(lengths >= 0 && feet.isFinite() && feet >= 0)
        // Validate even zero/cleared edits; malformed decimals cannot hide a previous line.
        total(lengths, feet, product.pipeLengthPricePaisa, product.pipeFootPricePaisa)
        val existing = cart.firstOrNull { it.product.id == product.id }
        existing?.offcut?.let { require(lengths==0L && BigDecimal.valueOf(feet).multiply(BigDecimal(100))<=BigDecimal(it.capacityFeet)) { "Feet exceed the selected offcut. Remove the line to use a new pipe." } }
        val next = if(lengths == 0L && feet == 0.0) null else item(product, lengths, feet,
            existing?.unitPricePaisa ?: product.pipeLengthPricePaisa,
            existing?.pipeFootPricePaisa ?: product.pipeFootPricePaisa).copy(offcut=existing?.offcut)
        val index = cart.indexOfFirst { it.product.id == product.id }
        return cart.filterNot { it.product.id == product.id }.toMutableList().apply {
            if(next != null) add(if(index < 0) size else index.coerceAtMost(size), next)
        }
    }
    fun amount(item: CartItem) = if(item.product.isPipe)
        total(item.pipeLengths, item.pipeFeet, item.unitPricePaisa, item.pipeFootPricePaisa)
        else CalculationEngine.itemTotalPaisa(item.quantity, item.unitPricePaisa)
    fun calculation(item: CartItem) = CalculationEngine.CartItemAmount(
        item.quantity, item.unitPricePaisa, item.product.gstRateBp,
        amountOverridePaisa = if(item.product.isPipe) amount(item) else null)
    fun restore(item: CartItem, current: Product): CartItem = if(item.product.isPipe) {
        require(current.isPipe) { "Pipe billing was disabled for ${current.displayLabel}" }
        item(current, item.pipeLengths, item.pipeFeet,
            if (item.unitPricePaisa != item.product.pipeLengthPricePaisa) item.unitPricePaisa else current.pipeLengthPricePaisa,
            if (item.pipeFootPricePaisa != item.product.pipeFootPricePaisa) item.pipeFootPricePaisa else current.pipeFootPricePaisa).copy(offcut=item.offcut)
    } else item.copy(product = current, itemTotalPaisa = CalculationEngine.itemTotalPaisa(item.quantity, item.unitPricePaisa))
}

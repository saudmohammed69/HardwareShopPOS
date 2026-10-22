package com.hardwareshop.pos.util

import java.math.BigDecimal
import java.math.RoundingMode

object TradeMath {
    fun quantity(value: Double): BigDecimal {
        require(value.isFinite() && value > 0) { "Quantity must be positive" }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.UNNECESSARY)
    }
    /** Cumulative allocation makes any sequence of partial returns reconcile to the original line. */
    fun portion(amount: Long, sold: Double, returned: Double, requested: Double): Long {
        val all = quantity(sold)
        require(returned.isFinite() && returned >= 0 && amount >= 0)
        val before = BigDecimal.valueOf(returned).setScale(2, RoundingMode.UNNECESSARY)
        val after = before.add(quantity(requested))
        require(after <= all) { "Return exceeds the remaining sold quantity" }
        fun allocated(q: BigDecimal) = BigDecimal.valueOf(amount).multiply(q).divide(all, 0, RoundingMode.HALF_UP).longValueExact()
        return allocated(after) - allocated(before)
    }
    fun addStock(stock: Double, delta: Double): Double {
        require(stock.isFinite() && delta.isFinite())
        val result = BigDecimal.valueOf(stock).add(BigDecimal.valueOf(delta)).setScale(2, RoundingMode.HALF_UP)
        require(result.abs() <= BigDecimal("1000000000")) { "Stock quantity is too large" }
        return result.toDouble()
    }
}

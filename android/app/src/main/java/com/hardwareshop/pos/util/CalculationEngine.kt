package com.hardwareshop.pos.util

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Money is stored in paisa. Intermediate arithmetic must not overflow Long. */
object CalculationEngine {
    fun itemTotalPaisa(quantity: Double, unitPricePaisa: Long): Long {
        require(quantity.isFinite() && quantity >= 0) { "Enter a finite, positive quantity" }
        require(unitPricePaisa >= 0) { "Price cannot be negative" }
        val qty = BigDecimal.valueOf(quantity).setScale(2, RoundingMode.UNNECESSARY)
        return qty.multiply(BigDecimal.valueOf(unitPricePaisa)).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    fun gstAmountPaisa(basePaisa: Long, gstRateBp: Int): Long {
        require(basePaisa >= 0 && gstRateBp in 0..10000) { "Invalid amount or tax rate" }
        return BigDecimal.valueOf(basePaisa).multiply(BigDecimal.valueOf(gstRateBp.toLong()))
            .divide(BigDecimal(10000), 0, RoundingMode.HALF_UP).longValueExact()
    }

    data class CartItemAmount(val quantity: Double, val unitPricePaisa: Long,
        val gstRateBp: Int = 0, val gstOnItem: Boolean = false, val amountOverridePaisa: Long? = null)
    data class BillCalculation(val subtotalPaisa: Long, val discountPaisa: Long,
        val afterDiscountPaisa: Long, val gstAmountPaisa: Long, val totalPaisa: Long,
        val gstBreakdown: Map<Int, Long>, val itemGstPaisa: List<Long> = emptyList())

    /** Largest-remainder allocation: every paisa is assigned exactly once, ties in line order. */
    internal fun allocate(amount: Long, weights: List<Long>): List<Long> {
        require(amount >= 0 && weights.all { it >= 0 })
        val total = weights.fold(BigInteger.ZERO) { a, w -> a + BigInteger.valueOf(w) }
        if (total == BigInteger.ZERO) return weights.map { 0L }
        val divisions = weights.map { BigInteger.valueOf(it).multiply(BigInteger.valueOf(amount)).divideAndRemainder(total) }
        // BigInteger.longValueExact needs API 31. BigDecimal preserves the exact overflow check on older Android.
        val result = divisions.map { BigDecimal(it[0]).longValueExact() }.toMutableList()
        val remainder = amount - result.fold(0L, Math::addExact)
        divisions.indices.sortedWith(compareByDescending<Int> { divisions[it][1] }.thenBy { it })
            .take(remainder.toInt()).forEach { result[it]++ }
        return result
    }

    fun calculateBill(items: List<CartItemAmount>, discountPaisa: Long = 0,
                      gstEnabled: Boolean = false): BillCalculation {
        require(discountPaisa >= 0) { "Discount cannot be negative" }
        require(items.all { it.gstRateBp in 0..10000 }) { "Invalid tax rate" }
        val bases = items.map { it.amountOverridePaisa?.also { amount -> require(amount >= 0) } ?: itemTotalPaisa(it.quantity, it.unitPricePaisa) }
        val subtotal = bases.fold(0L, Math::addExact)
        val discount = discountPaisa.coerceAtMost(subtotal)
        val discounts = allocate(discount, bases)
        val taxable = bases.indices.map { bases[it] - discounts[it] }
        val lineTax = MutableList(items.size) { 0L }
        val breakdown = linkedMapOf<Int, Long>()
        if (gstEnabled) items.indices.groupBy { items[it].gstRateBp }.forEach { (rate, indices) ->
            if (rate > 0) {
                val weights = indices.map { taxable[it] }
                val tax = gstAmountPaisa(weights.fold(0L, Math::addExact), rate)
                breakdown[rate] = tax
                val allocated = allocate(tax, weights)
                indices.forEachIndexed { i, index -> lineTax[index] = allocated[i] }
            }
        }
        val tax = breakdown.values.fold(0L, Math::addExact)
        return BillCalculation(subtotal, discount, subtotal - discount, tax,
            Math.addExact(subtotal - discount, tax), breakdown, lineTax)
    }

    fun formatRupees(paisa: Long): String = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        .format(BigDecimal.valueOf(paisa, 2))
    fun formatRupeesRaw(paisa: Long): String = String.format(Locale.US, "%,.2f", BigDecimal.valueOf(paisa, 2))
    fun parseRupeesToPaisa(input: String): Long? {
        val clean = input.trim().removePrefix("₹").replace(",", "").trim()
        if (!clean.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) return null
        return try { BigDecimal(clean).movePointRight(2).longValueExact() } catch (_: ArithmeticException) { null }
    }
    fun formatGstRate(rateBp: Int): String = BigDecimal.valueOf(rateBp.toLong(), 2).stripTrailingZeros().toPlainString() + "%"
    fun formatQuantity(qty: Double): String = if (!qty.isFinite()) "—" else
        BigDecimal.valueOf(qty).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    // ── GST inclusive breakdown (tax already included in item price) ──────────
    /**
     * For an inclusive GST rate, the tax embedded in [inclusivePaisa] is:
     *   taxPaisa = round( inclusivePaisa * rateBp / (10000 + rateBp) )
     * Returns Pair(taxPaisa, basePaisa).
     */
    fun gstInclusiveAmountPaisa(inclusivePaisa: Long, gstRateBp: Int): Pair<Long, Long> {
        require(inclusivePaisa >= 0 && gstRateBp in 0..10000) { "Invalid amount or tax rate" }
        if (gstRateBp == 0) return 0L to inclusivePaisa
        val tax = BigDecimal.valueOf(inclusivePaisa)
            .multiply(BigDecimal.valueOf(gstRateBp.toLong()))
            .divide(BigDecimal(10000 + gstRateBp), 0, RoundingMode.HALF_UP)
            .longValueExact()
        return tax to (inclusivePaisa - tax)
    }

    /**
     * Split a GST amount evenly into CGST and SGST (each = gstAmount / 2).
     * Odd paisa goes to CGST.
     */
    fun splitCgstSgst(gstAmountPaisa: Long): Pair<Long, Long> {
        val half = gstAmountPaisa / 2
        val cgst = half + (gstAmountPaisa % 2)   // remainder goes to CGST
        return cgst to half
    }

    /**
     * Full inclusive bill breakdown across a list of items.
     * Each item's price is treated as already containing GST.
     * Returns a [BillCalculation] with gstAmountPaisa = total embedded tax.
     */
    fun calculateBillInclusive(
        items: List<CartItemAmount>,
        discountPaisa: Long = 0
    ): BillCalculation {
        require(discountPaisa >= 0) { "Discount cannot be negative" }
        require(items.all { it.gstRateBp in 0..10000 }) { "Invalid tax rate" }

        val inclusiveTotals = items.map { it.amountOverridePaisa?.also { amount -> require(amount >= 0) } ?: itemTotalPaisa(it.quantity, it.unitPricePaisa) }
        val subtotal = inclusiveTotals.fold(0L, Math::addExact)
        val discount = discountPaisa.coerceAtMost(subtotal)
        val discounts = allocate(discount, inclusiveTotals)

        val breakdown = linkedMapOf<Int, Long>()
        val lineTax = MutableList(items.size) { 0L }

        items.indices.groupBy { items[it].gstRateBp }.forEach { (rate, indices) ->
            if (rate > 0) {
                indices.forEach { idx ->
                    val afterDisc = inclusiveTotals[idx] - discounts[idx]
                    val (tax, _) = gstInclusiveAmountPaisa(afterDisc, rate)
                    lineTax[idx] = tax
                    breakdown[rate] = (breakdown[rate] ?: 0L) + tax
                }
            }
        }

        val totalTax = breakdown.values.fold(0L, Math::addExact)
        val afterDiscount = subtotal - discount
        val basePaisa = afterDiscount - totalTax
        return BillCalculation(
            subtotalPaisa    = basePaisa,         // Base amount before tax (Selling Price ÷ 1.18)
            discountPaisa    = discount,
            afterDiscountPaisa = afterDiscount,
            gstAmountPaisa   = totalTax,          // Tax amount (Selling Price − Base)
            totalPaisa       = afterDiscount,     // inclusive: total = Selling Price (unchanged)
            gstBreakdown     = breakdown,
            itemGstPaisa     = lineTax
        )
    }
}

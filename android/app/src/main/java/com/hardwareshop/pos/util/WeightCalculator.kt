package com.hardwareshop.pos.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Measured calibrations only. No material, size or product-name assumptions. */
object WeightCalculator {
    enum class Mode { WIRE, PIECES }
    data class Estimate(val netGrams:BigDecimal,val meters:BigDecimal?,val feet:BigDecimal?,val pieces:BigDecimal?)
    fun decimal(text:String,positive:Boolean=false):BigDecimal {
        require(text.length<=24 && text.matches(Regex("[0-9]+(?:\\.[0-9]{1,8})?"))) { "Enter a valid number with up to eight decimals" }
        return BigDecimal(text).also { require(if(positive)it.signum()>0 else it.signum()>=0) { "Value must be positive" } }
    }
    fun grams(text:String,unit:String):BigDecimal {
        require(unit in setOf("g","kg")) { "Choose grams or kilograms" }
        return decimal(text).multiply(if(unit=="kg")BigDecimal(1000) else BigDecimal.ONE)
    }
    fun sampleUnitWeight(weightGrams:BigDecimal,count:String):BigDecimal {
        require(count.matches(Regex("[1-9][0-9]{0,8}"))) { "Sample count must be a positive whole number" }
        require(weightGrams.signum()>0) { "Sample net weight must be positive" }
        return weightGrams.divide(BigDecimal(count),8,RoundingMode.HALF_UP).also { require(it.signum()>0) { "Calibration is too small" } }
    }
    fun estimate(gross:BigDecimal,tare:BigDecimal,calibration:BigDecimal,mode:Mode):Estimate {
        require(gross.signum()>=0 && tare.signum()>=0 && gross>tare) { "Gross weight must exceed tare weight" }
        require(calibration.signum()>0) { "Save a positive measured calibration first" }
        val net=gross.subtract(tare)
        val value=net.divide(calibration,8,RoundingMode.HALF_UP)
        return if(mode==Mode.WIRE)Estimate(net,value,value.multiply(BigDecimal("3.28084")),null)
        else Estimate(net,null,null,value.setScale(0,RoundingMode.HALF_UP))
    }
    fun modeForUnit(unit:String):Mode? = when(unit.trim().lowercase(Locale.ROOT)) {
        "m","meter","metre","meters","metres","foot","feet","ft"->Mode.WIRE
        "piece","pieces","pcs","nos","number","numbers"->Mode.PIECES
        else->null
    }
    fun billingQuantity(estimate:Estimate,unit:String):BigDecimal = when(unit.trim().lowercase(Locale.ROOT)) {
        "m","meter","metre","meters","metres"->requireNotNull(estimate.meters)
        "foot","feet","ft"->requireNotNull(estimate.feet)
        "piece","pieces","pcs","nos","number","numbers"->requireNotNull(estimate.pieces)
        else->throw IllegalArgumentException("This calculator cannot convert to the product's billing unit")
    }.setScale(2,RoundingMode.HALF_UP).also { require(it.signum()>0) { "Estimated quantity rounds to zero" } }
    fun display(value:BigDecimal)=value.setScale(4,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

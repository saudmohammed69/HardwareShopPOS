package com.hardwareshop.pos

import com.hardwareshop.pos.util.WeightCalculator as W
import java.math.BigDecimal as D
import org.junit.Assert.*
import org.junit.Test

class WeightCalculatorTest {
    private fun fails(work:()->Unit) {try {work();fail("Expected invalid input rejection")}catch(_:IllegalArgumentException){}}
    @Test fun wireSubtractsTareAndUsesMeasuredGramsPerMeter() {
        val e=W.estimate(W.grams("1.2","kg"),W.grams("0.2","kg"),D("25"),W.Mode.WIRE)
        assertEquals(0,D("1000").compareTo(e.netGrams))
        assertEquals(0,D("40").compareTo(requireNotNull(e.meters)))
        assertEquals(0,D("131.2336").compareTo(requireNotNull(e.feet)))
        assertEquals(D("40.00"),W.billingQuantity(e,"Meter"))
        assertEquals(D("131.23"),W.billingQuantity(e,"Foot"))
    }
    @Test fun countedSampleCalibrationIsPerPieceAndRoundsHalfUp() {
        val calibration=W.sampleUnitWeight(D("125"),"50")
        assertEquals(0,D("2.5").compareTo(calibration))
        val e=W.estimate(D("106.25"),D("0"),calibration,W.Mode.PIECES)
        assertEquals(D("43"),e.pieces)
        assertEquals(D("43.00"),W.billingQuantity(e,"Piece"))
    }
    @Test fun tareAndCalibrationCannotProduceNegativeOrInfiniteQuantity() {
        fails {W.estimate(D.ONE,D.ONE,D.ONE,W.Mode.WIRE)}
        fails {W.estimate(D.ONE,D.TEN,D.ONE,W.Mode.WIRE)}
        fails {W.estimate(D.TEN,D.ZERO,D.ZERO,W.Mode.WIRE)}
        fails {W.estimate(D.TEN,D("-1"),D.ONE,W.Mode.WIRE)}
        fails {W.estimate(D.TEN,D.ZERO,D("-1"),W.Mode.PIECES)}
    }
    @Test fun invalidDecimalsAndUnknownUnitsAreRejected() {
        listOf("NaN","Infinity","-1","1e3","1,000","1.123456789","").forEach {text->fails {W.decimal(text)}}
        fails {W.grams("1","lb")}
        assertNull(W.modeForUnit("Box"))
        fails {W.billingQuantity(W.estimate(D.TEN,D.ZERO,D.ONE,W.Mode.PIECES),"kg")}
    }
    @Test fun sampleNeedsCountedPositiveWholePieces() {
        listOf("0","1.5","-2","").forEach {count->fails {W.sampleUnitWeight(D.TEN,count)}}
        fails {W.sampleUnitWeight(D.ZERO,"10")}
    }
    @Test fun quantityRoundingIsExplicitAndZeroEstimateCannotBeBilled() {
        val e=W.estimate(D("1"),D.ZERO,D("3"),W.Mode.WIRE)
        assertEquals(D("0.33"),W.billingQuantity(e,"m"))
        fails {W.billingQuantity(W.estimate(D("0.001"),D.ZERO,D.TEN,W.Mode.WIRE),"m")}
        fails {W.billingQuantity(W.estimate(D("0.1"),D.ZERO,D.TEN,W.Mode.PIECES),"Piece")}
    }
}

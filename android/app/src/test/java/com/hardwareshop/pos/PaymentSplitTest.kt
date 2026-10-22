package com.hardwareshop.pos
import com.hardwareshop.pos.util.*
import org.junit.Test
import org.junit.Assert.*
class PaymentSplitTest {
    @Test fun exactPaisaWithoutRounding(){ PaymentSplit(60001,89999).validate(150000); assertEquals(0L,PaymentSplit(60001,89999).remaining(150000)) }
    @Test fun onePaisaIsNeverLost(){PaymentSplit(0,1).validate(1);PaymentSplit(1,0).validate(1)}
    @Test fun zeroTotalIsValidWithNoMoney(){PaymentSplit(0,0).validate(0)}
    @Test(expected=IllegalArgumentException::class) fun negativeCashRejected(){PaymentSplit(-1,101).validate(100)}
    @Test(expected=IllegalArgumentException::class) fun negativeUpiRejected(){PaymentSplit(101,-1).validate(100)}
    @Test(expected=IllegalArgumentException::class) fun underpaymentRejected(){PaymentSplit(100,99).validate(200)}
    @Test(expected=IllegalArgumentException::class) fun excessRejected(){PaymentSplit(100,101).validate(200)}
    @Test(expected=ArithmeticException::class) fun overflowRejected(){PaymentSplit(Long.MAX_VALUE,1).validate(0)}
    @Test fun paymentParserDoesNotRoundThirdDecimal(){assertNull(CalculationEngine.parseRupeesToPaisa("0.001"));assertEquals(1001L,CalculationEngine.parseRupeesToPaisa("10.01"))}
}

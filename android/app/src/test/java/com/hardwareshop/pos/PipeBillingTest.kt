package com.hardwareshop.pos
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PipeBillingTest {
    private fun product(id: Long = 1) = Product(id,1,"PVC",1,"Pipe","PVC Pipe","1 inch","Length",40000,0,0,10.0,0.0,null,
        isActive=true,isPipe=true,pipeLengthPricePaisa=40000,pipeFootPricePaisa=3500,pipeFeetPerLength=20)
    @Test fun lengthOnly() { assertEquals(40000L, PipeBilling.total(1,0.0,40000,3500)) }
    @Test fun feetOnly() { assertEquals(35000L, PipeBilling.total(0,10.0,40000,3500)) }
    @Test fun independentAdditiveQuantities() { assertEquals(75000L, PipeBilling.total(1,10.0,40000,3500)); assertEquals(97500L,PipeBilling.total(2,5.0,40000,3500)) }
    @Test fun suppliedExamples() {
        val cases = listOf(Triple("PVC Pipe","1 inch",75000L),Triple("PVC Lite Pipe","4 inch",205000L),Triple("UPVC Pipe","1 inch",122000L),Triple("CPVC Pipe","3/4 inch",107000L),Triple("Blue Pipe","3/4 inch",88000L))
        cases.forEach { (name,size,total) -> val v=PipeProductCatalog.variants.single { it.name==name && it.size==size };assertEquals(total,PipeBilling.total(1,10.0,v.length,v.foot)) }
        assertEquals(27,PipeProductCatalog.variants.size)
    }
    @Test fun clearBothRemovesOnlyThisProduct() { val p=product(); val second=product(2);val cart=PipeBilling.replace(PipeBilling.replace(emptyList(),p,1,0.0),second,2,5.0);assertEquals(listOf(2L),PipeBilling.replace(cart,p,0,0.0).map { it.product.id }) }
    @Test fun repeatedUpdatesReplaceAndNeverAccumulate() { val p=product();var cart=emptyList<CartItem>();repeat(20) { cart=PipeBilling.replace(cart,p,1,10.0) };assertEquals(1,cart.size);assertEquals(75000L,cart.single().itemTotalPaisa);cart=PipeBilling.replace(cart,p,2,5.0);assertEquals(97500L,cart.single().itemTotalPaisa) }
    @Test fun multiplePipesKeepTheirOwnQuantities() { val cart=PipeBilling.replace(PipeBilling.replace(emptyList(),product(),1,10.0),product(2),0,5.0);assertEquals(1L,cart[0].pipeLengths);assertEquals(5.0,cart[1].pipeFeet,0.0) }
    @Test fun mixedBillUsesCorrectTaxAndDiscountBase() { val pipe=PipeBilling.item(product(),1,10.0);val ordinary=CartItem(product(2).copy(isPipe=false),2.0,5000,10000); val result=CalculationEngine.calculateBill(listOf(pipe,ordinary).map(PipeBilling::calculation),5000,false);assertEquals(80000L,result.totalPaisa) }
    @Test fun stockUsesTwentyFeetWithoutChangingPricing() { assertEquals(1.5,PipeBilling.item(product(),1,10.0).quantity,0.0);assertEquals(30.0,PipeBilling.item(product().copy(unit="Foot"),1,10.0).quantity,0.0);assertEquals(0.0005,PipeBilling.item(product(),0,0.01).quantity,0.0) }
    @Test fun fractionalFeetMoneyRoundsOnce() { assertEquals(1L,PipeBilling.total(0,0.01,40000,50)) }
    @Test fun twoDecimalFeet() { assertEquals(4375L,PipeBilling.total(0,1.25,40000,3500)) }
    @Test(expected=IllegalArgumentException::class) fun negativeRejected() { PipeBilling.total(-1,0.0,1,1) }
    @Test(expected=IllegalArgumentException::class) fun infinityRejected() { PipeBilling.total(0,Double.POSITIVE_INFINITY,1,1) }
    @Test(expected=ArithmeticException::class) fun overflowRejected() { PipeBilling.total(Long.MAX_VALUE,1.0,40000,3500) }
    @Test(expected=ArithmeticException::class) fun excessDecimalsRejected() { PipeBilling.total(0,0.001,40000,3500) }
    @Test fun zeroHasNoLine() { assertTrue(PipeBilling.replace(emptyList(),product(),0,0.0).isEmpty()) }
    @Test fun savedCartRestoresIndependentQuantitiesAndRates() { val original=PipeBilling.item(product(),1,10.0);val json=Gson().toJson(original);val restored=Gson().fromJson(json,CartItem::class.java);assertEquals(original,restored);val repriced=PipeBilling.restore(restored,product().copy(pipeFootPricePaisa=4000));assertEquals(80000L,repriced.itemTotalPaisa);assertEquals(10.0,repriced.pipeFeet,0.0) }
    @Test fun removeThenReselectStartsEmpty() { val p=product(); val cart=PipeBilling.replace(emptyList(),p,1,10.0).filterNot { it.product.id==p.id };assertTrue(cart.isEmpty());assertEquals(35000L,PipeBilling.replace(cart,p,0,10.0).single().itemTotalPaisa) }
    @Test fun editingRatesPreservesQuantitiesMasterAndLaterQuantityEdits() {
        val p=product().copy(pipeLengthPricePaisa=85000,pipeFootPricePaisa=8000)
        val edited=PipeBilling.item(p,2,5.0,85000,7000)
        assertEquals(205000L,edited.itemTotalPaisa)
        assertEquals(8000L,p.pipeFootPricePaisa)
        val changed=PipeBilling.replace(listOf(edited),p,3,6.0).single()
        assertEquals(297000L,changed.itemTotalPaisa)
        assertEquals(7000L,changed.pipeFootPricePaisa)
        assertEquals(85000L,changed.unitPricePaisa)
        val restored=PipeBilling.restore(Gson().fromJson(Gson().toJson(changed),CartItem::class.java),p)
        assertEquals(changed,restored)
        assertEquals(210000L,PipeBilling.replace(emptyList(),p,2,5.0).single().itemTotalPaisa)
        assertTrue(PipeBilling.replace(listOf(edited),p,0,0.0).isEmpty())
    }
    @Test fun lengthAndFootPricesAreIndependentAndBothReachCalculation() {
        val p=product()
        val lengthOnly=PipeBilling.item(p,2,5.0,85000,p.pipeFootPricePaisa)
        val feetOnly=PipeBilling.item(p,2,5.0,p.pipeLengthPricePaisa,7000)
        val both=PipeBilling.item(p,2,5.0,85000,7000)
        assertEquals(187500L,lengthOnly.itemTotalPaisa)
        assertEquals(115000L,feetOnly.itemTotalPaisa)
        assertEquals(205000L,CalculationEngine.calculateBill(listOf(PipeBilling.calculation(both)),0,false).totalPaisa)
    }
    @Test fun invalidPriceTextIsRejectedWithoutFloatingPointAmounts() {
        listOf("NaN","Infinity","-1","1.234","999999999999999999999999").forEach {
            assertNull(CalculationEngine.parseRupeesToPaisa(it))
        }
        assertEquals(8010L,CalculationEngine.parseRupeesToPaisa("80.10"))
    }
    @Test fun ordinaryRestoreKeepsExistingBehavior() { val p=product().copy(isPipe=false);val line=CartItem(p,2.0,40000,80000);assertEquals(80000L,PipeBilling.restore(line,p).itemTotalPaisa) }
}

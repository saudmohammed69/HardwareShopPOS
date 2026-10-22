package com.hardwareshop.pos
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*
import org.junit.Assert.*
import org.junit.Test
class CartVariantsTest {
    private fun p(id:Long,size:String)=Product(id,1,"PVC",1,"Elbow","PVC Elbow",size,"Piece",id*1000,500,0,20.0,0.0,null,isActive=true)
    private fun line(p:Product,q:Double)=CartItem(p,q,p.sellingPricePaisa,CalculationEngine.itemTotalPaisa(q,p.sellingPricePaisa))
    @Test fun switchingSizeUsesTargetIdAndPriceAndLeavesInventoryObjectsUntouched() {
        val a=p(1,"1/2 inch");val b=p(2,"1 inch")
        val next=CartVariants.edit(listOf(line(a,5.0)),a.id,b,5.0).single()
        assertEquals(2L,next.product.id);assertEquals(10000L,next.itemTotalPaisa);assertEquals(1000L,a.sellingPricePaisa)
    }
    @Test fun changingToExistingCompatibleVariantCombinesItsQuantity() {
        val a=p(1,"1/2 inch");val b=p(2,"1 inch")
        val next=CartVariants.edit(listOf(line(a,2.0),line(b,3.0)),a.id,b,2.0)
        assertEquals(1,next.size);assertEquals(5.0,next.single().quantity,0.0)
    }
    @Test(expected=IllegalArgumentException::class) fun cannotSilentlyMixOverriddenPrices() {
        val a=p(1,"1/2 inch");val b=p(2,"1 inch")
        CartVariants.edit(listOf(line(a,2.0),line(b,3.0).copy(unitPricePaisa=1700)),a.id,b,2.0)
    }
    @Test fun quantityEditPreservesCurrentBillOverride() {
        val a=p(1,"1/2 inch");val next=CartVariants.edit(listOf(line(a,2.0).copy(unitPricePaisa=900)),a.id,a,3.0).single()
        assertEquals(2700L,next.itemTotalPaisa)
    }
    @Test fun pipeEditCombinesBreakdownsWithCorrectIndependentRates() {
        val a=p(1,"1/2 inch").copy(isPipe=true,pipeFeetPerLength=20,pipeLengthPricePaisa=40000,pipeFootPricePaisa=3500)
        val b=a.copy(id=2,size="1 inch",pipeLengthPricePaisa=85000,pipeFootPricePaisa=8000)
        val next=CartVariants.edit(listOf(PipeBilling.item(a,1,5.0),PipeBilling.item(b,2,3.0)),a.id,b,0.0,1,5.0).single()
        assertEquals(3L,next.pipeLengths);assertEquals(8.0,next.pipeFeet,0.0);assertEquals(319000L,next.itemTotalPaisa)
    }
    @Test(expected=IllegalArgumentException::class) fun cannotSwitchToAnotherFamily() {
        val a=p(1,"1/2 inch");CartVariants.edit(listOf(line(a,2.0)),a.id,p(2,"1 inch").copy(name="PVC Tee"),2.0)
    }
    @Test(expected=IllegalArgumentException::class) fun invalidQuantityCannotReplaceExistingLine() {
        val a=p(1,"1/2 inch");CartVariants.edit(listOf(line(a,2.0)),a.id,a,Double.NaN)
    }
    @Test fun sizedHardwareGroupsButSingleVariantsUseExistingFlow() {
        val a=p(1,"3 inch").copy(categoryName="Hardware & Fasteners",name="Hinge",typeName="Hinges")
        val b=a.copy(id=2,size="4 inch")
        assertTrue(ProductSizeGroups.hasChoices(listOf(a,b),a));assertFalse(ProductSizeGroups.hasChoices(listOf(a),a))
        assertEquals(1,ProductSizeGroups.groups(listOf(a,b)).size)
    }
}

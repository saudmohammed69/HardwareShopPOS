package com.hardwareshop.pos

import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.ProductOrdering
import org.junit.Assert.*
import org.junit.Test

class ProductOrderingTest {
    @Test fun popularityPromotesMatchingFamiliesButKeepsExactRelevanceAndSizes() {
        val coupler = product(1, "1 inch", "PVC Coupler", "Coupler")
        val elbowSmall = product(2, "1/2 inch")
        val elbowLarge = product(3, "2 inch")
        val rows = listOf(coupler to 0, elbowLarge to 0, elbowSmall to 0)
        assertEquals(listOf(elbowSmall, elbowLarge, coupler),
            ProductOrdering.ranked(rows, mapOf(3L to 10L, 1L to 1L)))
        assertEquals(listOf(coupler, elbowSmall, elbowLarge),
            ProductOrdering.ranked(listOf(coupler to 0, elbowLarge to 2, elbowSmall to 2), mapOf(3L to 100L)))
    }
    private fun product(id:Long,size:String,name:String="PVC Elbow",type:String="Elbow")=
        Product(id,1,"PVC",1,type,name,size,"Piece",100,50,0,10.0,0.0,null,isActive=true)
    @Test fun fractionsDecimalsAndDoubleDigitSizesAreAscending() {
        val sizes=listOf("10 inch","2 inch","1 1/2 inch","3/4 inch","1 inch","1.25 inch","1/2 inch","1½ inch")
        val rows=sizes.mapIndexed { i,s->product(i.toLong(),s) }
        assertEquals(listOf("1/2 inch","3/4 inch","1 inch","1.25 inch","1 1/2 inch","1½ inch","2 inch","10 inch"),
            ProductOrdering.sorted(rows).map { it.size })
    }
    @Test fun unitsComparePhysicalSizeAndReducerSecondDimensionBreaksTies() {
        val sizes=listOf("1 inch","20 mm","1/2 inch","3/4 inch")
        assertEquals(listOf("1/2 inch","3/4 inch","20 mm","1 inch"),
            ProductOrdering.sorted(sizes.mapIndexed { i,s->product(i.toLong(),s) }).map { it.size })
        val reducers=listOf("1 × 3/4 inch","3/4 × 1/2 inch","1 × 1/2 inch")
        assertEquals(listOf("3/4 × 1/2 inch","1 × 1/2 inch","1 × 3/4 inch"),
            ProductOrdering.sorted(reducers.mapIndexed { i,s->product(i.toLong(),s) }).map { it.size })
    }
    @Test fun searchRelevanceKeepsFamiliesTogetherWithoutChangingProducts() {
        val pvcSmall=product(1,"1/2 inch")
        val pvcBig=product(2,"2 inch")
        val brassSmall=product(3,"1/2 inch","PVC Brass Elbow","Brass Elbow")
        val brassBig=product(4,"2 inch","PVC Brass Elbow","Brass Elbow")
        val sorted=ProductOrdering.ranked(listOf(brassBig to 5,pvcBig to 0,brassSmall to 1,pvcSmall to 2))
        assertEquals(listOf(pvcSmall,pvcBig,brassSmall,brassBig),sorted)
        assertEquals(4,sorted.distinctBy { it.id }.size)
    }
    @Test fun nameEmbeddedSizeNamedSizesAndUnsizedItemsAreStable() {
        val names=listOf(product(1,"","Elbow Conceal 2\""),product(2,"","Elbow Conceal 1/2\""))
        assertEquals(listOf(2L,1L),ProductOrdering.sorted(names).map { it.id })
        val labels=listOf("Large","","Small","Medium","XL")
        assertEquals(listOf("Small","Medium","Large","XL",""),
            ProductOrdering.sorted(labels.mapIndexed { i,s->product(i.toLong(),s,"Gloves") }).map { it.size })
    }
    @Test fun volumeAndWeightUseTheirOwnUnitConversions() {
        for(sizes in listOf(listOf("1 L","100 ml","500ML"),listOf("1 kg","100 g","500g"))) {
            assertEquals(listOf(sizes[1],sizes[2],sizes[0]),
                ProductOrdering.sorted(sizes.mapIndexed { i,s->product(i.toLong(),s) }).map { it.size })
        }
    }
    @Test fun anglesAndMaterialsRemainDifferentFamiliesAndBadSizesDoNotCrash() {
        val straight=product(1,"1 inch","PVC Elbow 90°")
        val angled=product(2,"2 inch","PVC Elbow 45°")
        assertEquals(listOf(angled,straight),ProductOrdering.sorted(listOf(straight,angled)))
        assertEquals(3,ProductOrdering.sorted(listOf(product(3,"1/0 inch"),product(4,"unknown"),product(5,"1 / 2 inch"))).size)
    }
    @Test fun ordinaryElbowsPrecedeBrassAndModelNumbersUseNaturalOrder() {
        val normal=product(1,"1 inch")
        val brass=product(2,"1 inch","PVC Brass Elbow","Brass Elbow")
        assertEquals(listOf(normal,brass),ProductOrdering.sorted(listOf(brass,normal)))
        val models=listOf(product(3,"","MCB 10A"),product(4,"","MCB 2A"),product(5,"","MCB 16A"))
        assertEquals(listOf(4L,3L,5L),ProductOrdering.sorted(models).map { it.id })
    }
}

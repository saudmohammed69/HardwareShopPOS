package com.hardwareshop.pos

import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.ProductSizeGroups
import org.junit.Assert.*
import org.junit.Test

class ProductSizeGroupsTest {
    private fun paint(id: Long, size: String)=Product(id,1,"Paints & Coatings",1,"Colour Paints","Paint – Black",size,
        "Piece",10000,5000,1800,10.0,0.0,null,isActive=true)
    private fun fitting(id: Long,size:String)=paint(id,size).copy(categoryId=2,categoryName="PVC",typeId=2,typeName="Elbow",name="PVC Elbow")
    @Test fun blackPaintCollapsesToOneFamilyWithAllSavedPackSizesInOrder() {
        val products=listOf(paint(1,"1 L"),paint(2,"500 ml"),paint(3,"100 ml"),paint(4,"250 ml"))
        val groups=ProductSizeGroups.groups(products)
        assertEquals(1,groups.size);assertEquals("Paint",groups.single().name)
        assertEquals(listOf("100 ml","250 ml","500 ml","1 L"),groups.single().products.map { it.size })
        assertTrue(groups.single().sizeFirst)
    }
    @Test fun searchForOneSizeStillOpensAllSavedSizesOfThatItem() {
        val all=listOf(paint(1,"100 ml"),paint(2,"500 ml"),paint(3,"1 L"))
        assertEquals(3,ProductSizeGroups.groups(listOf(all[1]),all).single().products.size)
        assertEquals(all.toSet(),ProductSizeGroups.choices(all,all[1]).toSet())
    }
    @Test fun coloursBrandsAndFinishesStaySeparate() {
        val black=paint(1,"100 ml")
        val products=listOf(black,paint(2,"1 L"),black.copy(id=3,name="Paint – White"),
            black.copy(id=4,name="Brand B Paint – Black"),black.copy(id=5,name="Matt Paint – Black"))
        assertEquals(3,ProductSizeGroups.groups(products).size)
    }
    @Test fun materialBrassGradesAndReducerDimensionsArePreserved() {
        val pvc=fitting(1,"1/2 inch")
        val products=listOf(pvc,fitting(2,"1 inch"),pvc.copy(id=3,categoryId=3,categoryName="CPVC",name="CPVC Elbow"),
            pvc.copy(id=4,name="PVC Brass Elbow"),pvc.copy(id=5,name="PVC Elbow Heavy"))
        assertEquals(4,ProductSizeGroups.groups(products).size)
        val reducer=pvc.copy(name="PVC Reducer",typeName="Reducer",typeId=5)
        assertEquals(listOf("1 inch × 1/2 inch","2 inch × 1 inch"),ProductSizeGroups.choices(
            listOf(reducer.copy(id=7,size="2 inch × 1 inch"),reducer.copy(id=6,size="1 inch × 1/2 inch")),reducer).map { it.size })
    }
    @Test fun capacitiesNeverBecomeBillingQuantityAndRatesAreUntouched() {
        val p=paint(1,"500 ml")
        val choice=ProductSizeGroups.choices(listOf(p),p).single()
        assertEquals(p,choice)
        assertEquals("Number of containers (500 ml)",ProductSizeGroups.quantityLabel(choice))
    }
    @Test fun sellingUnitsRemainSeparate() {
        val p=fitting(1,"1/2 inch").copy(name="Garden Pipe",categoryName="Plumbing Accessories")
        assertEquals(2,ProductSizeGroups.groups(listOf(p.copy(unit="Roll"),p.copy(id=2,unit="Meter"))).size)
        val pipe=p.copy(isPipe=true,pipeFeetPerLength=20,pipeLengthPricePaisa=85000,pipeFootPricePaisa=8000)
        val mixedStockUnits=listOf(pipe.copy(unit="Foot"),pipe.copy(id=3,size="1 inch",unit="Length"))
        assertEquals(1,ProductSizeGroups.groups(mixedStockUnits).size)
        assertEquals(mixedStockUnits.toSet(),ProductSizeGroups.groups(mixedStockUnits).single().products.toSet())
    }
    @Test fun inactiveVariantsAreNeverOfferedAndNewVariantsAppear() {
        val p=paint(1,"100 ml")
        assertEquals(listOf(p),ProductSizeGroups.choices(listOf(p,p.copy(id=2,size="1 L",isActive=false)),p))
        assertEquals(2,ProductSizeGroups.choices(listOf(p,p.copy(id=3,size="500 ml")),p).size)
    }
    @Test fun singleSizeKeepsOriginalFlow() {
        assertFalse(ProductSizeGroups.groups(listOf(paint(1,"100 ml"))).single().sizeFirst)
        val ordinary=paint(2,"").copy(categoryName="Tools",name="Hammer",typeName="Hammers")
        assertFalse(ProductSizeGroups.sizeFirst(ordinary))
        assertEquals("Quantity (Piece)",ProductSizeGroups.quantityLabel(ordinary))
        assertFalse(ProductSizeGroups.hasChoices(listOf(ordinary),ordinary))
    }
    @Test fun pipesAndAllThreeFittingMaterialsUseSizeSelection() {
        listOf("PVC","CPVC","UPVC").forEach { assertTrue(ProductSizeGroups.sizeFirst(fitting(1,"1/2 inch").copy(categoryName=it))) }
        assertTrue(ProductSizeGroups.sizeFirst(paint(2,"1 inch").copy(categoryName="Other",isPipe=true)))
        assertTrue(ProductSizeGroups.sizeFirst(fitting(3,"1 inch").copy(categoryName="Stock Items")))
        assertTrue(ProductSizeGroups.sizeFirst(fitting(4,"1 inch").copy(categoryName="Construction",name="Section Pipe")))
        assertTrue(ProductSizeGroups.sizeFirst(fitting(5,"1 inch").copy(categoryName="Electrical",name="Electrical Pipe")))
    }
    @Test fun groupOrderPreservesSearchRankingAndFavourites() {
        val black=paint(1,"1 L"); val white=paint(2,"500 ml").copy(name="Primer – White")
        assertEquals(listOf("Primer – White","Paint"),ProductSizeGroups.groups(listOf(white,black)).map { it.name })
    }
    @Test fun exactRepeatedSizeSuffixIsDisplayOnly() {
        val p=paint(1,"500 ml").copy(name="Brand Paint Black 500 ml")
        assertEquals("Brand Paint Black",ProductSizeGroups.familyName(p));assertEquals("Brand Paint Black 500 ml",p.name)
        assertEquals("Paint – Black",ProductSizeGroups.familyName(paint(2,"1 L")))
    }
    @Test fun unsizedAndDuplicateSizedInventoryRecordsKeepTheirIds() {
        val products=listOf(paint(1,""),paint(2,"500 ml"),paint(3,"500 ml").copy(sellingPricePaisa=20000))
        assertEquals(setOf(1L,2L,3L),ProductSizeGroups.groups(products).flatMap { it.products }.map { it.id }.toSet())
    }
}


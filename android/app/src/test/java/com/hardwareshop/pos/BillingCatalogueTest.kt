package com.hardwareshop.pos

import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BillingCatalogueTest {
    private fun product(id:Long,name:String,size:String="1 inch",price:Long=10000)=
        Product(id,1,"PVC",1,"Fittings",name,size,"Piece",price,0,1800,20.0,0.0,null,isActive=true)
    @Test fun preparedSearchPreservesExistingRankingFractionsAndMaterialExclusion()=runBlocking {
        val items=listOf(product(1,"PVC Elbow","1/2 inch"),product(2,"PVC Elbow","3/4 inch"),product(3,"CPVC Elbow","3/4 inch").copy(categoryName="CPVC"),product(4,"PVC Socket"))
        val frequency=mapOf(1L to 10L)
        val index=BillingCatalogue(items,frequency)
        for(query in listOf("3/4 elbow pvc","0.75 pvc elbow","pvc bend","socket","cpvc elbow","","nonsense")){
            val matcher=ProductSearch.Matcher(ProductSearch.tokens(query))
            val original=ProductOrdering.ranked(items.mapNotNull {p->matcher.score("${p.name} ${p.size} ${p.unit} ${p.categoryName} ${p.typeName}",p.searchAliases)?.let{p to it}},frequency).take(100)
            assertEquals(query,original.map{it.id},index.search(query).map{it.id})
        }
    }
    @Test fun legacyPipeAliasNamesStillFindTheirCanonicalInventoryRecord()=runBlocking {
        val current=product(1,"PVC Pipe").copy(isPipe=true,pipeLengthPricePaisa=10000,pipeFootPricePaisa=1000,pipeFeetPerLength=20)
        val legacy=product(2,"Old blue plumbing tube")
        val index=BillingCatalogue(listOf(current),emptyMap(),mapOf(2L to 1L),listOf(current,legacy))
        assertEquals(listOf(1L),index.search("old blue").map{it.id})
        assertEquals(listOf(1L),index.matchingPrevious("old blue",listOf(current)).map{it.id})
    }
    @Test fun pendingMatchesOnlyContainCurrentQueryAndFreshProductPrices(){
        val old=product(1,"PVC Elbow",price=10000)
        val current=old.copy(sellingPricePaisa=12500)
        val index=BillingCatalogue(listOf(current,product(2,"PVC Socket")),emptyMap())
        assertEquals(12500L,index.matchingPrevious("elbow",listOf(old)).single().sellingPricePaisa)
        assertTrue(index.matchingPrevious("socket",listOf(old)).isEmpty())
        assertTrue(BillingCatalogue(emptyList(),emptyMap()).matchingPrevious("elbow",listOf(old)).isEmpty())
    }
    @Test fun familyIndexReusesSortedVariantsWithoutChangingRelevance(){
        val half=product(1,"PVC Elbow","1/2 inch");val quarter=product(2,"PVC Elbow","3/4 inch");val socket=product(3,"PVC Socket")
        val source=listOf(quarter,socket,half)
        val index=ProductSizeGroups.Index(source)
        assertEquals(listOf(1L,2L),index.choices(quarter).map{it.id})
        assertEquals(listOf("PVC Socket","PVC Elbow"),index.groups(listOf(socket,quarter,half)).map{it.name})
        assertEquals(listOf(2L,3L,1L),source.map{it.id})
        assertTrue(index.hasChoices(half));assertFalse(index.hasChoices(socket))
    }
    @Test fun duplicateDisplayChoicesAndInactiveProductsRemainSeparateAsBefore(){
        val first=product(1,"PVC Elbow");val second=first.copy(id=2)
        val off=product(3,"PVC Elbow","2 inch").copy(isActive=false)
        val index=ProductSizeGroups.Index(listOf(first,second,off))
        assertFalse(index.hasChoices(first))
        assertEquals(listOf(1L,2L),index.groups(listOf(first,second,off)).map{it.products.single().id})
    }
}

package com.hardwareshop.pos
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*
import org.junit.Assert.*
import org.junit.Test
class Phase2VariantsTest {
    private fun product(id:Long=1,name:String="Paint · Purple",size:String="500 ml")=Product(id,1,"Paints & Coatings",1,"Colour Paints",name,size,"Piece",25000,0,1800,0.0,0.0,null,isActive=true)
    @Test fun explicitFamilyAttributesRoundTripThroughExistingField() {
        val attrs=ProductVariantMetadata.Attributes("Paint","Purple","Matt","Water based")
        val aliases=ProductVariantMetadata.aliases(attrs,"wall colour")
        assertEquals(attrs,ProductVariantMetadata.read(product().copy(searchAliases=aliases)))
        assertTrue(aliases.contains("wall colour"))
        assertEquals("Matt · Purple · Water based · 500 ml",ProductVariantMetadata.label(product().copy(searchAliases=aliases)))
    }
    @Test fun coloursAreDistinctCombinationsWithinOneConfirmedParent() {
        val red=product().copy(searchAliases=ProductVariantMetadata.aliases(ProductVariantMetadata.Attributes("Paint","Red")))
        val purple=product(2).copy(searchAliases=ProductVariantMetadata.aliases(ProductVariantMetadata.Attributes("Paint","Purple")))
        assertEquals(1,ProductSizeGroups.groups(listOf(red,purple)).size)
        assertTrue(ProductSizeGroups.hasChoices(listOf(red,purple),red))
        assertEquals(setOf(red.id,purple.id),ProductSizeGroups.choices(listOf(red,purple),red).map{it.id}.toSet())
        assertFalse(CartVariants.compatible(CartItem(red,1.0,25000,25000),purple.copy(id=red.id)))
    }
    @Test fun differentConfirmedParentsBrandsAndCategoriesNeverBecomeOneProduct() {
        val a=product().copy(searchAliases=ProductVariantMetadata.aliases(ProductVariantMetadata.Attributes("Brand A Paint","Purple")))
        val b=product(2).copy(searchAliases=ProductVariantMetadata.aliases(ProductVariantMetadata.Attributes("Brand B Paint","Purple")))
        assertEquals(2,ProductSizeGroups.groups(listOf(a,b)).size)
        assertEquals(2,ProductSizeGroups.groups(listOf(a,a.copy(id=3,categoryId=2))).size)
    }
    @Test fun smartSearchNormalizesWordOrderWhitespaceAndFractionNotation() {
        listOf("pvc elbow 3/4","3/4 elbow pvc"," PVC   ELBOW ¾ ","elbow 0.75 pvc").forEach {
            assertNotNull(ProductSearch.score(ProductSearch.tokens(it),"PVC Elbow 3/4 inch"))
        }
        assertNull(ProductSearch.score(ProductSearch.tokens("pvc elbow"),"CPVC Elbow 3/4 inch"))
        assertNull(ProductSearch.score(ProductSearch.tokens("pvc elbow"),"CPVC Elbow 3/4 inch","pvc bend"))
    }
    @Test fun metadataReplacementDoesNotAccumulateOldMarkers() {
        val old=ProductVariantMetadata.aliases(ProductVariantMetadata.Attributes("Paint","Red"),"wall colour")
        val next=ProductVariantMetadata.aliases(ProductVariantMetadata.Attributes("Paint","Blue"),old)
        assertEquals(1,Regex("pos-variant:").findAll(next).count())
        assertEquals("Blue",ProductVariantMetadata.read(product().copy(searchAliases=next))!!.colour)
    }
    @Test fun corruptMetadataIsNotUsedToGuessAParent() {
        val p=product(name="Unclear item").copy(searchAliases="[[pos-variant:invalid]]")
        assertNull(ProductVariantMetadata.read(p));assertEquals("Unclear item",ProductVariantMetadata.parent(p))
    }
    @Test fun editingVisibleAliasesPreservesTheVariantIdentity() {
        val attrs=ProductVariantMetadata.Attributes("Paint","Purple","Matt")
        val encoded=ProductVariantMetadata.aliases(attrs,"old alias")
        assertEquals("old alias",ProductVariantMetadata.visibleAliases(encoded))
        val next=ProductVariantMetadata.replaceVisibleAliases(encoded,"new alias")
        assertEquals("new alias",ProductVariantMetadata.visibleAliases(next))
        assertEquals(attrs,ProductVariantMetadata.read(product().copy(searchAliases=next)))
    }
    @Test fun groupedSelectorCarriesExactFractionSearchPriorityWithoutAutoSelecting() {
        val half=product(name="PVC Elbow",size="1/2 inch")
        val threeQuarter=product(2,name="PVC Elbow",size="3/4 inch")
        val state=com.hardwareshop.pos.presentation.worker.product.ProductSelectionUiState(
            availableProducts=listOf(half,threeQuarter),sizeChoiceProduct=half,searchQuery="0.75 elbow pvc",isSearchActive=true)
        assertEquals(threeQuarter.id,state.sizeChoices.first().id)
        assertEquals(setOf(half.id,threeQuarter.id),state.sizeChoices.map { it.id }.toSet())
        assertNull(state.pendingProduct)
    }
}

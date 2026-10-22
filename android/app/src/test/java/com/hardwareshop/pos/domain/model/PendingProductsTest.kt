package com.hardwareshop.pos.domain.model

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PendingProductsTest {
    @Test fun boxCountIsOptionalButInvalidCountsAndUnitsBlockSaving() {
        assertFalse(form().gaps().any { it.key == "box" })
        assertFalse(form().copy(piecesPerBox = "100").gaps().any { it.key == "box" })
        for (count in listOf("0", "-1", "1.5", "1000001", "invalid"))
            assertTrue(count, form().copy(piecesPerBox = count).gaps().any { it.key == "box" && !it.optional })
        assertTrue(form().copy(unit = "Box", piecesPerBox = "100").gaps().any { it.key == "box" })
    }
    @Test fun olderSavedDraftsDefaultToDisabledBoxConversion() {
        val old = """{"categoryId":1,"typeId":2,"name":"Elbow","size":"20mm","unit":"Piece","sellingPrice":"12.50"}"""
        val restored = Gson().fromJson(old, ProductForm::class.java)
        assertEquals("", restored.piecesPerBox)
        assertFalse(restored.gaps().any { it.key == "box" })
    }
    private fun form() = ProductForm(categoryId = 1, typeId = 2, name = "PVC Elbow", size = "20mm", sellingPrice = "12.50")

    @Test fun unfinishedFormIdentifiesRequiredDetails() {
        val required = ProductForm(name = "Pipe").gaps().filterNot { it.optional }.map { it.key }
        assertEquals(setOf("category", "type", "selling", "size"), required.toSet())
    }
    @Test fun optionalFieldsCanBeReviewedWithoutHidingRequiredProblems() {
        // Blank optional fields prompt for review; blank purchase price never does.
        assertTrue(form().gaps().isEmpty())
        assertTrue(form().gaps(ProductForm.OPTIONAL_KEYS).isEmpty())
        assertTrue(form().copy(sellingPrice = "").gaps(ProductForm.OPTIONAL_KEYS + "selling").any { it.key == "selling" })
    }
    @Test fun blankOptionalFieldsDoNotBlockSaving() {
        assertTrue(form().copy(stock = "", minimumStock = "", aliases = "", imagePath = null, purchasePrice = "").gaps().isEmpty())
        assertTrue(form().copy(stock = "abc").gaps().any { it.key == "stock" && !it.optional })
        assertTrue(form().copy(minimumStock = "-2").gaps().any { it.key == "minimum" && !it.optional })
        assertTrue(form().copy(size = "").gaps(ProductForm.OPTIONAL_KEYS + "size").any { it.key == "size" && !it.optional })
    }
    @Test fun zeroTaxZeroStockAndExplicitZeroCostAreValid() {
        val fields = form().copy(purchasePrice = "0", gstPercent = "0", stock = "0", minimumStock = "0")
        assertFalse(fields.gaps().any { it.key in setOf("gst", "stock", "minimum", "purchase") })
    }
    @Test fun monetaryParsingIsExactAndRejectsMalformedValues() {
        assertEquals(29L, ProductForm.scaled("0.29"))
        assertEquals(250L, ProductForm.scaled("2.5"))
        listOf("NaN", "Infinity", "-1", "1.001", "12x", "12,50", "1e4", "999999999999999999999").forEach {
            assertNull(it, ProductForm.scaled(it))
        }
    }
    @Test fun fractionalTaxAndInvalidPurchasePricesAreNotSilentlyChanged() {
        assertFalse(form().copy(gstPercent = "2.5").gaps().any { it.key == "gst" })
        assertTrue(form().copy(gstPercent = "101").gaps().any { it.key == "gst" })
        assertTrue(form().copy(purchasePrice = "abc").gaps(setOf("purchase")).any { it.key == "purchase" && !it.optional })
    }
    @Test fun draftRoundTripPreservesPartialInputAndReviewedFields() {
        val original = ProductDraft("draft-1", productId = 33,
            form = form().copy(name = "ಅಂಗಡಿ pipe", sellingPrice = "12.", purchasePrice = "?", size = "1/2\""),
            ignoredOptional = setOf("aliases"), updatedAt = 123L)
        val gson = Gson()
        assertEquals(original, gson.fromJson(gson.toJson(original), ProductDraft::class.java))
    }
    @Test fun fillingFieldsRemovesOnlyThoseGaps() {
        val missing = form().copy(size = "20mm", purchasePrice = "8.00").gaps().map { it.key }.toSet()
        // Photos left the pending flow and a valid purchase price never appears as a gap.
        assertTrue(missing.isEmpty())
    }
    @Test fun reopeningExistingProductPreservesTypeAndFractionalTaxUnderOtherLocales() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val p = Product(3, 1, "PVC", 2, "Pipe", "Pipe", "20mm", "Meter", 1250, 0, 250,
                0.0, 0.0, null, isActive = true)
            val f = ProductForm.fromProduct(p)
            assertEquals(2L, f.typeId)
            assertEquals("12.50", f.sellingPrice)
            assertEquals("2.50", f.gstPercent)
            assertEquals("", f.purchasePrice) // zero in old data is reviewed, not assumed complete
            assertEquals("0.00", ProductForm.fromProduct(p, setOf("purchase")).purchasePrice)
        } finally { Locale.setDefault(old) }
    }
    @Test fun editingDetailsDoesNotRequireChangingReadOnlyStock() {
        val f = form().copy(stock = "-1.0")
        assertTrue(f.gaps().any { it.key == "stock" })
        assertFalse(f.gaps(validateStock = false).any { it.key == "stock" })
    }
    @Test fun openingAndLeavingAnUnchangedFormDoesNotCreateADraft() {
        assertFalse(ProductDraft("new").needsSaving(ProductForm(), emptySet(), false))
        assertFalse(ProductDraft("edit", 2, form()).needsSaving(form(), emptySet(), false))
        assertTrue(ProductDraft("edit", 2, form().copy(name = "Changed")).needsSaving(form(), emptySet(), false))
    }
    @Test fun clearingAnAlreadySavedDraftPersistsTheClearedFields() {
        assertTrue(ProductDraft("saved").needsSaving(ProductForm(), emptySet(), true))
    }
    @Test fun onlyReviewingOptionalFieldsIsStillSaved() {
        assertTrue(ProductDraft("review", 2, form(), setOf("photo"))
            .needsSaving(form(), emptySet(), false))
    }
}

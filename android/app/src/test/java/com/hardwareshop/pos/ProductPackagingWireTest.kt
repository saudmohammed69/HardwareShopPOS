package com.hardwareshop.pos

import com.hardwareshop.pos.data.sync.ProductPackagingWire
import org.junit.Assert.*
import org.junit.Test

class ProductPackagingWireTest {
    @Test fun countAndUserAliasesRoundTripWithoutNewCloudFields() {
        val aliases = "screw, pech\nbolt"
        val encoded = ProductPackagingWire.encode(aliases, 100, "Piece")
        val metadata = ProductPackagingWire.decode(encoded, "Piece")
        assertEquals(aliases, metadata.aliases)
        assertEquals(100, metadata.piecesPerBox)
    }
    @Test fun legacyProductsAndDisabledConversionStayPlain() {
        assertEquals("bend", ProductPackagingWire.encode("bend", 0, "Meter"))
        assertEquals(0, ProductPackagingWire.decode("bend", "Meter").piecesPerBox)
        assertEquals("", ProductPackagingWire.decode("", "Piece").aliases)
    }
    @Test fun invalidMetadataRequiresReviewInsteadOfInventingCounts() {
        for (wire in listOf("@pos-box-v1:0\nx", "@pos-box-v1:1000001\nx", "@pos-box-v1:x\nx", "@pos-box-v1:10"))
            assertTrue(wire, runCatching { ProductPackagingWire.decode(wire, "Piece") }.isFailure)
        assertTrue(runCatching { ProductPackagingWire.decode("@pos-box-v1:100\nx", "Box") }.isFailure)
        assertTrue(runCatching { ProductPackagingWire.encode("@pos-box-v1:10\nx", 100, "Piece") }.isFailure)
    }
}

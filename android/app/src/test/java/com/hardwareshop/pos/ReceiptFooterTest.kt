package com.hardwareshop.pos

import com.hardwareshop.pos.util.*
import org.junit.Test
import org.junit.Assert.*

class ReceiptFooterTest {
    @Test fun defaultFooterRetainsTheExistingWordsAndWrapping() {
        assertEquals(listOf("Thank You!","Visit Again Soon"),ReceiptFooter.lines(ReceiptFooter.DEFAULT))
    }
    @Test fun customFooterWrapsToTheExistingPrinterWidthAndRetainsRegionalText() {
        val lines=ReceiptFooter.lines("Goods once sold require the original bill for any return. ಧನ್ಯವಾದಗಳು!")
        assertTrue(lines.size>=2)
        assertTrue(lines.all {it.codePointCount(0,it.length)<=ReceiptLayout.WIDTH})
        assertTrue(lines.joinToString(" ").contains("ಧನ್ಯವಾದಗಳು"))
    }
    @Test fun printerCommandsAreRemovedAndBlankOrOversizedValuesAreRejected() {
        assertFalse(ReceiptFooter.validate("Thank\u001b@ you").contains('\u001b'))
        for(value in listOf("", " ","a".repeat(301),(1..7).joinToString("\n"){"line"})) {
            assertTrue(runCatching {ReceiptFooter.validate(value)}.isFailure)
        }
    }
}

package com.hardwareshop.pos

import com.google.gson.JsonParser
import com.hardwareshop.pos.util.ProductSearch
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ProductSearchCatalogueTest {
    private fun catalogue() = listOf("final_catalog.json", "store_catalog.json").flatMap { name ->
        val file = listOf(File("app/src/main/assets/$name"), File("src/main/assets/$name"))
            .first { it.exists() }
        JsonParser.parseString(file.readText()).asJsonArray.map { it.asJsonObject }
    }
    @Test fun everyBundledItemIsSearchableByItsOwnNameAndSize() {
        val rows = catalogue()
        assertTrue("Both full catalogues must be checked", rows.size > 1900)
        assertTrue(rows.map { it.get("category").asString }.distinct().size >= 20)
        rows.forEach { row ->
            val text = row.get("name").asString + " " + row.get("size").asString
            assertNotNull(text, ProductSearch.score(ProductSearch.tokens(text), text))
        }
    }
    @Test fun everyCatalogueWordAcceptsOneMissingLetter() {
        val words = catalogue().flatMap { ProductSearch.tokens(it.get("name").asString) }
            .distinct().filter { it.length in 4..24 && it.all(Char::isLetter) }
        assertTrue("Exercise a broad vocabulary", words.size > 200)
        words.forEach { word ->
            val typo = word.removeRange(word.length / 2, word.length / 2 + 1)
            assertNotNull("$typo -> $word", ProductSearch.score(listOf(typo), word))
        }
    }
}

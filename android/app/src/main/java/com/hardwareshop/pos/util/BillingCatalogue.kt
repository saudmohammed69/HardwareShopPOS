package com.hardwareshop.pos.util

import com.hardwareshop.pos.domain.model.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Current Room-backed catalogue, prepared once per data change, never once per keypress. */
class BillingCatalogue(val products: List<Product>, val frequency: Map<Long,Long>, val aliases: Map<Long,Long> = emptyMap(),
    private val searchProducts: List<Product> = products) {
    val families = ProductSizeGroups.Index(products)
    private val byId=products.associateBy { it.id }
    private val labels = searchProducts.associate { product -> product.id to ProductSearch.preparedLabel(
        "${product.name} ${product.size} ${product.unit} ${product.categoryName} ${product.typeName}",product.searchAliases) }
    private val targetLabels=searchProducts.groupBy { aliases[it.id] ?: it.id }
        .mapValues { (_,sources)->sources.mapNotNull { labels[it.id] } }
    fun matchingPrevious(query: String, previous: List<Product>): List<Product> {
        val terms=ProductSearch.tokens(query.take(160)).take(20)
        if(terms.isEmpty())return emptyList()
        val matcher=ProductSearch.Matcher(terms)
        return previous.mapNotNull { byId[it.id] }.filter { targetLabels[it.id].orEmpty().any { label->matcher.score(label)!=null } }
    }
    suspend fun search(query: String): List<Product> = withContext(Dispatchers.Default) {
        val terms=ProductSearch.tokens(query.take(160)).take(20)
        if(terms.isEmpty())return@withContext emptyList()
        val matcher=ProductSearch.Matcher(terms)
        val ranked=searchProducts.mapNotNull { product ->
            currentCoroutineContext().ensureActive()
            matcher.score(labels.getValue(product.id))?.let { product to it }
        }
        ProductOrdering.ranked(ranked,frequency).take(100).mapNotNull { byId[aliases[it.id] ?: it.id] }.distinctBy { it.id }
    }
}

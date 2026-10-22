package com.hardwareshop.pos.util

import com.hardwareshop.pos.domain.model.Product
import java.util.Locale

/** Local hints, never automatic cart additions or a guarantee of compatibility. */
object JobSuggestions {
    data class Suggestion(val product: Product, val reason: String)
    private fun words(p: Product) = "${p.categoryName} ${p.typeName} ${p.name}".lowercase(Locale.ROOT)
    private fun material(p: Product): String? {
        val text=words(p)
        return listOf("cpvc","upvc","pvc","hdpe","ppr","gi").firstOrNull {
            Regex("\\b$it\\b").containsMatchIn(text)
        }
    }
    fun build(catalogue: List<Product>, cart: List<Product>, history: Map<Long,Long>): List<Suggestion> {
        if(cart.isEmpty()) return emptyList()
        val byId=catalogue.associateBy { it.id }
        val labelledCart=cart.map { byId[it.id] ?: it }
        val ids=cart.map { it.id }.toSet()
        val candidates=catalogue.filter { it.isActive && it.currentStock>0 && it.id !in ids }.distinctBy { it.id }
        val suggested=linkedMapOf<Long,Suggestion>()
        candidates.filter { (history[it.id] ?: 0)>0 }.sortedWith(compareByDescending<Product> { history[it.id] ?: 0 }
            .thenBy { it.id }).take(6).forEach {
            suggested[it.id]=Suggestion(it,"Bought together in ${history[it.id]} saved bills")
        }
        labelledCart.filter { it.isPipe }.forEach { pipe ->
            val size=PipeSizeChoices.key(pipe.size) ?: return@forEach
            val material=material(pipe) ?: return@forEach
            candidates.filter { candidate ->
                val text=words(candidate)
                !candidate.isPipe && material(candidate)==material && PipeSizeChoices.key(candidate.size)==size &&
                    Regex("\\b(elbow|socket|coupler|tee|clamp|union|cap)\\b").containsMatchIn(text)
            }.sortedWith(compareBy<Product> { it.typeName }.thenBy { it.name }).take(6).forEach {
                suggested.putIfAbsent(it.id,Suggestion(it,"Same material and size as ${pipe.displayLabel}"))
            }
            candidates.filter { !it.isPipe && material(it)==material &&
                Regex("\\b(solvent|cement)\\b").containsMatchIn(words(it)) }.take(2).forEach {
                suggested.putIfAbsent(it.id,Suggestion(it,"${material.uppercase(Locale.ROOT)} solvent — check label suitability"))
            }
        }
        if(suggested.isEmpty()) {
            val last=labelledCart.last()
            candidates.filter { it.categoryId==last.categoryId }.take(4).forEach {
                suggested[it.id]=Suggestion(it,"More in ${last.categoryName} — browse, check suitability")
            }
        }
        return suggested.values.take(8)
    }
}

package com.hardwareshop.pos.util

import com.google.gson.Gson
import com.hardwareshop.pos.domain.model.Product
import java.util.Base64

/** Explicit optional family attributes carried by the existing synced aliases field. */
object ProductVariantMetadata {
    data class Attributes(val parent:String,val colour:String="",val model:String="",val extra:String="")
    private val marker=Regex("\\[\\[pos-variant:([A-Za-z0-9_=-]+)]]")
    private val gson=Gson()
    fun visibleAliases(aliases:String)=marker.replace(aliases,"").trim()
    fun replaceVisibleAliases(aliases:String,visible:String):String =
        (visible.trim()+" "+marker.find(aliases)?.value.orEmpty()).trim()
    fun identity(product:Product):List<String> {
        val attrs=read(product) ?: Attributes(ProductSizeGroups.familyName(product))
        return listOf(attrs.parent,attrs.colour,attrs.model,attrs.extra,product.size,product.unit)
            .map { ProductSearch.tokens(it).joinToString(" ") }
    }
    fun read(product:Product):Attributes? {
        val encoded=marker.find(product.searchAliases)?.groupValues?.get(1)
        if(encoded!=null) return runCatching {
            gson.fromJson(String(Base64.getUrlDecoder().decode(encoded),Charsets.UTF_8),Attributes::class.java)
                .takeIf { it.parent.isNotBlank() && it.parent.length<=120 && listOf(it.colour,it.model,it.extra).all { value -> value!=null && value.length<=120 } }
        }.getOrNull()
        // This existing naming convention explicitly records a colour. No fuzzy
        // brand/grade removal is applied to other names.
        if(product.typeName.equals("Colour Paints",true) && product.name.startsWith("Paint – "))
            return Attributes("Paint",colour=product.name.removePrefix("Paint – "))
        return null
    }
    fun aliases(attributes:Attributes,existing:String=""):String {
        require(attributes.parent.isNotBlank() && attributes.parent.length<=120)
        require(listOf(attributes.colour,attributes.model,attributes.extra).all { it.length<=120 })
        val encoded=Base64.getUrlEncoder().encodeToString(gson.toJson(attributes).toByteArray(Charsets.UTF_8))
        return (marker.replace(existing,"").trim()+" [[pos-variant:$encoded]]").trim()
    }
    fun label(product:Product):String = read(product)?.let { attributes ->
        listOf(attributes.model,attributes.colour,attributes.extra,product.size).filter { it.isNotBlank() }.joinToString(" · ")
    } ?: product.size
    fun parent(product:Product)=read(product)?.parent ?: ProductSizeGroups.familyName(product)
    fun displayName(attributes:Attributes)=listOf(attributes.parent,attributes.model,attributes.colour,attributes.extra)
        .filter { it.isNotBlank() }.joinToString(" · ")
}

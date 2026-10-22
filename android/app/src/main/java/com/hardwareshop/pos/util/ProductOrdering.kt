package com.hardwareshop.pos.util

import com.hardwareshop.pos.domain.model.Product
import java.util.Locale

/** Display-only ordering: product IDs, stock, prices and saved bills never change. */
object ProductOrdering {
    // These immutable patterns are reused by every catalogue/family sort. The
    // cold catalogue trace identified this path; compiling the same patterns
    // per product needlessly allocates during initial display preparation.
    private val fractionGlyphBoundary = Regex("(?<=\\d)(?=[½¼¾⅛⅜⅝⅞])")
    private val whitespace = Regex("\\s+")
    private val fractionSlash = Regex("\\s*/\\s*")
    private val brass = Regex("\\bbrass\\b")
    private val number = Regex("\\d+(?:\\s+\\d+\\s*/\\s*\\d+|\\s*/\\s*\\d+|\\.\\d+)?")
    private val unit = Regex("^\\s*(mm|cm|meters?|metres?|m|inches|inch|in|ft|feet|foot|kg|grams?|g|ml|litres?|liters?|l|\"|′|″)(?![a-z])")
    private val measured = Regex("\\d+(?:\\s+\\d+\\s*/\\s*\\d+|\\s*/\\s*\\d+|\\.\\d+)?\\s*(?:mm|cm|inch(?:es)?|in|ft|feet|kg|g|ml|l|\"|″)(?![a-z])")
    private fun normalize(text:String):String = text.lowercase(Locale.ROOT)
        .replace(fractionGlyphBoundary," ")
        .replace("½","1/2").replace("¼","1/4").replace("¾","3/4")
        .replace("⅛","1/8").replace("⅜","3/8").replace("⅝","5/8").replace("⅞","7/8")
        .replace(whitespace," ").trim()
    private fun amount(text:String):Double? {
        val parts=text.trim().replace(fractionSlash,"/").split(whitespace,limit=2)
        val fraction=parts.last().split(fractionSlash)
        if(fraction.size==1) return text.toDoubleOrNull()
        val numerator=fraction[0].toDoubleOrNull() ?: return null
        val denominator=fraction[1].toDoubleOrNull()?.takeIf { it>0 } ?: return null
        return numerator/denominator + if(parts.size==2) parts[0].toDoubleOrNull() ?: return null else 0.0
    }
    private data class Size(val dimension:String,val values:List<Double>,val text:String)
    private fun size(text:String):Size {
        val normalized=normalize(text)
        val matches=number.findAll(normalized).toList()
        if(matches.isEmpty()) {
            val named=mapOf("xs" to 0.0,"extra small" to 0.0,"small" to 1.0,"s" to 1.0,
                "medium" to 2.0,"m" to 2.0,"large" to 3.0,"l" to 3.0,"xl" to 4.0,
                "extra large" to 4.0,"xxl" to 5.0)
            return named[normalized]?.let { Size("named",listOf(it),normalized) }
                ?: Size("unknown",emptyList(),normalized)
        }
        fun suffix(match:MatchResult)=unit.find(normalized.substring(match.range.last+1))?.groupValues?.get(1)
        // E.g. "3/4 × 1/2 inch" applies the trailing unit to both dimensions.
        val sharedUnit=matches.mapNotNull(::suffix).lastOrNull()
        var dimension="number"
        val values=matches.mapNotNull { match ->
            val raw=amount(match.value) ?: return@mapNotNull null
            val u=suffix(match) ?: sharedUnit
            val (kind,factor)=when(u) {
                "mm" -> "length" to 1.0; "cm" -> "length" to 10.0
                "m","meter","meters","metre","metres" -> "length" to 1000.0
                "inch","inches","in","\"","″" -> "length" to 25.4
                "ft","feet","foot","′" -> "length" to 304.8
                "kg" -> "weight" to 1000.0; "g","gram","grams" -> "weight" to 1.0
                "l","litre","litres","liter","liters" -> "volume" to 1000.0
                "ml" -> "volume" to 1.0
                else -> "number" to 1.0
            }
            if(dimension=="number") dimension=kind
            raw*factor
        }
        return Size(dimension,values,normalized)
    }
    private fun family(p:Product):String {
        var name=normalize(p.name)
        val explicit=normalize(p.size)
        if(explicit.isNotEmpty()) name=name.replace(explicit,"")
        name=measured.replace(name,"").replace(whitespace," ").trim()
        val variant=if(brass.containsMatchIn(name)) 1 else 0
        val baseName=brass.replace(name,"").replace(whitespace," ").trim()
        return "${normalize(p.categoryName)}|$baseName|$variant|${normalize(p.typeName)}|$name"
    }
    private fun key(p:Product)=size(p.size.ifBlank { measured.find(normalize(p.name))?.value.orEmpty() })
    private fun compareSize(a:Size,b:Size):Int {
        // Known numeric/named sizes precede unsized products; incomparable units stay grouped.
        if(a.values.isEmpty()!=b.values.isEmpty()) return if(a.values.isEmpty()) 1 else -1
        if(a.dimension!=b.dimension) return a.dimension.compareTo(b.dimension)
        for(i in 0 until minOf(a.values.size,b.values.size)) {
            val c=a.values[i].compareTo(b.values[i]); if(c!=0) return c
        }
        return a.values.size.compareTo(b.values.size)
    }
    private data class Row(val product:Product,val family:String,val size:Size)
    private val naturalParts=Regex("\\d+|\\D+")
    private data class NaturalPart(val text: String, val number: java.math.BigInteger?)
    private fun naturalKey(value: String) = naturalParts.findAll(value).map {
        NaturalPart(it.value, it.value.toBigIntegerOrNull())
    }.toList()
    private fun naturalCompare(left:List<NaturalPart>,right:List<NaturalPart>):Int {
        for(i in 0 until minOf(left.size,right.size)) {
            val x=left[i].number; val y=right[i].number
            val c=if(x!=null && y!=null) x.compareTo(y) else left[i].text.compareTo(right[i].text,true)
            if(c!=0) return c
        }
        return left.size.compareTo(right.size)
    }
    fun sorted(products:List<Product>):List<Product> = ranked(products.map { it to 0 })
    /** Relevance ranks whole families so sizes of one family cannot be interleaved. */
    fun ranked(products:List<Pair<Product,Int>>, salesFrequency: Map<Long, Long> = emptyMap()):List<Product> {
        // A singleton family already has its final order. Preserve the copied
        // result and original Product, without constructing unused sort keys.
        if (products.size <= 1) return products.map { it.first }
        val rows=products.map { Row(it.first,family(it.first),key(it.first)) }
        val relevance=rows.zip(products).groupBy { it.first.family }.mapValues { (_,items)->items.minOf { it.second.second } }
        val familyKeys=rows.map { it.family }.distinct().associateWith(::naturalKey)
        val popularity = rows.groupBy { it.family }.mapValues { (_, items) ->
            items.sumOf { salesFrequency[it.product.id] ?: 0L }
        }
        return rows.sortedWith(Comparator { a,b ->
            compareValues(relevance[a.family],relevance[b.family]).takeIf { it!=0 }
                ?: compareValues(popularity[b.family],popularity[a.family]).takeIf { it!=0 }
                ?: naturalCompare(familyKeys.getValue(a.family),familyKeys.getValue(b.family)).takeIf { it!=0 }
                ?: compareSize(a.size,b.size).takeIf { it!=0 }
                ?: a.product.name.compareTo(b.product.name,true).takeIf { it!=0 }
                ?: a.size.text.compareTo(b.size.text).takeIf { it!=0 }
                ?: a.product.id.compareTo(b.product.id)
        }).map { it.product }
    }
}

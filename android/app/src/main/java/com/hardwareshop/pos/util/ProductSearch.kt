package com.hardwareshop.pos.util

import java.util.Locale
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

/** Offline, token-aware ranking. Lower scores are better; null means no match. */
object ProductSearch {
    // Preserve decimal/fractional sizes and letter-led model codes such as B22.
    private val words = Regex("\\p{L}[\\p{L}\\p{M}\\p{N}]*|\\p{N}+(?:\\s+\\p{N}+\\s*/\\s*\\p{N}+|[./]\\p{N}+)?")
    private val compounds = listOf("screw driver", "hack saw", "rain coat", "ball valve",
        "allen key", "c clip", "wash basin", "door lock", "wall plug", "extension board")
        .map { Regex("\\b" + it.replace(" ", "[\\s-]*") + "\\b") to it }
    private val identifiers = setOf("pvc", "cpvc", "upvc", "hdpe", "ppr", "gi", "ss", "ms", "mcb", "rccb", "led", "ac", "dc")
    private val materials = setOf("pvc", "cpvc", "upvc", "hdpe", "ppr", "gi", "ss", "ms")
    // Applied automatically to every existing/new product; owners need not enter aliases.
    // These are search suggestions only. Product identity, size and stock never change.
    private val synonymGroups = listOf(
        listOf("elbow", "bend"), listOf("tee", "t joint", "t connector"),
        listOf("coupler", "coupling", "socket"), listOf("union", "union joint"),
        listOf("reducer", "reducing socket"), listOf("end cap", "end plug", "stop end"),
        listOf("faucet", "water tap", "tap"), listOf("bib cock", "bibcock", "bib tap"),
        listOf("angle cock", "angle valve"), listOf("stop cock", "stopcock", "stop valve"),
        listOf("check valve", "non return valve", "nrv"),
        listOf("ptfe tape", "teflon tape", "thread seal tape", "plumber tape"),
        listOf("hose", "flexible pipe"), listOf("hose clip", "hose clamp", "jubilee clip"),
        listOf("wash basin", "washbasin", "sink basin"), listOf("cistern", "flush tank"),
        listOf("wrench", "spanner", "pana"), listOf("adjustable wrench", "adjustable spanner"),
        listOf("pliers", "plier", "plaas"), listOf("screwdriver", "screw driver", "pechkas"),
        listOf("hacksaw", "hack saw"), listOf("allen key", "hex key"),
        listOf("hammer", "hathoda"), listOf("nail", "nails", "keel"),
        listOf("screw", "screws", "pech"), listOf("nut bolt", "nutbolt"),
        listOf("wall plug", "rawl plug", "rawlplug", "wall anchor"),
        listOf("circlip", "c clip", "retaining ring"), listOf("gasket", "seal gasket"),
        listOf("sandpaper", "sand paper", "emery paper", "abrasive paper"),
        listOf("cutting disc", "cut off wheel", "cutting wheel"),
        listOf("grinding disc", "grinding wheel"), listOf("drill bit", "drillbit"),
        listOf("measuring tape", "tape measure"), listOf("spirit level", "bubble level"),
        listOf("electrical tape", "insulation tape", "electric tape"),
        listOf("extension board", "power strip", "extension socket"),
        listOf("bulb holder", "lamp holder", "bulbholder"),
        listOf("tube light", "tubelight", "fluorescent tube"),
        listOf("wire", "electrical wire", "cable"),
        listOf("mcb", "miniature circuit breaker"),
        listOf("rccb", "residual current circuit breaker"),
        listOf("led", "light emitting diode"),
        listOf("pvc", "polyvinyl chloride"), listOf("cpvc", "chlorinated polyvinyl chloride"),
        listOf("upvc", "unplasticized polyvinyl chloride"),
        listOf("gi", "galvanized iron"), listOf("ss", "stainless steel"),
        listOf("ms", "mild steel"), listOf("hdpe", "high density polyethylene"),
        listOf("putty", "wall filler"), listOf("primer", "undercoat"),
        listOf("paint brush", "paintbrush"), listOf("paint roller", "roller brush"),
        listOf("thinner", "paint thinner"), listOf("adhesive", "glue"),
        listOf("sealant", "silicone sealant"), listOf("bucket", "balti"),
        listOf("broom", "jhaadu"), listOf("padlock", "pad lock", "tala"),
        listOf("door hinge", "hinge", "kabja"), listOf("door bolt", "tower bolt"),
        listOf("safety goggles", "protective glasses"), listOf("gloves", "hand gloves"),
        listOf("adaptor", "adapter"), listOf("fta", "female threaded adaptor", "female threaded adapter"),
        listOf("mta", "male threaded adaptor", "male threaded adapter"),
        listOf("vent cowl", "vent cap"), listOf("floor trap", "nahani trap"),
        listOf("pillar cock", "pillar tap"), listOf("swan neck", "swanneck", "gooseneck tap"),
        listOf("health faucet", "jet spray", "hand shower"),
        listOf("float valve", "ball cock", "ballcock"),
        listOf("tarpaulin", "tarpaulins", "tarp"), listOf("raincoat", "rain coat"),
        listOf("umbrella", "chatri"), listOf("mosquito net", "mosquito mesh"),
        listOf("dustbin", "waste bin", "trash bin"), listOf("dustpan", "dust pan"),
        listOf("phenyl", "floor disinfectant"), listOf("dish cleaner", "dishwash", "dish wash"),
        listOf("cable tie", "cable ties", "zip tie", "zip ties"),
        listOf("junction box", "junctionbox"), listOf("distribution board", "db box"),
        listOf("doorbell", "door bell"), listOf("tester", "test pen", "line tester"),
        listOf("tungsten carbide", "tc"), listOf("welding rod", "welding rods", "welding electrode"),
        listOf("buffer", "buffing wheel", "polishing wheel"),
        listOf("chisel", "chheni"), listOf("lubricant", "lubricating oil"),
        listOf("rope", "rassi"), listOf("twine", "sutli"), listOf("mesh", "jali"),
        listOf("wall putty", "wall filler"), listOf("crack filler", "crack seal"),
        listOf("tile grout", "grout"), listOf("tile adhesive", "tile glue"),
        listOf("turpentine oil", "turpentine"), listOf("wood finish", "wood polish"),
        listOf("enamel paint", "enamel"), listOf("spray paint", "aerosol paint"),
        listOf("safety helmet", "hard hat"), listOf("safety shoes", "work boots")
    ).map { group -> group to group.map { phrase ->
        Regex("(?<![\\p{L}\\p{N}])" + phrase.split(' ').joinToString("[\\s-]+") { Regex.escape(it) } + "(?![\\p{L}\\p{N}])")
    } }
    // Test only phrases whose first word exists in this label. Scanning every
    // synonym pattern for every product makes the first search unnecessarily slow.
    private val synonymTriggers = synonymGroups.flatMap { (group, patterns) ->
        group.zip(patterns).map { (phrase, pattern) -> Triple(phrase.substringBefore(' '), pattern, group) }
    }.groupBy { it.first }
    private val aliasCache = ConcurrentHashMap<String, String>()
    private val tokenCache = ConcurrentHashMap<String, List<String>>()
    private val spaces = Regex("\\s+")
    private val fractionGlyphs = mapOf('½' to "1/2", '¼' to "1/4", '¾' to "3/4", '⅛' to "1/8", '⅜' to "3/8", '⅝' to "5/8", '⅞' to "7/8")
        .map { (glyph, fraction) -> Triple(glyph, fraction, Regex("([0-9])${Regex.escape(glyph.toString())}")) }
    private val fractionWords = listOf("one and a half" to "1.5", "three quarters" to "0.75",
        "three quarter" to "0.75", "one quarter" to "0.25", "a quarter" to "0.25",
        "one half" to "0.5", "a half" to "0.5", "half" to "0.5", "quarter" to "0.25")
        .map { (phrase, amount) -> Regex("\\b$phrase(?=\\s*(?:inch|inches|in\\b|[\"″]))") to amount }
    private val inchQuote = Regex("(?<=[0-9])[\"″]")
    fun automaticAliases(text: String): String {
        if (aliasCache.size > 10_000) aliasCache.clear()
        return aliasCache.getOrPut(text) {
            val normalized = text.lowercase(Locale.ROOT)
            words.findAll(normalized).map { it.value }.toSet()
                .flatMap { synonymTriggers[it].orEmpty() }
                .filter { (_, pattern, _) -> pattern.containsMatchIn(normalized) }
                .flatMap { it.third }.distinct().joinToString(", ")
        }
    }

    private fun normalizeSizes(text: String): String {
        var value = text.lowercase(Locale.ROOT).replace('⁄', '/').replace('／', '/')
        for ((glyph, fraction, pattern) in fractionGlyphs)
            value = value.replace(pattern, "$1 $fraction")
                .replace(glyph.toString(), fraction)
        for ((pattern, amount) in fractionWords) value = value.replace(pattern, amount)
        value = value.replace(inchQuote, " inch")
        return value
    }
    private fun numberToken(raw: String): String {
        if (raw.all(Char::isDigit)) return raw // Keep numeric model/part codes, including leading zeros.
        return runCatching {
            val compact = raw.replace(" ", "")
            val number = if ('/' in compact) {
                val parts = raw.trim().split(spaces)
                val fraction = parts.last().split('/')
                val result = BigDecimal(fraction[0]).divide(BigDecimal(fraction[1]), 8, RoundingMode.UNNECESSARY)
                result + if (parts.size > 1) BigDecimal(parts.first()) else BigDecimal.ZERO
            } else BigDecimal(compact)
            number.stripTrailingZeros().toPlainString()
        }.getOrDefault(raw)
    }
    fun tokens(text: String): List<String> {
        if (tokenCache.size > 10_000) tokenCache.clear()
        return tokenCache.getOrPut(text) { tokenize(text) }
    }
    fun prepareLabel(text: String, aliases: String = "") {
        tokens(text)
        tokens(aliases + " " + automaticAliases("$text $aliases"))
    }
    data class PreparedLabel(val primary: List<String>, val extra: List<String>)
    fun preparedLabel(text: String, aliases: String = "") =
        PreparedLabel(tokens(text), tokens(aliases + " " + automaticAliases("$text $aliases")))
    private fun tokenize(text: String): List<String> {
        var normalized = normalizeSizes(text)
        for ((pattern, replacement) in compounds) normalized = pattern.replace(normalized, replacement)
        val base = words.findAll(normalized).map {
            val word = it.value.replace(spaces, " ").trim()
            if (word.first().isDigit()) numberToken(word) else when (word) {
                "in", "inches" -> "inch"
                "millimeter", "millimeters", "millimetre", "millimetres" -> "mm"
                "centimeter", "centimeters", "centimetre", "centimetres" -> "cm"
                "metre", "metres", "meters" -> "meter"
                "feet", "ft" -> "foot"
                "kilogram", "kilograms", "kgs" -> "kg"
                "litre", "litres", "liters" -> "liter"
                else -> word
            }
        }.toList()
        val joined = compounds.filter { (pattern, _) -> pattern.containsMatchIn(normalized) }
            .map { (_, phrase) -> phrase.replace(" ", "") }
        return (base + joined).distinct()
    }

    fun score(query: List<String>, text: String, aliases: String = ""): Int? {
        return Matcher(query).score(text, aliases)
    }

    /** Reuse word comparisons across catalogue entries/sizes for one query. */
    class Matcher(private val query: List<String>) {
        private val cache = HashMap<Pair<String, String>, Int>()
        private fun match(term: String, word: String) = cache.getOrPut(term to word) { cost(term, word) }
        fun score(text: String, aliases: String = ""): Int? {
            return score(preparedLabel(text,aliases))
        }
        fun score(label: PreparedLabel): Int? {
            if (query.isEmpty()) return null
            val primary = label.primary
            val extra = label.extra
            val actualMaterials=primary.filter { it in materials }.toSet()
            if(actualMaterials.isNotEmpty() && query.any { it in materials && it !in actualMaterials }) return null
            if(query.any { it in identifiers && it !in primary && it !in extra }) return null
            val costs = query.map { term ->
                val main = primary.minOfOrNull { match(term, it) } ?: 1000
                val alias = extra.minOfOrNull { match(term, it) + 1 } ?: 1000
                minOf(main, alias).takeIf { it < 1000 } ?: return null
            }
            // An explicitly typed material/electrical identifier takes priority even
            // if a different product has a closer spelling for the remaining word.
            val identityPenalty = query.count { it in identifiers && it !in primary } * 100_000
            return identityPenalty + costs.maxOrNull()!! * 1000 + costs.sum()
        }
    }

    private fun cost(query: String, word: String): Int {
        if (query == word) return 0
        // Do not guess sizes or model numbers (20 must not become 25).
        if (query.any { it.isDigit() }) return 1000
        if (word.startsWith(query)) return 10
        if (query.length >= 3 && word.contains(query)) return 20
        if (query.length < 3 || query.length > 64 || word.length > 64 || word.any { it.isDigit() }) return 1000
        val limit = if (query.length >= 7) 2 else 1
        if (kotlin.math.abs(query.length - word.length) > limit) return 1000
        return if (distance(query, word) <= limit) 30 else 1000
    }

    /** Optimal string alignment: insertion, deletion, substitution or adjacent swap. */
    private fun distance(a: String, b: String): Int {
        val rows = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) rows[i][0] = i
        for (j in 0..b.length) rows[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            rows[i][j] = minOf(rows[i - 1][j] + 1, rows[i][j - 1] + 1,
                rows[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1])
                rows[i][j] = minOf(rows[i][j], rows[i - 2][j - 2] + 1)
        }
        return rows[a.length][b.length]
    }
}

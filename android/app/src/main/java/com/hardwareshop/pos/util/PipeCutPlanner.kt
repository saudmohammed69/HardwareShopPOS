package com.hardwareshop.pos.util

import java.math.BigDecimal

/** All physical measurements use hundredths of a foot, never floating-point subtraction. */
object PipeCutPlanner {
    const val FULL=2000
    data class Piece(val id: String, val productId: Long, val length: Int, val label: String="", val revision: String="")
    data class Source(val piece: Piece?, val capacity: Int, val cuts: List<Int>, val remaining: Int)
    data class Plan(val productId: Long, val sources: List<Source>, val kerf: Int) {
        val newPipes get()=sources.count { it.piece==null }
        val usedOffcuts get()=sources.count { it.piece!=null }
    }
    fun feet(value: String): Int {
        require(value.trim().matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) { "Enter feet with at most two decimal places" }
        val cents=BigDecimal(value.trim()).movePointRight(2).intValueExact()
        require(cents in 1..FULL) { "Length must be greater than 0 and at most 20 ft" }
        return cents
    }
    fun format(value: Int): String=BigDecimal.valueOf(value.toLong(),2).stripTrailingZeros().toPlainString()
    fun requests(value: String): List<Int> {
        val tokens=value.trim().split(Regex("[,;\\n]"))
        require(tokens.size in 1..100 && tokens.all { it.isNotBlank() }) { "Enter 1–100 lengths separated by commas or new lines" }
        return tokens.map(::feet)
    }
    /** Best-fit decreasing heuristic: existing pieces first; exact edge fits need no extra saw cut. */
    fun plan(productId: Long, lengths: List<Int>, pieces: List<Piece>, kerf: Int=0): Plan {
        require(productId>0 && lengths.size in 1..100 && lengths.all { it in 1..FULL }) { "Check product and requested lengths" }
        require(kerf in 0..100) { "Saw allowance must be 0–1 ft" }
        require(pieces.map { it.id }.distinct().size==pieces.size) { "Duplicate offcut records" }
        data class Bin(val piece: Piece?, val capacity: Int, var remaining: Int, val cuts: MutableList<Int>)
        val bins=pieces.filter { it.productId==productId && it.length in 1..FULL }
            .map { Bin(it,it.length,it.length,mutableListOf()) }.toMutableList()
        for(length in lengths.sortedDescending()) {
            fun cost(bin: Bin)=if(bin.remaining==length) length else length+kerf
            val bin=bins.filter { it.remaining>=cost(it) }.minWithOrNull(compareBy<Bin> { if(it.piece!=null) 0 else 1 }
                .thenBy { it.remaining-cost(it) }.thenBy { it.piece?.id.orEmpty() })
                ?: Bin(null,FULL,FULL,mutableListOf()).also {
                    require(it.remaining>=cost(it)) { "A $length hundredth-ft cut plus saw allowance exceeds 20 ft" }
                    bins.add(it)
                }
            bin.remaining-=cost(bin);bin.cuts.add(length)
        }
        return Plan(productId,bins.filter { it.cuts.isNotEmpty() }.map { Source(it.piece,it.capacity,it.cuts.toList(),it.remaining) },kerf)
    }
}

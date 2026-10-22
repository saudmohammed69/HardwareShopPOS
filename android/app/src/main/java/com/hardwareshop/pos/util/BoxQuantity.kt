package com.hardwareshop.pos.util

import java.util.Locale

/** Bills, prices, returns and inventory always use the underlying piece quantity. */
object BoxQuantity {
    fun pieceUnit(unit: String) = unit.trim().lowercase(Locale.ROOT) in setOf("piece", "pieces", "nos")
    fun validate(unit: String, count: Int) {
        require(count in 0..1_000_000 && (count == 0 || pieceUnit(unit))) {
            "Box conversion needs a Piece/Nos product and 1–1,000,000 pieces per box"
        }
    }
    fun pieces(boxes: String, count: Int): Double? {
        if (count !in 1..1_000_000) return null
        val quantity = boxes.trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
        return runCatching { Math.multiplyExact(quantity, count.toLong()).toDouble() }
            .getOrNull()?.takeIf { it.isFinite() && it <= 1_000_000_000L }
    }
}

package com.hardwareshop.pos.data.sync

import com.hardwareshop.pos.util.BoxQuantity

/** Versioned extension in the existing aliases string keeps the deployed v2 field
 * allowlist unchanged. Room stores a dedicated count; user aliases stay plain text.
 * Older clients can still sell base pieces safely, but need upgrading for box entry. */
object ProductPackagingWire {
    private const val PREFIX = "@pos-box-v1:"
    data class Metadata(val aliases: String, val piecesPerBox: Int)
    fun encode(aliases: String, count: Int, unit: String): String {
        BoxQuantity.validate(unit, count)
        require(!aliases.startsWith(PREFIX)) { "Reserved product metadata prefix" }
        return if (count == 0) aliases else "$PREFIX$count\n$aliases"
    }
    fun decode(wire: String, unit: String): Metadata {
        if (!wire.startsWith(PREFIX)) return Metadata(wire, 0)
        val count = wire.substringBefore('\n').removePrefix(PREFIX).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid box metadata")
        require(count > 0 && '\n' in wire) { "Invalid box metadata" }
        BoxQuantity.validate(unit, count)
        return Metadata(wire.substringAfter('\n'), count)
    }
}

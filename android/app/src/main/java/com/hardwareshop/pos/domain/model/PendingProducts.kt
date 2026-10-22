package com.hardwareshop.pos.domain.model

import java.math.BigDecimal

/** Raw form values are retained in drafts, including incomplete or invalid numbers. */
data class ProductForm(
    val categoryId: Long = 0, val typeId: Long = 0,
    val name: String = "", val size: String = "", val unit: String = "Piece",
    val sellingPrice: String = "", val purchasePrice: String = "",
    val gstPercent: String = "18", val stock: String = "0", val minimumStock: String = "0",
    val aliases: String = "", val imagePath: String? = null,
    val piecesPerBox: String = "",
    val isPipe: Boolean = false, val pipeLengthPrice: String = "", val pipeFootPrice: String = "",
    val pipeFeetPerLength: String = ""
) {
    fun gaps(ignored: Set<String> = emptySet(), validateStock: Boolean = true,
             requireSize: Boolean = true): List<ProductDetailGap> = buildList {
        fun required(key: String, label: String, valid: Boolean) {
            if (!valid) add(ProductDetailGap(key, label, false))
        }
        fun optional(key: String, label: String, absent: Boolean) {
            if (absent && key !in ignored) add(ProductDetailGap(key, label, true))
        }
        required("name",     "Product name",                                      name.isNotBlank())
        required("category", "Category",                                          categoryId > 0)
        required("type",     "Product type",                                      typeId > 0)
        required("unit",     "Unit",                                              unit.isNotBlank())
        if (piecesPerBox.isNotBlank()) required("box", "Valid pieces per box (whole count; Piece/Nos unit)",
            piecesPerBox.toIntOrNull()?.let { it in 1..1_000_000 && com.hardwareshop.pos.util.BoxQuantity.pieceUnit(unit) } == true)
        if (isPipe) {
            required("pipeLengthPrice", "Valid price per length", (scaled(pipeLengthPrice) ?: 0) > 0)
            required("pipeFootPrice", "Valid price per foot", (scaled(pipeFootPrice) ?: 0) > 0)
            required("pipeFeetPerLength", "Feet per full pipe", pipeFeetPerLength.toIntOrNull()?.let { it in 1..10000 } == true)
            required("pipeUnit", "Pipe stock unit: Length, Piece or Foot", unit.lowercase() in setOf("length", "piece", "nos", "foot", "feet", "ft"))
        }
        required("selling",  "Valid selling price (greater than 0)",              (scaled(sellingPrice) ?: 0) > 0)
        required("gst",      "Valid GST % (0–100, up to 2 decimals)",             scaled(gstPercent)?.let { it in 0..10000 } == true)
        if (requireSize) required("size", "Size", this@ProductForm.size.isNotBlank())
        if (validateStock && stock.isNotBlank())
            required("stock", "Valid stock (0 or more, up to 2 decimals)",        scaled(stock) != null)
        if (minimumStock.isNotBlank()) required("minimum", "Valid minimum stock (0 or more, up to 2 decimals)", scaled(minimumStock) != null)

        // Purchase price is fully optional and never surfaces as a pending gap.
        // If non-blank it must still be a valid non-negative decimal — that error
        // is shown inline on the form screen but never blocks saving or pending-list display.
        if (purchasePrice.isNotBlank()) {
            required("purchase", "Valid purchase price (0 or greater)", (scaled(purchasePrice) ?: -1L) >= 0L)
        }

    }

    companion object {
        val OPTIONAL_KEYS = setOf("purchase", "stock", "minimum", "aliases", "photo")

        /** Strict decimal parsing: never silently turn malformed input into zero or overflow. */
        fun scaled(value: String): Long? {
            val text = value.trim()
            if (!Regex("[0-9]+(?:\\.[0-9]{1,2})?").matches(text)) return null
            return runCatching { BigDecimal(text).movePointRight(2).longValueExact() }.getOrNull()
        }

        fun decimal(value: Long): String = BigDecimal.valueOf(value, 2).toPlainString()

        fun fromProduct(p: Product, reviewed: Set<String> = emptySet()) = ProductForm(
            categoryId    = p.categoryId,
            typeId        = p.typeId,
            name          = p.name,
            size          = p.size,
            unit          = p.unit,
            sellingPrice  = decimal(p.sellingPricePaisa),
            // Show blank when purchase price is 0 and owner hasn't explicitly reviewed/set it.
            // If owner set it to 0.00 deliberately ("purchase" is in reviewed), show "0.00".
            purchasePrice = if (p.purchasePricePaisa == 0L && "purchase" !in reviewed) ""
                            else decimal(p.purchasePricePaisa),
            gstPercent    = decimal(p.gstRateBp.toLong()),
            stock         = p.currentStock.toString(),
            minimumStock  = p.minimumStock.toString(),
            aliases       = p.searchAliases,
            imagePath     = p.imagePath,
            isPipe = p.isPipe, pipeLengthPrice = decimal(p.pipeLengthPricePaisa), pipeFootPrice = decimal(p.pipeFootPricePaisa),
            pipeFeetPerLength = p.pipeFeetPerLength.toString(),
            piecesPerBox  = p.piecesPerBox.takeIf { it > 0 }?.toString().orEmpty()
        )
    }
}

data class ProductDetailGap(val key: String, val label: String, val optional: Boolean)

data class ProductDraft(
    val id: String, val productId: Long? = null, val form: ProductForm = ProductForm(),
    val ignoredOptional: Set<String> = emptySet(), val updatedAt: Long = System.currentTimeMillis()
) {
    fun needsSaving(initialForm: ProductForm, initialIgnored: Set<String>, alreadyStored: Boolean): Boolean =
        alreadyStored || form != initialForm || ignoredOptional != initialIgnored
}

data class PendingProduct(
    val key: String, val draftId: String?, val productId: Long?, val name: String,
    val size: String, val gaps: List<ProductDetailGap>, val updatedAt: Long? = null
) {
    val isDraft: Boolean get() = draftId != null
}

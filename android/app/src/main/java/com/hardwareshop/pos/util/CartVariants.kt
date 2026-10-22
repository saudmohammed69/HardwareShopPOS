package com.hardwareshop.pos.util

import com.hardwareshop.pos.domain.model.*
import java.math.BigDecimal

/** Current-cart edits only: no product, stock or historical bill writes. */
object CartVariants {
    fun compatible(line: CartItem, product: Product): Boolean = line.product.id==product.id &&
        line.product.categoryId==product.categoryId && line.product.typeId==product.typeId &&
        ProductVariantMetadata.identity(line.product)==ProductVariantMetadata.identity(product) &&
        line.product.unit==product.unit && line.product.gstRateBp==product.gstRateBp &&
        line.product.isPipe==product.isPipe && line.product.size==product.size &&
        line.product.sellingPricePaisa==product.sellingPricePaisa &&
        (!product.isPipe || (line.product.pipeFeetPerLength==product.pipeFeetPerLength &&
            line.product.pipeLengthPricePaisa==product.pipeLengthPricePaisa && line.product.pipeFootPricePaisa==product.pipeFootPricePaisa))

    fun edit(cart: List<CartItem>, sourceId: Long, target: Product, quantity: Double,
        lengths: Long=0, feet: Double=0.0): List<CartItem> {
        val source=cart.single { it.product.id==sourceId }
        require(sourceId>0 && target.id>0 && target.isActive) { "Choose an active inventory item" }
        require(ProductSizeGroups.key(source.product)==ProductSizeGroups.key(target)) { "Choose a size of the same item" }
        val same=sourceId==target.id
        source.offcut?.let { require(same && lengths==0L && BigDecimal.valueOf(feet).multiply(BigDecimal(100))<=BigDecimal(it.capacityFeet)) { "Keep this offcut with its recorded pipe variant and available feet." } }
        if(same) require(compatible(source,target)) { "Product price, unit or tax changed. Remove and select it again." }
        var next=if(target.isPipe) PipeBilling.item(target,lengths,feet,
            if(same) source.unitPricePaisa else target.pipeLengthPricePaisa,
            if(same) source.pipeFootPricePaisa else target.pipeFootPricePaisa)
        else {
            require(quantity.isFinite() && quantity>0) { "Enter a positive quantity" }
            val price=if(same) source.unitPricePaisa else target.sellingPricePaisa
            require(price>0) { "Selling price is not set" }
            CartItem(target,quantity,price,CalculationEngine.itemTotalPaisa(quantity,price))
        }
        if(same)next=next.copy(quantityEstimated=source.quantityEstimated,offcut=source.offcut)
        val existing=cart.firstOrNull { it.product.id==target.id && it.product.id!=sourceId }
        if(existing!=null) {
            require(existing.offcut==null && next.offcut==null) { "Keep registered offcuts separate from new pipe" }
            require(compatible(existing,target) && existing.unitPricePaisa==next.unitPricePaisa &&
                (!target.isPipe || existing.pipeFootPricePaisa==next.pipeFootPricePaisa)) {
                "That size already has a different price or configuration in this bill. Review its price before combining."
            }
            next=if(target.isPipe) PipeBilling.item(target,Math.addExact(existing.pipeLengths,next.pipeLengths),
                BigDecimal.valueOf(existing.pipeFeet).add(BigDecimal.valueOf(next.pipeFeet)).toDouble(),next.unitPricePaisa,next.pipeFootPricePaisa)
            else {
                val qty=BigDecimal.valueOf(existing.quantity).add(BigDecimal.valueOf(next.quantity)).toDouble()
                next.copy(quantity=qty,itemTotalPaisa=CalculationEngine.itemTotalPaisa(qty,next.unitPricePaisa),quantityEstimated=next.quantityEstimated || existing.quantityEstimated)
            }
        }
        val index=cart.indexOfFirst { it.product.id==sourceId }
        val result=cart.filterNot { it.product.id==sourceId || it.product.id==target.id }.toMutableList()
        result.add(index.coerceAtMost(result.size),next)
        result.map(PipeBilling::amount).fold(0L,Math::addExact)
        return result
    }
}

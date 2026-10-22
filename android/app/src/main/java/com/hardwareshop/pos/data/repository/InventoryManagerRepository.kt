package com.hardwareshop.pos.data.repository

import androidx.room.withTransaction
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.data.local.database.toDomain
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class InventoryManagerRepository @Inject constructor(private val db:AppDatabase,private val products:ProductRepository,
    private val session:SessionHolder) {
    data class Variant(val size:String="",val colour:String="",val model:String="",val extra:String="",
        val selling:String="",val buying:String="",val stock:String="",val lengthPrice:String="",val footPrice:String="")
    data class Request(val parent:String,val categoryId:Long,val typeId:Long,val unit:String,val gst:String,
        val pipe:Boolean=false,val feetPerLength:String="",val variants:List<Variant>,val aliases:String="",val structure:String="Size")
    suspend fun save(request:Request):List<Long> {
        PermissionChecker.enforceOwner(session.current)
        require(request.parent.isNotBlank() && request.variants.isNotEmpty()) { "Enter a name and at least one variant" }
        val gst=requireNotNull(ProductForm.scaled(request.gst)) { "Enter GST from 0 to 100" }.also { require(it in 0..10000) }
        return db.withTransaction {
            val typeNames=db.productTypeDao().getAllTypes().associate{it.id to it.name}
            request.variants.map { row ->
                require(request.structure=="Simple" || !request.structure.contains("Size") || row.size.isNotBlank()) { "Enter a size for each combination" }
                require(!request.structure.contains("Colour") || row.colour.isNotBlank()) { "Enter a colour" }
                require(!request.structure.contains("Type") || row.model.isNotBlank()) { "Enter a type / model" }
                require(!request.pipe || row.size.isNotBlank()) { "Enter the pipe diameter" }
                fun required(text:String,label:String)=requireNotNull(ProductForm.scaled(text)) { "Enter valid $label" }
                fun optional(text:String,label:String)=if(text.isBlank()) 0L else required(text,label)
                val attrs=ProductVariantMetadata.Attributes(request.parent.trim(),row.colour.trim(),row.model.trim(),row.extra.trim())
                fun norm(value:String)=ProductSearch.tokens(value).joinToString(" ")
                require(db.productDao().getAllProducts().none { entity ->
                    if(entity.categoryId!=request.categoryId || entity.typeId!=request.typeId || !entity.unit.trim().equals(request.unit.trim(),true)) false
                    else {
                        val existing=entity.toDomain(typeName=typeNames[entity.typeId].orEmpty())
                        val attributes=ProductVariantMetadata.read(existing) ?: ProductVariantMetadata.Attributes(ProductSizeGroups.familyName(existing))
                        listOf(attributes.parent,attributes.colour,attributes.model,attributes.extra,entity.size).map(::norm)==
                            listOf(attrs.parent,attrs.colour,attrs.model,attrs.extra,row.size).map(::norm)
                    }
                }) { "This combination already exists; open it to edit or reactivate it" }
                val length=if(request.pipe) required(row.lengthPrice,"price per length") else 0L
                val foot=if(request.pipe) required(row.footPrice,"price per foot") else 0L
                val selling=if(request.pipe) length else required(row.selling,"selling price")
                require(selling>0 && (!request.pipe || foot>0)) { "Selling prices must be greater than zero" }
                val feet=if(request.pipe) requireNotNull(request.feetPerLength.toIntOrNull()).also { require(it in 1..10000) } else 0
                val result=products.createProduct(request.categoryId,request.typeId,ProductVariantMetadata.displayName(attrs),row.size.trim(),
                    request.unit,selling,optional(row.buying,"buying price"),gst.toInt(),optional(row.stock,"stock")/100.0,
                    0.0,null,ProductVariantMetadata.aliases(attrs,request.aliases),isPipe=request.pipe,
                    pipeLengthPricePaisa=length,pipeFootPricePaisa=foot,pipeFeetPerLength=feet)
                when(result) { is Result.Success -> result.data; is Result.Error -> throw IllegalArgumentException(result.message); else -> error("Could not save variant") }
            }
        }
    }
}

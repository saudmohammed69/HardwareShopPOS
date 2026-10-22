package com.hardwareshop.pos.presentation.worker.billing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*

@Composable
internal fun MultiSizeEntry(choices:List<Product>,onAdd:(String,List<CartItem>)->Boolean,onCancel:()->Unit,cart:List<CartItem> = emptyList(),fieldsMaxHeight:androidx.compose.ui.unit.Dp) {
    val quantities=remember { mutableStateMapOf<Long,String>() }
    val lengths=remember { mutableStateMapOf<Long,String>() }
    val feet=remember { mutableStateMapOf<Long,String>() }
    val token=remember { java.util.UUID.randomUUID().toString() }
    fun unitPrice(product:Product)=cart.firstOrNull { it.product.id==product.id }?.unitPricePaisa
        ?: if(product.isPipe)product.pipeLengthPricePaisa else product.sellingPricePaisa
    fun footPrice(product:Product)=cart.firstOrNull { it.product.id==product.id }?.pipeFootPricePaisa ?: product.pipeFootPricePaisa
    val result=runCatching { choices.mapNotNull { product ->
        if(product.isPipe) {
            val l=lengths[product.id].orEmpty();val f=feet[product.id].orEmpty()
            if(l.isBlank() && f.isBlank()) null else {
                val lv=if(l.isBlank())0 else requireNotNull(l.toLongOrNull());val fv=if(f.isBlank())0.0 else requireNotNull(f.toDoubleOrNull())
                if(lv==0L && fv==0.0)null else PipeBilling.item(product,lv,fv,unitPrice(product),footPrice(product))
            }
        } else {
            val q=quantities[product.id].orEmpty()
            if(q.isBlank() || q=="0") null else {
                val value=requireNotNull(q.toDoubleOrNull());val price=unitPrice(product);require(value.isFinite() && value>=0 && price>0)
                if(value==0.0)null else CartItem(product,value,price,CalculationEngine.itemTotalPaisa(value,price))
            }
        }
    } }
    val selectedTotal=runCatching { result.getOrThrow().map(PipeBilling::amount).fold(0L,Math::addExact) }.getOrNull()
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Enter quantities for selected combinations")
        Column(Modifier.heightIn(max=fieldsMaxHeight).verticalScroll(rememberScrollState()).testTag("batch-size-fields")) {
            choices.forEach { product ->
                Text(ProductVariantMetadata.label(product),style=MaterialTheme.typography.titleSmall)
                Text(if(product.isPipe) "${CalculationEngine.formatRupees(unitPrice(product))} / length · ${CalculationEngine.formatRupees(footPrice(product))} / ft"
                    else "${CalculationEngine.formatRupees(unitPrice(product))} / ${product.unit}")
                if(unitPrice(product)!=(if(product.isPipe)product.pipeLengthPricePaisa else product.sellingPricePaisa) ||
                    product.isPipe && footPrice(product)!=product.pipeFootPricePaisa) Text("Current bill price",style=MaterialTheme.typography.labelSmall)
                if(product.isPipe) Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(lengths[product.id].orEmpty(),{lengths[product.id]=it},label={Text("Lengths")},singleLine=true,
                        modifier=Modifier.weight(1f).testTag("batch-lengths-${product.id}"),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                    OutlinedTextField(feet[product.id].orEmpty(),{feet[product.id]=it},label={Text("Feet")},singleLine=true,
                        modifier=Modifier.weight(1f).testTag("batch-feet-${product.id}"),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                } else OutlinedTextField(quantities[product.id].orEmpty(),{quantities[product.id]=it},label={Text("Quantity")},singleLine=true,
                    modifier=Modifier.fillMaxWidth().testTag("batch-quantity-${product.id}"),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                HorizontalDivider()
            }
        }
        selectedTotal?.let { Text("Selected total: ${CalculationEngine.formatRupees(it)}") }
        Button({onAdd(token,requireNotNull(result.getOrNull()))},enabled=result.getOrNull()?.isNotEmpty()==true && selectedTotal!=null) {Text("ADD SELECTED TO CART")}
        TextButton(onCancel){Text("SINGLE SIZE")}
    }
}

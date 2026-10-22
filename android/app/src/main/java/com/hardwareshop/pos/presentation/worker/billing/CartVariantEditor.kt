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
internal fun CartVariantEditor(item: CartItem, products: List<Product>, cart: List<CartItem>, enabled: Boolean,
    error: String?, onApply: (Product,String,String,String) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    var target by remember(item.product.id) { mutableStateOf(item.product) }
    var quantity by remember(item.product.id) { mutableStateOf(CalculationEngine.formatQuantity(item.quantity)) }
    var lengths by remember(item.product.id) { mutableStateOf(item.pipeLengths.toString()) }
    var feet by remember(item.product.id) { mutableStateOf(CalculationEngine.formatQuantity(item.pipeFeet)) }
    val choices=remember(products,item.product) { ProductSizeGroups.choices(products,item.product) }
    val preview=runCatching {
        CartVariants.edit(cart,item.product.id,target,if(target.isPipe) 0.0 else requireNotNull(quantity.toDoubleOrNull()),
            if(lengths.isBlank()) 0 else requireNotNull(lengths.toLongOrNull()),if(feet.isBlank()) 0.0 else requireNotNull(feet.toDoubleOrNull()))
    }
    BoxWithConstraints(modifier) {
    val panelHeight=maxHeight
    Surface(Modifier.fillMaxSize(),tonalElevation=3.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("cart-variant-editor").padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("Edit ${ProductVariantMetadata.parent(item.product)}",style=MaterialTheme.typography.titleMedium)
            Text("Selected: ${ProductVariantMetadata.label(target)}")
            InlineSizeChoices(choices,panelHeight*0.45f) { if(enabled) target=it }
            if(target.isPipe) {
                OutlinedTextField(lengths,{ lengths=it },label={ Text("Length No.") },singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().testTag("edit-lengths"))
                OutlinedTextField(feet,{ feet=it },label={ Text("Additional feet") },singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("edit-feet"))
            } else OutlinedTextField(quantity,{ quantity=it },label={ Text(ProductSizeGroups.quantityLabel(target)) },singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("edit-quantity"))
            preview.getOrNull()?.firstOrNull { it.product.id==target.id }?.let { Text("Amount: ${CalculationEngine.formatRupees(it.itemTotalPaisa)}") }
            preview.exceptionOrNull()?.message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            Row {
                Button({ onApply(target,quantity,lengths,feet) },enabled=enabled && preview.isSuccess) { Text("APPLY CHANGES") }
                TextButton(onCancel,enabled=enabled) { Text("CANCEL") }
            }
        }
    }
    }
}

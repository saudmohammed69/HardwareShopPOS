package com.hardwareshop.pos.presentation.worker.billing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.presentation.common.AppColors
import com.hardwareshop.pos.util.*

@Composable
internal fun BillingChoiceCard(group: ProductSizeGroups.Group, favouriteIds: Set<Long>,
    onFavourite: (Product) -> Unit, onChoose: (Product) -> Unit, onAddOne: (Product) -> Unit) {
    val product=group.products.first()
    if (!group.sizeFirst) {
        BillingProductCard(product,product.id in favouriteIds,{ onFavourite(product) },
            { onChoose(product) }, { onAddOne(product) })
        return
    }
    Card(Modifier.fillMaxWidth().testTag("billing-family-${group.key}").clickable { onChoose(product) }) {
        Column(Modifier.fillMaxWidth().padding(12.dp), Arrangement.spacedBy(6.dp)) {
            Text(group.name,fontWeight=FontWeight.SemiBold)
            Text("${group.products.size} saved size${if(group.products.size==1) "" else "s"} · per ${if(product.isPipe) "length / ft" else product.unit}",
                style=MaterialTheme.typography.bodySmall,color=AppColors.TextSecondary)
            Text(group.products.map { it.size.ifBlank { "Standard" } }.distinct().take(3).joinToString(" · ") +
                if (group.products.map { it.size }.distinct().size > 3) " …" else "",
                style=MaterialTheme.typography.bodySmall)
            Text("CHOOSE SIZE",color=AppColors.Primary,fontWeight=FontWeight.Bold)
            if (group.products.any { it.id in favouriteIds }) Text("Favourite",style=MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun InlineSizeChoices(choices: List<Product>, maxHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified, onChoose: (Product) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max=maxHeight).verticalScroll(rememberScrollState()).testTag("inline-size-choices"),
        verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if(choices.isEmpty()) Text("No available sizes")
        choices.forEach { choice ->
            val ready=if(choice.isPipe) choice.pipeLengthPricePaisa>0 && choice.pipeFootPricePaisa>0 else choice.sellingPricePaisa>0
            OutlinedButton({ onChoose(choice) },enabled=ready,modifier=Modifier.fillMaxWidth().testTag("size-option-${choice.id}")) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(ProductVariantMetadata.label(choice),Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                    Text(if(!ready) "Price not set" else if(choice.isPipe)
                        "${CalculationEngine.formatRupees(choice.pipeLengthPricePaisa)} / length\n${CalculationEngine.formatRupees(choice.pipeFootPricePaisa)} / ft"
                        else "${CalculationEngine.formatRupees(choice.sellingPricePaisa)} / ${choice.unit}",
                        style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun BillingInlineSizeEntry(anchor: Product, selected: Product?, choices: List<Product>, onSize: (Product) -> Unit,
    quantity: String, onQuantity: (String) -> Unit, onAdd: () -> Unit, onCancel: () -> Unit, onCart: () -> Unit,
    cartItem: com.hardwareshop.pos.domain.model.CartItem?, enabled: Boolean, onPipe: (String,String) -> Unit,
    modifier: Modifier, hint: String?, onAddPipe: (Product,String,String) -> Unit,
    onAddItems:(String,List<com.hardwareshop.pos.domain.model.CartItem>)->Boolean,priceEditor:(@Composable ()->Unit)?,entryRates:Pair<Long,Long>?,
    entryCart:List<com.hardwareshop.pos.domain.model.CartItem>) {
    var batch by rememberSaveable(anchor.id) { mutableStateOf(false) }
    BoxWithConstraints(modifier) {
    val panelHeight=maxHeight
    Surface(Modifier.fillMaxSize(),tonalElevation=3.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("inline-billing-entry").padding(8.dp)) {
            if(batch) {
                MultiSizeEntry(choices,onAddItems,{batch=false},entryCart,panelHeight*0.65f)
                Row { TextButton(onCancel) { Text("CANCEL") }; TextButton(onCart) { Text("Cart") } }
            }
            else if(selected==null) {
                Text(ProductVariantMetadata.parent(anchor),fontWeight=FontWeight.Bold)
                Text("Select size and price",style=MaterialTheme.typography.labelLarge)
                TextButton({batch=true}) {Text("ADD MULTIPLE SIZES")}
                InlineSizeChoices(choices,panelHeight*0.68f,onSize)
                Row { TextButton(onCancel) { Text("CANCEL") }; TextButton(onCart) { Text("Cart") } }
            } else {
                Row(Modifier.fillMaxWidth()) {
                    Text("${ProductVariantMetadata.parent(selected)} · ${ProductVariantMetadata.label(selected)}",Modifier.weight(1f),fontWeight=FontWeight.Bold)
                    TextButton(onCancel) { Text("CHANGE SIZE") }
                }
                if(!selected.isPipe)priceEditor?.invoke()
                if(!selected.isPipe && priceEditor==null) Text("${CalculationEngine.formatRupees(entryRates?.first ?: cartItem?.unitPricePaisa ?: selected.sellingPricePaisa)} / ${selected.unit}")
                if(selected.isPipe) {
                    var lengths by rememberSaveable(selected.id) { mutableStateOf("") }
                    var feet by rememberSaveable(selected.id) { mutableStateOf("") }
                    val entered=runCatching { com.hardwareshop.pos.util.PipeBilling.item(selected,
                        if(lengths.isBlank()) 0 else requireNotNull(lengths.toLongOrNull()),
                        if(feet.isBlank()) 0.0 else requireNotNull(feet.toDoubleOrNull()),
                        entryRates?.first ?: cartItem?.unitPricePaisa ?: selected.pipeLengthPricePaisa,
                        entryRates?.second ?: cartItem?.pipeFootPricePaisa ?: selected.pipeFootPricePaisa) }.getOrNull()
                    PipeQuantityFields(selected,null,enabled,resetWhenRemoved=false) { l,f -> lengths=l;feet=f }
                    Text("${CalculationEngine.formatRupees(entryRates?.first ?: cartItem?.unitPricePaisa ?: selected.pipeLengthPricePaisa)} / length · ${CalculationEngine.formatRupees(entryRates?.second ?: cartItem?.pipeFootPricePaisa ?: selected.pipeFootPricePaisa)} / ft")
                    entered?.let { Text("Amount: ${CalculationEngine.formatRupees(it.itemTotalPaisa)}") }
                    priceEditor?.invoke()
                    Button({ onAddPipe(selected,lengths,feet) },enabled=enabled && entered!=null) { Text("ADD TO CART") }
                    Row { TextButton(onCancel) { Text("CANCEL") }; TextButton(onCart) { Text("Cart") } }
                } else BillingQuantityKeypad(selected,quantity,onQuantity,onAdd,onCancel,onCart,cartItem,enabled,onPipe,Modifier.fillMaxWidth(),hint,showProductTitle=false,scrollable=false)
            }
        }
    }
    }
}

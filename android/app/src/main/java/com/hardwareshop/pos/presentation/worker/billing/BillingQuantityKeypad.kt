package com.hardwareshop.pos.presentation.worker.billing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.BoxQuantity

@Composable
internal fun BillingPickerLayout(product: Product?, quantity: String, onQuantity: (String) -> Unit,
    onAdd: () -> Unit, onCancel: () -> Unit, onCart: () -> Unit,
    pipeItem: com.hardwareshop.pos.domain.model.CartItem? = null,
    pipeEnabled: Boolean = true, onPipe: (String, String) -> Unit = { _, _ -> },
    pipeHint: String? = null,
    sizeChoiceProduct: Product? = null, sizeChoices: List<Product> = emptyList(), onSize: (Product) -> Unit = {},
    onAddPipe: (Product,String,String) -> Unit = { _,_,_ -> },
    onAddItems: (String,List<com.hardwareshop.pos.domain.model.CartItem>) -> Boolean = { _,_ -> false },
    priceEditor: (@Composable () -> Unit)? = null,
    entryRates: Pair<Long,Long>? = null,
    entryCart: List<com.hardwareshop.pos.domain.model.CartItem> = emptyList(),
    entryContent: (@Composable (Modifier) -> Unit)? = null,
    content: @Composable (Modifier) -> Unit) {
    val entry: @Composable (Modifier) -> Unit = { modifier ->
        if(entryContent!=null) entryContent(modifier)
        else if(sizeChoiceProduct!=null) BillingInlineSizeEntry(sizeChoiceProduct,product,sizeChoices,onSize,
            quantity,onQuantity,onAdd,onCancel,onCart,pipeItem,pipeEnabled,onPipe,modifier,pipeHint,onAddPipe,onAddItems,priceEditor,entryRates,entryCart)
        else product?.let {
            Surface(modifier,tonalElevation=3.dp) { Column(Modifier.verticalScroll(rememberScrollState())) {
                // Keep primary pipe inputs visible on short phone panels. Optional
                // price/Want List/offcut actions can be long and remain scrollable.
                if(!it.isPipe)priceEditor?.invoke()
                BillingQuantityKeypad(it,quantity,onQuantity,onAdd,onCancel,onCart,pipeItem,pipeEnabled,onPipe,Modifier.fillMaxWidth(),pipeHint,scrollable=false)
                if(it.isPipe)priceEditor?.invoke()
            } }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val hasEntry=product!=null || sizeChoiceProduct!=null || entryContent!=null
        val panelWidth=(maxWidth*0.46f).coerceAtMost(480.dp)
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().testTag("billing-grid-region")) { content(Modifier.fillMaxSize()) }
                if(hasEntry) entry(Modifier.width(panelWidth).fillMaxHeight().testTag("billing-entry-region"))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                val keyboardOpen=WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)>0
                if(!hasEntry || !keyboardOpen) Box(Modifier.weight(if(hasEntry)0.3f else 1f).fillMaxWidth().testTag("billing-grid-region")) {
                    content(Modifier.fillMaxSize())
                }
                if(hasEntry) entry(Modifier.fillMaxWidth().weight(if(keyboardOpen)1f else 0.7f).testTag("billing-entry-region"))
            }
        }
    }
}

@Composable
internal fun BillingQuantityKeypad(product: Product, quantity: String, onQuantity: (String) -> Unit,
    onAdd: () -> Unit, onCancel: () -> Unit, onCart: () -> Unit,
    pipeItem: com.hardwareshop.pos.domain.model.CartItem?, pipeEnabled: Boolean,
    onPipe: (String, String) -> Unit, modifier: Modifier,pipeHint:String?=null,showProductTitle:Boolean=true,scrollable:Boolean=true) {
    if (product.isPipe) {
        Surface(modifier.testTag("billing-pipe-fields"), tonalElevation = 3.dp) {
            Column((if(scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(8.dp).navigationBarsPadding()) {
                Text(product.displayLabel, fontWeight = FontWeight.Bold)
                PipeQuantityFields(product, pipeItem, pipeEnabled, onChange=onPipe)
                pipeHint?.let { Text(it,Modifier.testTag("offcut-billing-hint"),style=MaterialTheme.typography.bodySmall) }
            }
        }
        return
    }
    var replaceDefault by rememberSaveable(product.id) { mutableStateOf(true) }
    val valid = quantity.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true
    Surface(modifier.testTag("billing-quantity-keypad"), tonalElevation = 3.dp) {
        Column((if(scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(8.dp).navigationBarsPadding()) {
            if(showProductTitle) {
                Text(product.name, fontWeight = FontWeight.Bold)
                if (product.size.isNotBlank()) Text(product.size, style = MaterialTheme.typography.labelSmall)
            }
            if (product.piecesPerBox > 0 && BoxQuantity.pieceUnit(product.unit)) {
                AssistChip(onClick = { onQuantity(product.piecesPerBox.toString()); replaceDefault = true },
                    label = { Text("1 box = ${product.piecesPerBox} pieces") })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(com.hardwareshop.pos.util.ProductSizeGroups.quantityLabel(product), style = MaterialTheme.typography.labelSmall)
                    Text(quantity.ifBlank { "0" }, Modifier.testTag("billing-quantity-value"),
                        style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                    Button(onAdd, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("ADD TO CART") }
                    TextButton(onCancel) { Text("Cancel") }
                    TextButton(onCart) { Text("Cart") }
                }
                Column(Modifier.weight(1.3f)) {
                    listOf(listOf("1", "2", "3"), listOf("4", "5", "6"),
                        listOf("7", "8", "9"), listOf(".", "0", "⌫")).forEach { keys ->
                        Row(Modifier.fillMaxWidth()) {
                            keys.forEach { key ->
                                TextButton(onClick = {
                                    val next = when (key) {
                                        "⌫" -> quantity.dropLast(1)
                                        "." -> if (replaceDefault || quantity.isBlank()) "0."
                                            else if ('.' in quantity) quantity else "$quantity."
                                        else -> if (replaceDefault) key else quantity + key
                                    }
                                    replaceDefault = false
                                    if (next.length <= 12) onQuantity(next)
                                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                    contentDescription = if (key == "⌫") "Quantity backspace" else "Quantity key $key"
                                }) { Text(key) }
                            }
                        }
                    }
                }
            }
        }
    }
}

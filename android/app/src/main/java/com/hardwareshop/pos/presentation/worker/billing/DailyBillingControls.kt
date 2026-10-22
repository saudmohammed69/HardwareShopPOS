package com.hardwareshop.pos.presentation.worker.billing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.presentation.common.AppColors
import com.hardwareshop.pos.util.CalculationEngine
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
internal fun BillingProductCard(product: Product, favourite: Boolean, onFavourite: () -> Unit,
                                onQuantity: () -> Unit, onAddOne: () -> Unit, sizeAsTitle: Boolean = false) {
    val pieceUnit = product.unit.lowercase(java.util.Locale.ROOT) in setOf("piece", "pieces", "nos", "box", "packet", "set", "bag", "roll", "bundle")
    Card(Modifier.fillMaxWidth().testTag("billing-product-${product.id}").clickable(onClick = onQuantity)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), Arrangement.spacedBy(4.dp)) {
            val context = LocalContext.current
            product.imagePath?.let { java.io.File(context.filesDir, it) }?.takeIf { it.exists() }?.let { image ->
                AsyncImage(ImageRequest.Builder(context).data(image).size(112).build(),
                    contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp))
            }
            Text(if (sizeAsTitle) product.size.ifBlank { "Size not recorded" } else product.name,
                fontWeight = FontWeight.SemiBold, minLines = if (sizeAsTitle) 1 else 2)
            Text(if (sizeAsTitle) product.name else product.size.ifBlank { product.typeName }, style = MaterialTheme.typography.bodySmall,
                minLines = 1, color = AppColors.TextSecondary)
            Text(CalculationEngine.formatRupees(if (product.isPipe) product.pipeLengthPricePaisa else product.sellingPricePaisa),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppColors.Primary)
            Text(if (product.isPipe) "per length / ${CalculationEngine.formatRupees(product.pipeFootPricePaisa)} per ft" else "/ ${product.unit}", style = MaterialTheme.typography.bodySmall)
            Text(when {
                product.currentStock <= 0 -> "Check availability"
                product.isLowStock -> "Low stock: ${CalculationEngine.formatQuantity(product.currentStock)}"
                else -> "Stock: ${CalculationEngine.formatQuantity(product.currentStock)}"
            }, style = MaterialTheme.typography.labelSmall,
                color = if (product.currentStock <= 0 || product.isLowStock) AppColors.LowStock else AppColors.TextSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onFavourite) {
                    Icon(if (favourite) Icons.Default.Star else Icons.Default.StarBorder,
                        if (favourite) "Remove favourite" else "Add favourite", tint = AppColors.Secondary)
                }
                if (!product.isPipe) IconButton(onClick = if (pieceUnit) onAddOne else onQuantity) {
                    Icon(Icons.Default.AddCircleOutline,
                        if (pieceUnit) "Add one ${product.unit}" else "Choose quantity", tint = AppColors.Secondary)
                }
            }
        }
    }
}

@Composable
internal fun BillingHierarchyCard(label: String, onClick: () -> Unit, icon: ImageVector,
                                  pinned: Boolean = false, onPin: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().testTag("billing-group-$label").clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.Start) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = categoryIndicator(label), modifier = Modifier.size(28.dp))
                onPin?.let { pin -> IconButton(onClick = pin) {
                    Icon(Icons.Default.PushPin, if (pinned) "Unpin $label" else "Pin $label",
                        tint = if (pinned) AppColors.Secondary else AppColors.TextSecondary)
                } }
            }
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun CashChangeCard(received: String, onReceived: (String) -> Unit, totalPaisa: Long, enabled: Boolean) {
    val amount = CalculationEngine.parseRupeesToPaisa(received)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
            Text("Cash received & change", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(received, onReceived, label = { Text("Cash received ₹ (optional)") },
                singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = received.isNotBlank() && (amount == null || amount < totalPaisa))
            if (received.isBlank()) Text("Leave blank for exact payment.", style = MaterialTheme.typography.bodySmall)
            else if (amount == null) Text("Enter a valid amount with up to 2 decimals.")
            else if (amount < totalPaisa) Text("Still due: ${CalculationEngine.formatRupees(totalPaisa - amount)}")
            else Text("Change: ${CalculationEngine.formatRupees(amount - totalPaisa)}", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { onReceived(java.math.BigDecimal.valueOf(totalPaisa, 2).toPlainString()) },
                    label = { Text("Exact amount") }, enabled = enabled)
                listOf(10000L, 20000L, 50000L, 100000L).filter { it >= totalPaisa }.forEach { cash ->
                    AssistChip(onClick = { onReceived(java.math.BigDecimal.valueOf(cash, 2).toPlainString()) },
                        label = { Text("₹${cash / 100}") }, enabled = enabled)
                }
                if (received.isNotBlank()) AssistChip(onClick = { onReceived("") }, label = { Text("Clear") }, enabled = enabled)
            }
            Text("The bill records its total as paid. Cash handed over and change do not increase sales.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Indicators derive from existing labels and never create/rename categories. */
private fun categoryIndicator(name:String):androidx.compose.ui.graphics.Color {
    val label=name.lowercase(java.util.Locale.ROOT)
    return when {
        listOf("plumb","pvc","cpvc","upvc","pipe").any(label::contains)->androidx.compose.ui.graphics.Color(0xFF1976A3)
        listOf("electr","wire","cable").any(label::contains)->androidx.compose.ui.graphics.Color(0xFF8C6500)
        listOf("paint","primer","coating").any(label::contains)->androidx.compose.ui.graphics.Color(0xFF8E4A8E)
        listOf("fasten","bolt","nut","screw","nail").any(label::contains)->androidx.compose.ui.graphics.Color(0xFF9B4D25)
        "hardware" in label->androidx.compose.ui.graphics.Color(0xFF38734E)
        else->androidx.compose.ui.graphics.Color(0xFF546E7A)
    }
}

@Composable internal fun BillingCategoryTabs(categories:List<com.hardwareshop.pos.domain.model.Category>,selectedId:Long?,onSelect:(com.hardwareshop.pos.domain.model.Category)->Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),Arrangement.spacedBy(6.dp)) {
        categories.forEach { category->
            FilterChip(selected=selectedId==category.id,onClick={onSelect(category)},label={Text(category.name)},
                leadingIcon={Text("●",color=categoryIndicator(category.name))})
        }
    }
}

package com.hardwareshop.pos.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.CalculationEngine
import com.hardwareshop.pos.util.BoxQuantity
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// APP COLORS
// ─────────────────────────────────────────────────────────────────────────────

object AppColors {
    val Primary        = Color(0xFF1565C0)   // Deep blue — professional, trust
    @Suppress("Unused")
    val PrimaryVariant = Color(0xFF003C8F)
    val Secondary      = Color(0xFFFF6F00)   // Amber — action buttons
    val Success        = Color(0xFF2E7D32)
    val Warning        = Color(0xFFF57F17)
    val Error          = Color(0xFFC62828)
    val Surface        = Color(0xFFF5F5F5)
    val SurfaceCard    = Color(0xFFFFFFFF)
    val OnPrimary      = Color(0xFFFFFFFF)
    val TextPrimary    = Color(0xFF212121)
    val TextSecondary  = Color(0xFF757575)
    val Divider        = Color(0xFFE0E0E0)
    val LowStock       = Color(0xFFFF6F00)
    val Credit         = Color(0xFFC62828)
}

/** Uses a familiar visual cue for the shop's main product groups. */
fun categoryIcon(categoryName: String): ImageVector {
    val category = categoryName.trim().lowercase()
    return when {
        category.contains("pvc") || category.contains("cpvc") ||
            category.contains("plumb") || category.contains("pipe") ||
            category.contains("valve") || category.contains("water") -> Icons.Default.WaterDrop
        category.contains("elect") || category.contains("wire") ||
            category.contains("light") || category.contains("bulb") -> Icons.Default.Bolt
        category.contains("paint") || category.contains("coat") ||
            category.contains("primer") || category.contains("finish") -> Icons.Default.Palette
        category.contains("clean") || category.contains("soap") ||
            category.contains("sanit") -> Icons.Default.CleaningServices
        category.contains("tool") || category.contains("hardware") ||
            category.contains("fastener") || category.contains("weld") -> Icons.Default.Build
        category.contains("construct") || category.contains("cement") ||
            category.contains("steel") -> Icons.Default.Construction
        category.contains("home") || category.contains("kitchen") ||
            category.contains("storage") -> Icons.Default.Home
        category.contains("safety") || category.contains("glove") -> Icons.Default.Security
        else -> Icons.Default.Category
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TOP BAR
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AppTopBar(
    title: String,
    onNavigateUp: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Text(
                text       = title,
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            if (onNavigateUp != null) {
                IconButton(onClick = onNavigateUp) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        },
        actions = actions,
        colors  = TopAppBarDefaults.topAppBarColors(
            containerColor             = AppColors.Primary,
            titleContentColor          = AppColors.OnPrimary,
            navigationIconContentColor = AppColors.OnPrimary,
            actionIconContentColor     = AppColors.OnPrimary
        )
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// PRIMARY ACTION BUTTON
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    Button(
        onClick  = onClick,
        enabled  = enabled && !isLoading,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor         = AppColors.Secondary,
            contentColor           = AppColors.OnPrimary,
            disabledContainerColor = AppColors.Divider
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier    = Modifier.size(24.dp),
                color       = AppColors.OnPrimary,
                strokeWidth = 2.dp
            )
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text       = text,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PRODUCT CARD
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ProductCard(
    product: Product,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onQuickAdd: (() -> Unit)? = null,
    isFavourite: Boolean = false,
    onToggleFavourite: (() -> Unit)? = null
) {
    Card(
        modifier  = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape     = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors    = CardDefaults.cardColors(containerColor = AppColors.SurfaceCard)
    ) {
        Row(
            modifier              = Modifier.padding(12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val context = LocalContext.current
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.Surface),
                contentAlignment = Alignment.Center
            ) {
                val imageFile = product.imagePath?.let { File(context.filesDir, it) }
                if (imageFile != null && imageFile.exists()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageFile)
                            .crossfade(true)
                            .size(112)
                            .build(),
                        contentDescription = product.name,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector        = categoryIcon(product.categoryName),
                        contentDescription = null,
                        tint               = AppColors.Primary,
                        modifier           = Modifier.size(32.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = product.name,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color      = AppColors.TextPrimary
                )
                if (product.categoryName.isNotBlank() || product.typeName.isNotBlank()) {
                    Text(
                        text = listOf(product.categoryName, product.typeName)
                            .filter { it.isNotBlank() }
                            .joinToString(" › "),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = buildAnnotatedString {
                        if (product.size.isNotBlank()) {
                            append(product.size)
                            append(" — ")
                        }
                        withStyle(SpanStyle(color = AppColors.Primary, fontWeight = FontWeight.Bold)) {
                            append(CalculationEngine.formatRupees(product.sellingPricePaisa))
                        }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextPrimary
                )
                Text(
                    text = "/ ${product.unit}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary
                )
                if (product.currentStock <= 0 || product.isLowStock) {
                    Text(
                        text  = if (product.currentStock <= 0) "No recorded stock — check availability"
                            else "Low stock: ${CalculationEngine.formatQuantity(product.currentStock)} ${product.unit}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.LowStock
                    )
                }
                if (product.currentStock > 0 && !product.isLowStock) {
                    Text("Stock: ${CalculationEngine.formatQuantity(product.currentStock)} ${product.unit}",
                        style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                onToggleFavourite?.let { toggle ->
                    IconButton(onClick = toggle) {
                        Icon(if (isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                            if (isFavourite) "Remove favourite" else "Add favourite", tint = AppColors.Secondary)
                    }
                }
                IconButton(onClick = onQuickAdd ?: onClick) {
                    Icon(Icons.Default.AddCircleOutline,
                        if (onQuickAdd != null) "Add one ${product.unit}" else "Choose quantity", tint = AppColors.Secondary)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// STAT CARD  (dashboard stat tile)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun StatCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    color: Color = AppColors.Primary
) {
    Card(
        modifier  = modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier            = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = color,
                modifier           = Modifier.size(24.dp)
            )
            Text(
                text       = value,
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color      = color
            )
            Text(
                text  = label,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HIERARCHY CARD  (drill-down navigation tile)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun HierarchyCard(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: String = "",
    icon: ImageVector = Icons.Default.Category
) {
    Card(
        modifier  = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape     = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors    = CardDefaults.cardColors(containerColor = AppColors.SurfaceCard)
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = AppColors.Primary,
                modifier           = Modifier.size(28.dp)
            )
            Text(
                text       = label,
                style      = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier   = Modifier.weight(1f),
                color      = AppColors.TextPrimary
            )
            if (count.isNotBlank()) {
                Text(
                    text  = count,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary
                )
            }
            Icon(
                imageVector        = Icons.Default.ChevronRight,
                contentDescription = null,
                tint               = AppColors.TextSecondary
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CART ITEM ROW
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CartItemRow(
    name: String,
    size: String,
    qty: String,
    unitPrice: String,
    total: String,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onEditQuantity: (() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = name,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (size.isNotBlank()) {
                    Text(
                        text  = size,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary
                    )
                }
                Text(
                    text  = unitPrice,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary
                )
            }

            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onDecrease, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease")
                }
                TextButton(onClick = { onEditQuantity?.invoke() }, enabled = onEditQuantity != null,
                    contentPadding = PaddingValues(4.dp)) {
                    Text(qty, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                IconButton(onClick = onIncrease, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Increase")
                }
            }

            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text       = total,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Remove",
                        tint = AppColors.Error
                    )
                }
            }
        }
        HorizontalDivider(color = AppColors.Divider, thickness = 0.5.dp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AMOUNT ROW
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AmountRow(
    label: String,
    amountPaisa: Long,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle  = MaterialTheme.typography.bodyMedium,
    amountStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    labelColor: Color      = AppColors.TextPrimary,
    amountColor: Color     = AppColors.TextPrimary
) {
    Row(
        modifier              = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = labelStyle, color = labelColor)
        Text(
            text  = CalculationEngine.formatRupees(amountPaisa),
            style = amountStyle,
            color = amountColor
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TOTAL ROW
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TotalRow(label: String = "TOTAL", amountPaisa: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Primary)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(
            text       = label,
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color      = AppColors.OnPrimary
        )
        Text(
            text       = CalculationEngine.formatRupees(amountPaisa),
            style      = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color      = AppColors.OnPrimary
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ERROR MESSAGE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ErrorMessage(message: String, modifier: Modifier = Modifier) {
    if (message.isNotBlank()) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = AppColors.Error.copy(alpha = 0.1f)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier              = Modifier.padding(12.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = AppColors.Error
                )
                Text(
                    text  = message,
                    color = AppColors.Error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PAYMENT METHOD CHIP
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PaymentMethodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick  = onClick,
        label    = {
            Text(
                text       = label,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        },
        modifier = modifier,
        colors   = FilterChipDefaults.filterChipColors(
            selectedContainerColor   = AppColors.Primary,
            selectedLabelColor       = AppColors.OnPrimary,
            selectedLeadingIconColor = AppColors.OnPrimary
        )
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// QUANTITY INPUT DIALOG
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun QuantityInputDialog(
    product: Product,
    quantity: String,
    onQuantityChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "ADD TO CART"
) {
    var boxMode by remember(product.id) { mutableStateOf(false) }
    var boxText by remember(product.id) { mutableStateOf("1") }
    val canUseBoxes = product.piecesPerBox > 0 && BoxQuantity.pieceUnit(product.unit)
    fun setQuantity(value: String) {
        if (boxMode) {
            if (!value.all(Char::isDigit)) return
            boxText = value
            onQuantityChange(BoxQuantity.pieces(value, product.piecesPerBox)?.let { CalculationEngine.formatQuantity(it) }.orEmpty())
        } else onQuantityChange(value)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(product.name, fontWeight = FontWeight.Bold)
                if (product.size.isNotBlank()) {
                    Text(
                        text  = product.size,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary
                    )
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text  = "Price: ${CalculationEngine.formatRupees(product.sellingPricePaisa)} / ${product.unit}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Primary
                )
                if (canUseBoxes) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !boxMode, onClick = { boxMode = false; onQuantityChange("1") }, label = { Text("Pieces") })
                        FilterChip(selected = boxMode, onClick = {
                            boxMode = true; boxText = "1"; onQuantityChange(product.piecesPerBox.toString())
                        }, label = { Text("Boxes") })
                    }
                    Text("1 box = ${product.piecesPerBox} pieces", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value           = if (boxMode) boxText else quantity,
                    onValueChange   = ::setQuantity,
                    label           = { Text(if (boxMode) "Number of boxes" else "Quantity (${product.unit})") },
                    keyboardOptions = KeyboardOptions(keyboardType = if (boxMode) KeyboardType.Number else KeyboardType.Decimal),
                    singleLine      = true,
                    modifier        = Modifier.fillMaxWidth()
                )
                val shortcuts = if (boxMode || BoxQuantity.pieceUnit(product.unit) || product.unit in listOf("Box", "Bag", "Packet", "Set", "Roll", "Bundle"))
                    listOf("1", "5", "10", "25", "50", "100") else listOf("0.5", "1", "2", "5", "10")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    shortcuts.forEach { value -> AssistChip(onClick = { setQuantity(value) }, label = { Text(value) }) }
                }
                if (boxMode) {
                    val pieces = BoxQuantity.pieces(boxText, product.piecesPerBox)
                    if (pieces == null) Text("Enter a valid whole number of boxes.", color = AppColors.Error)
                    else {
                        Text("${boxText} boxes = ${CalculationEngine.formatQuantity(pieces)} pieces. Billing and stock use pieces.", style = MaterialTheme.typography.bodySmall)
                        runCatching { CalculationEngine.itemTotalPaisa(pieces, product.sellingPricePaisa) }.getOrNull()?.let {
                            Text("Item total: ${CalculationEngine.formatRupees(it)}", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = quantity.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true &&
                    (!boxMode || BoxQuantity.pieces(boxText, product.piecesPerBox) != null)
            ) {
                Text(confirmLabel, fontWeight = FontWeight.Bold, color = AppColors.Primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// SECTION HEADER
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelLarge,
        color    = AppColors.TextSecondary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

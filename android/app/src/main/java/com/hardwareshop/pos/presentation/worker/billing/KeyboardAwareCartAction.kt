package com.hardwareshop.pos.presentation.worker.billing

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.hardwareshop.pos.presentation.common.AppColors
import com.hardwareshop.pos.util.CalculationEngine
import kotlin.math.max
import kotlin.math.roundToInt

/** One cart action moves between measured anchors; neither the list nor cart panel is animated. */
@Composable
internal fun KeyboardAwareCartAction(
    keyboardOpen: Boolean, itemCount: Int, subtotalPaisa: Long,
    topAnchor: Rect?, bottomAnchor: Rect?, rootBounds: Rect?,
    availableWidth: Dp, availableHeight: Dp, onClick: () -> Unit
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val textStyle = MaterialTheme.typography.labelLarge
    val label = "Cart ($itemCount) · ${CalculationEngine.formatRupees(subtotalPaisa)}"
    val measurer = rememberTextMeasurer()
    val labelWidth = remember(label, textStyle, density) { measurer.measure(label, textStyle).size.width }
    val expandedWidth = with(density) { (labelWidth.toDp() + 72.dp).coerceAtMost(availableWidth - 32.dp).coerceAtLeast(48.dp) }
    val targetWidth = if (keyboardOpen) 48.dp else expandedWidth
    val targetHeight = if (keyboardOpen) 48.dp else 56.dp
    val width by animateDpAsState(targetWidth, tween(180, easing = LinearOutSlowInEasing), label = "cartWidth")
    val height by animateDpAsState(targetHeight, tween(180, easing = LinearOutSlowInEasing), label = "cartHeight")
    val iconInset by animateDpAsState(if (keyboardOpen) 12.dp else 16.dp, tween(180), label = "cartIconInset")
    val labelAlpha by animateFloatAsState(if (keyboardOpen) 0f else 1f, tween(120), label = "cartLabelAlpha")
    val originX = rootBounds?.left ?: 0f
    val originY = rootBounds?.top ?: 0f
    val navigationBottom = WindowInsets.navigationBars.getBottom(density)
    val statusTop = WindowInsets.statusBars.getTop(density)
    val imeBottom = WindowInsets.ime.getBottom(density)
    val topX = topAnchor?.let { it.left - originX } ?: with(density) { (if (rtl) 4.dp else availableWidth - 52.dp).toPx() }
    val topY = topAnchor?.let { it.top - originY } ?: (statusTop + with(density) { 8.dp.toPx() })
    val bottomX = if (rtl) bottomAnchor?.left?.minus(originX) ?: with(density) { 16.dp.toPx() }
        else (bottomAnchor?.right?.minus(originX) ?: with(density) { (availableWidth - 16.dp).toPx() }) - with(density) { targetWidth.toPx() }
    val bottomY = bottomAnchor?.let { it.top - originY } ?: with(density) { (availableHeight - 72.dp).toPx() - navigationBottom }
    val x by animateFloatAsState(if (keyboardOpen) topX else bottomX, tween(180, easing = LinearOutSlowInEasing), label = "cartX")
    val y by animateFloatAsState(if (keyboardOpen) topY else bottomY, tween(180, easing = LinearOutSlowInEasing), label = "cartY")
    val visibleWidth = width.coerceAtMost(availableWidth)
    val safeX = with(density) { x.coerceIn(0f, max(0f, availableWidth.toPx() - visibleWidth.toPx())) }
    // Keep the moving action above a rising IME, even before its travel animation ends.
    val safeTop = max(0f, statusTop - originY)
    // IME height may remain nonzero during dismissal after its visibility flag changes.
    val safeBottom = with(density) { availableHeight.toPx() - height.toPx() - 8.dp.toPx() } - max(imeBottom, navigationBottom)
    val safeY = y.coerceIn(safeTop, max(safeTop, safeBottom))
    Surface(
        onClick = onClick,
        modifier = Modifier.absoluteOffset { IntOffset(safeX.roundToInt(), safeY.roundToInt()) }
            .size(visibleWidth, height).semantics {
                role = Role.Button
                contentDescription = "Open cart, $itemCount items, ${CalculationEngine.formatRupees(subtotalPaisa)}"
            },
        shape = RoundedCornerShape(16.dp), color = AppColors.Secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary, shadowElevation = 6.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            BadgedBox(modifier = Modifier.align(Alignment.CenterStart).padding(start = iconInset), badge = {
                if (keyboardOpen && itemCount > 0) Badge { Text(if (itemCount > 99) "99+" else itemCount.toString(), Modifier.clearAndSetSemantics { }) }
            }) { Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(24.dp)) }
            Box(Modifier.fillMaxSize().padding(start = 52.dp, end = 16.dp).graphicsLayer { alpha = labelAlpha }, contentAlignment = Alignment.CenterStart) {
                Text(label, Modifier.clearAndSetSemantics { }, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

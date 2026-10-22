package com.hardwareshop.pos.presentation.worker.billing
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.CalculationEngine

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PipeQuantityFields(product: Product, item: CartItem?, enabled: Boolean = true,
                                resetWhenRemoved: Boolean = true,
                                onChange: (String, String) -> Unit) {
    var lengths by rememberSaveable(product.id) { mutableStateOf(item?.pipeLengths?.takeIf { it > 0 }?.toString().orEmpty()) }
    var feet by rememberSaveable(product.id) { mutableStateOf(item?.pipeFeet?.takeIf { it > 0 }?.let(CalculationEngine::formatQuantity).orEmpty()) }
    // External removal/clear uses the bill as source of truth; typing zero already removes its line.
    LaunchedEffect(item?.pipeLengths, item?.pipeFeet) {
        if (resetWhenRemoved && item == null && (lengths.toLongOrNull() ?: 0) > 0) lengths = ""
        if (resetWhenRemoved && item == null && (feet.toDoubleOrNull() ?: 0.0) > 0) feet = ""
    }
    val lengthRequester = remember { BringIntoViewRequester() }
    val feetRequester = remember { BringIntoViewRequester() }
    var lengthFocused by remember { mutableStateOf(false) }
    var feetFocused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    // Re-run as the IME rises, and when text changes, to keep the complete field visible.
    LaunchedEffect(lengthFocused, feetFocused, imeBottom, lengths, feet) {
        if (lengthFocused) lengthRequester.bringIntoView()
        if (feetFocused) feetRequester.bringIntoView()
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(lengths, { value ->
            if (value.length <= 8 && value.all { it in '0'..'9' }) {
                lengths = value; onChange(value, feet)
            }
        }, label = { Text("Length No.") }, singleLine = true, enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f).bringIntoViewRequester(lengthRequester)
                .onFocusChanged { lengthFocused = it.isFocused }.testTag("pipe-length-${product.id}"))
        OutlinedTextField(feet, { value ->
            if (value.length <= 12 && value.matches(Regex("[0-9]*(?:\\.[0-9]{0,2})?"))) {
                feet = if (value == ".") "0." else value; onChange(lengths, feet)
            }
        }, label = { Text("Length (ft)") }, suffix = { Text("ft") }, singleLine = true, enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f).bringIntoViewRequester(feetRequester)
                .onFocusChanged { feetFocused = it.isFocused }.testTag("pipe-feet-${product.id}"))
    }
}

package com.hardwareshop.pos.presentation.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hardwareshop.pos.util.ProductSortOrder

/** A closed-by-default menu suitable for the top of billing and inventory lists. */
@Composable
fun ProductSortControl(
    order: ProductSortOrder,
    onOrderChange: (ProductSortOrder) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        if (compact) IconButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.testTag("product-sort-control")
        ) {
            Icon(Icons.Default.Sort, contentDescription = "Sort products: ${order.label}")
        } else TextButton(
            onClick = { expanded = true },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.testTag("product-sort-control")
        ) {
            Icon(Icons.Default.Sort, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Sort: ${order.label}")
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ProductSortOrder.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { expanded = false; onOrderChange(option) },
                    trailingIcon = {
                        if (option == order) Icon(Icons.Default.Check, contentDescription = "Selected")
                    },
                    modifier = Modifier.testTag("product-sort-${option.name}")
                )
            }
            HorizontalDivider()
            Text(
                "Default keeps the current order.\nPrice uses each selling unit; pipes use a length.\nFamilies use their lowest/highest rate or lowest stock.\nStock uses each product's stock unit.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp).width(256.dp)
            )
        }
    }
}

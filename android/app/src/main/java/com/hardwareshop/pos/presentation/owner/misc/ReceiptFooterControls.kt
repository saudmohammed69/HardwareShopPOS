package com.hardwareshop.pos.presentation.owner.misc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hardwareshop.pos.util.ReceiptFooter

@Composable internal fun ReceiptFooterControls(state:SettingsState,vm:SettingsViewModel,onPrint:()->Unit) {
    var preview by remember {mutableStateOf(false)}
    val parsed=runCatching {ReceiptFooter.lines(state.receiptFooter)}
    Text("Receipt Footer / Tagline",style=MaterialTheme.typography.titleMedium)
    OutlinedTextField(state.receiptFooter,{value->if(value.length<=600)vm.update {copy(receiptFooter=value)}},
        label={Text("58mm receipt footer")},minLines=2,maxLines=6,modifier=Modifier.fillMaxWidth(),enabled=!state.isLoading)
    Text("Up to 300 characters and six lines. Text wraps to the existing 32-column receipt width.",style=MaterialTheme.typography.bodySmall)
    parsed.exceptionOrNull()?.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(vm::saveReceiptFooter,enabled=!state.isLoading && parsed.isSuccess){Text("SAVE FOOTER")}
        TextButton({preview=!preview},enabled=parsed.isSuccess){Text("PREVIEW")}
    }
    Column {
        TextButton({vm.update {copy(receiptFooter=ReceiptFooter.DEFAULT)};preview=true},enabled=!state.isLoading){Text("RESET TO DEFAULT")}
        TextButton(onPrint,enabled=!state.isPrinting && parsed.isSuccess){Text("PRINT FOOTER TEST")}
    }
    if(preview)Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
        parsed.getOrNull().orEmpty().forEach {line->Text(line,fontFamily=FontFamily.Monospace)}
    }}
}

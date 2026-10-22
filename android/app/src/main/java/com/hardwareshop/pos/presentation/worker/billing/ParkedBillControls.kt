// Compose state delegate writes are observed during recomposition; retain the UI state updates.
@file:Suppress("AssignedValueIsNeverRead")

package com.hardwareshop.pos.presentation.worker.billing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ParkedBillControls(vm: BillingViewModel) {
    val state by vm.state.collectAsState()
    val parked by vm.parkedBills.collectAsState()
    var showList by rememberSaveable { mutableStateOf(false) }
    var showName by rememberSaveable { mutableStateOf(false) }
    var label by rememberSaveable { mutableStateOf("") }
    var renameKey by remember { mutableStateOf<String?>(null) }
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var renameLabel by rememberSaveable { mutableStateOf("") }
    Column {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),Arrangement.spacedBy(6.dp)) {
        InputChip(selected=true,onClick={renameKey=null;renameLabel=state.cartLabel;renameOpen=true},label={Text(state.cartLabel.ifBlank { state.customerName.ifBlank { "Current cart" } })})
        parked.forEach { row->AssistChip(onClick={vm.resumeParked(row.key)},enabled=!state.isLoading,label={Text(row.label)}) }
        AssistChip(onClick={vm.parkCurrent(state.cartLabel)},enabled=!state.isLoading && state.cart.isNotEmpty(),label={Text("NEW CART")})
        AssistChip(onClick=vm::saveDraft,enabled=!state.isLoading && state.cart.isNotEmpty(),label={Text("SAVE DRAFT")})
    }
    if(renameOpen)AlertDialog(onDismissRequest={renameOpen=false},title={Text("Rename cart")},text={
        OutlinedTextField(renameLabel,{renameLabel=it.take(80)},label={Text("Customer / label")},singleLine=true)
    },confirmButton={TextButton({if(renameKey==null)vm.renameCart(renameLabel) else vm.renameParked(renameKey!!,renameLabel);renameOpen=false}){Text("SAVE")}},dismissButton={TextButton({renameOpen=false}){Text("CANCEL")}})
    var discard by remember { mutableStateOf<ParkedBill?>(null) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton({ label = state.customer?.name ?: ""; showName = true }, enabled = !state.isLoading && state.cart.isNotEmpty() && state.savedBillId == null) { Text("PARK & NEW") }
        TextButton({ showList = true }, enabled = !state.isLoading && state.savedBillId == null) { Text("PARKED BILLS (${parked.size})") }
    }
    if (showName) AlertDialog(onDismissRequest = { showName = false }, title = { Text("Park this bill") }, text = {
        Column {
            Text("Give it a customer name or reminder. Parking does not change stock or create a sale.")
            OutlinedTextField(label, { label = it.take(80) }, label = { Text("Label (optional)") }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton({ showName = false; vm.parkCurrent(label) }) { Text("Park & start new") } },
        dismissButton = { TextButton({ showName = false }) { Text("Keep editing") } })
    if (showList) AlertDialog(onDismissRequest = { showList = false }, title = { Text("Your parked bills") }, text = {
        Column {
            Text("Resuming parks your current cart first. Recheck current prices and quantities before payment.")
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                if (parked.isEmpty()) item { Text("No parked bills yet.", Modifier.padding(16.dp)) }
                items(parked, key = { it.key }) { row ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(row.label, style = MaterialTheme.typography.titleMedium)
                        Text("${row.itemCount} products · ${java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(row.updatedAt))}")
                        Row {
                            TextButton({ showList = false; vm.resumeParked(row.key) }, enabled = !state.isLoading) { Text("Resume") }
                            TextButton({renameKey=row.key;renameLabel=row.label;renameOpen=true},enabled=!state.isLoading){Text("Rename")}
                            TextButton({ discard = row }, enabled = !state.isLoading) { Text("Discard") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }, confirmButton = { TextButton({ showList = false }) { Text("Close") } })
    discard?.let { row -> AlertDialog(onDismissRequest = { discard = null }, title = { Text("Discard ${row.label}?") },
        text = { Text("This permanently removes this unfinished bill. Other parked bills and completed sales stay unchanged.") },
        confirmButton = { TextButton({ vm.discardParked(row.key); discard = null }) { Text("Discard") } },
        dismissButton = { TextButton({ discard = null }) { Text("Keep") } }) }
}
}

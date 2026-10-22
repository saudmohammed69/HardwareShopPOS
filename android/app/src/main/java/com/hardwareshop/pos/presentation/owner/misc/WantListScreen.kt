package com.hardwareshop.pos.presentation.owner.misc

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hardwareshop.pos.data.repository.WantListRepository
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.presentation.common.*

@Composable fun WantListScreen(onNavigateUp:()->Unit,viewModel:WantListViewModel=hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context=LocalContext.current
    var filter by rememberSaveable {mutableStateOf(0)}
    var search by rememberSaveable {mutableStateOf("")}
    var editingId by rememberSaveable {mutableStateOf<String?>(null)}
    val editing=state.entries.firstOrNull {it.id==editingId}
    var editor by rememberSaveable {mutableStateOf(false)}
    var removing by remember {mutableStateOf<WantListRepository.Entry?>(null)}
    var suggestions by rememberSaveable {mutableStateOf(false)}
    val rows=remember(state.entries,filter,search) {state.entries.filter {entry->
        (filter==2 || entry.purchased==(filter==1)) && "${entry.name} ${entry.notes} ${entry.unit}".contains(search,true)
    }}
    LaunchedEffect(editor,editingId,state.loaded,editing) {
        if(editor && editingId!=null && state.loaded && editing==null){editor=false;editingId=null;viewModel.error("This entry is no longer available")}
    }
    if(editor && (editingId==null || editing!=null))key(editingId ?: "new") {
        WantListEditor(editing,state,viewModel){editor=false;editingId=null;viewModel.search("")}
    }
    removing?.let {entry->AlertDialog(onDismissRequest={removing=null},title={Text("Remove Want List item?")},text={Text(entry.name)},
        confirmButton={TextButton({removing=null;viewModel.remove(entry)},enabled=!state.busy){Text("REMOVE")}},
        dismissButton={TextButton({removing=null}){Text("KEEP")}})}
    Scaffold(topBar={AppTopBar("Want List / Kharidi List",onNavigateUp=onNavigateUp)}) {padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("want-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Text("Purchasing notes on this phone, included in backups. Marking purchased does not add stock; record received goods through Purchases.",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button({editingId=null;editor=true;viewModel.dismissError()},enabled=!state.busy){Text("ADD ITEM")}
                    OutlinedButton({try {
                        val text=viewModel.shareText(rows)
                        require(rows.isNotEmpty()) {"No items in this view to share"}
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"Share Want List"))
                    }catch(e:Exception){viewModel.error(e.message ?: "No sharing app is available")}},enabled=!state.busy){Text("SHARE LIST")}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {listOf("Pending","Completed","All").forEachIndexed {index,label->
                    FilterChip(filter==index,{filter=index},label={Text(label)})
                }}
                OutlinedTextField(search,{search=it.take(160)},label={Text("Filter items or notes")},singleLine=true,modifier=Modifier.fillMaxWidth())
                TextButton({suggestions=!suggestions}){Text(if(suggestions)"HIDE LOW-STOCK SUGGESTIONS" else "LOW-STOCK SUGGESTIONS")}
            }
            state.error?.let {error->item {ErrorMessage(error);TextButton(viewModel::dismissError){Text("DISMISS")}}}
            state.message?.let {message->item {Text(message,color=AppColors.Success)}}
            if(state.busy)item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            if(suggestions) {
                item {Text("Up to 100 current low-stock suggestions. Add only what you need, then edit its purchase quantity.")}
                items(state.lowStock,key={"suggestion-${it.id}"}) {product->
                    OutlinedButton({viewModel.addLowStock(product)},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("ADD: ${product.displayLabel}")}
                }
            }
            if(rows.isEmpty())item {Text("No items in this view")}
            items(rows,key={it.id}) {entry->Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(entry.name,style=MaterialTheme.typography.titleMedium)
                Text("${entry.quantity} ${entry.unit} · ${listOf("Low","Normal","High")[entry.priority]} priority")
                if(entry.notes.isNotBlank())Text(entry.notes)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Checkbox(entry.purchased,{viewModel.purchased(entry,it)},enabled=!state.busy)
                    Text(if(entry.purchased)"Purchased" else "Pending",Modifier.padding(top=12.dp))
                }
                if(viewModel.canEdit(entry))Row {
                    TextButton({editingId=entry.id;editor=true;viewModel.dismissError()},enabled=!state.busy){Text("EDIT")}
                    TextButton({removing=entry},enabled=!state.busy){Text("REMOVE")}
                }
            }}}
        }
    }
}

@Composable private fun WantListEditor(previous:WantListRepository.Entry?,state:WantListState,vm:WantListViewModel,onDismiss:()->Unit) {
    val originalRevision=rememberSaveable(previous?.id ?: "new") {previous?.revision.orEmpty()}
    var productId by rememberSaveable {mutableStateOf(previous?.productId)}
    var name by rememberSaveable {mutableStateOf(previous?.name.orEmpty())}
    var quantity by rememberSaveable {mutableStateOf(previous?.quantity ?: "1")}
    var unit by rememberSaveable {mutableStateOf(previous?.unit ?: "Piece")}
    var notes by rememberSaveable {mutableStateOf(previous?.notes.orEmpty())}
    var priority by rememberSaveable {mutableStateOf(previous?.priority ?: 1)}
    AlertDialog(onDismissRequest={if(!state.busy)onDismiss()},title={Text(if(previous==null)"Add to Want List" else "Edit Want List item")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.query,vm::search,label={Text("Find product (optional)")},singleLine=true,enabled=!state.busy,modifier=Modifier.fillMaxWidth().testTag("want-product-search"))
            state.matches.forEach {product:Product->TextButton({productId=product.id;name=product.displayLabel;unit=product.unit;vm.search("")},enabled=!state.busy){Text(product.displayLabel)}}
            Text(if(productId==null)"Unlinked purchase note" else "Linked to selected product",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(name,{name=it.take(160);productId=null},label={Text("Item name")},enabled=!state.busy,modifier=Modifier.fillMaxWidth().testTag("want-name"))
            OutlinedTextField(quantity,{quantity=it.take(24)},label={Text("Quantity")},singleLine=true,enabled=!state.busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("want-quantity"))
            OutlinedTextField(unit,{unit=it.take(24)},label={Text("Purchase unit")},singleLine=true,enabled=!state.busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(notes,{notes=it.take(500)},label={Text("Notes")},enabled=!state.busy,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {listOf("Low","Normal","High").forEachIndexed {index,label->
                FilterChip(priority==index,{priority=index},label={Text(label)},enabled=!state.busy)
            }}
            state.error?.let {ErrorMessage(it)}
        }},confirmButton={TextButton({vm.save(previous?.copy(revision=requireNotNull(originalRevision)),productId,name,quantity,unit,notes,priority,onDismiss)},enabled=!state.busy && name.isNotBlank() && quantity.isNotBlank() && unit.isNotBlank()){Text("SAVE ITEM")}},
        dismissButton={TextButton(onDismiss,enabled=!state.busy){Text("CANCEL")}})
}

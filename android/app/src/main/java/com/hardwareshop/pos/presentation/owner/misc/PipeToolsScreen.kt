package com.hardwareshop.pos.presentation.owner.misc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hardwareshop.pos.data.repository.PipeOffcutRepository
import com.hardwareshop.pos.presentation.common.*
import com.hardwareshop.pos.util.PipeCutPlanner

@Composable
fun PipeToolsScreen(onNavigateUp:()->Unit,planner:Boolean,viewModel:PipeToolsViewModel=hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val listState=rememberLazyListState()
    LaunchedEffect(state.message,state.error) {
        if(state.error!=null || state.message!=null) listState.scrollToItem(0)
    }
    var filter by rememberSaveable { mutableStateOf("") }
    var length by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var lengths by rememberSaveable { mutableStateOf("") }
    var kerf by rememberSaveable { mutableStateOf("0") }
    var confirmPlan by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<PipeOffcutRepository.Record?>(null) }
    var discard by remember { mutableStateOf<PipeOffcutRepository.Record?>(null) }
    var showCompleted by rememberSaveable {mutableStateOf(false)}
    var pieceSearch by rememberSaveable {mutableStateOf("")}
    var use by remember { mutableStateOf<PipeOffcutRepository.Record?>(null) }
    var usedLength by rememberSaveable { mutableStateOf("") }
    if(confirmPlan) AlertDialog(onDismissRequest={confirmPlan=false},title={Text("Have these cuts been made?")},
        text={Text("Confirm the physical cuts before updating the offcut register. Used pieces will be replaced by their remaining lengths, and new leftovers will be saved. Product stock and bills stay unchanged; record sales through Billing.")},
        confirmButton={TextButton({confirmPlan=false;viewModel.recordPlan()},enabled=!state.busy){Text("RECORD CUTS")}},
        dismissButton={TextButton({confirmPlan=false}){Text("KEEP PLAN ONLY")}})
    remove?.let { record->AlertDialog(onDismissRequest={remove=null},title={Text("Remove this offcut?")},
        text={Text("${record.productLabel} · ${PipeCutPlanner.format(record.feet)} ft · ${record.location}. Removes the register note only. Product stock stays unchanged. Use DISCARD & ADJUST STOCK when the physical material is discarded.")},
        confirmButton={TextButton({remove=null;viewModel.remove(record)},enabled=!state.busy){Text("REMOVE")}},
        dismissButton={TextButton({remove=null}){Text("KEEP")}}) }
    discard?.let {record->AlertDialog(onDismissRequest={discard=null},title={Text("Discard and deduct pipe stock?")},
        text={Text("${record.productLabel} · ${PipeCutPlanner.format(record.feet)} ft. This piece is included in normal pipe stock. Discarding removes that length from stock once and records a damaged-goods adjustment. This creates no bill.")},
        confirmButton={TextButton({discard=null;viewModel.discard(record)},enabled=!state.busy){Text("DISCARD & ADJUST STOCK")}},
        dismissButton={TextButton({discard=null}){Text("KEEP PIECE")}})}
    use?.let { record->AlertDialog(onDismissRequest={use=null},title={Text("Record a piece already used")},
        text={Column {
            Text("${record.productLabel} · available ${PipeCutPlanner.format(record.feet)} ft")
            OutlinedTextField(usedLength,{usedLength=it},label={Text("Used length (ft)")},suffix={Text("ft")},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            Text("Include saw loss in the used length. This updates the piece register only.")
        }},confirmButton={TextButton({use=null;viewModel.use(record,usedLength)},enabled=!state.busy){Text("RECORD USE")}},
        dismissButton={TextButton({use=null}){Text("CANCEL")}}) }
    Scaffold(topBar={AppTopBar(if(planner) "Cut Planner" else "Pipe Offcuts",onNavigateUp=onNavigateUp)}) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("pipe-tools-list"),
            state=listState,
            contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Text(if(planner) "Plan cuts from 20-ft pipes; try existing offcuts first." else "Record each physical leftover by pipe, length and shelf location.",
                    style=MaterialTheme.typography.titleMedium)
                Text("Offcuts are saved on this phone and included in backups. Keep the register on one phone; it does not sync between phones.",
                    style=MaterialTheme.typography.bodySmall)
            }
            state.error?.let { error->item { ErrorMessage(error);TextButton(viewModel::dismissError){Text("DISMISS")}} }
            state.message?.let { message->item { Text(message,color=AppColors.Success) } }
            if(state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                OutlinedTextField(filter,{filter=it},label={Text("Find pipe by material or size")},singleLine=true,modifier=Modifier.fillMaxWidth())
                state.selected?.let { Text("Selected: ${it.categoryName} · ${it.displayLabel}",style=MaterialTheme.typography.titleSmall) }
            }
            val choices=state.products.filter { "${it.categoryName} ${it.displayLabel}".contains(filter,true) }
            item {
                if(state.products.isEmpty()) Text("No configured pipes available. Configure a pipe in Products & Prices first.")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=180.dp).testTag("pipe-tools-products")) {
                    items(choices,key={it.id}) { product->TextButton(onClick={viewModel.select(product)},enabled=!state.busy,
                        modifier=Modifier.fillMaxWidth().testTag("pipe-tools-product-${product.id}")) {
                        Text("${product.categoryName} · ${product.displayLabel}")
                    } }
                }
            }
            if(planner) {
                item {
                    OutlinedTextField(lengths,{lengths=it.take(2000);viewModel.invalidatePlan()},label={Text("Cuts needed (ft), e.g. 8, 7, 5")},
                        modifier=Modifier.fillMaxWidth().testTag("cut-requests"),minLines=2)
                    OutlinedTextField(kerf,{kerf=it;viewModel.invalidatePlan()},label={Text("Saw loss per cut (ft)")},singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
                    Text("Best-fit estimate, not a guaranteed minimum. Exact end-to-end fits need no extra cut; check real measurements and blade loss.",
                        style=MaterialTheme.typography.bodySmall)
                    Button({viewModel.calculate(lengths,kerf)},enabled=state.selected!=null && !state.busy){Text("CALCULATE CUT PLAN")}
                }
                state.plan?.let { plan->
                    item { Text("${plan.usedOffcuts} offcuts first · ${plan.newPipes} new 20-ft pipes",style=MaterialTheme.typography.titleMedium) }
                    items(plan.sources.indices.toList()) { index->
                        val source=plan.sources[index]
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            Text(if(source.piece==null) "New 20-ft pipe" else "Offcut: ${source.piece.label.ifBlank { source.piece.id.take(8) }} · ${PipeCutPlanner.format(source.capacity)} ft")
                            Text("Cut: ${source.cuts.joinToString(" + ") { PipeCutPlanner.format(it) }} ft")
                            Text("Remaining: ${PipeCutPlanner.format(source.remaining)} ft")
                        } }
                    }
                    item { Button({confirmPlan=true},enabled=!state.busy){Text("CUTS MADE — UPDATE OFFCUTS")} }
                }
            } else {
                item {
                    OutlinedTextField(length,{length=it},label={Text("Leftover length (ft)")},suffix={Text("ft")},singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("offcut-length"))
                    OutlinedTextField(location,{location=it.take(100)},label={Text("Shelf / label (optional)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Button({viewModel.add(length,location)},enabled=state.selected!=null && !state.busy){Text("SAVE OFFCUT")}
                    Text("Recording a leftover does not add product stock; this is a register of physical pieces already counted in your stock.",style=MaterialTheme.typography.bodySmall)
                }
                item {
                    OutlinedTextField(pieceSearch,{pieceSearch=it},label={Text("Search offcuts by pipe, size or location")},modifier=Modifier.fillMaxWidth())
                    FilterChip(showCompleted,{showCompleted=!showCompleted},label={Text(if(showCompleted)"Sold / discarded" else "Available")})
                    Text("Sell through the USE OFFCUT action in Billing. Register returned physical pieces after checking their actual lengths.",style=MaterialTheme.typography.bodySmall)
                }
                val rows=state.offcuts.filter {(state.selected==null || it.productId==state.selected?.id) && it.available!=showCompleted &&
                    "${it.productLabel} ${it.location}".contains(pieceSearch,true)}
                item { Text("Recorded pieces (${rows.size})",style=MaterialTheme.typography.titleMedium) }
                items(rows,key={it.id}) { record->Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(record.productLabel)
                    Text("${PipeCutPlanner.format(record.feet)} ft · ${record.location.ifBlank { record.id.take(8) }}")
                    Text(if(record.available)"Available" else "${record.status} ${record.soldBillId?.let {id->"· bill $id"}.orEmpty()}")
                    state.products.firstOrNull {it.id==record.productId}?.let {product->
                        Text("Suggested selling price: ${com.hardwareshop.pos.util.CalculationEngine.formatRupees(com.hardwareshop.pos.util.PipeBilling.total(0,record.feet/100.0,product.pipeLengthPricePaisa,product.pipeFootPricePaisa))}",style=MaterialTheme.typography.bodySmall)
                    }
                    if(record.available)Column {
                        TextButton({use=record;usedLength=""},enabled=!state.busy){Text("RECORD USE")}
                        TextButton({discard=record},enabled=!state.busy){Text("DISCARD & ADJUST STOCK")}
                        TextButton({remove=record},enabled=!state.busy){Text("REMOVE REGISTER NOTE") }
                    }
                } } }
            }
        }
    }
}

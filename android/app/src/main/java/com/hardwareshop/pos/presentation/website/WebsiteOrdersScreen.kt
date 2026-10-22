package com.hardwareshop.pos.presentation.website

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.gson.*
import com.hardwareshop.pos.data.website.*
import com.hardwareshop.pos.presentation.common.*
import com.hardwareshop.pos.presentation.worker.billing.BillingViewModel
import com.hardwareshop.pos.util.CalculationEngine

@Composable
fun WebsiteOrdersScreen(onNavigateUp:()->Unit,onBill:(String)->Unit,onHistory:(Long)->Unit,
    viewModel:WebsiteOrdersViewModel=hiltViewModel()){
    val state by viewModel.state.collectAsState();var tab by rememberSaveable{mutableIntStateOf(0)}
    var selected by rememberSaveable{mutableStateOf<String?>(null)};var availability by rememberSaveable(selected){mutableStateOf(false)}
    var cancelReason by rememberSaveable(selected){mutableStateOf("")};var adjusting by rememberSaveable(selected){mutableStateOf(false)}
    Scaffold(topBar={AppTopBar("Orders (${state.rows.count{it.unread}} new)",onNavigateUp)}){padding->
        if(!viewModel.authenticated){Text("Log in to view Orders",Modifier.padding(padding).padding(16.dp));return@Scaffold}
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{
                if(!viewModel.configured)Text("Local test connection is not configured. Production website integration is disabled.",color=AppColors.Warning)
                else Text("Local synthetic Orders · Check availability manually. No stock is checked or reserved on submission.")
                if(!viewModel.alertsPermitted)Text("Order alerts are disabled by Android settings. New orders still appear on refresh.",color=AppColors.Warning)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button({viewModel.refresh()},enabled=!state.busy&&viewModel.configured,modifier=Modifier.testTag("website-refresh")){Text("Refresh Orders")}
                    if(selected!=null)TextButton({selected=null;adjusting=false}){Text("Back to list")}
                }
                if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let{ErrorMessage(it)};state.message?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                state.storage?.let{Text(it,color=AppColors.Warning,style=MaterialTheme.typography.bodySmall)}
            }
            val row=state.rows.firstOrNull{it.id==selected}
            if(row==null){
                item{ScrollableTabRow(tab){listOf("Active Orders","Completed Orders","Cancelled Orders").forEachIndexed{i,title->Tab(tab==i,{tab=i},text={Text(title)})}}}
                val shown=state.rows.filter{when(tab){1->it.status=="COMPLETED";2->it.status=="CANCELLED";else->it.status !in WebsiteOrdersRepository.TERMINAL}}
                if(shown.isEmpty())item{Text("No ${listOf("active","completed","cancelled")[tab]} orders cached.")}
                items(shown,key={it.id}){r->val doc=JsonParser.parseString(r.document).asJsonObject
                    OutlinedCard(onClick={selected=r.id;viewModel.read(r.id)},modifier=Modifier.fillMaxWidth().testTag("website-order-${r.id}")){
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                            Text("WO-${r.id.uppercase()}",style=MaterialTheme.typography.titleSmall)
                            Text(doc.getAsJsonObject("customer")?.string("name")?:"Archived order")
                            Text(r.status.replace('_',' ')+(if(r.unread)" · NEW" else ""))
                            Text("Estimate ${CalculationEngine.formatRupees(doc.long("estimatedSubtotalPaisa"))}")
                        }
                    }
                }
                if(viewModel.owner&&viewModel.configured)item{OwnerWebsiteControls(viewModel,state)}
            }else{
                val doc=JsonParser.parseString(row.document).asJsonObject;val lines=doc.getAsJsonArray("items")?:JsonArray()
                item{
                    Text("WO-${row.id.uppercase()}",style=MaterialTheme.typography.titleMedium)
                    Text(row.status.replace('_',' '));doc.getAsJsonObject("customer")?.let{Text(it.string("name"));Text(it.string("phone"))}
                    val pickup=doc.string("pickupAt")
                    Text("Pickup: "+if(pickup.isBlank())"Next available" else runCatching{java.time.Instant.parse(pickup).atZone(java.time.ZoneId.of("Asia/Kolkata")).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM, h:mm a",java.util.Locale.ENGLISH))+" IST"}.getOrDefault("Please confirm pickup time with customer"))
                    if(doc.string("note").isNotBlank())Text("Note: ${doc.string("note")}")
                    if(doc.string("missingRequest").isNotBlank())Text("Requested item: ${doc.string("missingRequest")}")
                    if(doc.has("archiveRef"))Text("Verified archive: ${doc.string("archiveRef")}. Details are in the owner's encrypted archive. Do not bill this order again.")
                    if(doc.get("localCompletionPending")?.asBoolean==true)Text("Bill saved locally. Completion sync is pending. Do not bill this order again.",color=AppColors.Warning)
                }
                if(adjusting){item{WebsiteAdjustmentEditor(row,viewModel){adjusting=false}}}
                else{
                    items(lines.map{it.asJsonObject},key={it.string("productId")}){line->
                        val checked=JsonParser.parseString(row.checklist).asJsonArray.any{it.asString==line.string("productId")}
                        OutlinedCard(Modifier.fillMaxWidth()){
                            Column(Modifier.padding(12.dp)){
                                Text("${line.string("name")} · ${line.string("size")}")
                                Text(if(line.get("isPipe")?.asBoolean==true)"${line.string("fullLengths")} full lengths + ${line.string("extraFeet")} extra ft" else "${line.string("quantity")} ${line.string("unit")}")
                                Text("Estimate ${CalculationEngine.formatRupees(line.long("estimatedLineTotalPaisa"))}")
                                if(line.string("note").isNotBlank())Text(line.string("note"))
                                if(row.status in listOf("ACCEPTED","PREPARING"))Row{Checkbox(checked,{viewModel.check(row.id,line.string("productId"),it)},enabled=!state.busy);Text("Prepared / checked",Modifier.padding(top=12.dp))}
                            }
                        }
                    }
                    item{
                        if(row.status=="NEW"){
                            Row{Checkbox(availability,{availability=it});Text("I manually checked availability",Modifier.padding(top=12.dp))}
                            Button({viewModel.action(row.id,"accept",available=availability)},enabled=availability&&!state.busy,modifier=Modifier.testTag("website-accept")){Text("Accept order")}
                        }
                        if(row.status=="ACCEPTED")Button({viewModel.action(row.id,"prepare")},enabled=!state.busy){Text("Start preparing")}
                        if(row.status=="PREPARING")Button({viewModel.action(row.id,"ready")},enabled=!state.busy){Text("Ready for pickup")}
                        if(row.status in listOf("ACCEPTED","PREPARING","READY_FOR_PICKUP")){
                            TextButton({adjusting=true},enabled=!state.busy){Text("Adjust with customer confirmation")}
                            if(row.status=="READY_FOR_PICKUP")Button({onBill(row.id)},enabled=!state.busy,modifier=Modifier.testTag("website-open-billing")){Text("Review in existing billing")}
                        }
                        if(row.status=="BILLING_IN_PROGRESS")Button({onBill(row.id)},enabled=!state.busy){Text("Recover this installation's billing")}
                        if(row.status=="COMPLETED")Button({viewModel.history(row.id,onHistory)},enabled=!state.busy){Text("Open existing bill / Bill History")}
                        if(row.status in listOf("NEW","ACCEPTED","PREPARING","READY_FOR_PICKUP")){
                            OutlinedTextField(cancelReason,{cancelReason=it.take(300)},label={Text("Cancellation reason")},modifier=Modifier.fillMaxWidth())
                            TextButton({viewModel.action(row.id,"cancel",cancelReason)},enabled=cancelReason.isNotBlank()&&!state.busy){Text("Cancel order with reason")}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnerWebsiteControls(vm:WebsiteOrdersViewModel,state:WebsiteOrdersState){
    fun time(n:Int)="%02d:%02d".format(java.util.Locale.US,n/60,n%60)
    var open by rememberSaveable(state.hours?.first){mutableStateOf(time(state.hours?.first?:540))};var close by rememberSaveable(state.hours?.second){mutableStateOf(time(state.hours?.second?:1200))};var maintenance by rememberSaveable(state.hours?.third){mutableStateOf(state.hours?.third?:false)}
    LaunchedEffect(Unit){vm.loadHours()}
    var publicationSearch by rememberSaveable{mutableStateOf("")};var publicationLimit by rememberSaveable{mutableIntStateOf(30)}
    HorizontalDivider();Text("Owner website controls",style=MaterialTheme.typography.titleMedium)
    Text("Public fields only. Product names, selling prices and stock in the POS are unchanged.")
    Button({vm.publish()},enabled=!state.busy){Text("Publish active catalog")}
    TextButton({vm.publications()},enabled=!state.busy){Text("Review hide / show products")}
    if(state.publications.isNotEmpty())OutlinedTextField(publicationSearch,{publicationSearch=it;publicationLimit=30},label={Text("Find published product")},modifier=Modifier.fillMaxWidth())
    val visiblePublications=state.publications.filter{it.second.contains(publicationSearch,true)}
    for((row,label) in visiblePublications.take(publicationLimit)){Row(Modifier.fillMaxWidth()){Checkbox(row.published,{vm.visible(row.publicId,it)},enabled=!state.busy);Text(label,Modifier.weight(1f).padding(top=12.dp))}}
    if(visiblePublications.size>publicationLimit)TextButton({publicationLimit+=30}){Text("Show more products")}
    Text("Visibility choices are saved locally before sending. Retry Publish active catalog after a connection error.",style=MaterialTheme.typography.bodySmall)
    OutlinedTextField(open,{open=it.take(5)},label={Text("Open (IST, HH:mm)")},singleLine=true)
    OutlinedTextField(close,{close=it.take(5)},label={Text("Close (IST, HH:mm)")},singleLine=true)
    Row{Checkbox(maintenance,{maintenance=it});Text("Pause new website orders",Modifier.padding(top=12.dp))}
    fun minute(s:String):Int?=if(s.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")))s.take(2).toInt()*60+s.takeLast(2).toInt()else if(s=="24:00")1440 else null
    val a=minute(open);val b=minute(close)
    Button({vm.settings(a?:0,b?:0,maintenance)},enabled=a!=null&&b!=null&&a<b&&!state.busy){Text("Publish shop details and hours")}
    Text("Production capacity remains blocked. No automatic archive cleanup or paid fallback.",color=AppColors.Warning)
}

@Composable
private fun WebsiteAdjustmentEditor(row:WebsiteOrderRow,vm:WebsiteOrdersViewModel,done:()->Unit){
    val original=remember(row.revision){JsonParser.parseString(row.document).asJsonObject.getAsJsonArray("items").map{it.asJsonObject}}
    val quantities=remember(row.revision){mutableStateMapOf<String,String>()};val feet=remember(row.revision){mutableStateMapOf<String,String>()};val removed=remember(row.revision){mutableStateMapOf<String,Boolean>()}
    var reason by rememberSaveable(row.id){mutableStateOf("")};var confirmed by rememberSaveable(row.id){mutableStateOf(false)}
    Text("Confirm quantities/removals with the customer before saving. Availability is checked manually.")
    original.forEach{line->val id=line.string("productId");val pipe=line.get("isPipe")?.asBoolean==true
        Text("${line.string("name")} · ${line.string("size")}")
        OutlinedTextField(quantities[id]?:line.string(if(pipe)"fullLengths" else "quantity"),{quantities[id]=it.take(12)},label={Text(if(pipe)"Full lengths" else "Quantity")},singleLine=true)
        if(pipe)OutlinedTextField(feet[id]?:line.string("extraFeet"),{feet[id]=it.take(12)},label={Text("Extra feet")},singleLine=true)
        Row{Checkbox(removed[id]?:false,{removed[id]=it});Text("Remove this size",Modifier.padding(top=12.dp))}
    }
    OutlinedTextField(reason,{reason=it.take(300)},label={Text("Reason / customer agreement")},modifier=Modifier.fillMaxWidth())
    Row{Checkbox(confirmed,{confirmed=it});Text("Customer confirmed these changes",Modifier.padding(top=12.dp))}
    Button({val items=JsonArray();original.filter{removed[it.string("productId")]!=true}.forEach{l->val id=l.string("productId");val pipe=l.get("isPipe")?.asBoolean==true
        items.add(json("productId" to id,"note" to l.string("note")).apply{if(pipe){addProperty("fullLengths",quantities[id]?:l.string("fullLengths"));addProperty("extraFeet",feet[id]?:l.string("extraFeet"))}else addProperty("quantity",quantities[id]?:l.string("quantity"))})}
        vm.adjust(row.id,items,reason,confirmed);done()},enabled=confirmed&&reason.isNotBlank()){Text("Save confirmed changes")}
    TextButton(done){Text("Keep current order")}
}

@Composable
fun WebsiteBillingReview(id:String,billing:BillingViewModel,onNavigateUp:()->Unit,onImported:()->Unit,vm:WebsiteOrdersViewModel=hiltViewModel()){
    val state by vm.state.collectAsState();val bill by billing.state.collectAsState();var agreed by rememberSaveable(id){mutableStateOf(false)}
    LaunchedEffect(id){vm.handoff(id)}
    Scaffold(topBar={AppTopBar("Review website billing",onNavigateUp)}){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{if(state.busy||bill.isLoading)LinearProgressIndicator(Modifier.fillMaxWidth());state.error?.let{ErrorMessage(it)};bill.error?.let{ErrorMessage(it)}}
            state.handoff?.let{h->
                item{Text("WO-${h.row.id.uppercase()}");Text("${h.customerName} · ${h.phone}");Text("Current POS selling prices below. Check availability manually and confirm any difference with the customer. GST, discounts and payment follow the existing billing screen.")}
                items(h.items){line->Text("${line.product.displayLabel}: ${line.quantityDisplay} · ${CalculationEngine.formatRupees(line.itemTotalPaisa)}")}
                item{
                    Row{Checkbox(agreed,{agreed=it});Text("Availability and current rates confirmed with customer",Modifier.padding(top=12.dp))}
                    if(bill.cart.isNotEmpty()){
                        Text("An unfinished bill is open. Choose how to protect it before loading this order.")
                        Button({billing.importWebsiteOrder(h,true,onImported)},enabled=agreed&&!bill.isLoading){Text("Park current bill and load order")}
                        TextButton({billing.importWebsiteOrder(h,false,onImported)},enabled=agreed&&!bill.isLoading){Text("Replace current bill and load order")}
                    }else Button({billing.importWebsiteOrder(h,true,onImported)},enabled=agreed&&!bill.isLoading){Text("Load into existing billing")}
                    TextButton(onNavigateUp){Text("Cancel / keep existing cart")}
                }
            }
        }
    }
}

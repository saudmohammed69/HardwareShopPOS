package com.hardwareshop.pos.presentation.owner.products

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hardwareshop.pos.data.repository.InventoryManagerRepository
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.presentation.common.*
import com.hardwareshop.pos.util.*

@Composable
fun InventoryManagerScreen(onNavigateUp:()->Unit,onEdit:(Long)->Unit,onStock:()->Unit,
    vm:InventoryManagerViewModel=hiltViewModel()) {
    val catalogue by vm.catalogue.collectAsState();val categories by vm.categories.collectAsState()
    val types by vm.types.collectAsState();val busy by vm.busy.collectAsState();val message by vm.message.collectAsState()
    var creating by remember { mutableStateOf(false) };var mode by remember { mutableStateOf("Manage Existing Products") }
    var query by remember { mutableStateOf("") };var filter by remember { mutableStateOf(0L) }
    var sortOrder by remember { mutableStateOf(ProductSortOrder.DEFAULT) }
    var parent by remember { mutableStateOf("") };var categoryId by remember { mutableStateOf(0L) };var typeId by remember { mutableStateOf(0L) }
    var structure by remember { mutableStateOf("Size") };var unit by remember { mutableStateOf("Piece") };var gst by remember { mutableStateOf("18") }
    var physicalFeet by remember { mutableStateOf("") };var newCategory by remember { mutableStateOf("") };var newType by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(listOf(InventoryManagerRepository.Variant())) }
    val selected=catalogue.filter { filter==0L || it.categoryId==filter }.filter { p ->
        query.isBlank() || ProductSearch.score(ProductSearch.tokens(query),p.name+" "+p.size+" "+p.categoryName+" "+p.typeName,p.searchAliases)!=null
    }
    val groups=remember(selected,catalogue,sortOrder) { ProductSort.groups(selected,catalogue,sortOrder) }
    fun newFamily(product:Product?=null) {
        parent=product?.let(ProductVariantMetadata::parent).orEmpty();categoryId=product?.categoryId ?: 0;typeId=product?.typeId ?: 0
        unit=product?.unit ?: "Piece";gst=product?.let { ProductForm.decimal(it.gstRateBp.toLong()) } ?: "18"
        structure=if(product?.isPipe==true) "Pipe" else "Size";physicalFeet=product?.pipeFeetPerLength?.takeIf { it>0 }?.toString().orEmpty()
        val attributes=product?.let(ProductVariantMetadata::read)
        if(structure!="Pipe" && product!=null) structure=when {
            attributes?.model?.isNotBlank()==true && attributes.colour.isNotBlank() -> "Type + Colour + Size"
            attributes?.model?.isNotBlank()==true -> "Type + Size"
            attributes?.colour?.isNotBlank()==true -> if(product.size.isBlank()) "Colour" else "Colour + Size"
            product.size.isBlank() -> "Simple"
            else -> "Size"
        }
        rows=listOf(InventoryManagerRepository.Variant(colour=attributes?.colour.orEmpty(),model=attributes?.model.orEmpty(),extra=attributes?.extra.orEmpty()))
        if(categoryId>0) vm.category(categoryId);creating=true
    }
    androidx.activity.compose.BackHandler(enabled=creating && !busy) { creating=false }
    Scaffold(topBar={AppTopBar("Product & Inventory Manager",onNavigateUp={if(creating)creating=false else onNavigateUp()})}) { padding ->
        if(!vm.owner) { ErrorMessage("Owner access required");return@Scaffold }
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("inventory-manager-list"),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            message?.let { item { Text(it) } };if(busy)item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if(!creating) {
                item { ProductSortControl(sortOrder,{sortOrder=it},enabled=!busy) }
                item { Row(Modifier.fillMaxWidth()) {
                    Button({newFamily()},enabled=!busy) {Text("Add New Product")}
                    TextButton({mode="Add Variant to Existing Product"}) {Text("Add Variant")}
                } }
                item { Row {TextButton({mode="Manage Existing Products"}){Text("Manage Products")};TextButton({mode="View Inventory"}){Text("View Inventory")}} }
                item { Text(mode);OutlinedTextField(query,{query=it},label={Text("Search products and combinations")},modifier=Modifier.fillMaxWidth(),singleLine=true) }
                item { InventoryChoice("Category filter",listOf(0L to "All categories")+categories.map {it.id to it.name},filter){filter=it} }
                items(groups,key={it.key}) {group ->
                    var expanded by remember(group.key){mutableStateOf(false)}
                    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
                        TextButton({if(mode.startsWith("Add Variant"))newFamily(group.products.first()) else expanded=!expanded}) {
                            Text("${group.name} · ${group.products.size} combination${if(group.products.size==1)"" else "s"}")
                        }
                        if(expanded) {
                            TextButton({newFamily(group.products.first())}) {Text("Add Colour / Size / Type")}
                            group.products.forEach {p ->
                                Text(p.displayLabel,Modifier.clickable{onEdit(p.id)})
                                Text("Buy: ${if(p.purchasePricePaisa==0L) "Not set" else CalculationEngine.formatRupees(p.purchasePricePaisa)} · Sell: ${CalculationEngine.formatRupees(p.sellingPricePaisa)} · Stock: ${CalculationEngine.formatQuantity(p.currentStock)} ${p.unit}",style=MaterialTheme.typography.bodySmall)
                                if(p.isPipe) Text("${CalculationEngine.formatRupees(p.pipeLengthPricePaisa)} / length · ${CalculationEngine.formatRupees(p.pipeFootPricePaisa)} / ft")
                                Row {TextButton({onEdit(p.id)}){Text("Edit")};TextButton(onStock){Text("Stock history / adjust")}}
                                HorizontalDivider()
                            }
                        }
                    }}
                }
            } else {
                item { Text("Add product combinations",style=MaterialTheme.typography.titleLarge)
                    OutlinedTextField(parent,{parent=it},label={Text("Parent product / family name")},modifier=Modifier.fillMaxWidth()) }
                item { InventoryChoice("Structure",listOf("Simple","Size","Colour","Colour + Size","Type + Size","Type + Colour + Size","Pipe").mapIndexed {i,v->i.toLong() to v},
                    listOf("Simple","Size","Colour","Colour + Size","Type + Size","Type + Colour + Size","Pipe").indexOf(structure).toLong()) {
                    structure=listOf("Simple","Size","Colour","Colour + Size","Type + Size","Type + Colour + Size","Pipe")[it.toInt()]
                } }
                item { InventoryChoice("Category",categories.map{it.id to it.name},categoryId){categoryId=it;typeId=0;vm.category(it)}
                    Row {OutlinedTextField(newCategory,{newCategory=it},label={Text("New category")},modifier=Modifier.weight(1f));TextButton({vm.createCategory(newCategory){categoryId=it;typeId=0}},enabled=newCategory.isNotBlank()){Text("CREATE")}} }
                item { InventoryChoice("Product type",types.map{it.id to it.name},typeId){typeId=it}
                    Row {OutlinedTextField(newType,{newType=it},label={Text("New type")},modifier=Modifier.weight(1f));TextButton({vm.createType(categoryId,newType){typeId=it}},enabled=categoryId>0 && newType.isNotBlank()){Text("CREATE")}} }
                item {OutlinedTextField(unit,{unit=it},label={Text("Selling / stock unit")},modifier=Modifier.fillMaxWidth());OutlinedTextField(gst,{gst=it},label={Text("GST %")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))}
                if(structure=="Pipe")item {OutlinedTextField(physicalFeet,{physicalFeet=it},label={Text("Actual feet per full length")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))}
                items(rows.indices.toList(),key={it}) {index ->
                    val row=rows[index]
                    fun change(value:InventoryManagerRepository.Variant){rows=rows.toMutableList().also{it[index]=value}}
                    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("Combination ${index+1}")
                        if(structure.contains("Type"))OutlinedTextField(row.model,{change(row.copy(model=it))},label={Text("Type / finish / model")})
                        if(structure.contains("Colour"))OutlinedTextField(row.colour,{change(row.copy(colour=it))},label={Text("Colour")})
                        if(structure.contains("Size") || structure=="Pipe")OutlinedTextField(row.size,{change(row.copy(size=it))},label={Text(if(structure=="Pipe")"Diameter" else "Size / capacity")})
                        if(structure=="Pipe") {
                            InventoryNumber("Price per length ₹",row.lengthPrice){change(row.copy(lengthPrice=it))}
                            InventoryNumber("Price per foot ₹",row.footPrice){change(row.copy(footPrice=it))}
                        } else InventoryNumber("Selling price ₹",row.selling){change(row.copy(selling=it))}
                        InventoryNumber("Buying price ₹ (optional)",row.buying){change(row.copy(buying=it))}
                        InventoryNumber("Opening stock (optional)",row.stock){change(row.copy(stock=it))}
                        OutlinedTextField(row.extra,{change(row.copy(extra=it))},label={Text("Other specification (optional)")})
                        if(rows.size>1)TextButton({rows=rows.filterIndexed{i,_->i!=index}}){Text("Remove combination")}
                    }}
                }
                item { Text("Blank buying price means not set. This app records blank opening stock as 0 (stock not recorded); billing asks staff to check availability. Existing stock is changed only through Stock Management.",style=MaterialTheme.typography.bodySmall)
                    TextButton({rows=rows+InventoryManagerRepository.Variant()},enabled=!busy){Text("ADD COMBINATION")}
                    Button({vm.save(InventoryManagerRepository.Request(parent,categoryId,typeId,unit,gst,structure=="Pipe",physicalFeet,
                        rows.map{row->row.copy(colour=if(structure.contains("Colour"))row.colour else "",model=if(structure.contains("Type"))row.model else "",
                            size=if(structure.contains("Size") || structure=="Pipe")row.size else "")},structure=structure)){creating=false}},enabled=!busy){Text("SAVE ALL VARIANTS")}
                    TextButton({creating=false},enabled=!busy){Text("CANCEL")}}
            }
        }
    }
}
@Composable private fun InventoryNumber(label:String,value:String,onValue:(String)->Unit)=OutlinedTextField(value,onValue,
    label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
@Composable private fun InventoryChoice(label:String,items:List<Pair<Long,String>>,selected:Long,onChoose:(Long)->Unit) {
    var open by remember{mutableStateOf(false)}
    Box {OutlinedButton({open=true}){Text("$label: ${items.firstOrNull{it.first==selected}?.second ?: "Choose"}")}
        DropdownMenu(open,{open=false}){items.forEach{(id,name)->DropdownMenuItem(text={Text(name)},onClick={onChoose(id);open=false})}}}
}

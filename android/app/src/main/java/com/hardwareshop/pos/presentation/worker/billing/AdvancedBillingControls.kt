package com.hardwareshop.pos.presentation.worker.billing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hardwareshop.pos.util.*

@Composable internal fun SplitPaymentFields(state:BillingUiState,onAmounts:(Long,Long)->Unit) {
    var cash by rememberSaveable { mutableStateOf(java.math.BigDecimal.valueOf(state.splitCashPaisa.coerceAtLeast(0),2).toPlainString()) }
    var upi by rememberSaveable { mutableStateOf(java.math.BigDecimal.valueOf(state.splitUpiPaisa.coerceAtLeast(0),2).toPlainString()) }
    val total=state.calculation?.totalPaisa ?: 0
    val cashValue=CalculationEngine.parseRupeesToPaisa(cash)
    val upiValue=CalculationEngine.parseRupeesToPaisa(upi)
    val split=if(cashValue!=null && upiValue!=null)PaymentSplit(cashValue,upiValue) else null
    val remaining=runCatching { split?.remaining(total) }.getOrNull()
    Card(Modifier.fillMaxWidth().testTag("split-payment")) { Column(Modifier.padding(16.dp),Arrangement.spacedBy(8.dp)) {
        Text("Split Cash + UPI",style=MaterialTheme.typography.titleMedium)
        Text("Total: ${CalculationEngine.formatRupees(total)}")
        OutlinedTextField(cash,{cash=it;onAmounts(CalculationEngine.parseRupeesToPaisa(it) ?: -1,upiValue ?: -1)},label={Text("Cash amount ₹")},
            enabled=!state.isLoading,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("split-cash"))
        OutlinedTextField(upi,{upi=it;onAmounts(cashValue ?: -1,CalculationEngine.parseRupeesToPaisa(it) ?: -1)},label={Text("UPI amount ₹")},
            enabled=!state.isLoading,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("split-upi"))
        Text(if(remaining==null)"Enter valid non-negative amounts with up to two decimals" else "Remaining: ${CalculationEngine.formatRupees(remaining)}")
        Text("Confirm that both payments were received before saving. A QR code is not payment confirmation.",style=MaterialTheme.typography.bodySmall)
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CartSwipeActions(enabled:Boolean,modifier:Modifier=Modifier,onEdit:()->Unit,onRemove:()->Unit,content:@Composable ()->Unit) {
    val swipe=rememberSwipeToDismissBoxState(confirmValueChange={ value->
        if(enabled)when(value){SwipeToDismissBoxValue.StartToEnd->onEdit();SwipeToDismissBoxValue.EndToStart->onRemove();else->Unit}
        false
    })
    SwipeToDismissBox(state=swipe,modifier=modifier,enableDismissFromStartToEnd=enabled,enableDismissFromEndToStart=enabled,
        backgroundContent={Row(Modifier.fillMaxSize().padding(16.dp),Arrangement.SpaceBetween){Text("EDIT");Text("REMOVE")}}) { Surface(Modifier.fillMaxWidth()) { Column { content() } } }
}

@Composable internal fun CartSwipeEditor(item:com.hardwareshop.pos.domain.model.CartItem,state:BillingUiState,vm:BillingViewModel,onQuantity:()->Unit,onDismiss:()->Unit) {
    var mode by remember { mutableStateOf("") }
    var price by remember { mutableStateOf(java.math.BigDecimal.valueOf(item.unitPricePaisa,2).toPlainString()) }
    var foot by remember { mutableStateOf(java.math.BigDecimal.valueOf(item.pipeFootPricePaisa,2).toPlainString()) }
    var discount by remember { mutableStateOf(java.math.BigDecimal.valueOf(state.discountPaisa,2).toPlainString()) }
    val amount=CalculationEngine.parseRupeesToPaisa(if(mode=="discount")discount else price)
    AlertDialog(onDismissRequest=onDismiss,title={Text("Edit ${item.product.displayLabel}")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(mode.isEmpty()) {
            TextButton(onQuantity){Text("EDIT QUANTITY / SIZE")}
            if(vm.canEditPrice)TextButton({mode="price"}){Text("EDIT PRICE FOR THIS BILL")}
            TextButton({mode="discount"}){Text("EDIT BILL DISCOUNT")}
        } else if(mode=="price") {
            OutlinedTextField(price,{price=it},label={Text(if(item.product.isPipe)"Price per length ₹" else "Unit price ₹")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            if(item.product.isPipe)OutlinedTextField(foot,{foot=it},label={Text("Price per foot ₹")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            Text("Current bill only")
        } else {
            OutlinedTextField(discount,{discount=it},label={Text("Bill discount ₹")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            Text("Existing worker discount limits apply")
        }
        state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={if(mode.isNotEmpty())TextButton({
        if(mode=="discount"){vm.setDiscount(requireNotNull(amount),"Swipe edit");onDismiss()}
        else if(item.product.isPipe){if(vm.setPipePrices(item.product.id,price,foot))onDismiss()}
        else {vm.setItemPrice(item.product.id,price);onDismiss()}
    },enabled=!state.isLoading && amount!=null && (mode=="discount" || amount>0) && (mode!="price" || !item.product.isPipe || (CalculationEngine.parseRupeesToPaisa(foot) ?: 0)>0)){Text("APPLY")}},
        dismissButton={TextButton(onDismiss){Text("CANCEL")}})
}

package com.hardwareshop.pos.presentation.worker.billing
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.CalculationEngine

@Composable internal fun EntryPriceEditor(product:Product,price:String,foot:String,onPrice:(String)->Unit,onFoot:(String)->Unit) {
    var open by remember(product.id){mutableStateOf(false)}
    Column {
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("Bill price: ₹$price${if(product.isPipe) " / length · ₹$foot / ft" else " / ${product.unit}"}",
                modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            TextButton({open=!open}){Text("EDIT PRICE")}
        }
        if(open) {
            OutlinedTextField(price,onPrice,label={Text(if(product.isPipe)"Price per length ₹" else "Unit price ₹")},singleLine=true,
                isError=(CalculationEngine.parseRupeesToPaisa(price) ?: 0)<=0,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            if(product.isPipe)OutlinedTextField(foot,onFoot,label={Text("Price per foot ₹")},singleLine=true,
                isError=(CalculationEngine.parseRupeesToPaisa(foot) ?: 0)<=0,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            Text("Current bill only",style=MaterialTheme.typography.labelSmall)
        }
    }
}

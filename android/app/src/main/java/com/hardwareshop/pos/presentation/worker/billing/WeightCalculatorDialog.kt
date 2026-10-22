package com.hardwareshop.pos.presentation.worker.billing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hardwareshop.pos.domain.model.CartItem
import com.hardwareshop.pos.util.WeightCalculator
import kotlinx.coroutines.launch

@Composable internal fun WeightCalculatorDialog(item:CartItem,vm:BillingViewModel,onDismiss:()->Unit) {
    val mode=WeightCalculator.modeForUnit(item.product.unit) ?: return
    var gross by rememberSaveable { mutableStateOf("") }
    var tare by rememberSaveable { mutableStateOf("0") }
    var unit by rememberSaveable { mutableStateOf("g") }
    var calibration by rememberSaveable { mutableStateOf("") }
    var savedCalibration by remember { mutableStateOf("") }
    var sampleWeight by rememberSaveable { mutableStateOf("") }
    var sampleCount by rememberSaveable { mutableStateOf("") }
    var sampleOpen by rememberSaveable { mutableStateOf(false) }
    var result by remember { mutableStateOf<WeightCalculator.Estimate?>(null) }
    var quantity by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    val scope=rememberCoroutineScope()
    fun invalidate() { result=null;quantity="";error=null }
    LaunchedEffect(item.product.id) {
        try {calibration=vm.loadWeightCalibration(item.product);savedCalibration=calibration}
        catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){error=e.message}
        finally {busy=false}
    }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val focus=androidx.compose.ui.platform.LocalFocusManager.current
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(),contentAlignment=androidx.compose.ui.Alignment.Center) {
            Surface(Modifier.widthIn(max=560.dp).fillMaxWidth().fillMaxHeight(),shape=MaterialTheme.shapes.large) {
                Column(Modifier.imePadding().padding(16.dp)) {
                    Text(if(mode==WeightCalculator.Mode.WIRE)"Wire weight → length" else "Fastener weight → pieces",style=MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("weight-calculator"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("${item.product.displayLabel} · billing unit: ${item.product.unit}")
                        Text("Estimate from this variant's measured calibration. Check the final quantity before applying.")
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf("g","kg").forEach { weightUnit->FilterChip(selected=unit==weightUnit,onClick={unit=weightUnit;invalidate()},label={Text(weightUnit)}) }
                        }
                        WeightField("Gross weight ($unit)",gross,{gross=it;invalidate()},"weight-gross")
                        WeightField("Tare weight ($unit)",tare,{tare=it;invalidate()},"weight-tare")
                        OutlinedTextField(calibration,{calibration=it;invalidate()},enabled=vm.canEditPrice && !busy,
                            label={Text(if(mode==WeightCalculator.Mode.WIRE)"Calibrated grams per meter" else "Calibrated grams per piece")},
                            singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("weight-calibration"))
                        if(vm.canEditPrice) {
                            if(mode==WeightCalculator.Mode.PIECES) {
                                TextButton({sampleOpen=!sampleOpen}){Text("CALIBRATE FROM COUNTED SAMPLE")}
                                if(sampleOpen) {
                                    WeightField("Sample net weight (g), excluding container",sampleWeight,{sampleWeight=it},"sample-weight")
                                    WeightField("Counted sample pieces",sampleCount,{sampleCount=it},"sample-count")
                                    TextButton({focus.clearFocus();try {
                                        calibration=WeightCalculator.sampleUnitWeight(WeightCalculator.decimal(sampleWeight,true),sampleCount).toPlainString();invalidate()
                                    }catch(e:Exception){error=e.message}}){Text("USE SAMPLE CALIBRATION")}
                                }
                            }
                            TextButton({
                                focus.clearFocus();busy=true;error=null
                                scope.launch {try {vm.saveWeightCalibration(item.product,calibration);savedCalibration=calibration}
                                    catch(e:kotlinx.coroutines.CancellationException){throw e}
                                    catch(e:Exception){error=e.message}
                                    finally {busy=false}}
                            },enabled=!busy && calibration.isNotBlank()){Text("SAVE VARIANT CALIBRATION")}
                        }
                        Text(if(calibration.isBlank())"Ask the owner to measure and save a calibration for this variant."
                            else if(calibration!=savedCalibration)"Save this calibration before using it."
                            else "Saved on this phone and included in shop backups.",style=MaterialTheme.typography.bodySmall)
                        Button({focus.clearFocus();try {
                            result=WeightCalculator.estimate(WeightCalculator.grams(gross,unit),WeightCalculator.grams(tare,unit),WeightCalculator.decimal(calibration,true),mode)
                            quantity=WeightCalculator.billingQuantity(requireNotNull(result),item.product.unit).toPlainString();error=null
                        }catch(e:Exception){result=null;quantity="";error=e.message}},enabled=!busy && calibration.isNotBlank() && calibration==savedCalibration){Text("CALCULATE ESTIMATE")}
                        result?.let { estimate->
                            Text("Net weight: ${WeightCalculator.display(estimate.netGrams)} g")
                            estimate.meters?.let {Text("Estimated: ${WeightCalculator.display(it)} m · ${WeightCalculator.display(requireNotNull(estimate.feet))} ft")}
                            estimate.pieces?.let {Text("Estimated: ${it.toPlainString()} pieces (nearest whole piece)")}
                            WeightField("Review / correct quantity (${item.product.unit})",quantity,{quantity=it},"weight-quantity")
                            Text("Applying replaces this cart line's quantity. The receipt labels it as estimated. Stock changes only when the bill is saved.",style=MaterialTheme.typography.bodySmall)
                        }
                        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        TextButton(onDismiss){Text("CANCEL")}
                        Button({if(vm.applyWeightQuantity(item.product.id,quantity))onDismiss() else error=vm.state.value.error},enabled=!busy && result!=null && quantity.isNotBlank()){Text("APPLY QUANTITY")}
                    }
                }
            }
        }
    }
}

@Composable private fun WeightField(label:String,value:String,onValue:(String)->Unit,tag:String) {
    OutlinedTextField(value,{onValue(it.take(24))},label={Text(label)},singleLine=true,
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag(tag))
}

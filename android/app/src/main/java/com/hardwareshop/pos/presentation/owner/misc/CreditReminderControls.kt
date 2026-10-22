package com.hardwareshop.pos.presentation.owner.misc

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.repository.CreditReminderRepository
import com.hardwareshop.pos.util.CalculationEngine
import com.hardwareshop.pos.util.CreditReminders
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel class CreditReminderViewModel @Inject constructor(private val repo:CreditReminderRepository):ViewModel() {
    data class State(val snapshot:CreditReminderRepository.Snapshot?=null,val error:String?=null,val busy:Boolean=false)
    private val _state=MutableStateFlow(State())
    val state=_state.asStateFlow()
    private var observation:Job?=null
    private var observing=0L
    fun observe(id:Long) {
        if(id==observing && observation?.isActive==true)return
        observing=id;observation?.cancel()
        _state.value=State()
        observation=viewModelScope.launch {
            try {repo.observe(id).collect {value->_state.update {it.copy(snapshot=value,error=null)}}}
            catch(e:CancellationException){throw e}
            catch(e:Exception){_state.update {it.copy(error=e.message ?: "Could not load reminders")}}
        }
    }
    fun saveDate(id:Long,date:String) {
        if(_state.value.busy)return
        _state.update {it.copy(busy=true,error=null)}
        viewModelScope.launch {
            try {repo.setDueDate(id,date)}
            catch(e:CancellationException){throw e}
            catch(e:Exception){_state.update {it.copy(error=e.message ?: "Could not save due date")}}
            finally {_state.update {it.copy(busy=false)}}
        }
    }
    suspend fun prepare(id:Long)=repo.prepare(id)
    suspend fun opening(attempt:CreditReminderRepository.Attempt)=repo.recordOpening(attempt)
    suspend fun outcome(id:String,opened:Boolean,detail:String)=repo.markOutcome(id,opened,detail)
}

@Composable internal fun CreditReminderControls(customerId:Long,vm:CreditReminderViewModel=hiltViewModel()) {
    val state by vm.state.collectAsState()
    val context=LocalContext.current
    val focus=LocalFocusManager.current
    val scope=rememberCoroutineScope()
    var date by remember(customerId){mutableStateOf("")}
    var edited by remember(customerId){mutableStateOf(false)}
    var working by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var prepared by remember(customerId){mutableStateOf<CreditReminderRepository.Attempt?>(null)}
    LaunchedEffect(customerId){vm.observe(customerId)}
    LaunchedEffect(state.snapshot?.dueDate){if(!edited)date=state.snapshot?.dueDate.orEmpty()}
    prepared?.let {attempt->AlertDialog(
        onDismissRequest={if(!working)prepared=null},title={Text("Review Udhar reminder")},
        text={Column(Modifier.verticalScroll(rememberScrollState()).imePadding(),Arrangement.spacedBy(8.dp)) {
            Text(attempt.text)
            Text("Opening a share app is recorded as an attempt. Delivery and payment are not confirmed.",style=MaterialTheme.typography.bodySmall)
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={Column {
            fun openShare(whatsapp:Boolean) {
                if(working)return
                working=true;error=null
                scope.launch {
                    try {
                        val recorded=vm.opening(attempt)
                        val send=Intent(Intent.ACTION_SEND).apply {type="text/plain";putExtra(Intent.EXTRA_TEXT,recorded.text)}
                        try {
                            if(whatsapp) {
                                try {context.startActivity(Intent(send).setPackage("com.whatsapp"))}
                                catch(_:android.content.ActivityNotFoundException){context.startActivity(Intent.createChooser(send,"Share Udhar reminder"))}
                            }else context.startActivity(Intent.createChooser(send,"Share Udhar reminder"))
                            vm.outcome(recorded.id,true,if(whatsapp)"WhatsApp or chooser opened" else "Android chooser opened")
                            prepared=null
                        }catch(e:Exception){vm.outcome(recorded.id,false,e.message.orEmpty());throw e}
                    }catch(e:CancellationException){throw e}
                    catch(e:Exception){error=e.message ?: "Could not open reminder"}
                    finally {working=false}
                }
            }
            TextButton({openShare(true)},enabled=!working){Text("OPEN WHATSAPP")}
            TextButton({openShare(false)},enabled=!working){Text("SHARE MESSAGE")}
        }},dismissButton={TextButton({prepared=null},enabled=!working){Text("CANCEL")}}
    )}
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),Arrangement.spacedBy(8.dp)) {
        Text("Udhar Reminders",style=MaterialTheme.typography.titleMedium)
        state.snapshot?.let {snapshot->
            Text("Current ledger balance: ${CalculationEngine.formatRupees(snapshot.balancePaisa)}")
            Text(when(CreditReminders.state(snapshot.balancePaisa,CreditReminders.parseDate(snapshot.dueDate),LocalDate.now())) {
                CreditReminders.DueState.NO_BALANCE->"No outstanding payment"
                CreditReminders.DueState.NO_DATE->"Due date has not been set"
                CreditReminders.DueState.FUTURE->"Due on ${snapshot.dueDate}"
                CreditReminders.DueState.TODAY->"Due today"
                CreditReminders.DueState.OVERDUE->"Overdue since ${snapshot.dueDate}"
            })
            OutlinedTextField(date,{date=it;edited=true},label={Text("Agreed due date (YYYY-MM-DD)")},
                supportingText={Text("Optional. Leave blank to remove the date.")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("reminder-due-date"),enabled=!working && !state.busy)
            TextButton({focus.clearFocus();vm.saveDate(customerId,date);edited=false},enabled=!working && !state.busy){Text("SAVE DUE DATE")}
            Button({if(!working){focus.clearFocus();working=true;error=null;scope.launch {
                try {prepared=vm.prepare(customerId)}catch(e:CancellationException){throw e}
                catch(e:Exception){error=e.message ?: "Could not prepare reminder"}
                finally {working=false}
            }}},enabled=!working && !state.busy && snapshot.balancePaisa>0){Text("PREPARE REMINDER")}
            if(snapshot.attempts.isNotEmpty()) {
                Text("Recent reminder attempts",style=MaterialTheme.typography.titleSmall)
                val format=remember {SimpleDateFormat("dd MMM yyyy HH:mm",Locale.getDefault())}
                snapshot.attempts.take(10).forEach {item->
                    Text("${format.format(Date(item.createdAt))} · ${CalculationEngine.formatRupees(item.balancePaisa)} · ${item.state.replace('_',' ')}",style=MaterialTheme.typography.bodySmall)
                }
            }
            Text("Saved on this phone and included in backups. Sharing never changes the credit ledger.",style=MaterialTheme.typography.bodySmall)
        }
        (error ?: state.error)?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }}
}

package com.hardwareshop.pos.presentation.common

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.hardwareshop.pos.data.repository.BillingRepository
import com.hardwareshop.pos.data.repository.SettingsRepository
import com.hardwareshop.pos.util.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import javax.inject.Inject

@HiltViewModel class ReceiptShareViewModel @Inject constructor(private val billing:BillingRepository,
    private val settings:SettingsRepository,private val session:SessionHolder):ViewModel() {
    suspend fun document(context:android.content.Context,id:Long)=withContext(Dispatchers.IO) {
        PermissionChecker.enforceLogin(session.current)
        val bill=requireNotNull(billing.getBillById(id)) {"Saved bill is unavailable"}
        SavedReceiptPdf.create(context,bill,settings.getShopInfo())
    }
}

/** Opens the system chooser only after a worker explicitly presses Share Receipt. */
@Composable fun ReceiptShareAction(billId:Long,modifier:Modifier=Modifier,viewModel:ReceiptShareViewModel=hiltViewModel()) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    androidx.compose.foundation.layout.Column(modifier) {
        OutlinedButton({
            if(!busy){busy=true;error=null;scope.launch {
                try {
                    val file=viewModel.document(context.applicationContext,billId)
                    ShopDocumentPdf.share(context,file,"Share receipt PDF")
                }catch(e:CancellationException){throw e}
                catch(e:Exception){error=e.message ?: "Could not share the saved receipt"}
                finally {busy=false}
            }}
        },enabled=!busy && billId>0){Text(if(busy)"PREPARING PDF…" else "SHARE RECEIPT")}
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
}

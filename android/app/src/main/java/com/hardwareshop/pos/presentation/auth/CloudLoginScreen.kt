package com.hardwareshop.pos.presentation.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hardwareshop.pos.presentation.common.*

@Composable
fun CloudLoginScreen(onOwner:()->Unit,onWorker:()->Unit,onOffline:()->Unit,
    viewModel:CloudLoginViewModel=hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var create by remember { mutableStateOf(false) }
    var workerPhone by remember { mutableStateOf(false) }
    var freshStart by remember { mutableStateOf(false) }
    val context = LocalContext.current
    if (freshStart) FreshStartDialog(onDismiss = { freshStart = false },
        onClearLocalData = { clearLocalShopData(context) })
    LaunchedEffect(state.ownerHome,state.workerLogin) {
        if(state.ownerHome) onOwner() else if(state.workerLogin) onWorker()
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(28.dp))
        Icon(Icons.Default.Store,null,tint=AppColors.Primary,modifier=Modifier.size(56.dp))
        Text("Hardware Shop POS",style=MaterialTheme.typography.headlineSmall)
        Text(if(create) "Create your shop account" else "Sign in to your shop",style=MaterialTheme.typography.titleLarge)
        Text("Your products, stock, customers and bills sync automatically. Keep this owner account private.",
            style=MaterialTheme.typography.bodyMedium,color=AppColors.TextSecondary)
        OutlinedTextField(email,{email=it},label={Text("Email")},singleLine=true,enabled=!state.loading,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth())
        OutlinedTextField(password,{password=it},label={Text("Password")},singleLine=true,enabled=!state.loading,
            visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
            modifier=Modifier.fillMaxWidth())
        if(!create) Row(verticalAlignment=Alignment.CenterVertically) {
            Checkbox(workerPhone,{workerPhone=it},enabled=!state.loading)
            Text("Set up a worker phone",modifier=Modifier.weight(1f))
        }
        Text("Stays signed in on this phone. Use Log out before handing it to someone else.",style=MaterialTheme.typography.bodySmall)
        state.error?.let { ErrorMessage(it) }
        if(state.status.isNotBlank()) Text(state.status,style=MaterialTheme.typography.bodyMedium)
        PrimaryActionButton(text=if(create) "CREATE ACCOUNT & SYNC" else "SIGN IN & SYNC",isLoading=state.loading,
            onClick={
                viewModel.connect(email,password,create,workerPhone && !create)
                password=""
            })
        TextButton(enabled=state.ready && !state.loading,onClick={create=!create}) {
            Text(if(create) "Already have an account? Sign in" else "Create a shop account")
        }
        TextButton(enabled=state.ready && !state.loading,onClick=onOffline) { Text("Use offline / local login") }
        TextButton(enabled=state.ready && !state.loading,onClick={freshStart=true}) { Text("Start fresh on this phone") }
        Text("First sign-in needs internet. After connection, saved data works offline and sync retries when internet returns.",
            style=MaterialTheme.typography.bodySmall,color=AppColors.TextSecondary)
        Spacer(Modifier.height(24.dp))
    }
}

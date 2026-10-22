package com.hardwareshop.pos.presentation.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.hardwareshop.pos.presentation.common.*
import androidx.compose.material.icons.automirrored.filled.Login
// ─────────────────────────────────────────────────────────────────────────────
// SPLASH SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SplashScreen(
    onOwnerNotSetup:  () -> Unit,
    onNavigateToLogin: () -> Unit,
    onCloud: () -> Unit,
    onOwner: () -> Unit,
    onWorker: () -> Unit,
    viewModel: SplashViewModel = hiltViewModel()
) {
    val destination by viewModel.destination.collectAsState()
    LaunchedEffect(destination) {
        when (destination) {
            SplashDestination.Setup -> onOwnerNotSetup()
            SplashDestination.Login -> onNavigateToLogin()
            SplashDestination.Cloud -> onCloud()
            SplashDestination.Owner -> onOwner()
            SplashDestination.Worker -> onWorker()
            null                    -> Unit
        }
    }
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector        = Icons.Default.Store,
                contentDescription = null,
                tint               = AppColors.Primary,
                modifier           = Modifier.size(80.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("Hardware Shop POS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = AppColors.Primary)
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = AppColors.Primary)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// OWNER SETUP SCREEN  (first-run only)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun OwnerSetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: OwnerSetupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val focus = LocalFocusManager.current

    LaunchedEffect(state.isComplete) {
        if (state.isComplete) onSetupComplete()
    }

    Scaffold(topBar = { AppTopBar("First-Time Setup") }) { padding ->
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Welcome! Let's set up your shop. You will use these details on receipts and GST bills.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary
            )

            SectionHeader("Shop Details")
            OutlinedTextField(
                value         = state.shopName,
                onValueChange = { viewModel.update { copy(shopName = it) } },
                label         = { Text("Shop Name *") },
                leadingIcon   = { Icon(Icons.Default.Store, null) },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
            )
            OutlinedTextField(
                value         = state.shopPhone,
                onValueChange = { viewModel.update { copy(shopPhone = it) } },
                label         = { Text("Phone Number") },
                leadingIcon   = { Icon(Icons.Default.Phone, null) },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
            )
            OutlinedTextField(
                value         = state.shopAddress,
                onValueChange = { viewModel.update { copy(shopAddress = it) } },
                label         = { Text("Address") },
                leadingIcon   = { Icon(Icons.Default.LocationOn, null) },
                modifier      = Modifier.fillMaxWidth(),
                maxLines      = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            OutlinedTextField(
                value         = state.shopGstNumber,
                onValueChange = { viewModel.update { copy(shopGstNumber = it.uppercase()) } },
                label         = { Text("GST Number (Optional)") },
                leadingIcon   = { Icon(Icons.Default.Badge, null) },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            SectionHeader("Owner Password")
            Text("Remember this! It protects reports, prices, and all sensitive data.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp))
            var showPass by remember { mutableStateOf(false) }
            OutlinedTextField(
                value             = state.password,
                onValueChange     = { viewModel.update { copy(password = it) } },
                label             = { Text("Password (min. 6 characters) *") },
                leadingIcon       = { Icon(Icons.Default.Lock, null) },
                trailingIcon      = { IconButton(onClick = { showPass = !showPass }) { Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } },
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine        = true,
                modifier          = Modifier.fillMaxWidth(),
                keyboardOptions   = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next)
            )
            OutlinedTextField(
                value             = state.confirmPass,
                onValueChange     = { viewModel.update { copy(confirmPass = it) } },
                label             = { Text("Confirm Password *") },
                leadingIcon       = { Icon(Icons.Default.LockReset, null) },
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine        = true,
                modifier          = Modifier.fillMaxWidth(),
                keyboardOptions   = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions   = KeyboardActions(onDone = { focus.clearFocus(); viewModel.setup() })
            )

            if (state.error != null) ErrorMessage(state.error!!)

            PrimaryActionButton(
                text      = "COMPLETE SETUP",
                onClick   = viewModel::setup,
                isLoading = state.isLoading,
                icon      = Icons.Default.Check
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LOGIN SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LoginScreen(
    onWorkerLogin: () -> Unit,
    onOwnerLogin:  () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val focus = LocalFocusManager.current
    var tab      by remember { mutableIntStateOf(0) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }

    LaunchedEffect(state.navigateToWorker) { if (state.navigateToWorker) onWorkerLogin() }
    LaunchedEffect(state.navigateToOwner)  { if (state.navigateToOwner)  onOwnerLogin() }

    Column(
        modifier              = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement   = Arrangement.Center,
        horizontalAlignment   = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Store, null, tint = AppColors.Primary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(8.dp))
        Text("Hardware Shop POS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = AppColors.Primary)
        if (state.shopName.isNotBlank()) {
            Text(state.shopName, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        }
        Spacer(Modifier.height(32.dp))

        // Role toggle
        TabRow(selectedTabIndex = tab, containerColor = AppColors.Surface) {
            Tab(selected = tab == 0, onClick = { tab = 0; username = ""; password = "" },
                text = { Text("Worker", fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal) })
            Tab(selected = tab == 1, onClick = { tab = 1; username = ""; password = "" },
                text = { Text("Owner", fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal) })
        }

        Spacer(Modifier.height(24.dp))

        AnimatedVisibility(visible = tab == 0) {
            OutlinedTextField(
                value             = username,
                onValueChange     = { username = it },
                label             = { Text("Username") },
                leadingIcon       = { Icon(Icons.Default.Person, null) },
                singleLine        = true,
                modifier          = Modifier.fillMaxWidth(),
                keyboardOptions   = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions   = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
            )
        }
        if (tab == 0) Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value             = password,
            onValueChange     = { password = it },
            label             = { Text(if (tab == 0) "Password" else "Owner Password") },
            leadingIcon       = { Icon(Icons.Default.Lock, null) },
            trailingIcon      = { IconButton(onClick = { showPass = !showPass }) { Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } },
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine        = true,
            modifier          = Modifier.fillMaxWidth(),
            keyboardOptions   = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions   = KeyboardActions(onDone = {
                focus.clearFocus()
                if (tab == 0) viewModel.loginWorker(username, password) else viewModel.loginOwner(password)
            })
        )

        if (state.error != null) {
            Spacer(Modifier.height(8.dp))
            ErrorMessage(state.error!!)
        }

        Spacer(Modifier.height(24.dp))

        PrimaryActionButton(
            text      = if (tab == 0) "LOGIN AS WORKER" else "LOGIN AS OWNER",
            onClick   = { if (tab == 0) viewModel.loginWorker(username, password) else viewModel.loginOwner(password) },
            isLoading = state.isLoading,
            icon      = Icons.AutoMirrored.Filled.Login
        )
    }
}

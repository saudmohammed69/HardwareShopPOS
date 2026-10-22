package com.hardwareshop.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.hardwareshop.pos.presentation.navigation.AppNavGraph
import com.hardwareshop.pos.presentation.common.AppColors
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var syncJournal: com.hardwareshop.pos.data.sync.SyncJournal

    override fun onResume() {
        super.onResume()
        // Keep both phones converging without waiting for the 15-minute periodic job.
        com.hardwareshop.pos.service.CloudSyncScheduler.requestNow(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.hardwareshop.pos.util.BillingLoadDiagnostics.event("activity-create-start")
        // Android 12+ splash screen API (icon displayed during cold start)
        installSplashScreen()
        val session = dagger.hilt.android.EntryPointAccessors.fromApplication(applicationContext, com.hardwareshop.pos.di.SessionEntryPoint::class.java).sessionHolder()
        // A new process has no authenticated session. Never restore a protected back stack.
        super.onCreate(if (session.isLoggedIn) savedInstanceState else null)
        com.hardwareshop.pos.util.BillingLoadDiagnostics.event("activity-super-ready")
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(30_000)
                    if (syncJournal.linkedStore() != null)
                        com.hardwareshop.pos.service.CloudSyncScheduler.requestNow(applicationContext)
                }
            }
        }
        enableEdgeToEdge()

        setContent {
            androidx.compose.runtime.SideEffect { com.hardwareshop.pos.util.BillingLoadDiagnostics.event("activity-composed") }
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary          = AppColors.Primary,
                    secondary        = AppColors.Secondary,
                    background       = AppColors.Surface,
                    surface          = AppColors.SurfaceCard,
                    error            = AppColors.Error,
                    onPrimary        = Color.White,
                    onSecondary      = Color.White,
                    onBackground     = AppColors.TextPrimary,
                    onSurface        = AppColors.TextPrimary
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavGraph()
                }
            }
        }
    }
}

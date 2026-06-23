package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.AppDatabase
import com.example.data.repository.ScrollSentryRepository
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.MainDashboard
import com.example.ui.screens.SetupScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.ScrollSentryViewModel
import com.example.util.NotificationHelper
import com.example.util.ProtectionMonitor
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ScrollSentryViewModel
    private var pendingNavigateTo: String? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Result handled by checkNotificationPermission in next composition or manually
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        NotificationHelper.createNotificationChannel(this)

        handleIntent(intent)

        val database = AppDatabase.getDatabase(applicationContext)
        val repository = ScrollSentryRepository(database.dao())

        setContent {
            MyApplicationTheme {
                viewModel = viewModel(
                    factory = ScrollSentryViewModel.provideFactory(application, repository)
                )

                val currentUser by viewModel.currentUser.collectAsState()
                val friends by viewModel.friends.collectAsState()
                var isSetupComplete by remember { mutableStateOf(false) }
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()

                LaunchedEffect(currentUser) {
                    if (currentUser != null) {
                        checkNotificationPermission { message, action ->
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = message,
                                    actionLabel = action,
                                    duration = SnackbarDuration.Long
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    openAppSettings()
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(pendingNavigateTo) {
                    pendingNavigateTo?.let { target ->
                        if (target == "inbox") {
                            viewModel.setActiveTab("inbox")
                        }
                        pendingNavigateTo = null
                    }
                }

                // Navigation logic
                val initialSetupNeeded = (currentUser == null || friends.size < 2) && !isSetupComplete

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    when {
                        currentUser == null -> {
                            AuthScreen(
                                viewModel = viewModel,
                                onAuthComplete = {
                                    // User just logged in
                                }
                            )
                        }
                        initialSetupNeeded -> {
                            SetupScreen(
                                viewModel = viewModel,
                                onSetupComplete = { 
                                    isSetupComplete = true
                                }
                            )
                        }
                        else -> {
                            MainDashboard(
                                viewModel = viewModel,
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) {
            lifecycleScope.launch {
                ProtectionMonitor.checkProtectionState(applicationContext)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("navigate_to")?.let { target ->
            pendingNavigateTo = target
            if (::viewModel.isInitialized && target == "inbox") {
                viewModel.setActiveTab("inbox")
            }
        }
    }

    private fun checkNotificationPermission(onDenied: (String, String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Already granted
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    onDenied("Notifications are needed for friend requests.", "Enable")
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }
}

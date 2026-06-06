package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.*
import com.example.data.local.AppDatabase
import com.example.data.repository.ScrollSentryRepository
import com.example.ui.screens.MainDashboard
import com.example.ui.screens.SetupScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.ScrollSentryViewModel

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ScrollSentryViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getDatabase(applicationContext)
        val repository = ScrollSentryRepository(database.dao())

        setContent {
            MyApplicationTheme {
                viewModel = viewModel(
                    factory = ScrollSentryViewModel.provideFactory(repository)
                )

                val currentUser by viewModel.currentUser.collectAsState()
                val friends by viewModel.friends.collectAsState()
                var isSetupComplete by remember { mutableStateOf(false) }

                // Initial setup is needed if profile is missing OR friends are < 2
                val initialSetupNeeded = (currentUser == null || friends.size < 2) && !isSetupComplete

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (initialSetupNeeded) {
                        SetupScreen(
                            viewModel = viewModel,
                            onSetupComplete = { isSetupComplete = true }
                        )
                    } else {
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

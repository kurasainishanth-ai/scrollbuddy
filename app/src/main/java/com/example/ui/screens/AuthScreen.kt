package com.example.ui.screens

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.viewmodel.ScrollSentryViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.example.R

@Composable
fun AuthScreen(
    viewModel: ScrollSentryViewModel,
    onAuthComplete: () -> Unit
) {
    val context = LocalContext.current
    var isAuthenticating by remember { mutableStateOf(false) }
    var needsUsername by remember { mutableStateOf(false) }
    var idToken by remember { mutableStateOf<String?>(null) }
    var usernameInput by remember { mutableStateOf("") }

    // Debugging UI states
    var showDebugDialog by remember { mutableStateOf(false) }
    var debugTitle by remember { mutableStateOf("") }
    var debugMessage by remember { mutableStateOf("") }

    // Configure Google Sign In
    val webClientId = context.getString(R.string.default_web_client_id)
    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
    }
    val googleSignInClient = remember { GoogleSignIn.getClient(context as Activity, gso) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val token = account.idToken
                val email = account.email ?: "Unknown Email"

                // Prepare initial debug info
                debugTitle = "Auth Debug: Step 1"
                debugMessage = "Email: $email\nID Token Null: ${token == null}"
                showDebugDialog = true

                if (token != null) {
                    idToken = token
                    isAuthenticating = true
                    viewModel.signInWithGoogle(token) { success, error ->
                        isAuthenticating = false
                        if (success) {
                            debugMessage += "\nFirebase Auth: Success\nBackend Auth: Success"
                            onAuthComplete()
                        } else if (error == "NEEDS_USERNAME") {
                            debugMessage += "\nFirebase Auth: Success\nBackend: NEEDS_USERNAME"
                            needsUsername = true
                        } else {
                            debugTitle = "Auth Failure: Backend"
                            debugMessage += "\nFirebase/Backend Error: $error"
                            showDebugDialog = true // Ensure dialog shows on failure
                            
                            // Show requested Toast with details
                            val baseUrl = viewModel.getApiBaseUrl()
                            val fullUrl = "$baseUrl/api/auth/google"
                            Toast.makeText(context, "HTTP Error: $error\nEndpoint: $fullUrl", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    debugTitle = "Auth Failure: Token Missing"
                    debugMessage = "Email: $email\nGoogle ID token is NULL. Most likely Web Client ID mismatch in strings.xml or Firebase Console."
                    Toast.makeText(context, "Authentication failed: ID Token is NULL", Toast.LENGTH_LONG).show()
                }
            } catch (e: ApiException) {
                debugTitle = "Auth Failure: Google API"
                debugMessage = when (e.statusCode) {
                    7 -> "Network error. Please check your connection."
                    10 -> "SHA-1 fingerprint mismatch in Firebase. Code 10 (DEVELOPER_ERROR)."
                    12500 -> "Sign-in failed. Internal error (12500)."
                    12501 -> "Sign-in cancelled by user."
                    else -> "Google API Error ${e.statusCode}: ${e.message}"
                }
                Toast.makeText(context, debugMessage, Toast.LENGTH_LONG).show()
                showDebugDialog = true
            } catch (e: Exception) {
                debugTitle = "Auth Failure: Unexpected"
                debugMessage = "Error: ${e.localizedMessage ?: "Unknown error"}"
                showDebugDialog = true
            }
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            Toast.makeText(context, "Sign-in cancelled.", Toast.LENGTH_SHORT).show()
        } else {
            debugTitle = "Auth Failure: Activity Result"
            debugMessage = "Result Code: ${result.resultCode}. Ensure Google Play Services is updated."
            showDebugDialog = true
        }
    }

    if (showDebugDialog) {
        AlertDialog(
            onDismissRequest = { showDebugDialog = false },
            title = { Text(debugTitle, fontWeight = FontWeight.Bold) },
            text = { Text(debugMessage) },
            confirmButton = {
                Button(onClick = { showDebugDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!needsUsername) {
            Text(
                text = "ScrollBuddy",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Mindful scrolling starts here.",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.outline
            )
            
            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = {
                    launcher.launch(googleSignInClient.signInIntent)
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isAuthenticating,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                if (isAuthenticating) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Normally you'd use a Google icon here
                        Text("Sign in with Google", fontWeight = FontWeight.Medium)
                    }
                }
            }
        } else {
            // Step 2: Choose Username
            Text(
                text = "Almost there!",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Choose a unique username for your account.",
                modifier = Modifier.padding(top = 8.dp)
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            OutlinedTextField(
                value = usernameInput,
                onValueChange = { usernameInput = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = {
                    val cleaned = usernameInput.trim().lowercase()
                    val usernameRegex = Regex("^[a-z0-9_-]{3,20}$")
                    if (!usernameRegex.matches(cleaned)) {
                        Toast.makeText(context, "Invalid username format.", Toast.LENGTH_LONG).show()
                        return@Button
                    }

                    isAuthenticating = true
                    viewModel.completeRegistration(idToken!!, cleaned) { success, error ->
                        isAuthenticating = false
                        if (success) {
                            onAuthComplete()
                        } else {
                            Toast.makeText(context, error ?: "Registration failed", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = usernameInput.isNotBlank() && !isAuthenticating,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isAuthenticating) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Text("Complete Profile")
                }
            }
        }
    }
}

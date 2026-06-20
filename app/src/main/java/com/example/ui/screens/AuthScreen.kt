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

                if (token != null) {
                    idToken = token
                    isAuthenticating = true
                    viewModel.signInWithGoogle(token) { success, error ->
                        isAuthenticating = false
                        if (success) {
                            onAuthComplete()
                        } else if (error == "NEEDS_USERNAME") {
                            needsUsername = true
                        } else {
                            Toast.makeText(context, "Authentication failed: $error", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Toast.makeText(context, "Authentication failed: ID Token is missing", Toast.LENGTH_LONG).show()
                }
            } catch (e: ApiException) {
                val errorMsg = when (e.statusCode) {
                    7 -> "Network error. Please check your connection."
                    10 -> "Configuration error. Please try again later."
                    12500 -> "Sign-in failed. Internal error."
                    12501 -> "Sign-in cancelled by user."
                    else -> "Google API Error ${e.statusCode}: ${e.message}"
                }
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_LONG).show()
            }
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            Toast.makeText(context, "Sign-in cancelled.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Sign-in failed. Please ensure Google Play Services is updated.", Toast.LENGTH_LONG).show()
        }
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

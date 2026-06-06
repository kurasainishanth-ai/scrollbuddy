package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.Friend
import com.example.ui.viewmodel.ScrollSentryViewModel
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(
    viewModel: ScrollSentryViewModel,
    onSetupComplete: () -> Unit
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val friends by viewModel.friends.collectAsState()
    val context = LocalContext.current
    
    var usernameInput by remember { mutableStateOf("") }
    var isRegistering by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        
        Text(
            text = "Welcome to ScrollBuddy",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        
        if (currentUser == null) {
            // Step 1: Create Username
            Text(
                text = "Choose a unique username to get started.",
                modifier = Modifier.padding(top = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                        Toast.makeText(context, "Invalid username. Use 3-20 characters: letters, numbers, underscores, or dashes only.", Toast.LENGTH_LONG).show()
                        return@Button
                    }

                    isRegistering = true
                    viewModel.registerUsername(cleaned) { success, error ->
                        isRegistering = false
                        if (!success) {
                            Toast.makeText(context, error ?: "Registration failed.", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = usernameInput.isNotBlank() && !isRegistering,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isRegistering) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                } else {
                    Text("Register Profile")
                }
            }
        } else {
            // Step 2: Add Friends
            Text(
                text = "Hello, ${currentUser?.username}! Now add at least 2 trusted friends by their username.",
                modifier = Modifier.padding(top = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(32.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (friends.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Person, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                        Text("No friends added yet", color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(friends) { friend ->
                            FriendItem(friend)
                        }
                    }
                }
            }

            var showAddDialog by remember { mutableStateOf(false) }

            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Search & Add Friend")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onSetupComplete,
                modifier = Modifier.fillMaxWidth(),
                enabled = friends.size >= 2,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Continue to Dashboard")
            }
            
            if (showAddDialog) {
                SearchFriendDialog(
                    onDismiss = { showAddDialog = false },
                    onConfirm = { viewModel.addFriend(it) },
                    searchFn = { viewModel.searchUser(it) }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun SearchFriendDialog(
    onDismiss: () -> Unit,
    onConfirm: (Friend) -> Unit,
    searchFn: suspend (String) -> Friend?
) {
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Friend?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Friend by Username") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { 
                        query = it
                        hasSearched = false
                    },
                    label = { Text("Friend's Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            scope.launch {
                                isSearching = true
                                result = searchFn(query)
                                isSearching = false
                                hasSearched = true
                            }
                        }) {
                            Icon(Icons.Default.Search, null)
                        }
                    }
                )
                
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp).align(Alignment.CenterHorizontally))
                } else if (hasSearched) {
                    if (result != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("👤", fontSize = 24.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(result!!.username, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Text("User not found.", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    result?.let { onConfirm(it) }
                    onDismiss()
                },
                enabled = result != null
            ) {
                Text("Add Friend")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun FriendItem(friend: Friend) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(friend.avatarEmoji, fontSize = 24.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = friend.displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

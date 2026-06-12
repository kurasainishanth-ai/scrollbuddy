package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.BorderStroke
import java.util.Locale
import android.content.ComponentName
import android.provider.Settings
import android.text.TextUtils
import android.content.Context
import android.content.Intent
import android.widget.Toast
import coil.compose.AsyncImage
import com.example.data.local.Friend
import com.example.data.local.ScrollRequest
import com.example.data.local.UserAccount
import com.example.ui.viewmodel.MockPost
import com.example.ui.viewmodel.ScrollSentryViewModel

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainDashboard(
    viewModel: ScrollSentryViewModel,
    modifier: Modifier = Modifier
) {
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val dailyUsage by viewModel.dailyUsage.collectAsStateWithLifecycle()
    val isSimulatorActive by viewModel.isSimulatorActive.collectAsStateWithLifecycle()
    val mockPosts by viewModel.mockPosts.collectAsStateWithLifecycle()
    val inbox by viewModel.inbox.collectAsStateWithLifecycle()
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()

    var showAddFriendDialog by remember { mutableStateOf(false) }

    val limitSeconds = dailyUsage?.limitSeconds ?: 30
    val consumedSeconds = dailyUsage?.consumedSeconds ?: 0
    val secondsRemaining = (limitSeconds - consumedSeconds).coerceAtLeast(0)
    val progressPercent = if (limitSeconds > 0) {
        (secondsRemaining.toFloat() / limitSeconds.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val isLimitReached = secondsRemaining <= 0

    val sentRequestActive = requests
        .sortedByDescending { it.timestamp }
        .firstOrNull {
            (it.status == "PENDING" || it.status == "REJECTED" || it.status == "ERROR") &&
                    it.serverRequestId != null || it.status == "ERROR"
        }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (!isSimulatorActive && !isLimitReached) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    NavigationBarItem(
                        selected = activeTab == "dashboard",
                        onClick = { viewModel.setActiveTab("dashboard") },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Home") }
                    )
                    NavigationBarItem(
                        selected = activeTab == "inbox",
                        onClick = { viewModel.setActiveTab("inbox") },
                        icon = { 
                            BadgedBox(badge = { if (inbox.isNotEmpty()) Badge { Text(inbox.size.toString()) } }) {
                                Icon(Icons.Default.Notifications, contentDescription = null)
                            }
                        },
                        label = { Text("Inbox") }
                    )
                    NavigationBarItem(
                        selected = activeTab == "friends",
                        onClick = { viewModel.setActiveTab("friends") },
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                        label = { Text("Friends") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    )
                )
        ) {
            AnimatedContent(
                targetState = if (isSimulatorActive) "simulator" else activeTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                }
            ) { targetScreen ->
                when (targetScreen) {
                    "simulator" -> {
                        SimulatorFeedScreen(
                            posts = mockPosts,
                            secondsRemaining = secondsRemaining,
                            onLikeClick = { viewModel.toggleLikeMockPost(it) },
                            onBackClick = { viewModel.stopSimulator() }
                        )
                    }
                    "inbox" -> {
                        InboxScreen(viewModel = viewModel)
                    }
                    "friends" -> {
                        FriendsHubScreen(
                            friends = friends,
                            requests = requests,
                            onAddFriendClick = { showAddFriendDialog = true }
                        )
                    }
                    else -> {
                        val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
                        DashboardMainView(
                            currentUser = currentUser,
                            secondsRemaining = secondsRemaining,
                            limitSeconds = limitSeconds,
                            progressPercent = progressPercent,
                            extensionsCount = dailyUsage?.extensionsCount ?: 0,
                            onStartSimulator = { viewModel.startSimulator() },
                            onResetUsage = { viewModel.resetDailyUsage() },
                            onUpdateLimit = { viewModel.updateDailyLimitSetting(it) },
                            onLogout = { viewModel.logout() }
                        )
                    }
                }
            }

            if (isLimitReached) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.92f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    LimitHitOverlayContent(
                        friends = friends,
                        activePendingRequest = sentRequestActive,
                        onSendRequest = { viewModel.sendScrollExtensionRequest(it) },
                        onResetDefault = { viewModel.resetDailyUsage() },
                        onDismissRequest = { viewModel.dismissRequest(it) },
                    )
                }
            }
        }
    }

    if (showAddFriendDialog) {
        SearchFriendDialog(
            onDismiss = { showAddFriendDialog = false },
            onConfirm = { viewModel.addFriend(it) },
            searchFn = { viewModel.searchUsers(it) }
        )
    }
}

@Composable
fun DashboardMainView(
    currentUser: UserAccount?,
    secondsRemaining: Int,
    limitSeconds: Int,
    progressPercent: Float,
    extensionsCount: Int,
    onStartSimulator: () -> Unit,
    onResetUsage: () -> Unit,
    onUpdateLimit: (Int) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var isAccessibilityActive by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }

    LaunchedEffect(Unit) {
        isAccessibilityActive = isAccessibilityServiceEnabled(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("ScrollBuddy", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            
            IconButton(onClick = onLogout) {
                Icon(Icons.Default.ExitToApp, "Logout")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = currentUser?.photoUrl,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(CircleShape),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(currentUser?.displayName ?: "User", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("@${currentUser?.username}", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                        Toast.makeText(context, "Enable ScrollBuddy service to block Instagram.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {}
                },
            colors = CardDefaults.cardColors(
                containerColor = if (isAccessibilityActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) 
                                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
            )
        ) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    imageVector = if (isAccessibilityActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isAccessibilityActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isAccessibilityActive) "Blocker: ACTIVE" else "Blocker: INACTIVE 🛑",
                        fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                    Text(
                        text = if (isAccessibilityActive) "Monitoring Instagram usage." else "Tap to enable blocking in settings.",
                        fontSize = 11.sp
                    )
                }
            }
        }

        Box(modifier = Modifier.size(220.dp).padding(16.dp), contentAlignment = Alignment.Center) {
            val progressAnim by animateFloatAsState(targetValue = progressPercent, animationSpec = tween(800))
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(Color.LightGray.copy(alpha = 0.25f), 135f, 270f, false, style = Stroke(14.dp.toPx(), cap = StrokeCap.Round))
                val ringColor = when {
                    progressPercent > 0.5f -> Color(0xFF43A047)
                    progressPercent > 0.2f -> Color(0xFFF5AF19)
                    else -> Color(0xFFE53935)
                }
                drawArc(ringColor, 135f, progressAnim * 270f, false, style = Stroke(14.dp.toPx(), cap = StrokeCap.Round))
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatTime(secondsRemaining), fontSize = 32.sp, fontWeight = FontWeight.Black)
                Text("Remaining Today", fontSize = 12.sp, color = Color.Gray)
                if (extensionsCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Text("+$extensionsCount Exts", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
        }

        Button(
            onClick = onStartSimulator,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Launch Sandbox Feed", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.weight(1f))

        Slider(value = limitSeconds.toFloat(), onValueChange = { onUpdateLimit(it.toInt()) }, valueRange = 10f..300f, steps = 29)
        TextButton(onClick = onResetUsage) {
            Icon(Icons.Default.Refresh, null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Reset Stats")
        }
    }
}

@Composable
fun SimulatorFeedScreen(posts: List<MockPost>, secondsRemaining: Int, onLikeClick: (Int) -> Unit, onBackClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onBackClick) { Icon(Icons.Default.ArrowBack, null) }
                Text("Sandbox Feed", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Surface(color = if (secondsRemaining < 10) Color(0xFFE53935) else MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Text(formatTime(secondsRemaining), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp), color = if (secondsRemaining < 10) Color.White else Color.Unspecified)
                }
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(posts) { post -> MockPostCard(post = post, onLikeClick = onLikeClick) }
        }
    }
}

@Composable
fun MockPostCard(post: MockPost, onLikeClick: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(32.dp).background(Color.LightGray.copy(alpha = 0.3f), CircleShape), contentAlignment = Alignment.Center) { Text(post.avatarEmoji) }
                Spacer(modifier = Modifier.width(10.dp))
                Text(post.username, fontWeight = FontWeight.Bold)
            }
            Box(modifier = Modifier.fillMaxWidth().height(200.dp).background(Brush.linearGradient(listOf(Color(post.imageGradientStart), Color(post.imageGradientEnd)))))
            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onLikeClick(post.id) }) { Icon(if (post.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (post.isLiked) Color.Red else Color.Unspecified) }
                Text("${post.likesCount} likes", fontWeight = FontWeight.Bold)
            }
            Text(post.caption, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun FriendsHubScreen(friends: List<Friend>, requests: List<ScrollRequest>, onAddFriendClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Trusted Friends", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Friends who can authorize extensions", fontSize = 12.sp, color = Color.Gray)
            }
            IconButton(onClick = onAddFriendClick, colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Icon(Icons.Default.Add, null) }
        }
        if (friends.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { Text("No friends added yet.", color = Color.Gray) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(friends) { friend -> FriendItem(friend) }
            }
        }
    }
}

@Composable
fun LimitHitOverlayContent(friends: List<Friend>, activePendingRequest: ScrollRequest?, onSendRequest: (Friend) -> Unit, onResetDefault: () -> Unit, onDismissRequest: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Default.Lock, null, tint = Color(0xFFEF5350), modifier = Modifier.size(48.dp))
        Text("Instagram Blocked 🛑", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.White)
        Text("Social wellness limit reached. Your friends must authorize an extension.", color = Color.LightGray, textAlign = TextAlign.Center)

        if (activePendingRequest != null) {
            Box(modifier = Modifier.fillMaxWidth().background(Color.DarkGray.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Color(0xFFF5AF19), modifier = Modifier.size(32.dp))
                    Text("Request Pending...", fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Waiting for ${activePendingRequest.friendName} to approve in-app.", color = Color.LightGray, textAlign = TextAlign.Center, fontSize = 12.sp)
                    TextButton(onClick = { onDismissRequest(activePendingRequest.uuid) }) { Text("Cancel", color = Color.White.copy(alpha = 0.6f)) }
                }
            }
        } else {
            Text("Request Emergency Extension (+5 mins):", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(friends) { friend ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onSendRequest(friend) }, colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.8f)), shape = RoundedCornerShape(12.dp)) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(friend.avatarEmoji, fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(friend.displayName, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.Send, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        TextButton(onClick = onResetDefault, colors = ButtonDefaults.textButtonColors(contentColor = Color.LightGray)) { Text("Developer Reset (Bypass)", fontSize = 12.sp) }
    }
}

fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponentName = ComponentName(context, com.example.service.ScrollSentryAccessibilityService::class.java)
    val enabledServicesSetting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledService = ComponentName.unflattenFromString(componentNameString)
        if (enabledService != null && enabledService == expectedComponentName) return true
    }
    return false
}

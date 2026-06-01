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
import com.example.data.local.Friend
import com.example.data.local.ScrollRequest
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

    var activeTab by remember { mutableStateOf("dashboard") } // "dashboard", "friends"

    // Dialog state for adding a custom friend
    var showAddFriendDialog by remember { mutableStateOf(false) }

    val limitSeconds = dailyUsage?.limitSeconds ?: 30
    val consumedSeconds = dailyUsage?.consumedSeconds ?: 0
    val secondsRemaining = (limitSeconds - consumedSeconds).coerceAtLeast(0)
    val progressPercent = if (limitSeconds > 0) {
        (secondsRemaining.toFloat() / limitSeconds.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val isLimitReached = secondsRemaining <= 0
    android.util.Log.e(
        "ScrollSentry",
        "limit=$limitSeconds consumed=$consumedSeconds remaining=$secondsRemaining isLimitReached=$isLimitReached"
    )

    val sentRequestActive = requests
        .sortedByDescending { it.timestamp }
        .firstOrNull {
            (it.status == "PENDING" || it.status == "REJECTED" || it.status == "ERROR") &&
                    it.serverRequestId != null || it.status == "ERROR"
        }
    android.util.Log.e(
        "ScrollSentry",
        "requests=${requests.map { "${it.status}:${it.serverRequestId}" }}"
    )

    android.util.Log.e(
        "ScrollSentry",
        "sentRequestActive=${sentRequestActive?.status}"
    )

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (!isSimulatorActive && !isLimitReached) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.testTag("app_bottom_bar")
                ) {
                    NavigationBarItem(
                        selected = activeTab == "dashboard",
                        onClick = { activeTab = "dashboard" },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                        label = { Text("Dashboard") },
                        modifier = Modifier.testTag("tab_dashboard")
                    )
                    NavigationBarItem(
                        selected = activeTab == "friends",
                        onClick = { activeTab = "friends" },
                        icon = { Icon(Icons.Default.Person, contentDescription = "Friends Hub") },
                        label = { Text("Friends Hub") },
                        modifier = Modifier.testTag("tab_friends")
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
                    "friends" -> {
                        FriendsHubScreen(
                            friends = friends,
                            requests = requests,
                            onAddFriendClick = { showAddFriendDialog = true },
                            onApproveManual = { viewModel.approveExtensionRequest(it) }
                        )
                    }
                    else -> {
                        DashboardMainView(
                            secondsRemaining = secondsRemaining,
                            limitSeconds = limitSeconds,
                            progressPercent = progressPercent,
                            extensionsCount = dailyUsage?.extensionsCount ?: 0,
                            onStartSimulator = { viewModel.startSimulator() },
                            onResetUsage = { viewModel.resetDailyUsage() },
                            onUpdateLimit = { viewModel.updateDailyLimitSetting(it) }
                        )
                    }
                }
            }

            // Beautiful Alert Overlay if limit is hit (Locks the App screen completely)
            if (isLimitReached) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.92f))
                        .clickable(enabled = false) {}, // Absorb all click interactions
                    contentAlignment = Alignment.Center
                ) {
                    LimitHitOverlayContent(
                        friends = friends,
                        activePendingRequest = sentRequestActive,
                        onSendRequest = { viewModel.sendScrollExtensionRequest(it) },
                        onResetDefault = { viewModel.resetDailyUsage() },
                        onDismissRequest = { 
                            // Ensure dismissal works for both UUIDs and IDs if needed
                            viewModel.dismissRequest(it) 
                        },
                    )
                }
            }
        }
    }

    // Add Friend Dialog Popup
    if (showAddFriendDialog) {
        AddFriendDialog(
            onDismiss = { showAddFriendDialog = false },
            onConfirm = { name, emoji, autoAccept, delayVal ->
                viewModel.addCustomFriend(name, emoji, autoAccept, delayVal)
                showAddFriendDialog = false
            }
        )
    }
}

@Composable
fun DashboardMainView(
    secondsRemaining: Int,
    limitSeconds: Int,
    progressPercent: Float,
    extensionsCount: Int,
    onStartSimulator: () -> Unit,
    onResetUsage: () -> Unit,
    onUpdateLimit: (Int) -> Unit
) {
    val context = LocalContext.current
    var isAccessibilityActive by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }

    // Recheck check each time view loads or is refreshed
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
        // App Header Name
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "ScrollSentry Launcher Icon",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "ScrollSentry",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Live Real-World Instagram Blocker Status Banner Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                        Toast.makeText(context, "Locate 'ScrollSentry' in downloaded services list to enable blocker.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not open settings automatically. Please go to Settings > Accessibility.", Toast.LENGTH_SHORT).show()
                    }
                },
            colors = CardDefaults.cardColors(
                containerColor = if (isAccessibilityActive) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                }
            ),
            border = BorderStroke(
                width = 1.dp,
                color = if (isAccessibilityActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (isAccessibilityActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = "Status symbol",
                    tint = if (isAccessibilityActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isAccessibilityActive) "Live Instagram Blocker: ACTIVE" else "Live Instagram Blocker: INACTIVE 🛑",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (isAccessibilityActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = if (isAccessibilityActive) "Monitoring real-world Instagram scrolls on this phone." else "Tap to enable live blocking in Android settings.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "Configure option",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        // Circular Timer Gauge Widget
        Box(
            modifier = Modifier
                .size(220.dp)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val progressAnim by animateFloatAsState(
                targetValue = progressPercent,
                animationSpec = tween(durationMillis = 800)
            )

            // Outer clean ring background
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    color = Color.LightGray.copy(alpha = 0.25f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // High priority custom color indicator based on time remaining range
            val ringColor = when {
                progressPercent > 0.5f -> MaterialTheme.colorScheme.primary
                progressPercent > 0.2f -> Color(0xFFF5AF19) // Caution Amber
                else -> Color(0xFFE53935) // Emergency Warning Red
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    color = ringColor,
                    startAngle = 135f,
                    sweepAngle = progressAnim * 270f,
                    useCenter = false,
                    style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Stats content label inside circle
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatTime(secondsRemaining),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Remaining Today",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    fontWeight = FontWeight.Medium
                )
                if (extensionsCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "+$extensionsCount Exts",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // Action Trigger Button: "Open Instagram Simulator"
        Button(
            onClick = onStartSimulator,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .testTag("start_simulator_button")
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Launch Screen")
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Launch Instagram Sandbox Feed",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Explanatory Hint Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Mindful Limitation Details",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Mindful Limitation Demo",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Spend scroll time inside this interactive Sandbox feed. When your limit counts down to 0, friends must grant you a 5-minute unlock code!",
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Live slider parameter for customizing today's mock quota
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Daily Scroll Quota Setting:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${limitSeconds}s",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Slider(
                value = limitSeconds.toFloat(),
                onValueChange = { onUpdateLimit(it.toInt()) },
                valueRange = 10f..300f,
                steps = 29,
                modifier = Modifier.testTag("quota_slider")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("10s (Fast Test)", fontSize = 11.sp, color = Color.Gray)
                Text("5m (Max)", fontSize = 11.sp, color = Color.Gray)
            }
        }

        // Emergency Reset Action Row
        TextButton(
            onClick = onResetUsage,
            modifier = Modifier.testTag("reset_usage_button")
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Reset Stats")
            Spacer(modifier = Modifier.width(4.dp))
            Text("Reset Simulator Stats for Today", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SimulatorFeedScreen(
    posts: List<MockPost>,
    secondsRemaining: Int,
    onLikeClick: (Int) -> Unit,
    onBackClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Mock Feed Header Bar resembling classic social bar
        Surface(
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Exit Simulator")
                }

                // Insta styled logo font placeholder
                Text(
                    text = "📸 Sandbox Feed",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )

                // Timer badge
                Surface(
                    color = if (secondsRemaining < 10) Color(0xFFE53935) else MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Active clock",
                            modifier = Modifier.size(16.dp),
                            tint = if (secondsRemaining < 10) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatTime(secondsRemaining),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (secondsRemaining < 10) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        // Live Feed Container
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .testTag("simulator_feed_list"),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(posts) { post ->
                MockPostCard(
                    post = post,
                    onLikeClick = onLikeClick
                )
            }
        }
    }
}

@Composable
fun MockPostCard(
    post: MockPost,
    onLikeClick: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // Header row with username and user profile icon
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color.LightGray.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(post.avatarEmoji, fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = post.username,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Post details options"
                )
            }

            // Colored post image representation with cool linear gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(post.imageGradientStart), Color(post.imageGradientEnd))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Post placeholder visual",
                    tint = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(64.dp)
                )
            }

            // Quick interaction button row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onLikeClick(post.id) }) {
                    Icon(
                        imageVector = if (post.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Like Button",
                        tint = if (post.isLiked) Color.Red else MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "${post.likesCount} likes",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            // Caption details
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = post.username,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = post.caption,
                        fontSize = 13.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun FriendsHubScreen(
    friends: List<Friend>,
    requests: List<ScrollRequest>,
    onAddFriendClick: () -> Unit,
    onApproveManual: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Screen Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Guardian Buddies",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Friends who can authorize screen time extension",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            IconButton(
                onClick = onAddFriendClick,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.minimumInteractiveComponentSize().testTag("add_friend_icon")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Friend")
            }
        }

        // Friends horizontal/row listing
        Text(
            text = "Active Buddies",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary
        )

        if (friends.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No friends configured yet.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(friends) { friend ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(friend.avatarEmoji, fontSize = 24.sp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(friend.name, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (friend.isAutoAccept) Icons.Default.Check else Icons.Default.Person,
                                        contentDescription = "Response status mode",
                                        modifier = Modifier.size(12.dp),
                                        tint = if (friend.isAutoAccept) Color(0xFF43A047) else Color.Gray
                                    )
                                    Text(
                                        text = if (friend.isAutoAccept) "Auto-reviews in ${friend.acceptDelaySec}s" else "Manual review needed",
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Extension Requests section
        Text(
            text = "Request History Logs",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary
        )

        if (requests.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "History empty",
                        modifier = Modifier.size(48.dp),
                        tint = Color.LightGray
                    )
                    Text("No extension requests sent today.", color = Color.Gray, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("requests_history_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(requests) { req ->
                    RequestLogCard(
                        request = req,
                        onApproveClick = { onApproveManual(req.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun RequestLogCard(
    request: ScrollRequest,
    onApproveClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (request.status) {
                "APPROVED" -> Color(0xFFE8F5E9)      // Success Soft Green
                "REJECTED" -> Color(0xFFFFEBEE)      // Rejected Soft Red
                else -> MaterialTheme.colorScheme.surface // Pending standard Surface
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = when (request.status) {
                "APPROVED" -> Color(0xFF81C784)
                "REJECTED" -> Color(0xFFE57373)
                else -> Color.LightGray.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(request.friendEmoji, fontSize = 24.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Request to ${request.friendName}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.Black
                )
                Text(
                    text = "Emergency unlock extension: +5 Minutes",
                    fontSize = 11.sp,
                    color = Color.DarkGray
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                when (request.status) {
                    "APPROVED" -> {
                        Surface(
                            color = Color(0xFF2E7D32),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(2.dp)
                        ) {
                            Text(
                                "APPROVED",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    "REJECTED" -> {
                        Surface(
                            color = Color(0xFFC62828),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(2.dp)
                        ) {
                            Text(
                                "REJECTED",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    else -> {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Pending spinner simulation loading badge
                            Surface(
                                color = Color(0xFFF57C00),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "PENDING",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            
                            // Let the user force approve to test easily
                            Button(
                                onClick = onApproveClick,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Simulate Accept", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LimitHitOverlayContent(
    friends: List<Friend>,
    activePendingRequest: ScrollRequest?,
    onSendRequest: (Friend) -> Unit,
    onResetDefault: () -> Unit,
    onDismissRequest: (String) -> Unit,
) {
    val context = LocalContext.current
    var showPinInput by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Red warning lock symbol
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Color(0xFFE53935).copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "App locked icon",
                tint = Color(0xFFEF5350),
                modifier = Modifier.size(36.dp)
            )
        }

        Text(
            text = "Instagram Blocked 🛑",
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Text(
            text = "You have exceeded your daily scrolling limit designated for social wellness. Access to Instagram feed is locked.",
            color = Color.LightGray,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))

        if (showPinInput) {
            // In-Person PIN entry screen
            Text(
                text = "Enter Companion Bypass PIN",
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Let your friend enter their 4-digit code (Use '1234' or '2580' for testing) to grant 5 minutes instantly.",
                color = Color.LightGray,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )

            OutlinedTextField(
                value = enteredPin,
                onValueChange = {
                    if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                        enteredPin = it
                        pinError = false
                        if (it == "1234" || it == "2580") {
                            onResetDefault() // reset and grant access!
                            Toast.makeText(context, "Bypass Activated! Guardian PIN Accepted. 🎉", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                placeholder = { Text("xxxx", color = Color.Gray) },
                singleLine = true,
                isError = pinError,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Gray
                ),
                modifier = Modifier.width(120.dp)
            )

            if (pinError) {
                Text("Invalid Guardian PIN! Try again.", color = Color.Red, fontSize = 11.sp)
            }

            TextButton(onClick = { showPinInput = false }) {
                Text("Back to Friend Requests", color = MaterialTheme.colorScheme.primary)
            }

        } else if (activePendingRequest != null) {
            if (activePendingRequest.status == "ERROR") {
                // Network failure state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFC62828).copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                        .border(BorderStroke(1.dp, Color(0xFFC62828).copy(alpha = 0.4f)), RoundedCornerShape(16.dp))
                        .padding(18.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Connection Error",
                            tint = Color(0xFFEF5350),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Connection Failed 🌐",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "Could not reach the Scroll Sentry server. Please check your internet connection or Wi-Fi settings.",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        
                        Button(
                            onClick = { onDismissRequest(activePendingRequest.uuid) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text("Try Again", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (activePendingRequest.status == "REJECTED") {
                // An active request has been REJECTED by the guardian
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFC62828).copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                        .border(BorderStroke(1.dp, Color(0xFFC62828).copy(alpha = 0.4f)), RoundedCornerShape(16.dp))
                        .padding(18.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFFC62828).copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Access Denied",
                                tint = Color(0xFFEF5350),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            text = "Access Denied 🔒",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "Your request was explicitly REJECTED by ${activePendingRequest.friendName} ${activePendingRequest.friendEmoji}.",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp
                        )
                        Text(
                            text = "Guardians designate screen limits for your wellness. Please step away from your device and take a mindful breather.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 15.sp
                        )
                        
                        Button(
                            onClick = { onDismissRequest(activePendingRequest.uuid) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text("Try Another Guardian Friend", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // An active request is sent and is pending approval
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.DarkGray.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFFF5AF19),
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp
                        )
                        Text(
                            text = "Extension Pending...",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Requested code from ${activePendingRequest.friendName} ${activePendingRequest.friendEmoji}. Listening for their online response live.",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )

                        // REAL SMS / WhatsApp Sharing Button!
                        Button(
                            onClick = {
                                val approvalUrl = activePendingRequest.approvalUrl
                                if (approvalUrl.isNullOrBlank()) {
                                    Toast.makeText(context, "Approval link not ready yet.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                try {
                                    val shareText = "Hey — I hit my Instagram limit on Scroll Sentry. Can you approve a few more minutes?\n\n$approvalUrl"
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                        type = "text/plain"
                                    }
                                    val shareIntent = Intent.createChooser(sendIntent, "Send approval link to friend")
                                    shareIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    context.startActivity(shareIntent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open share chooser.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share Approval Link to Friend", fontSize = 12.sp)
                        }

                        Text(
                            text = "Your friend opens the link in their browser and taps Approve or Reject.",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        } else {
            // Screen showing options of friends to send emergency request to
            Text(
                text = "Request Emergency Extension (+5 mins)",
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )

            if (friends.isEmpty()) {
                Text("Error: No guardian friends available.", color = Color.Red, fontSize = 12.sp)
            } else {
                Text(
                    text = "Tap on a friend to ping them immediately:",
                    fontSize = 12.sp,
                    color = Color.LightGray
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(friends) { friend ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSendRequest(friend) },
                            colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.8f)),
                            border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(friend.avatarEmoji, fontSize = 24.sp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(friend.name, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(
                                        text = if (friend.isAutoAccept) "Replies within ${friend.acceptDelaySec}s" else "Needs manual click",
                                        fontSize = 11.sp,
                                        color = Color.LightGray
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Send request",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Expand companion pin fallback override options
            OutlinedButton(
                onClick = { showPinInput = true },
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Lock, contentDescription = "PIN Unlock", tint = Color.LightGray, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Parent / Friend PIN Override", color = Color.LightGray, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Demo fallback reset button
        TextButton(
            onClick = onResetDefault,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.LightGray)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Bypass / Reset Limits (Developer Demo)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AddFriendDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, emoji: String, isAutoAccept: Boolean, delaySeconds: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("🧘‍♀️") }
    var isAutoAccept by remember { mutableStateOf(false) }
    var delayValue by remember { mutableFloatStateOf(4f) }

    val emojisList = listOf("🧘‍♀️", "🛡️", "✨", "🎒", "🦊", "🐼", "⭐", "🍕", "🦾", "👾")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Guardian Friend") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Friend's Name") },
                    placeholder = { Text("e.g. Chris") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("add_friend_name_input")
                )

                // Emoji picker row
                Text("Select Emoji avatar:", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    emojisList.take(6).forEach { e ->
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (emoji == e) MaterialTheme.colorScheme.primaryContainer else Color.LightGray.copy(alpha = 0.2f))
                                .clickable { emoji = e }
                                .border(
                                    width = if (emoji == e) 1.5.dp else 0.dp,
                                    color = if (emoji == e) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(e, fontSize = 18.sp)
                        }
                    }
                }

                // Auto accept configuration toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = isAutoAccept,
                        onCheckedChange = { isAutoAccept = it },
                        modifier = Modifier.testTag("add_friend_auto_accept_checkbox")
                    )
                    Text("Auto-accept requests (Simulation)", fontSize = 14.sp)
                }

                if (isAutoAccept) {
                    Column {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Simulated reply delay:", fontSize = 13.sp)
                            Text("${delayValue.toInt()} seconds", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Slider(
                            value = delayValue,
                            onValueChange = { delayValue = it },
                            valueRange = 2f..15f,
                            steps = 12
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.trim().isNotEmpty()) {
                        onConfirm(name.trim(), emoji, isAutoAccept, delayValue.toInt())
                    }
                }
            ) {
                Text("Add Buddy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// Utility clock format helper
fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}

// Live Accessibility checker specifically for our ScrollSentry Service class
fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponentName = ComponentName(context, com.example.service.ScrollSentryAccessibilityService::class.java)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledService = ComponentName.unflattenFromString(componentNameString)
        if (enabledService != null && enabledService == expectedComponentName) {
            return true
        }
    }
    return false
}

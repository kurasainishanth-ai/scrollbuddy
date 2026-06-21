package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.*
import com.example.data.network.ApprovalApi
import com.example.data.network.BackendRequest
import com.example.data.network.BackendUser
import com.example.data.repository.ScrollSentryRepository
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.example.util.NotificationHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScrollSentryViewModel(
    application: Application,
    private val repository: ScrollSentryRepository
) : AndroidViewModel(application) {

    private val api = ApprovalApi()
    private val context = application.applicationContext

    private val dateStr: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    val friends: StateFlow<List<Friend>> = repository.getFriendsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val requests: StateFlow<List<ScrollRequest>> = repository.getRequestsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyUsage: StateFlow<DailyUsage?> = repository.getLatestDailyUsageFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _currentUser = MutableStateFlow<UserAccount?>(null)
    val currentUser: StateFlow<UserAccount?> = _currentUser.asStateFlow()

    private val _inbox = MutableStateFlow<List<BackendRequest>>(emptyList())
    val inbox: StateFlow<List<BackendRequest>> = _inbox.asStateFlow()

    private val _activeTab = MutableStateFlow("dashboard")
    val activeTab: StateFlow<String> = _activeTab.asStateFlow()

    private val _isSimulatorActive = MutableStateFlow(false)
    val isSimulatorActive: StateFlow<Boolean> = _isSimulatorActive.asStateFlow()

    private var timerJob: Job? = null
    private var inboxPollJob: Job? = null

    private val _mockPosts = MutableStateFlow(getInitialMockPosts())
    val mockPosts: StateFlow<List<MockPost>> = _mockPosts.asStateFlow()

    private val activePollJobs = mutableMapOf<String, Job>()
    private val notifiedRequestIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            // Load current user
            val user = repository.getUserAccount()
            _currentUser.value = user
            if (user != null) {
                startInboxPolling(user.username)
            }

            val usage = repository.getDailyUsageDirect(dateStr)
            if (usage == null) {
                repository.saveDailyUsage(
                    DailyUsage(
                        date = dateStr,
                        limitSeconds = 30,
                        consumedSeconds = 0,
                        extensionsCount = 0
                    )
                )
            }

            repository.getRequestsFlow().firstOrNull()?.forEach { req ->
                if (req.status == "PENDING" && req.serverRequestId != null) {
                    startPollingServerStatus(req.serverRequestId)
                }
            }
        }
    }

    fun signInWithGoogle(idToken: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                Log.d("ScrollSentry", "Starting Firebase Auth with Google ID Token")
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = withContext(Dispatchers.IO) {
                    FirebaseAuth.getInstance().signInWithCredential(credential).await()
                }
                Log.d("ScrollSentry", "Firebase Auth successful: ${authResult.user?.email}")

                val backendUser = withContext(Dispatchers.IO) {
                    api.authenticateGoogle(idToken)
                }
                saveUserLocally(backendUser)
                onResult(true, null)
            } catch (e: ApprovalApi.UserNeedsUsernameException) {
                Log.d("ScrollSentry", "New user needs username")
                onResult(false, "NEEDS_USERNAME")
            } catch (e: Exception) {
                Log.e("ScrollSentry", "Google Sign-In failed", e)
                onResult(false, e.message ?: "Auth failed")
            }
        }
    }

    fun completeRegistration(idToken: String, username: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val cleaned = username.trim().lowercase()
                if (cleaned.isEmpty()) {
                    onResult(false, "Username cannot be empty")
                    return@launch
                }
                
                Log.d("ScrollSentry", "Completing registration for: $cleaned")
                // Ensure Firebase is still authenticated
                if (FirebaseAuth.getInstance().currentUser == null) {
                    val credential = GoogleAuthProvider.getCredential(idToken, null)
                    withContext(Dispatchers.IO) {
                        FirebaseAuth.getInstance().signInWithCredential(credential).await()
                    }
                }

                val backendUser = withContext(Dispatchers.IO) {
                    api.authenticateGoogle(idToken, cleaned)
                }
                saveUserLocally(backendUser)
                onResult(true, null)
            } catch (e: Exception) {
                Log.e("ScrollSentry", "Registration failed", e)
                onResult(false, e.message ?: "Registration failed")
            }
        }
    }

    private suspend fun saveUserLocally(user: BackendUser) {
        val account = UserAccount(
            username = user.username,
            googleUid = user.googleUid!!,
            email = user.email!!,
            displayName = user.displayName!!,
            photoUrl = user.photoUrl
        )
        repository.setUserAccount(account)
        _currentUser.value = account
        startInboxPolling(account.username)

        try {
            val token = com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
            api.registerFcmToken(account.username, token)
            Log.d("ScrollSentry", "FCM token registered for ${account.username}")
        } catch (e: Exception) {
            Log.e("ScrollSentry", "Failed to register FCM token", e)
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                // 1. Clear FirebaseAuth
                FirebaseAuth.getInstance().signOut()
                
                // 2. Clear Google Sign-In session
                val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
                    com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
                ).build()
                val googleSignInClient = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, gso)
                googleSignInClient.signOut().await()
            } catch (e: Exception) {
                Log.e("ScrollSentry", "Error during Google/Firebase sign out", e)
            }
            
            // 3. Clear Room Data
            withContext(Dispatchers.IO) {
                com.example.data.local.AppDatabase.getDatabase(context).clearAllTables()
            }
            
            // 4. Clear local state
            _currentUser.value = null
            inboxPollJob?.cancel()
            notifiedRequestIds.clear()
            setActiveTab("dashboard")
        }
    }

    fun registerUsername(username: String, onResult: (Boolean, String?) -> Unit) {
        // This is now replaced by completeRegistration but kept for potential simple usage
        onResult(false, "Method deprecated, use completeRegistration with Google idToken")
    }

    suspend fun searchUsers(query: String): List<Friend> {
        val current = _currentUser.value?.username ?: ""
        return withContext(Dispatchers.IO) {
            try {
                api.searchUsers(query, current).map {
                    Friend(username = it.username, displayName = it.username, avatarEmoji = "👤")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    fun addFriend(friend: Friend) {
        viewModelScope.launch {
            repository.insertFriend(friend)
        }
    }

    fun setActiveTab(tab: String) {
        _activeTab.value = tab
    }

    fun getApiBaseUrl(): String {
        return com.example.BuildConfig.SERVER_URL.trimEnd('/')
    }

    private var isFirstPoll = true
    private fun startInboxPolling(username: String) {
        inboxPollJob?.cancel()
        inboxPollJob = viewModelScope.launch {
            var pollCount = 0
            while (isActive) {
                try {
                    // 1. Send foreground heartbeat every 3rd poll (30s) or immediately on first poll
                    if (pollCount % 3 == 0) {
                        withContext(Dispatchers.IO) {
                            try {
                                val friends = repository.getFriendsDirect().map { it.username }
                                val isProtected = com.example.util.ProtectionMonitor.isAccessibilityServiceEnabled(context)
                                api.sendHeartbeat(username, isProtected, friends)
                                android.util.Log.d("ScrollSentry", "Foreground heartbeat sent")
                            } catch (e: Exception) {
                                android.util.Log.e("ScrollSentry", "Foreground heartbeat failed", e)
                            }
                        }
                    }
                    pollCount++

                    val result = withContext(Dispatchers.IO) { api.getInbox(username) }
                    
                    // Check for new requests to notify
                    result.forEach { request ->
                        if (!notifiedRequestIds.contains(request.id)) {
                            if (!isFirstPoll) {
                                if (request.type == "PROTECTION_ALERT") {
                                    NotificationHelper.showProtectionAlertNotification(
                                        context,
                                        request.id,
                                        request.requester,
                                        request.reason
                                    )
                                } else {
                                    NotificationHelper.showRequestNotification(
                                        context,
                                        request.id,
                                        request.requester
                                    )
                                }
                            }
                            notifiedRequestIds.add(request.id)
                        }
                    }
                    isFirstPoll = false
                    
                    _inbox.value = result
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(10000) // Poll every 10 seconds as requested
            }
        }
    }

    fun approveIncomingRequest(requestId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.updateRequestStatus(requestId, "APPROVED") }
                // Refresh inbox immediately
                currentUser.value?.let { startInboxPolling(it.username) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun rejectIncomingRequest(requestId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.updateRequestStatus(requestId, "REJECTED") }
                currentUser.value?.let { startInboxPolling(it.username) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun startSimulator() {
        _isSimulatorActive.value = true
        startTimer()
    }

    fun stopSimulator() {
        _isSimulatorActive.value = false
        stopTimer()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_isSimulatorActive.value) {
                delay(1000)
                val currentUsage = repository.getDailyUsageDirect(dateStr) ?: continue
                val secondsRemaining = currentUsage.limitSeconds - currentUsage.consumedSeconds

                if (secondsRemaining > 0) {
                    repository.saveDailyUsage(
                        currentUsage.copy(consumedSeconds = currentUsage.consumedSeconds + 1)
                    )
                } else {
                    stopSimulator()
                    break
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    fun updateDailyLimitSetting(newLimitSeconds: Int) {
        viewModelScope.launch {
            val currentUsage = repository.getDailyUsageDirect(dateStr)
            if (currentUsage != null) {
                repository.saveDailyUsage(currentUsage.copy(limitSeconds = newLimitSeconds))
            } else {
                repository.saveDailyUsage(
                    DailyUsage(
                        date = dateStr,
                        limitSeconds = newLimitSeconds,
                        consumedSeconds = 0
                    )
                )
            }
        }
    }

    fun resetDailyUsage() {
        viewModelScope.launch {
            val currentUsage = repository.getDailyUsageDirect(dateStr)
            if (currentUsage != null) {
                repository.saveDailyUsage(
                    currentUsage.copy(
                        consumedSeconds = 0,
                        extensionsCount = 0,
                        limitSeconds = 30
                    )
                )
            }
            activePollJobs.values.forEach { it.cancel() }
            activePollJobs.clear()
        }
    }

    fun sendScrollExtensionRequest(friend: Friend) {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            val extraSeconds = 300
            try {
                // Clear any stale pending requests for this friend
                val existing = requests.value.filter { it.friendId == 0L && it.friendName == friend.username && it.status == "PENDING" }
                existing.forEach { 
                    repository.updateRequestStatus(it.id, "DISMISSED")
                }

                val created = withContext(Dispatchers.IO) {
                    api.createRequest(
                        requester = user.username,
                        approver = friend.username,
                        minutes = extraSeconds / 60
                    )
                }
                
                val req = ScrollRequest(
                    date = dateStr,
                    friendId = 0, // Using username instead of ID
                    friendName = friend.displayName,
                    friendEmoji = friend.avatarEmoji,
                    status = "PENDING",
                    extraSeconds = extraSeconds,
                    serverRequestId = created.id,
                    approvalUrl = null, // Old link system removed
                )
                repository.insertRequest(req)
                startPollingServerStatus(created.id)
            } catch (e: Exception) {
                e.printStackTrace()
                android.util.Log.e("ScrollSentry", "Failed to create request", e)
                val failedReq = ScrollRequest(
                    date = dateStr,
                    friendId = 0,
                    friendName = friend.displayName,
                    friendEmoji = friend.avatarEmoji,
                    status = "ERROR",
                    extraSeconds = extraSeconds,
                    serverRequestId = null,
                    approvalUrl = null,
                )
                repository.insertRequest(failedReq)
            }
        }
    }

    private fun startPollingServerStatus(serverRequestId: String) {
        activePollJobs[serverRequestId]?.cancel()
        activePollJobs[serverRequestId] = viewModelScope.launch {
            while (isActive) {
                try {
                    val result = withContext(Dispatchers.IO) { api.getStatus(serverRequestId) }
                    when (result.status) {
                        "APPROVED" -> {
                            if (applyServerApproval(serverRequestId, result.minutes)) {
                                return@launch
                            }
                        }
                        "REJECTED", "EXPIRED" -> {
                            if (applyServerRejection(serverRequestId)) {
                                return@launch
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(3000)
            }
        }
    }

    private suspend fun applyServerApproval(
        serverRequestId: String,
        approvedMinutes: Int
    ): Boolean {
        val req = repository.getRequestByServerId(serverRequestId) ?: return false
        
        if (req.status != "PENDING") {
            activePollJobs.remove(serverRequestId)
            return true
        }

        repository.updateRequestStatus(req.id, "APPROVED")
        activePollJobs.remove(serverRequestId)

        val bonusSeconds = approvedMinutes * 60
        val currentDate = dateStr 
        val currentUsage = repository.getDailyUsageDirect(currentDate)

        if (currentUsage != null) {
            repository.saveDailyUsage(
                currentUsage.copy(
                    limitSeconds = currentUsage.limitSeconds + bonusSeconds,
                    extensionsCount = currentUsage.extensionsCount + 1
                )
            )
        } else {
            repository.saveDailyUsage(
                DailyUsage(
                    date = currentDate,
                    limitSeconds = 30 + bonusSeconds,
                    consumedSeconds = 0,
                    extensionsCount = 1
                )
            )
        }

        return true
    }

    private suspend fun applyServerRejection(serverRequestId: String): Boolean {
        val req = repository.getRequestByServerId(serverRequestId) ?: return false
        if (req.status != "PENDING") return true

        repository.updateRequestStatus(req.id, "REJECTED")
        activePollJobs.remove(serverRequestId)
        return true
    }

    fun dismissRequest(uuid: String) {
        viewModelScope.launch {
            val req = repository.getRequestByUuid(uuid)
            if (req != null) {
                repository.updateRequestStatus(req.id, "DISMISSED")
                req.serverRequestId?.let { serverId ->
                    activePollJobs[serverId]?.cancel()
                    activePollJobs.remove(serverId)
                }
            }
        }
    }

    fun toggleLikeMockPost(postId: Int) {
        _mockPosts.update { currentList ->
            currentList.map { post ->
                if (post.id == postId) {
                    val isNewLiked = !post.isLiked
                    post.copy(
                        isLiked = isNewLiked,
                        likesCount = if (isNewLiked) post.likesCount + 1 else post.likesCount - 1
                    )
                } else post
            }
        }
    }

    private fun getInitialMockPosts(): List<MockPost> {
        return listOf(
            MockPost(
                id = 1,
                username = "ocean_breeze",
                avatarEmoji = "🌊",
                caption = "Woke up early to catch the perfect sunrise. Nature is the ultimate therapy! 🌅 #mindful #nature #morning",
                likesCount = 142,
                imageGradientStart = 0xFF4facfe,
                imageGradientEnd = 0xFF00f2fe
            ),
            MockPost(
                id = 2,
                username = "nomad_cook",
                avatarEmoji = "🍳",
                caption = "Simmered this local curry for 4 hours. Absolute bliss of slow living. 🍲✨ Who wants the recipe?",
                likesCount = 89,
                imageGradientStart = 0xFFff0844,
                imageGradientEnd = 0xFFffb199
            ),
            MockPost(
                id = 3,
                username = "pixel_wanderer",
                avatarEmoji = "👾",
                caption = "Finally built my dream custom ortholinear mechanical keyboard! Sound test in stories. ⌨️🎵",
                likesCount = 205,
                imageGradientStart = 0xFFf12711,
                imageGradientEnd = 0xFFf5af19
            ),
            MockPost(
                id = 4,
                username = "growth_mindset",
                avatarEmoji = "🌱",
                caption = "Quick reminder: Scrolling is fine, but living is better. Go take a slow breath! 🤍 #detox #slowliving",
                likesCount = 312,
                imageGradientStart = 0xFF11998e,
                imageGradientEnd = 0xFF38ef7d
            ),
            MockPost(
                id = 5,
                username = "cozy_reads",
                avatarEmoji = "📚",
                caption = "Raining outside, hot cocoa inside, and a brand new novel in hand. What are you reading today? ☕📖",
                likesCount = 97,
                imageGradientStart = 0xFF6a11cb,
                imageGradientEnd = 0xFF2575fc
            )
        )
    }

    companion object {
        fun provideFactory(application: Application, repository: ScrollSentryRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ScrollSentryViewModel(application, repository) as T
                }
            }
    }
}

data class MockPost(
    val id: Int,
    val username: String,
    val avatarEmoji: String,
    val caption: String,
    val likesCount: Int,
    val isLiked: Boolean = false,
    val imageGradientStart: Long,
    val imageGradientEnd: Long
)

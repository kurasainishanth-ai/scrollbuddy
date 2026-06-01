package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.DailyUsage
import com.example.data.local.Friend
import com.example.data.local.ScrollRequest
import com.example.data.network.ApprovalApi
import com.example.data.repository.ScrollSentryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScrollSentryViewModel(private val repository: ScrollSentryRepository) : ViewModel() {

    private val api = ApprovalApi()

    private val dateStr: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    val friends: StateFlow<List<Friend>> = repository.getFriendsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val requests: StateFlow<List<ScrollRequest>> = repository.getRequestsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyUsage: StateFlow<DailyUsage?> = repository.getLatestDailyUsageFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isSimulatorActive = MutableStateFlow(false)
    val isSimulatorActive: StateFlow<Boolean> = _isSimulatorActive.asStateFlow()

    private var timerJob: Job? = null

    private val _mockPosts = MutableStateFlow(getInitialMockPosts())
    val mockPosts: StateFlow<List<MockPost>> = _mockPosts.asStateFlow()

    private val activePollJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            val currentFriends = repository.getFriendsDirect()
            if (currentFriends.isEmpty()) {
                repository.insertFriend(Friend(name = "Sarah (Guardian)", avatarEmoji = "🧘‍♀️", isAutoAccept = false, acceptDelaySec = 0))
                repository.insertFriend(Friend(name = "Marcus (Best Friend)", avatarEmoji = "🛡️", isAutoAccept = false, acceptDelaySec = 0))
                repository.insertFriend(Friend(name = "Emma (Companion)", avatarEmoji = "✨", isAutoAccept = false, acceptDelaySec = 0))
                repository.insertFriend(Friend(name = "Jake (Parent)", avatarEmoji = "🎒", isAutoAccept = false, acceptDelaySec = 0))
            } else {
                currentFriends.forEach { friend ->
                    if (friend.isAutoAccept) {
                        repository.insertFriend(friend.copy(isAutoAccept = false, acceptDelaySec = 0))
                    }
                }
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
        viewModelScope.launch {
            val extraSeconds = 300
            try {
                // FORCE: Clear any stale pending requests for this friend to ensure the tap always proceeds
                val existing = requests.value.filter { it.friendId == friend.id && it.status == "PENDING" }
                existing.forEach { 
                    repository.updateRequestStatus(it.id, "DISMISSED")
                }

                val created = withContext(Dispatchers.IO) {
                    api.createRequest(minutes = extraSeconds / 60)
                }
                
                val req = ScrollRequest(
                    date = dateStr,
                    friendId = friend.id,
                    friendName = friend.name,
                    friendEmoji = friend.avatarEmoji,
                    status = "PENDING",
                    extraSeconds = extraSeconds,
                    serverRequestId = created.id,
                    approvalUrl = created.approvalUrl,
                )
                repository.insertRequest(req)
                startPollingServerStatus(created.id)
            } catch (e: Exception) {
                e.printStackTrace()
                // SURFALCE ERROR: Use a specific status or throw so UI/Log shows the failure
                android.util.Log.e("ScrollSentry", "NETWORK FAILURE: Tapping friend failed. URL: ${com.example.BuildConfig.SERVER_URL}", e)
                
                // Create a failed request locally so the UI transitions or shows the error state
                val failedReq = ScrollRequest(
                    date = dateStr,
                    friendId = friend.id,
                    friendName = friend.name,
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
                    android.util.Log.e("ScrollSentry", "Request failed", e)
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
        if (req.status != "PENDING") return true

        // 1. Add bonus time to DailyUsage first
        val bonusSeconds = approvedMinutes * 60
        val currentDate = dateStr // use same date for fetch and save
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

        // 2. Mark request as approved
        repository.updateRequestStatus(req.id, "APPROVED")

        // 3. Remove from active jobs map without cancelling self
        activePollJobs.remove(serverRequestId)

        return true
    }

    private suspend fun applyServerRejection(serverRequestId: String): Boolean {
        val req = repository.getRequestByServerId(serverRequestId) ?: return false
        if (req.status != "PENDING") return true

        repository.updateRequestStatus(req.id, "REJECTED")
        activePollJobs.remove(serverRequestId)
        return true
    }

    fun approveExtensionRequest(requestId: Long) {
        viewModelScope.launch {
            val req = repository.getRequestById(requestId)
            if (req != null) {
                approveExtensionRequestByUuid(req.uuid)
            }
        }
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

    private suspend fun approveExtensionRequestByUuid(uuid: String) {
        val req = repository.getRequestByUuid(uuid)
        if (req != null && req.status == "PENDING") {
            // 1. Add time
            val currentDate = dateStr
            val currentUsage = repository.getDailyUsageDirect(currentDate)
            if (currentUsage != null) {
                repository.saveDailyUsage(
                    currentUsage.copy(
                        limitSeconds = currentUsage.limitSeconds + req.extraSeconds,
                        extensionsCount = currentUsage.extensionsCount + 1
                    )
                )
            } else {
                repository.saveDailyUsage(
                    DailyUsage(
                        date = currentDate,
                        limitSeconds = 30 + req.extraSeconds,
                        consumedSeconds = 0,
                        extensionsCount = 1
                    )
                )
            }

            // 2. Update status
            repository.updateRequestStatus(req.id, "APPROVED")

            // 3. Stop polling
            req.serverRequestId?.let { serverId ->
                activePollJobs[serverId]?.cancel()
                activePollJobs.remove(serverId)
            }
        }
    }

    private suspend fun rejectExtensionRequestByUuid(uuid: String) {
        val req = repository.getRequestByUuid(uuid)
        if (req != null && req.status == "PENDING") {
            repository.updateRequestStatus(req.id, "REJECTED")
            req.serverRequestId?.let { serverId ->
                activePollJobs[serverId]?.cancel()
                activePollJobs.remove(serverId)
            }
        }
    }

    fun addCustomFriend(name: String, emoji: String, isAutoAccept: Boolean, delaySeconds: Int) {
        viewModelScope.launch {
            val safeEmoji = if (emoji.trim().isEmpty()) "👤" else emoji.trim()
            repository.insertFriend(
                Friend(
                    name = name,
                    avatarEmoji = safeEmoji,
                    isAutoAccept = isAutoAccept,
                    acceptDelaySec = delaySeconds
                )
            )
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
        fun provideFactory(repository: ScrollSentryRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ScrollSentryViewModel(repository) as T
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

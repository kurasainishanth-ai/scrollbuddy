package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.example.BuildConfig
import com.example.MainActivity
import com.example.data.local.AppDatabase
import com.example.data.local.DailyUsage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ScrollSentryAccessibilityService : AccessibilityService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var trackerJob: Job? = null

    private var lastSupportedAppEventTime: Long = 0
    private var isAddictiveContentActive = false
    private var currentScreenLabel = "Unknown"
    private var currentPackageName = ""

    private var windowManager: WindowManager? = null
    private var debugOverlay: TextView? = null

    private val dateStr: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val detector = detectors[packageName]

        if (detector == null) {
            hideDebugOverlay()
            stopActiveSession()
            return
        }

        lastSupportedAppEventTime = System.currentTimeMillis()
        currentPackageName = packageName
        showDebugOverlay()

        val rootNode = rootInActiveWindow
        val detection = if (rootNode != null) {
            detector.detect(buildSnapshot(rootNode))
        } else {
            DetectionResult(active = false, label = "No active window")
        }

        currentScreenLabel = detection.label
        updateDebugOverlay(
            "APP: ${detector.displayName}\nSCREEN: ${detection.label}\nACTIVE: ${detection.active}"
        )

        if (detection.active) {
            if (!isAddictiveContentActive) {
                Log.d("ScrollSentry", "${detector.displayName} addictive content detected. Starting tracking.")
                isAddictiveContentActive = true
                startBackgroundTracking()
            }
        } else if (isAddictiveContentActive) {
            Log.d("ScrollSentry", "Left addictive content: ${detection.label}. Stopping tracking.")
            stopActiveSession()
        }
    }

    private fun buildSnapshot(rootNode: AccessibilityNodeInfo): UiSnapshot {
        val snapshot = UiSnapshot()
        collectNodeInfo(rootNode, snapshot, depth = 0, recycleAfter = false)
        return snapshot
    }

    private fun collectNodeInfo(
        node: AccessibilityNodeInfo?,
        snapshot: UiSnapshot,
        depth: Int,
        recycleAfter: Boolean
    ) {
        if (node == null || depth > 12 || snapshot.size >= MAX_SNAPSHOT_ITEMS) {
            if (recycleAfter) node?.recycle()
            return
        }

        val label = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }

        node.text?.toString()?.let(snapshot::addText)
        node.contentDescription?.toString()?.let(snapshot::addDescription)
        node.viewIdResourceName?.let(snapshot::addViewId)

        if (node.isSelected && label != null) {
            snapshot.addSelectedLabel(label)
        }

        for (index in 0 until node.childCount) {
            collectNodeInfo(node.getChild(index), snapshot, depth + 1, recycleAfter = true)
        }

        if (recycleAfter) node.recycle()
    }

    private fun startBackgroundTracking() {
        if (trackerJob?.isActive == true) return

        trackerJob = scope.launch {
            val dao = AppDatabase.getDatabase(applicationContext).dao()

            while (isAddictiveContentActive) {
                val now = System.currentTimeMillis()
                if (now - lastSupportedAppEventTime > ACTIVE_SESSION_STALE_MS) {
                    isAddictiveContentActive = false
                    break
                }

                val usage = dao.getDailyUsageDirect(dateStr) ?: DailyUsage(
                    date = dateStr,
                    limitSeconds = DEFAULT_DAILY_LIMIT_SECONDS,
                    consumedSeconds = 0,
                    extensionsCount = 0
                ).also { dao.insertDailyUsage(it) }

                val secondsRemaining = usage.limitSeconds - usage.consumedSeconds
                if (secondsRemaining <= 0) {
                    launchBlockingScreen()
                    isAddictiveContentActive = false
                    break
                }

                dao.insertDailyUsage(usage.copy(consumedSeconds = usage.consumedSeconds + 1))
                delay(1000)
            }
        }
    }

    private fun stopActiveSession() {
        isAddictiveContentActive = false
        trackerJob?.cancel()
        trackerJob = null
    }

    private fun launchBlockingScreen() {
        Log.d("ScrollSentry", "Addictive scrolling limit reached on $currentPackageName.")
        val blockIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("limit_reached", true)
            putExtra("blocked_package", currentPackageName)
        }
        startActivity(blockIntent)
    }

    private fun showDebugOverlay() {
        if (!BuildConfig.DEBUG || debugOverlay != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        debugOverlay = TextView(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFF00FF00.toInt())
            textSize = 13f
            setPadding(20, 20, 20, 20)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 10
            y = 100
        }

        windowManager?.addView(debugOverlay, params)
    }

    private fun hideDebugOverlay() {
        debugOverlay?.let { windowManager?.removeView(it) }
        debugOverlay = null
    }

    private fun updateDebugOverlay(text: String) {
        debugOverlay?.text = text
    }

    override fun onInterrupt() {
        stopActiveSession()
    }

    override fun onDestroy() {
        hideDebugOverlay()
        stopActiveSession()
        job.cancel()
        super.onDestroy()
    }

    private data class DetectionResult(
        val active: Boolean,
        val label: String
    )

    private interface AddictiveContentDetector {
        val displayName: String
        fun detect(snapshot: UiSnapshot): DetectionResult
    }

    private class InstagramReelsDetector : AddictiveContentDetector {
        override val displayName = "Instagram"

        override fun detect(snapshot: UiSnapshot): DetectionResult {
            if (snapshot.hasAny(INSTAGRAM_HARD_EXCLUSIONS)) {
                return DetectionResult(active = false, label = "Instagram non-Reels surface")
            }

            val reelsTabSelected = snapshot.hasSelectedLabel("reels")
            val reelsContainerVisible = snapshot.hasViewIdContainingAny(INSTAGRAM_REELS_IDS)
            val reelsActionCluster = snapshot.countLabelsContaining(INSTAGRAM_REELS_ACTIONS) >= 2

            return if (reelsContainerVisible || reelsTabSelected || (snapshot.hasAny("reels") && reelsActionCluster)) {
                DetectionResult(active = true, label = "Instagram Reels")
            } else {
                DetectionResult(active = false, label = "Instagram non-Reels surface")
            }
        }
    }

    private class YouTubeShortsDetector : AddictiveContentDetector {
        override val displayName = "YouTube"

        override fun detect(snapshot: UiSnapshot): DetectionResult {
            if (snapshot.hasAny(YOUTUBE_HARD_EXCLUSIONS)) {
                return DetectionResult(active = false, label = "YouTube non-Shorts surface")
            }

            val shortsTabSelected = snapshot.hasSelectedLabel("shorts")
            val shortsContainerVisible = snapshot.hasViewIdContainingAny(YOUTUBE_SHORTS_IDS)

            return if (shortsTabSelected || shortsContainerVisible) {
                DetectionResult(active = true, label = "YouTube Shorts")
            } else {
                DetectionResult(active = false, label = "YouTube non-Shorts surface")
            }
        }
    }

    private class TikTokDetector : AddictiveContentDetector {
        override val displayName = "TikTok"

        override fun detect(snapshot: UiSnapshot): DetectionResult {
            if (snapshot.hasAny(TIKTOK_HARD_EXCLUSIONS)) {
                return DetectionResult(active = false, label = "TikTok non-video surface")
            }

            val selectedVideoFeed = snapshot.hasSelectedLabel("home") ||
                snapshot.hasSelectedLabel("for you") ||
                snapshot.hasSelectedLabel("following")
            val videoActionCluster = snapshot.countLabelsContaining(TIKTOK_VIDEO_ACTIONS) >= 2

            return if (selectedVideoFeed || videoActionCluster) {
                DetectionResult(active = true, label = "TikTok video feed")
            } else {
                DetectionResult(active = false, label = "TikTok non-video surface")
            }
        }
    }

    private class UiSnapshot {
        private val texts = linkedSetOf<String>()
        private val descriptions = linkedSetOf<String>()
        private val viewIds = linkedSetOf<String>()
        private val selectedLabels = linkedSetOf<String>()

        val size: Int
            get() = texts.size + descriptions.size + viewIds.size + selectedLabels.size

        fun addText(value: String) {
            addNormalized(texts, value)
        }

        fun addDescription(value: String) {
            addNormalized(descriptions, value)
        }

        fun addViewId(value: String) {
            addNormalized(viewIds, value)
        }

        fun addSelectedLabel(value: String) {
            addNormalized(selectedLabels, value)
        }

        fun hasAny(vararg terms: String): Boolean = hasAny(terms.asIterable())

        fun hasAny(terms: Iterable<String>): Boolean {
            val normalizedTerms = terms.map { it.lowercase(Locale.US) }
            return allLabels().any { label -> normalizedTerms.any(label::contains) }
        }

        fun hasSelectedLabel(term: String): Boolean {
            val normalizedTerm = term.lowercase(Locale.US)
            return selectedLabels.any { it.contains(normalizedTerm) }
        }

        fun hasViewIdContainingAny(terms: Iterable<String>): Boolean {
            val normalizedTerms = terms.map { it.lowercase(Locale.US) }
            return viewIds.any { viewId -> normalizedTerms.any(viewId::contains) }
        }

        fun countLabelsContaining(terms: Iterable<String>): Int {
            val normalizedTerms = terms.map { it.lowercase(Locale.US) }
            return normalizedTerms.count { term -> allLabels().any { it.contains(term) } }
        }

        private fun allLabels(): Sequence<String> = sequence {
            yieldAll(texts)
            yieldAll(descriptions)
        }

        private fun addNormalized(target: MutableSet<String>, value: String) {
            val normalized = value.trim().lowercase(Locale.US)
            if (normalized.isNotBlank()) target.add(normalized)
        }
    }

    companion object {
        private const val ACTIVE_SESSION_STALE_MS = 4_000L
        private const val DEFAULT_DAILY_LIMIT_SECONDS = 30
        private const val MAX_SNAPSHOT_ITEMS = 300

        private val detectors = mapOf(
            "com.instagram.android" to InstagramReelsDetector(),
            "com.google.android.youtube" to YouTubeShortsDetector(),
            "com.zhiliaoapp.musically" to TikTokDetector(),
            "com.ss.android.ugc.trill" to TikTokDetector()
        )

        private val INSTAGRAM_REELS_IDS = listOf(
            "clips_video_container",
            "clips_viewer_video_container",
            "clips_viewer",
            "clips_tab",
            "reel_viewer_container",
            "reels_viewer"
        )

        private val INSTAGRAM_REELS_ACTIONS = listOf(
            "like",
            "comment",
            "share",
            "send",
            "audio",
            "remix"
        )

        private val INSTAGRAM_HARD_EXCLUSIONS = listOf(
            "direct",
            "messages",
            "search",
            "search and explore",
            "edit profile",
            "add a comment",
            "reply to"
        )

        private val YOUTUBE_SHORTS_IDS = listOf(
            "shorts",
            "reel",
            "reel_watch",
            "shorts_video"
        )

        private val YOUTUBE_HARD_EXCLUSIONS = listOf(
            "search youtube",
            "search results",
            "add a comment",
            "reply to",
            "live chat",
            "description",
            "transcript"
        )

        private val TIKTOK_VIDEO_ACTIONS = listOf(
            "like",
            "comment",
            "share",
            "favorite",
            "sound",
            "following"
        )

        private val TIKTOK_HARD_EXCLUSIONS = listOf(
            "inbox",
            "messages",
            "profile",
            "edit profile",
            "search",
            "add comment",
            "reply"
        )
    }
}

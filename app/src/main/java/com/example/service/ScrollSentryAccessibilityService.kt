package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import com.example.BuildConfig
import com.example.MainActivity
import com.example.data.local.AppDatabase
import com.example.data.local.DailyUsage
import com.example.data.network.ApprovalApi
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var trackerJob: Job? = null
    private var heartbeatJob: Job? = null

    private var activeBlockedApp: ResolvedBlockableApp? = null
    private var currentScreenLabel = "Unknown"
    private var currentPackageName = ""

    private var windowManager: WindowManager? = null
    private var debugOverlay: TextView? = null

    private val dateStr: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    override fun onServiceConnected() {
        super.onServiceConnected()
        startHeartbeat()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            val dao = AppDatabase.getDatabase(applicationContext).dao()
            val api = ApprovalApi()
            while (true) {
                try {
                    val user = dao.getUserAccount()
                    if (user != null) {
                        val friends = dao.getFriendsDirect().map { it.username }
                        api.sendHeartbeat(user.username, true, friends)
                        Log.d("ScrollSentry", "Heartbeat sent for ${user.username}")
                    }
                } catch (e: Exception) {
                    Log.e("ScrollSentry", "Failed to send heartbeat", e)
                }
                delay(5 * 60 * 1000) // 5 minutes
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val rootNode = rootInActiveWindow

        val detectedApp = if (rootNode != null) {
            detectBlockedContent(packageName, rootNode)
        } else {
            activeBlockedApp?.takeIf(::isBlockedContentStillVisible)
        }

        if (detectedApp != null) {
            currentPackageName = detectedApp.packageId
            currentScreenLabel = detectedApp.app.displayName
            showDebugOverlay()
            updateDebugOverlay("APP: ${detectedApp.app.displayName}\nACTIVE: true")

            if (activeBlockedApp == null) {
                Log.d("ScrollSentry", "${detectedApp.app.displayName} detected. Starting tracking.")
                activeBlockedApp = detectedApp
                startBackgroundTracking()
            } else {
                activeBlockedApp = detectedApp
            }
        } else {
            hideDebugOverlay()
            if (activeBlockedApp != null) {
                Log.d("ScrollSentry", "Blocked content no longer detected. Stopping tracking.")
                stopActiveSession()
            }
        }
    }

    private fun detectBlockedContent(
        packageName: String,
        rootNode: AccessibilityNodeInfo
    ): ResolvedBlockableApp? {
        activeBlockedApp?.let { currentApp ->
            if (rootNode.matchesBlockedContent(currentApp)) {
                return currentApp
            }

            if (isBlockedContentStillVisible(currentApp)) {
                return currentApp
            }
        }

        return BlockableApp.entries.firstNotNullOfOrNull { app ->
            val matchedPackage = app.resolvePackage(packageName) ?: return@firstNotNullOfOrNull null
            val resolvedApp = ResolvedBlockableApp(app, matchedPackage)
            resolvedApp.takeIf { rootNode.matchesBlockedContent(it) }
        }
    }

    private fun isBlockedContentStillVisible(blockableApp: ResolvedBlockableApp): Boolean {
        return findVisibleBlockedContentRoot(blockableApp) != null
    }

    private fun findVisibleBlockedContentRoot(blockableApp: ResolvedBlockableApp): AccessibilityNodeInfo? {
        return windows.firstNotNullOfOrNull { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                return@firstNotNullOfOrNull null
            }

            val root = window.root ?: return@firstNotNullOfOrNull null
            val windowPackage = root.packageName?.toString()
            if (windowPackage == blockableApp.packageId && root.matchesBlockedContent(blockableApp)) {
                root
            } else {
                null
            }
        }
    }

    private fun startBackgroundTracking() {
        if (trackerJob?.isActive == true) return

        trackerJob = scope.launch {
            val dao = AppDatabase.getDatabase(applicationContext).dao()

            while (activeBlockedApp != null) {
                val trackedApp = activeBlockedApp
                if (trackedApp == null || !isBlockedContentStillVisible(trackedApp)) {
                    stopActiveSession()
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
                    exitBlockedContentAndLaunchScreen(trackedApp)
                    stopActiveSession()
                    break
                }

                dao.insertDailyUsage(usage.copy(consumedSeconds = usage.consumedSeconds + 1))
                delay(1000)
            }
        }
    }

    private fun stopActiveSession() {
        activeBlockedApp = null
        trackerJob?.cancel()
        trackerJob = null
    }

    private suspend fun exitBlockedContentAndLaunchScreen(blockedApp: ResolvedBlockableApp) {
        if (blockedApp.app == BlockableApp.SHORTS) {
            var attempts = 0
            while (isAppStillInForeground(blockedApp.packageId) && attempts < 5) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(YOUTUBE_SHORTS_EXIT_DELAY_MS)
                attempts++
            }
        }
        launchBlockingScreen()
    }

    private fun isAppStillInForeground(packageId: String): Boolean {
        return windows.any { window ->
            window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            window.root?.packageName?.toString() == packageId
        }
    }

    private fun launchBlockingScreen() {
        Log.d("ScrollSentry", "Addictive scrolling limit reached on $currentPackageName.")
        mainHandler.post {
            val blockIntent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("limit_reached", true)
                putExtra("blocked_package", currentPackageName)
            }
            startActivity(blockIntent)
        }
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
        heartbeatJob?.cancel()
        job.cancel()
        super.onDestroy()
    }

    private fun AccessibilityNodeInfo.matchesBlockedContent(blockableApp: ResolvedBlockableApp): Boolean {
        return matchesDetectionMethod(blockableApp, blockableApp.getDetectionMethod())
    }

    private fun AccessibilityNodeInfo.matchesDetectionMethod(
        blockableApp: ResolvedBlockableApp,
        detectionMethod: DetectionMethod
    ): Boolean {
        return when (detectionMethod) {
            is DetectionMethod.ViewId -> hasVisibleViewId(blockableApp.getViewId(detectionMethod))
            is DetectionMethod.ContentDescriptions -> hasVisibleContentDescription(detectionMethod.contentDescriptions)
            is DetectionMethod.ContentDescriptionPrefix -> hasVisibleContentDescriptionPrefix(detectionMethod)
            is DetectionMethod.AnyOf -> detectionMethod.detectionMethods.any {
                matchesDetectionMethod(blockableApp, it)
            }
        }
    }

    private fun AccessibilityNodeInfo.hasVisibleViewId(viewId: String): Boolean {
        return findAccessibilityNodeInfosByViewId(viewId).any(::isNodeVisibleToTheUser)
    }

    private fun AccessibilityNodeInfo.hasVisibleContentDescription(contentDescriptions: Set<String>): Boolean {
        return hasVisibleNodeMatching { node ->
            node.contentDescription?.toString() in contentDescriptions
        }
    }

    private fun AccessibilityNodeInfo.hasVisibleContentDescriptionPrefix(
        detectionMethod: DetectionMethod.ContentDescriptionPrefix
    ): Boolean {
        val rootBounds = Rect().also(::getBoundsInScreen)
        val maxTop = detectionMethod.maxTopScreenFraction?.let { fraction ->
            val clampedFraction = fraction.coerceIn(0f, 1f)
            rootBounds.top + (rootBounds.height() * clampedFraction).toInt()
        }

        return hasVisibleNodeMatching { node ->
            val contentDescription = node.contentDescription?.toString() ?: return@hasVisibleNodeMatching false
            val matchesPrefix = detectionMethod.prefixes.any(contentDescription::startsWith)
            if (!matchesPrefix) {
                return@hasVisibleNodeMatching false
            }

            val nodeBounds = Rect().also(node::getBoundsInScreen)
            val matchesSelectedState = !detectionMethod.requireSelected || node.isSelected
            val matchesTopConstraint = maxTop == null || nodeBounds.bottom <= maxTop
            matchesSelectedState && matchesTopConstraint
        }
    }

    private fun AccessibilityNodeInfo.hasVisibleNodeMatching(
        matchesNode: (AccessibilityNodeInfo) -> Boolean
    ): Boolean {
        val nodesToVisit = ArrayDeque<AccessibilityNodeInfo>()
        nodesToVisit.add(this)

        while (nodesToVisit.isNotEmpty()) {
            val node = nodesToVisit.removeFirst()
            if (isNodeVisibleToTheUser(node) && matchesNode(node)) {
                return true
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(nodesToVisit::addLast)
            }
        }

        return false
    }

    private fun isNodeVisibleToTheUser(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return node.isVisibleToUser && rect.width() > 0 && rect.height() > 0
    }

    private sealed class DetectionMethod {
        data class ViewId(val viewId: String) : DetectionMethod()
        data class ContentDescriptions(val contentDescriptions: Set<String>) : DetectionMethod()
        data class ContentDescriptionPrefix(
            val prefixes: Set<String>,
            val requireSelected: Boolean = false,
            val maxTopScreenFraction: Float? = null
        ) : DetectionMethod()
        data class AnyOf(val detectionMethods: List<DetectionMethod>) : DetectionMethod()
    }

    private enum class BlockableApp(
        val displayName: String,
        private val packageIds: List<String>,
        private val detectionMethod: DetectionMethod
    ) {
        REELS(
            displayName = "Instagram Reels",
            packageIds = listOf("com.instagram.android"),
            detectionMethod = DetectionMethod.ViewId("clips_viewer_view_pager")
        ),
        SHORTS(
            displayName = "YouTube Shorts",
            packageIds = listOf(
                "com.google.android.youtube",
                "com.google.android.apps.youtube.kids",
                "app.revanced.android.youtube"
            ),
            detectionMethod = DetectionMethod.ViewId("reel_player_page_container")
        ),
        TIKTOK(
            displayName = "TikTok",
            packageIds = listOf(
                "com.zhiliaoapp.musically",
                "com.ss.android.ugc.trill",
                "com.ss.android.ugc.aweme",
                "com.zhiliaoapp.musically.go"
            ),
            detectionMethod = DetectionMethod.ViewId("player_view")
        );

        fun getDetectionMethod(): DetectionMethod = detectionMethod

        fun getPackageIds(): List<String> = packageIds

        fun resolvePackage(packageName: String): String? = packageName.takeIf(packageIds::contains)
    }

    private data class ResolvedBlockableApp(
        val app: BlockableApp,
        val packageId: String
    ) {
        fun getDetectionMethod(): DetectionMethod = app.getDetectionMethod()

        fun getViewId(detectionMethod: DetectionMethod.ViewId): String {
            return "$packageId:id/${detectionMethod.viewId}"
        }
    }

    private companion object {
        private const val DEFAULT_DAILY_LIMIT_SECONDS = 30
        private const val YOUTUBE_SHORTS_EXIT_DELAY_MS = 450L
    }
}

package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.data.local.AppDatabase
import com.example.data.local.DailyUsage
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import android.view.Gravity
import android.graphics.PixelFormat
import android.widget.TextView
import com.example.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScrollSentryAccessibilityService : AccessibilityService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var trackerJob: Job? = null

    private var lastInstagramInteractTime: Long = 0
    private var isInstagramActiveGroup = false
    private var isReelsVisible = false

    private var windowManager: WindowManager? = null
    private var debugOverlay: TextView? = null

    private val dateStr: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        if (packageName == "com.instagram.android") {
            lastInstagramInteractTime = System.currentTimeMillis()
            
            showDebugOverlay()
            
            // Check if we are specifically in Reels
            isReelsVisible = checkIsReelsActive()
            
            val debugText = StringBuilder().apply {
                append("PKG: $packageName\n")
                append("EVT: ${AccessibilityEvent.eventTypeToString(event.eventType)}\n")
                append("REELS: $isReelsVisible\n")
                
                val ids = mutableSetOf<String>()
                val descs = mutableSetOf<String>()
                collectDebugInfo(rootInActiveWindow, ids, descs, 0)
                
                append("IDS: ${ids.take(5).joinToString(", ")}\n")
                append("DESC: ${descs.take(5).joinToString(", ")}")
            }.toString()
            
            updateDebugOverlay(debugText)
            
            if (isReelsVisible) {
                if (!isInstagramActiveGroup) {
                    Log.d("ScrollSentry", "Instagram Reels detected! Starting session tracking.")
                    isInstagramActiveGroup = true
                    startBackgroundTracking()
                }
            } else {
                if (isInstagramActiveGroup) {
                    Log.d("ScrollSentry", "User left Reels. Stopping session tracking.")
                    isInstagramActiveGroup = false
                    stopBackgroundTracking()
                }
            }
        } else {
            // User left Instagram completely
            hideDebugOverlay()
            if (isInstagramActiveGroup) {
                isInstagramActiveGroup = false
                isReelsVisible = false
                stopBackgroundTracking()
            }
        }
    }

    private fun showDebugOverlay() {
        if (!BuildConfig.DEBUG || debugOverlay != null) return
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        debugOverlay = TextView(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFF00FF00.toInt()) // Debug green
            textSize = 12f
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
        debugOverlay?.let {
            windowManager?.removeView(it)
            debugOverlay = null
        }
    }

    private fun updateDebugOverlay(text: String) {
        debugOverlay?.text = text
    }

    private fun collectDebugInfo(node: AccessibilityNodeInfo?, ids: MutableSet<String>, descs: MutableSet<String>, depth: Int) {
        if (node == null || depth > 10 || ids.size > 20) return
        
        node.viewIdResourceName?.let { ids.add(it.removePrefix("com.instagram.android:id/")) }
        node.contentDescription?.let { descs.add(it.toString()) }
        
        for (i in 0 until node.childCount) {
            collectDebugInfo(node.getChild(i), ids, descs, depth + 1)
        }
    }

    private fun checkIsReelsActive(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        
        // Resource IDs commonly used for Instagram Reels / Clips
        val reelsIds = listOf(
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/reel_viewer_container",
            "com.instagram.android:id/clips_viewer_video_container"
        )
        
        var detected = false
        for (id in reelsIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (nodes != null && nodes.isNotEmpty()) {
                detected = true
                nodes.forEach { it.recycle() }
                break
            }
        }
        
        return detected
    }

    private fun startBackgroundTracking() {
        if (trackerJob != null && trackerJob?.isActive == true) return

        trackerJob = scope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val dao = db.dao()

            while (isInstagramActiveGroup) {
                val now = System.currentTimeMillis()
                // If user hasn't interacted with Instagram for more than 4 seconds, mark inactive
                if (now - lastInstagramInteractTime > 4000) {
                    isInstagramActiveGroup = false
                    break
                }

                // Get today's usage from DB
                var usage = dao.getDailyUsageDirect(dateStr)
                if (usage == null) {
                    usage = DailyUsage(
                        date = dateStr,
                        limitSeconds = 30, // 30s default
                        consumedSeconds = 0,
                        extensionsCount = 0
                    )
                    dao.insertDailyUsage(usage)
                }

                val secondsRemaining = usage.limitSeconds - usage.consumedSeconds

                if (secondsRemaining <= 0) {
                    // Lock and launch Main Bloocking Screen!
                    launchBlockingScreen()
                    isInstagramActiveGroup = false
                    break
                } else {
                    // Spend 1 second of quota
                    val updated = usage.copy(consumedSeconds = usage.consumedSeconds + 1)
                    dao.insertDailyUsage(updated)
                }

                delay(1000)
            }
        }
    }

    private fun stopBackgroundTracking() {
        trackerJob?.cancel()
        trackerJob = null
    }

    private fun launchBlockingScreen() {
        Log.d("ScrollSentry", "Instagram Scroll limit reached! Intercepting foreground block...")
        val blockIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("limit_reached", true)
        }
        startActivity(blockIntent)
    }

    override fun onInterrupt() {
        isInstagramActiveGroup = false
        stopBackgroundTracking()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}

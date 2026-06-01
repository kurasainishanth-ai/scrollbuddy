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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScrollSentryAccessibilityService : AccessibilityService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var trackerJob: Job? = null

    private var lastInstagramInteractTime: Long = 0
    private var isInstagramActiveGroup = false

    private val dateStr: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        if (packageName == "com.instagram.android") {
            lastInstagramInteractTime = System.currentTimeMillis()
            if (!isInstagramActiveGroup) {
                isInstagramActiveGroup = true
                startBackgroundTracking()
            }
        } else {
            // User left Instagram
            isInstagramActiveGroup = false
            stopBackgroundTracking()
        }
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

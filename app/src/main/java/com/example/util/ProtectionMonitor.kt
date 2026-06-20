package com.example.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.local.ProtectionEvent
import com.example.data.network.ApprovalApi
import com.example.service.ScrollSentryAccessibilityService

object ProtectionMonitor {
    private const val DUPLICATE_WINDOW_MS = 5 * 60 * 1000L

    suspend fun checkProtectionState(context: Context) {
        if (!isAccessibilityServiceEnabled(context)) {
            recordProtectionLoss(context, "Accessibility service disabled")
        }

        if (!hasNotificationPermission(context)) {
            recordProtectionLoss(context, "Notification permission revoked")
        }
    }

    suspend fun recordProtectionLoss(context: Context, reason: String) {
        val appContext = context.applicationContext
        val dao = AppDatabase.getDatabase(appContext).dao()
        val now = System.currentTimeMillis()
        val recent = dao.getLatestProtectionEventForReason(reason)
        if (recent != null && now - recent.timestamp < DUPLICATE_WINDOW_MS) {
            return
        }

        val eventId = dao.insertProtectionEvent(
            ProtectionEvent(
                timestamp = now,
                reason = reason
            )
        )

        NotificationHelper.showProtectionWarningNotification(appContext, reason)

        val user = dao.getUserAccount() ?: return
        val friends = dao.getFriendsDirect().map { it.username }
        try {
            ApprovalApi().reportProtectionLoss(
                username = user.username,
                reason = reason,
                timestamp = now,
                friends = friends
            )
            dao.markProtectionEventBackendNotified(eventId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun consumeLatestWarning(context: Context): ProtectionEvent? {
        val dao = AppDatabase.getDatabase(context.applicationContext).dao()
        val event = dao.getLatestUnacknowledgedProtectionEvent() ?: return null
        dao.markProtectionEventAcknowledged(event.id)
        return event
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponentName = ComponentName(context, ScrollSentryAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val enabledService = ComponentName.unflattenFromString(colonSplitter.next())
            if (enabledService == expectedComponentName) return true
        }
        return false
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }
}

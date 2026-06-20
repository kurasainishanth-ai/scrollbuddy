package com.example.service

import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.network.ApprovalApi
import com.example.util.NotificationHelper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScrollBuddyFCMService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("ScrollBuddyFCM", "FCM token refreshed")
        scope.launch {
            try {
                val dao = AppDatabase.getDatabase(applicationContext).dao()
                val user = dao.getUserAccount() ?: return@launch
                ApprovalApi().registerFcmToken(user.username, token)
                Log.d("ScrollBuddyFCM", "FCM token registered for ${user.username}")
            } catch (e: Exception) {
                Log.e("ScrollBuddyFCM", "Failed to register FCM token", e)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val type = message.data["type"]
        val username = message.data["username"]

        when (type) {
            "PROTECTION_LOST" -> {
                NotificationHelper.showProtectionAlertNotification(
                    this,
                    "protection_lost_${username}_${System.currentTimeMillis()}",
                    username ?: "A friend",
                    "ScrollBuddy protection may have been disabled"
                )
            }
        }
    }
}

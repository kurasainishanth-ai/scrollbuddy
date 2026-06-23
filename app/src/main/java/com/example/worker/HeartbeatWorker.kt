package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.AppDatabase
import com.example.data.network.ApprovalApi
import com.example.util.ProtectionMonitor

class HeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val dao = AppDatabase.getDatabase(applicationContext).dao()
            val user = dao.getUserAccount() ?: return Result.success()
            val friends = dao.getFriendsDirect().map { it.username }
            val isProtected = ProtectionMonitor.isAccessibilityServiceEnabled(applicationContext)
            ApprovalApi().sendHeartbeat(user.username, isProtected, friends)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

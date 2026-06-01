package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "scroll_requests")
data class ScrollRequest(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String, // YYYY-MM-DD
    val friendId: Long,
    val friendName: String,
    val friendEmoji: String,
    val status: String, // "PENDING", "APPROVED", "REJECTED"
    val timestamp: Long = System.currentTimeMillis(),
    val extraSeconds: Int = 300, // default extra 5 minutes (300 seconds)
    val uuid: String = UUID.randomUUID().toString(),
    val serverRequestId: String? = null,
    val approvalUrl: String? = null,
)

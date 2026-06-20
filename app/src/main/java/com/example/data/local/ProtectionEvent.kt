package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "protection_events")
data class ProtectionEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val reason: String,
    val notifiedBackend: Boolean = false,
    val acknowledged: Boolean = false
)

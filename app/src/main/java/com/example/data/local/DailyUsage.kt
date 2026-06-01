package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_usage")
data class DailyUsage(
    @PrimaryKey val date: String, // YYYY-MM-DD
    val limitSeconds: Int, // Limit count for the day
    val consumedSeconds: Int, // Accumulated scroll time today
    val extensionsCount: Int = 0 // How many 5-min extensions granted
)

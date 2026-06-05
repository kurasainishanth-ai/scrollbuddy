package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "friends")
data class Friend(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phoneNumber: String = "",
    val avatarEmoji: String,
    val isAutoAccept: Boolean = true,
    val acceptDelaySec: Int = 5
)

package com.example.lifelinemesh.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val text: String,
    val isFromMe: Boolean,
    val senderName: String,
    val senderPhone: String,
    val latitude: Double?,
    val longitude: Double?,
    val timestamp: Long,
    // Priority levels: 3 = CRITICAL, 2 = WARNING, 1 = NORMAL
    val priority: Int
)
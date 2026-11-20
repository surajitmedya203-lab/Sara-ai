package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personal_tasks")
data class PersonalTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val dueLabel: String = "Today",
    val priority: String = "NORMAL", // HIGH, NORMAL, LOW
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "memory_notes")
data class MemoryNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyTopic: String,
    val content: String,
    val category: String = "Personal",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "command_logs")
data class CommandLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val spokenText: String,
    val intentCategory: String,
    val actionSummary: String,
    val saraReply: String,
    val status: String = "EXECUTED", // EXECUTED, SAMPLE_AI, DENIED, INFO
    val voiceVerified: Boolean = false,
    val confidenceScore: Int = 100,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val triggerPhrase: String,
    val stepsSummary: String,
    val actionTags: String, // comma-separated: "TIME_BRIEF,TASK_BRIEF,FLASHLIGHT_OFF"
    val isEnabled: Boolean = true,
    val lastRunAt: Long? = null
)

package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_clips")
data class DownloadedClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val platformName: String,
    val sourceUrl: String,
    val title: String,
    val author: String,
    val durationSec: Int,
    val thumbnailUrl: String?,
    val localFilePath: String,
    val contentUriString: String,
    val fileSizeBytes: Long,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "prompt_blueprints")
data class PromptBlueprintEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val videoTitle: String,
    val sourceUriString: String,
    val targetGeneratorName: String,
    val geminiModelUsed: String,
    val masterVideoPrompt: String,
    val payloadJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

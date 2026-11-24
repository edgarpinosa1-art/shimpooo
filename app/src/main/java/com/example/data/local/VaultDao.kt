package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM downloaded_clips ORDER BY createdAt DESC")
    fun getAllDownloadedClips(): Flow<List<DownloadedClipEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloadedClip(clip: DownloadedClipEntity): Long

    @Query("DELETE FROM downloaded_clips WHERE id = :id")
    suspend fun deleteDownloadedClip(id: Long)

    @Query("SELECT * FROM prompt_blueprints ORDER BY createdAt DESC")
    fun getAllPromptBlueprints(): Flow<List<PromptBlueprintEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPromptBlueprint(blueprint: PromptBlueprintEntity): Long

    @Query("DELETE FROM prompt_blueprints WHERE id = :id")
    suspend fun deletePromptBlueprint(id: Long)

    @Query("SELECT * FROM video_transcripts ORDER BY createdAt DESC")
    fun getAllVideoTranscripts(): Flow<List<VideoTranscriptEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideoTranscript(transcript: VideoTranscriptEntity): Long

    @Query("DELETE FROM video_transcripts WHERE id = :id")
    suspend fun deleteVideoTranscript(id: Long)
}

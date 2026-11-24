package com.example.data.local

import com.example.data.model.DownloadedVideoResult
import com.example.data.model.ExtractedVideoInfo
import com.example.data.model.PromptBlueprint
import com.example.data.model.VideoTranscript
import com.example.data.network.PromptBlueprintSerializer
import com.example.data.network.TranscriptSerializer
import kotlinx.coroutines.flow.Flow

class VaultRepository(private val dao: VaultDao) {

    val downloadedClips: Flow<List<DownloadedClipEntity>> = dao.getAllDownloadedClips()
    val promptBlueprints: Flow<List<PromptBlueprintEntity>> = dao.getAllPromptBlueprints()
    val videoTranscripts: Flow<List<VideoTranscriptEntity>> = dao.getAllVideoTranscripts()

    suspend fun saveDownloadedClip(
        info: ExtractedVideoInfo,
        result: DownloadedVideoResult
    ): Long {
        val entity = DownloadedClipEntity(
            platformName = info.platform.displayName,
            sourceUrl = info.sourceUrl,
            title = info.title,
            author = info.author,
            durationSec = info.durationSec,
            thumbnailUrl = info.thumbnailUrl,
            localFilePath = result.localFilePath,
            contentUriString = result.contentUriString,
            fileSizeBytes = result.fileSizeBytes
        )
        return dao.insertDownloadedClip(entity)
    }

    suspend fun savePromptBlueprint(blueprint: PromptBlueprint): Long {
        val json = PromptBlueprintSerializer.toJson(blueprint)
        val entity = PromptBlueprintEntity(
            videoTitle = blueprint.videoTitle,
            sourceUriString = blueprint.sourceUriString,
            targetGeneratorName = blueprint.targetGenerator.name,
            geminiModelUsed = blueprint.geminiModelUsed,
            masterVideoPrompt = blueprint.masterVideoPrompt,
            payloadJson = json,
            createdAt = blueprint.createdAt
        )
        return dao.insertPromptBlueprint(entity)
    }

    suspend fun saveVideoTranscript(transcript: VideoTranscript): Long {
        val json = TranscriptSerializer.toJson(transcript)
        val preview = transcript.segments.take(2).joinToString(" · ") {
            "[${it.startTime}] ${it.text}"
        }.ifBlank { transcript.fullPlainTranscript.take(160) }
        val entity = VideoTranscriptEntity(
            videoTitle = transcript.videoTitle,
            sourceUriString = transcript.sourceUriString,
            geminiModelUsed = transcript.geminiModelUsed,
            languageModeName = transcript.languageMode.name,
            detectedLanguage = transcript.detectedLanguage,
            previewSnippet = preview,
            segmentCount = transcript.segments.size,
            payloadJson = json,
            createdAt = transcript.createdAt
        )
        return dao.insertVideoTranscript(entity)
    }

    suspend fun deleteClip(id: Long) = dao.deleteDownloadedClip(id)

    suspend fun deleteBlueprint(id: Long) = dao.deletePromptBlueprint(id)

    suspend fun deleteTranscript(id: Long) = dao.deleteVideoTranscript(id)
}

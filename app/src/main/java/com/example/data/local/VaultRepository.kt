package com.example.data.local

import com.example.data.model.DownloadedVideoResult
import com.example.data.model.ExtractedVideoInfo
import com.example.data.model.PromptBlueprint
import com.example.data.network.PromptBlueprintSerializer
import kotlinx.coroutines.flow.Flow

class VaultRepository(private val dao: VaultDao) {

    val downloadedClips: Flow<List<DownloadedClipEntity>> = dao.getAllDownloadedClips()
    val promptBlueprints: Flow<List<PromptBlueprintEntity>> = dao.getAllPromptBlueprints()

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

    suspend fun deleteClip(id: Long) = dao.deleteDownloadedClip(id)

    suspend fun deleteBlueprint(id: Long) = dao.deletePromptBlueprint(id)
}

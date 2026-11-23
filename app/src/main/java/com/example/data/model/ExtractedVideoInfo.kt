package com.example.data.model

data class ExtractedVideoInfo(
    val platform: SocialPlatform,
    val sourceUrl: String,
    val resolvedPageUrl: String,
    val title: String,
    val author: String,
    val durationSec: Int,
    val thumbnailUrl: String?,
    val hdDownloadUrl: String?,
    val sdDownloadUrl: String?,
    val audioUrl: String?,
    val extractionMethod: String
) {
    val bestDownloadUrl: String?
        get() = hdDownloadUrl?.takeIf { it.isNotBlank() } ?: sdDownloadUrl?.takeIf { it.isNotBlank() }

    val hasHdQuality: Boolean
        get() = !hdDownloadUrl.isNullOrBlank()
}

data class DownloadedVideoResult(
    val localFilePath: String,
    val contentUriString: String,
    val gallerySaved: Boolean,
    val fileSizeBytes: Long,
    val fileName: String
)

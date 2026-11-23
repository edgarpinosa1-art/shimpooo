package com.example.data.network

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.model.LocalVideoInspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class VideoFrameExtractor(private val context: Context) {

    suspend fun inspectVideo(uri: Uri): LocalVideoInspection = withContext(Dispatchers.IO) {
        val displayName = resolveDisplayName(uri)
        val fileSize = resolveFileSize(uri)
        val mimeType = context.contentResolver.getType(uri) ?: "video/mp4"

        val retriever = MediaMetadataRetriever()
        var durationMs = 0L
        var width = 0
        var height = 0
        val keyframePaths = mutableListOf<String>()

        try {
            retriever.setDataSource(context, uri)
            durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0

            val framesDir = File(context.cacheDir, "frames").apply {
                if (exists()) {
                    listFiles()?.forEach { it.delete() }
                } else {
                    mkdirs()
                }
            }

            val sampleCount = 6
            val safeDurationUs = (durationMs.coerceAtLeast(1000L)) * 1000L
            for (i in 0 until sampleCount) {
                val timeUs = if (sampleCount == 1) 0L
                else ((safeDurationUs * (i + 0.5)) / sampleCount).toLong().coerceAtLeast(0L)

                val bitmap = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: continue

                val scaled = scaleBitmapDown(bitmap, 768)
                val outFile = File(framesDir, "frame_${System.currentTimeMillis()}_$i.jpg")
                FileOutputStream(outFile).use { fos ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                }
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                keyframePaths.add(outFile.absolutePath)
            }
        } finally {
            runCatching { retriever.release() }
        }

        LocalVideoInspection(
            uriString = uri.toString(),
            displayName = displayName,
            durationMs = durationMs,
            width = width,
            height = height,
            fileSizeBytes = fileSize,
            mimeType = mimeType,
            keyframePaths = keyframePaths
        )
    }

    private fun scaleBitmapDown(source: Bitmap, maxDimension: Int): Bitmap {
        val maxSide = maxOf(source.width, source.height)
        if (maxSide <= maxDimension) return source
        val ratio = maxDimension.toFloat() / maxSide.toFloat()
        val targetW = (source.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "file") {
            return File(uri.path ?: "").name.ifBlank { "Selected Video" }
        }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "video_clip.mp4"
    }

    private fun resolveFileSize(uri: Uri): Long {
        if (uri.scheme == "file") {
            return File(uri.path ?: "").length()
        }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) return cursor.getLong(idx)
                }
            }
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        }.getOrDefault(0L)
    }

    suspend fun saveKeyframesToGallery(framePaths: List<String>): Int = withContext(Dispatchers.IO) {
        var savedCount = 0
        val resolver = context.contentResolver
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        framePaths.forEachIndexed { index, path ->
            val src = File(path)
            if (!src.exists() || src.length() == 0L) return@forEachIndexed
            runCatching {
                val fileName = "ReelForge_Frame_${System.currentTimeMillis()}_${index + 1}.jpg"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        put(
                            android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                            "${android.os.Environment.DIRECTORY_PICTURES}/ReelForge"
                        )
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = resolver.insert(collection, values) ?: return@forEachIndexed
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { input -> input.copyTo(out) }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    resolver.update(
                        uri,
                        android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                        },
                        null,
                        null
                    )
                }
                savedCount++
            }
        }
        savedCount
    }
}

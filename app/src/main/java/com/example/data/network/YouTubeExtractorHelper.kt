package com.example.data.network

import com.example.data.model.ExtractedVideoInfo
import com.example.data.model.SocialPlatform
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object YouTubeExtractorHelper {

    fun extractYouTubeVideoId(rawUrl: String): String? {
        val patterns = listOf(
            Regex("""(?:youtu\.be/)([A-Za-z0-9_-]{11})"""),
            Regex("""(?:youtube\.com/shorts/)([A-Za-z0-9_-]{11})"""),
            Regex("""(?:youtube\.com/live/)([A-Za-z0-9_-]{11})"""),
            Regex("""(?:youtube\.com/embed/)([A-Za-z0-9_-]{11})"""),
            Regex("""[?&]v=([A-Za-z0-9_-]{11})""")
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(rawUrl)?.groupValues?.getOrNull(1)
        }
    }

    fun extract(
        client: OkHttpClient,
        resolvedUrl: String,
        originalUrl: String
    ): ExtractedVideoInfo? {
        val videoId = extractYouTubeVideoId(resolvedUrl)
            ?: extractYouTubeVideoId(originalUrl)
            ?: return null

        val clientConfigs = listOf(
            InnerTubeClientSpec(
                label = "YouTube Android",
                clientName = "ANDROID",
                clientVersion = "20.10.38",
                userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 15) gzip",
                extraClientFields = mapOf("androidSdkVersion" to 35, "hl" to "en", "gl" to "US")
            ),
            InnerTubeClientSpec(
                label = "YouTube iOS",
                clientName = "IOS",
                clientVersion = "20.10.4",
                userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3 like Mac OS X)",
                extraClientFields = mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "hl" to "en", "gl" to "US")
            )
        )

        for (spec in clientConfigs) {
            val extracted = runCatching {
                val clientObj = JSONObject().apply {
                    put("clientName", spec.clientName)
                    put("clientVersion", spec.clientVersion)
                    spec.extraClientFields.forEach { (k, v) -> put(k, v) }
                }
                val payload = JSONObject().apply {
                    put("videoId", videoId)
                    put("context", JSONObject().apply { put("client", clientObj) })
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }.toString()

                val req = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .header("User-Agent", spec.userAgent)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    parsePlayerJson(
                        playerJson = JSONObject(body),
                        videoId = videoId,
                        originalUrl = originalUrl,
                        resolvedUrl = resolvedUrl,
                        methodLabel = spec.label
                    )
                }
            }.getOrNull()

            if (extracted != null && !extracted.bestDownloadUrl.isNullOrBlank()) {
                return extracted
            }
        }

        // Strategy 2: Watch page HTML ytInitialPlayerResponse fallback
        return runCatching {
            val watchUrl = "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1"
            val req = Request.Builder()
                .url(watchUrl)
                .header("User-Agent", NetworkClient.DESKTOP_USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                val marker = "var ytInitialPlayerResponse = "
                val startIdx = html.indexOf(marker)
                if (startIdx == -1) return@use null
                val jsonSlice = html.substring(startIdx + marker.length)
                val endIdx = jsonSlice.indexOf(";</script>")
                val rawJson = if (endIdx > 0) jsonSlice.substring(0, endIdx) else jsonSlice
                parsePlayerJson(
                    playerJson = JSONObject(rawJson),
                    videoId = videoId,
                    originalUrl = originalUrl,
                    resolvedUrl = resolvedUrl,
                    methodLabel = "YouTube Web"
                )
            }
        }.getOrNull()
    }

    private fun parsePlayerJson(
        playerJson: JSONObject,
        videoId: String,
        originalUrl: String,
        resolvedUrl: String,
        methodLabel: String
    ): ExtractedVideoInfo? {
        val status = playerJson.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        if (status.isNotBlank() && status != "OK") return null

        val streamingData = playerJson.optJSONObject("streamingData") ?: return null
        val muxedFormats = streamingData.optJSONArray("formats") ?: JSONArray()
        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()

        val directMuxed = mutableListOf<Pair<Int, String>>()
        for (i in 0 until muxedFormats.length()) {
            val item = muxedFormats.optJSONObject(i) ?: continue
            val directUrl = item.optString("url").takeIf { it.startsWith("http") } ?: continue
            val mime = item.optString("mimeType")
            if (mime.contains("video/mp4") || mime.contains("video/")) {
                val height = item.optInt("height", 360)
                directMuxed.add(height to directUrl)
            }
        }
        val sortedMuxed = directMuxed.sortedByDescending { it.first }
        val bestMuxedHeight = sortedMuxed.firstOrNull()?.first ?: 0
        val bestMuxedUrl = sortedMuxed.firstOrNull()?.second
        val fallbackMuxedUrl = sortedMuxed.lastOrNull()?.second

        // Adaptive H.264 (avc1) MP4 streams up to 1080p for MediaMuxer compatibility
        val adaptiveAvcVideo = mutableListOf<Pair<Int, String>>()
        val audioCandidates = mutableListOf<Pair<Int, String>>()

        for (i in 0 until adaptiveFormats.length()) {
            val item = adaptiveFormats.optJSONObject(i) ?: continue
            val directUrl = item.optString("url").takeIf { it.startsWith("http") } ?: continue
            val mime = item.optString("mimeType")
            when {
                mime.contains("video/mp4") && mime.contains("avc1", ignoreCase = true) -> {
                    val height = item.optInt("height", 0)
                    if (height in 360..1080) {
                        adaptiveAvcVideo.add(height to directUrl)
                    }
                }
                mime.contains("audio/mp4") -> {
                    audioCandidates.add(item.optInt("bitrate", 0) to directUrl)
                }
            }
        }

        val bestAdaptive = adaptiveAvcVideo.maxByOrNull { it.first }
        val bestAudioUrl = audioCandidates.maxByOrNull { it.first }?.second

        val useAdaptiveMux = bestAdaptive != null &&
            bestAudioUrl != null &&
            bestAdaptive.first > bestMuxedHeight

        val hdUrl: String?
        val sdUrl: String?
        val methodDisplay: String
        val needsMux: Boolean

        if (useAdaptiveMux && bestAdaptive != null) {
            hdUrl = bestAdaptive.second
            sdUrl = bestMuxedUrl ?: bestAdaptive.second
            methodDisplay = "$methodLabel · ${bestAdaptive.first}p Muxed"
            needsMux = true
        } else {
            hdUrl = bestMuxedUrl ?: bestAudioUrl
            sdUrl = fallbackMuxedUrl ?: bestMuxedUrl ?: bestAudioUrl
            methodDisplay = if (bestMuxedHeight > 0) "$methodLabel · ${bestMuxedHeight}p" else methodLabel
            needsMux = false
        }

        if (hdUrl.isNullOrBlank()) return null

        val details = playerJson.optJSONObject("videoDetails")
        val title = details?.optString("title")?.ifBlank { null } ?: "YouTube Video"
        val author = details?.optString("author")?.ifBlank { null } ?: "YouTube Creator"
        val durationSec = details?.optString("lengthSeconds")?.toIntOrNull() ?: 0
        val thumbs = details?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumbUrl = if (thumbs != null && thumbs.length() > 0) {
            thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")
        } else {
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        }

        return ExtractedVideoInfo(
            platform = SocialPlatform.YOUTUBE,
            sourceUrl = originalUrl,
            resolvedPageUrl = resolvedUrl,
            title = title,
            author = author,
            durationSec = durationSec,
            thumbnailUrl = thumbUrl,
            hdDownloadUrl = hdUrl,
            sdDownloadUrl = sdUrl,
            audioUrl = bestAudioUrl,
            extractionMethod = methodDisplay,
            requiresAudioMux = needsMux
        )
    }

    private data class InnerTubeClientSpec(
        val label: String,
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val extraClientFields: Map<String, Any>
    )
}

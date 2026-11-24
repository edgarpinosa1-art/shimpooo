package com.example.data.network

import com.example.data.model.ExtractedVideoInfo
import com.example.data.model.SocialPlatform
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

object PinterestExtractorHelper {

    private val moshi: Moshi by lazy { Moshi.Builder().build() }

    fun extractPinId(rawUrl: String): String? {
        val patterns = listOf(
            Regex("""pinterest\.[^/]+/pin/(?:[^/?#]*--)?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""/pin/(?:[^/?#]*--)?(\d+)""", RegexOption.IGNORE_CASE)
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
        val pinId = extractPinId(resolvedUrl) ?: extractPinId(originalUrl)

        // Strategy 1: Pinterest PinResource JSON endpoint (fast, clean HD video_list)
        if (!pinId.isNullOrBlank()) {
            val apiInfo = runCatching {
                val dataParam = URLEncoder.encode(
                    """{"options":{"id":"$pinId","field_set_key":"detailed"}}""",
                    "UTF-8"
                )
                val sourceParam = URLEncoder.encode("/pin/$pinId/", "UTF-8")
                val apiUrl = "https://www.pinterest.com/resource/PinResource/get/?source_url=$sourceParam&data=$dataParam"

                val req = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", NetworkClient.DESKTOP_USER_AGENT)
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("X-Pinterest-AppState", "active")
                    .header("Referer", "https://www.pinterest.com/pin/$pinId/")
                    .get()
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    parsePinResourceJson(
                        jsonString = body,
                        originalUrl = originalUrl,
                        resolvedUrl = resolvedUrl
                    )
                }
            }.getOrNull()

            if (apiInfo != null && !apiInfo.bestDownloadUrl.isNullOrBlank()) {
                return apiInfo
            }
        }

        // Strategy 2: Direct pin page HTML (__PWS_DATA__, JSON-LD, v.pinimg.com MP4s, og:video)
        val userAgents = listOf(
            NetworkClient.DESKTOP_USER_AGENT,
            NetworkClient.MOBILE_USER_AGENT
        )
        for (ua in userAgents) {
            val htmlInfo = runCatching {
                val req = Request.Builder()
                    .url(resolvedUrl)
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .get()
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val html = resp.body?.string().orEmpty()
                    parsePinterestHtml(html, originalUrl, resolvedUrl)
                }
            }.getOrNull()

            if (htmlInfo != null && !htmlInfo.bestDownloadUrl.isNullOrBlank()) {
                return htmlInfo
            }
        }

        return null
    }

    fun parsePinResourceJson(
        jsonString: String,
        originalUrl: String,
        resolvedUrl: String
    ): ExtractedVideoInfo? {
        val root = parseJsonToMap(jsonString) ?: return null
        val data = root.obj("resource_response")?.obj("data") ?: return null

        val directVideoList = data.obj("videos")?.obj("video_list")
        val storyVideoList = findStoryPinVideoList(data.obj("story_pin_data"))
        val videoList = directVideoList ?: storyVideoList

        val candidates = collectMp4Candidates(videoList)
        val hdUrl = candidates.firstOrNull()?.second
            ?: findFirstMp4InTree(data)
            ?: return null
        val sdUrl = candidates.lastOrNull()?.second ?: hdUrl

        val bestEntry = videoList?.let { pickBestVideoEntry(it) }
        val durationMs = bestEntry?.longVal("duration") ?: 0L
        val durationSec = if (durationMs > 1000L) (durationMs / 1000L).toInt() else durationMs.toInt()

        val title = listOf(
            data.str("grid_title"),
            data.str("title"),
            data.str("seo_title"),
            data.str("description")
        ).firstOrNull { !it.isNullOrBlank() && it != "null" }
            ?.take(140)
            ?: "Pinterest Video Pin"

        val pinner = data.obj("pinner") ?: data.obj("native_creator")
        val authorRaw = listOf(
            pinner?.str("full_name"),
            pinner?.str("username")
        ).firstOrNull { !it.isNullOrBlank() && it != "null" } ?: "Pinterest Creator"
        val author = if (authorRaw.contains(" ") || authorRaw.startsWith("@") || authorRaw == "Pinterest Creator") {
            authorRaw
        } else {
            "@$authorRaw"
        }

        val images = data.obj("images")
        val thumbUrl = listOf(
            bestEntry?.str("thumbnail"),
            images?.obj("orig")?.str("url"),
            images?.obj("736x")?.str("url"),
            images?.obj("474x")?.str("url")
        ).firstOrNull { !it.isNullOrBlank() && it.startsWith("http") }

        return ExtractedVideoInfo(
            platform = SocialPlatform.PINTEREST,
            sourceUrl = originalUrl,
            resolvedPageUrl = resolvedUrl,
            title = title,
            author = author,
            durationSec = durationSec.coerceAtLeast(0),
            thumbnailUrl = thumbUrl,
            hdDownloadUrl = hdUrl,
            sdDownloadUrl = sdUrl,
            audioUrl = null,
            extractionMethod = if (storyVideoList != null && directVideoList == null) {
                "Pinterest Idea Pin HD"
            } else {
                "Pinterest Pin API HD"
            }
        )
    }

    fun parsePinterestHtml(
        html: String,
        originalUrl: String,
        resolvedUrl: String
    ): ExtractedVideoInfo? {
        // 1) Try __PWS_DATA__ embedded JSON
        val pwsMatch = Regex(
            """<script[^>]*id=["']__PWS_DATA__["'][^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE
        ).find(html)
        val pwsJsonStr = pwsMatch?.groupValues?.getOrNull(1)
        if (!pwsJsonStr.isNullOrBlank()) {
            val pwsRoot = parseJsonToMap(pwsJsonStr)
            if (pwsRoot != null) {
                val videoList = findMapRecursive(pwsRoot, "video_list")
                val candidates = collectMp4Candidates(videoList)
                val hdUrl = candidates.firstOrNull()?.second
                    ?: findFirstMp4InTree(pwsRoot)
                if (!hdUrl.isNullOrBlank()) {
                    val sdUrl = candidates.lastOrNull()?.second ?: hdUrl
                    val entry = videoList?.let { pickBestVideoEntry(it) }
                    val durMs = entry?.longVal("duration") ?: 0L
                    val durSec = if (durMs > 1000L) (durMs / 1000L).toInt() else durMs.toInt()
                    val title = extractMeta(html, "og:title")
                        ?: extractHtmlTitle(html)
                        ?: "Pinterest Video Pin"
                    val thumb = entry?.str("thumbnail")?.takeIf { it.startsWith("http") }
                        ?: extractMeta(html, "og:image")

                    return ExtractedVideoInfo(
                        platform = SocialPlatform.PINTEREST,
                        sourceUrl = originalUrl,
                        resolvedPageUrl = resolvedUrl,
                        title = title.removeSuffix(" | Pinterest").take(140).ifBlank { "Pinterest Video Pin" },
                        author = "Pinterest Creator",
                        durationSec = durSec.coerceAtLeast(0),
                        thumbnailUrl = thumb,
                        hdDownloadUrl = hdUrl,
                        sdDownloadUrl = sdUrl,
                        audioUrl = null,
                        extractionMethod = "Pinterest PWS HD"
                    )
                }
            }
        }

        // 2) Scan embedded pinimg.com MP4 URLs (unescaped or JSON-escaped) + og:video meta tags
        val mp4Regex = Regex("""https?:\\?/\\?/v[0-9]*\.pinimg\.com\\?/videos\\?/[^\s"'<>]+?\.mp4""")
        val matchedMp4s = mp4Regex.findAll(html)
            .mapNotNull { unescapeUrl(it.value) }
            .filter { it.startsWith("http") && !it.endsWith(".m3u8", ignoreCase = true) }
            .distinct()
            .toList()

        val sortedMp4s = matchedMp4s.sortedByDescending { scoreMp4UrlQuality(it) }
        val ogVideo = extractMeta(html, "og:video:secure_url")
            ?: extractMeta(html, "og:video")

        val hdUrl = sortedMp4s.firstOrNull()
            ?: ogVideo?.takeIf { it.startsWith("http") && !it.contains(".m3u8", ignoreCase = true) }
            ?: return null
        val sdUrl = sortedMp4s.lastOrNull() ?: hdUrl

        val title = extractMeta(html, "og:title")
            ?: extractHtmlTitle(html)
            ?: "Pinterest Video Pin"
        val thumb = extractMeta(html, "og:image")

        return ExtractedVideoInfo(
            platform = SocialPlatform.PINTEREST,
            sourceUrl = originalUrl,
            resolvedPageUrl = resolvedUrl,
            title = title.removeSuffix(" | Pinterest").take(140).ifBlank { "Pinterest Video Pin" },
            author = "Pinterest Creator",
            durationSec = 0,
            thumbnailUrl = thumb,
            hdDownloadUrl = hdUrl,
            sdDownloadUrl = sdUrl,
            audioUrl = null,
            extractionMethod = "Pinterest Direct MP4"
        )
    }

    private fun parseJsonToMap(jsonString: String): Map<*, *>? {
        return runCatching {
            moshi.adapter(Map::class.java).fromJson(jsonString)
        }.getOrNull()
    }

    private fun Map<*, *>.obj(key: String): Map<*, *>? = this[key] as? Map<*, *>

    private fun Map<*, *>.arr(key: String): List<*>? = this[key] as? List<*>

    private fun Map<*, *>.str(key: String): String? = this[key] as? String

    private fun Map<*, *>.intVal(key: String): Int = (this[key] as? Number)?.toInt() ?: 0

    private fun Map<*, *>.longVal(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L

    private fun findStoryPinVideoList(storyPinData: Map<*, *>?): Map<*, *>? {
        val pages = storyPinData?.arr("pages") ?: return null
        for (page in pages) {
            val pageMap = page as? Map<*, *> ?: continue
            val blocks = pageMap.arr("blocks") ?: continue
            for (block in blocks) {
                val blockMap = block as? Map<*, *> ?: continue
                val videoList = blockMap.obj("video")?.obj("video_list")
                if (!videoList.isNullOrEmpty()) {
                    return videoList
                }
            }
        }
        return null
    }

    private fun findMapRecursive(node: Any?, targetKey: String, depth: Int = 0): Map<*, *>? {
        if (node == null || depth > 10) return null
        when (node) {
            is Map<*, *> -> {
                val direct = node[targetKey] as? Map<*, *>
                if (!direct.isNullOrEmpty()) return direct
                for (value in node.values) {
                    val found = findMapRecursive(value, targetKey, depth + 1)
                    if (found != null) return found
                }
            }
            is List<*> -> {
                for (item in node) {
                    val found = findMapRecursive(item, targetKey, depth + 1)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun findFirstMp4InTree(node: Any?, depth: Int = 0): String? {
        if (node == null || depth > 10) return null
        when (node) {
            is Map<*, *> -> {
                for (value in node.values) {
                    if (value is String && value.startsWith("http") && value.contains(".mp4", ignoreCase = true)) {
                        return value
                    }
                    val nested = findFirstMp4InTree(value, depth + 1)
                    if (nested != null) return nested
                }
            }
            is List<*> -> {
                for (item in node) {
                    val nested = findFirstMp4InTree(item, depth + 1)
                    if (nested != null) return nested
                }
            }
        }
        return null
    }

    private fun collectMp4Candidates(videoList: Map<*, *>?): List<Pair<Int, String>> {
        if (videoList == null) return emptyList()
        val list = mutableListOf<Pair<Int, String>>()
        for (entryVal in videoList.values) {
            val entry = entryVal as? Map<*, *> ?: continue
            val url = entry.str("url") ?: continue
            if (url.startsWith("http") && url.contains(".mp4", ignoreCase = true)) {
                val score = maxOf(entry.intVal("height"), entry.intVal("width")) + scoreMp4UrlQuality(url)
                list.add(score to url)
            }
        }
        return list.sortedByDescending { it.first }
    }

    private fun pickBestVideoEntry(videoList: Map<*, *>): Map<*, *>? {
        val preferredKeys = listOf("V_1080P", "V_720P", "V_480P", "V_360P", "V_EXP7", "V_EXP6", "V_EXP5")
        for (k in preferredKeys) {
            val entry = videoList.obj(k)
            val url = entry?.str("url").orEmpty()
            if (entry != null && url.contains(".mp4", ignoreCase = true)) {
                return entry
            }
        }
        for (entryVal in videoList.values) {
            val entry = entryVal as? Map<*, *> ?: continue
            if (entry.str("url").orEmpty().contains(".mp4", ignoreCase = true)) {
                return entry
            }
        }
        return null
    }

    private fun scoreMp4UrlQuality(url: String): Int {
        val lower = url.lowercase()
        return when {
            lower.contains("1080") -> 1080
            lower.contains("720p") || lower.contains("720") -> 720
            lower.contains("480p") || lower.contains("480") -> 480
            lower.contains("360p") || lower.contains("360") -> 360
            lower.contains("exp7") -> 700
            else -> 240
        }
    }

    private fun extractMeta(html: String, propertyName: String): String? {
        val escapedProp = Regex.escape(propertyName)
        val p1 = Regex(
            """<meta\s+(?:property|name)=["']$escapedProp["']\s+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val p2 = Regex(
            """<meta\s+content=["']([^"']+)["']\s+(?:property|name)=["']$escapedProp["']""",
            RegexOption.IGNORE_CASE
        )
        val raw = p1.find(html)?.groupValues?.getOrNull(1)
            ?: p2.find(html)?.groupValues?.getOrNull(1)
        return unescapeUrl(raw)
    }

    private fun extractHtmlTitle(html: String): String? {
        return Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun unescapeUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u0025", "%")
            .replace("\\u003d", "=")
            .replace("&amp;", "&")
            .trim()
    }
}

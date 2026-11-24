package com.example.data.network

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.data.model.DownloadedVideoResult
import com.example.data.model.ExtractedVideoInfo
import com.example.data.model.SocialPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "VideoExtractorRepo"

class VideoExtractorRepository(private val context: Context) {

    private val client = NetworkClient.httpClient
    private val fastDownloader = FastParallelDownloader(client)

    fun extractFirstUrl(rawInput: String): String? = Companion.extractFirstUrl(rawInput)

    companion object {
        fun extractFirstUrl(rawInput: String): String? {
            val regex = Regex("""https?://[^\s<>"'()]+""", RegexOption.IGNORE_CASE)
            return regex.find(rawInput.trim())?.value?.trimEnd('.', ',', ';', '!', '?')
        }
    }

    suspend fun extractVideoInfo(
        rawInput: String,
        customCobaltUrl: String = ""
    ): Result<ExtractedVideoInfo> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = extractFirstUrl(rawInput)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Walang valid na URL na nahanap. Mag-paste ng TikTok, Facebook, Instagram, YouTube, o Pinterest link.")
                )

            val initialPlatform = SocialPlatform.detectFromUrl(cleanUrl)
            val resolvedUrl = resolveRedirects(cleanUrl)
            val platform = SocialPlatform.detectFromUrl(resolvedUrl).let {
                if (it != SocialPlatform.UNKNOWN) it else initialPlatform
            }

            if (platform == SocialPlatform.UNKNOWN) {
                return@withContext Result.failure(
                    IllegalArgumentException("Hindi sinusuportahan ang link na ito. Gumamit ng TikTok, Facebook, Instagram, YouTube, o Pinterest URL.")
                )
            }

            if (customCobaltUrl.isNotBlank()) {
                tryCobaltInstance(customCobaltUrl, resolvedUrl, platform)?.let {
                    return@withContext Result.success(it)
                }
            }

            val extracted = when (platform) {
                SocialPlatform.TIKTOK -> extractTikTok(resolvedUrl, cleanUrl)
                SocialPlatform.FACEBOOK -> extractFacebook(resolvedUrl, cleanUrl)
                SocialPlatform.INSTAGRAM -> extractInstagram(resolvedUrl, cleanUrl)
                SocialPlatform.YOUTUBE -> YouTubeExtractorHelper.extract(client, resolvedUrl, cleanUrl)
                SocialPlatform.PINTEREST -> PinterestExtractorHelper.extract(client, resolvedUrl, cleanUrl)
                SocialPlatform.UNKNOWN -> null
            }

            if (extracted != null && extracted.bestDownloadUrl != null) {
                Result.success(extracted)
            } else {
                Result.failure(
                    IllegalStateException(
                        "Hindi ma-extract ang video mula sa ${platform.displayName}. " +
                            "Siguraduhing public ang video at tama ang link (hindi private o expired)."
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extraction error", e)
            Result.failure(e)
        }
    }

    private fun resolveRedirects(url: String): String {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", NetworkClient.DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                resp.request.url.toString()
            }
        }.getOrDefault(url)
    }

    private fun tryCobaltInstance(
        cobaltBaseUrl: String,
        targetUrl: String,
        platform: SocialPlatform
    ): ExtractedVideoInfo? {
        return runCatching {
            val endpoint = cobaltBaseUrl.trim().trimEnd('/') + "/"
            val payload = JSONObject().apply {
                put("url", targetUrl)
                put("vQuality", "1080")
                put("filenameStyle", "pretty")
            }.toString()

            val req = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val status = json.optString("status")
                val directUrl = json.optString("url")
                if ((status == "redirect" || status == "tunnel") && directUrl.startsWith("http")) {
                    ExtractedVideoInfo(
                        platform = platform,
                        sourceUrl = targetUrl,
                        resolvedPageUrl = targetUrl,
                        title = json.optString("filename", "${platform.displayName} Clip").removeSuffix(".mp4"),
                        author = platform.displayName,
                        durationSec = 0,
                        thumbnailUrl = null,
                        hdDownloadUrl = directUrl,
                        sdDownloadUrl = directUrl,
                        audioUrl = null,
                        extractionMethod = "Cobalt API"
                    )
                } else null
            }
        }.getOrNull()
    }

    // =========================================================================
    // TIKTOK (NO WATERMARK)
    // =========================================================================
    private fun extractTikTok(resolvedUrl: String, originalUrl: String): ExtractedVideoInfo? {
        // Strategy 1: TikWM HD No-Watermark API
        runCatching {
            val encoded = URLEncoder.encode(resolvedUrl, "UTF-8")
            val apiUrl = "https://www.tikwm.com/api/?url=$encoded&hd=1"
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", NetworkClient.DESKTOP_USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string().orEmpty()
                    val root = JSONObject(bodyStr)
                    if (root.optInt("code", -1) == 0) {
                        val data = root.optJSONObject("data")
                        if (data != null) {
                            val hdPlay = normalizeTikWmUrl(data.optString("hdplay"))
                            val sdPlay = normalizeTikWmUrl(data.optString("play"))
                            val cover = normalizeTikWmUrl(data.optString("cover"))
                            val musicUrl = normalizeTikWmUrl(
                                data.optJSONObject("music_info")?.optString("play")
                            )
                            val authorObj = data.optJSONObject("author")
                            val authorName = authorObj?.optString("nickname")
                                ?.takeIf { it.isNotBlank() }
                                ?: authorObj?.optString("unique_id")
                                ?: "TikTok Creator"

                            if (!hdPlay.isNullOrBlank() || !sdPlay.isNullOrBlank()) {
                                return ExtractedVideoInfo(
                                    platform = SocialPlatform.TIKTOK,
                                    sourceUrl = originalUrl,
                                    resolvedPageUrl = resolvedUrl,
                                    title = data.optString("title", "TikTok Video").ifBlank { "TikTok Video" },
                                    author = authorName,
                                    durationSec = data.optInt("duration", 0),
                                    thumbnailUrl = cover,
                                    hdDownloadUrl = hdPlay ?: sdPlay,
                                    sdDownloadUrl = sdPlay ?: hdPlay,
                                    audioUrl = musicUrl,
                                    extractionMethod = "TikWM HD No-Watermark"
                                )
                            }
                        }
                    }
                }
            }
        }

        // Strategy 2: Direct TikTok HTML Universal Data JSON
        return runCatching {
            val req = Request.Builder()
                .url(resolvedUrl)
                .header("User-Agent", NetworkClient.DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                val playAddrMatch = Regex(""""playAddr"\s*:\s*"([^"]+)"""").find(html)
                val downloadAddrMatch = Regex(""""downloadAddr"\s*:\s*"([^"]+)"""").find(html)
                val coverMatch = Regex(""""cover"\s*:\s*"([^"]+)"""").find(html)
                val descMatch = Regex(""""desc"\s*:\s*"([^"]+)"""").find(html)

                val cleanPlay = unescapeUrl(playAddrMatch?.groupValues?.getOrNull(1))
                    ?: unescapeUrl(downloadAddrMatch?.groupValues?.getOrNull(1))

                if (!cleanPlay.isNullOrBlank()) {
                    ExtractedVideoInfo(
                        platform = SocialPlatform.TIKTOK,
                        sourceUrl = originalUrl,
                        resolvedPageUrl = resolvedUrl,
                        title = unescapeText(descMatch?.groupValues?.getOrNull(1)) ?: "TikTok Video",
                        author = "TikTok Creator",
                        durationSec = 0,
                        thumbnailUrl = unescapeUrl(coverMatch?.groupValues?.getOrNull(1)),
                        hdDownloadUrl = cleanPlay,
                        sdDownloadUrl = cleanPlay,
                        audioUrl = null,
                        extractionMethod = "TikTok Direct Stream"
                    )
                } else null
            }
        }.getOrNull()
    }

    private fun normalizeTikWmUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return if (raw.startsWith("/")) "https://www.tikwm.com$raw" else raw
    }

    // =========================================================================
    // FACEBOOK (REELS / WATCH / SHARE LINKS)
    // =========================================================================
    private fun extractFacebook(resolvedUrl: String, originalUrl: String): ExtractedVideoInfo? {
        val candidateUrls = listOf(
            resolvedUrl,
            resolvedUrl.replace("www.facebook.com", "m.facebook.com")
        ).distinct()

        val userAgents = listOf(
            NetworkClient.DESKTOP_USER_AGENT,
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
        )

        for (target in candidateUrls) {
            for (ua in userAgents) {
                val parsed = runCatching {
                    val req = Request.Builder()
                        .url(target)
                        .header("User-Agent", ua)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Sec-Fetch-Dest", "document")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Site", "none")
                        .get()
                        .build()

                    client.newCall(req).execute().use { resp ->
                        val html = resp.body?.string().orEmpty()
                        parseFacebookHtml(html, originalUrl, resolvedUrl)
                    }
                }.getOrNull()

                if (parsed != null && !parsed.bestDownloadUrl.isNullOrBlank()) {
                    return parsed
                }
            }
        }

        // Strategy 2: mbasic video_redirect fallback
        return runCatching {
            val mbasicUrl = resolvedUrl
                .replace("www.facebook.com", "mbasic.facebook.com")
                .replace("m.facebook.com", "mbasic.facebook.com")

            val req = Request.Builder()
                .url(mbasicUrl)
                .header("User-Agent", NetworkClient.MOBILE_USER_AGENT)
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                val redirectMatch = Regex("""/video_redirect/\?src=([^"&]+)""").find(html)
                val encodedSrc = redirectMatch?.groupValues?.getOrNull(1)
                val decodedUrl = encodedSrc?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                if (!decodedUrl.isNullOrBlank() && decodedUrl.startsWith("http")) {
                    ExtractedVideoInfo(
                        platform = SocialPlatform.FACEBOOK,
                        sourceUrl = originalUrl,
                        resolvedPageUrl = resolvedUrl,
                        title = extractMetaTag(html, "og:title") ?: "Facebook Reel",
                        author = "Facebook Creator",
                        durationSec = 0,
                        thumbnailUrl = extractMetaTag(html, "og:image"),
                        hdDownloadUrl = decodedUrl,
                        sdDownloadUrl = decodedUrl,
                        audioUrl = null,
                        extractionMethod = "Facebook Direct Stream"
                    )
                } else null
            }
        }.getOrNull()
    }

    private fun parseFacebookHtml(
        html: String,
        originalUrl: String,
        resolvedUrl: String
    ): ExtractedVideoInfo? {
        val hdPatterns = listOf(
            Regex(""""browser_native_hd_url"\s*:\s*"([^"]+)""""),
            Regex(""""playable_url_quality_hd"\s*:\s*"([^"]+)""""),
            Regex(""""hd_src_no_ratelimit"\s*:\s*"([^"]+)""""),
            Regex(""""hd_src"\s*:\s*"([^"]+)"""")
        )
        val sdPatterns = listOf(
            Regex(""""browser_native_sd_url"\s*:\s*"([^"]+)""""),
            Regex(""""playable_url"\s*:\s*"([^"]+)""""),
            Regex(""""sd_src_no_ratelimit"\s*:\s*"([^"]+)""""),
            Regex(""""sd_src"\s*:\s*"([^"]+)"""")
        )

        val hdUrl = hdPatterns.firstNotNullOfOrNull { regex ->
            unescapeUrl(regex.find(html)?.groupValues?.getOrNull(1))
        }
        val sdUrl = sdPatterns.firstNotNullOfOrNull { regex ->
            unescapeUrl(regex.find(html)?.groupValues?.getOrNull(1))
        } ?: extractMetaTag(html, "og:video:secure_url")
            ?: extractMetaTag(html, "og:video")

        val best = hdUrl ?: sdUrl
        if (best.isNullOrBlank() || !best.startsWith("http")) return null

        val title = extractMetaTag(html, "og:title")
            ?: extractHtmlTitle(html)
            ?: "Facebook Video"
        val thumb = extractMetaTag(html, "og:image")
            ?: unescapeUrl(Regex(""""preferred_thumbnail"\s*:\s*\{[^}]*"uri"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1))

        return ExtractedVideoInfo(
            platform = SocialPlatform.FACEBOOK,
            sourceUrl = originalUrl,
            resolvedPageUrl = resolvedUrl,
            title = title.removeSuffix(" | Facebook").trim().ifBlank { "Facebook Video" },
            author = "Facebook",
            durationSec = 0,
            thumbnailUrl = thumb,
            hdDownloadUrl = hdUrl ?: sdUrl,
            sdDownloadUrl = sdUrl ?: hdUrl,
            audioUrl = null,
            extractionMethod = if (hdUrl != null) "Facebook HD Native Stream" else "Facebook SD Stream"
        )
    }

    // =========================================================================
    // INSTAGRAM (REELS / POSTS / TV)
    // =========================================================================
    private fun extractInstagram(resolvedUrl: String, originalUrl: String): ExtractedVideoInfo? {
        val shortcode = extractInstagramShortcode(resolvedUrl)

        // Strategy 1: Embed Captioned Page (very reliable for public Reels/Posts)
        if (!shortcode.isNullOrBlank()) {
            val embedUrls = listOf(
                "https://www.instagram.com/reel/$shortcode/embed/captioned/",
                "https://www.instagram.com/p/$shortcode/embed/captioned/"
            )
            for (embedUrl in embedUrls) {
                val info = runCatching {
                    val req = Request.Builder()
                        .url(embedUrl)
                        .header("User-Agent", NetworkClient.DESKTOP_USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .get()
                        .build()

                    client.newCall(req).execute().use { resp ->
                        val html = resp.body?.string().orEmpty()
                        parseInstagramHtml(html, originalUrl, resolvedUrl, "Instagram Embed HD")
                    }
                }.getOrNull()

                if (info != null && !info.bestDownloadUrl.isNullOrBlank()) return info
            }
        }

        // Strategy 2: Instagram Public GraphQL Shortcode Media
        if (!shortcode.isNullOrBlank()) {
            val gqlInfo = runCatching {
                val formBody = FormBody.Builder()
                    .add("variables", """{"shortcode":"$shortcode"}""")
                    .add("doc_id", "8845758582119845")
                    .build()

                val req = Request.Builder()
                    .url("https://www.instagram.com/graphql/query")
                    .header("User-Agent", NetworkClient.DESKTOP_USER_AGENT)
                    .header("X-IG-App-ID", "936619743392459")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "https://www.instagram.com/reel/$shortcode/")
                    .post(formBody)
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val media = json.optJSONObject("data")?.optJSONObject("xdt_shortcode_media")
                        ?: return@use null
                    val videoUrl = media.optString("video_url").takeIf { it.startsWith("http") }
                        ?: return@use null
                    val owner = media.optJSONObject("owner")?.optString("username") ?: "Instagram Creator"
                    val thumb = media.optString("display_url").takeIf { it.startsWith("http") }
                    val caption = media.optJSONObject("edge_media_to_caption")
                        ?.optJSONArray("edges")
                        ?.optJSONObject(0)
                        ?.optJSONObject("node")
                        ?.optString("text")
                        ?.take(140)
                        ?: "Instagram Reel"

                    ExtractedVideoInfo(
                        platform = SocialPlatform.INSTAGRAM,
                        sourceUrl = originalUrl,
                        resolvedPageUrl = resolvedUrl,
                        title = caption.ifBlank { "Instagram Reel" },
                        author = "@$owner",
                        durationSec = media.optDouble("video_duration", 0.0).toInt(),
                        thumbnailUrl = thumb,
                        hdDownloadUrl = videoUrl,
                        sdDownloadUrl = videoUrl,
                        audioUrl = null,
                        extractionMethod = "Instagram GraphQL HD"
                    )
                }
            }.getOrNull()

            if (gqlInfo != null) return gqlInfo
        }

        // Strategy 3: Direct Page Fetch with crawler/mobile UA
        return runCatching {
            val req = Request.Builder()
                .url(resolvedUrl)
                .header("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                parseInstagramHtml(html, originalUrl, resolvedUrl, "Instagram Direct Stream")
            }
        }.getOrNull()
    }

    private fun parseInstagramHtml(
        html: String,
        originalUrl: String,
        resolvedUrl: String,
        methodLabel: String
    ): ExtractedVideoInfo? {
        val videoPatterns = listOf(
            Regex(""""video_url"\s*:\s*"([^"]+)""""),
            Regex(""""video_versions"\s*:\s*\[\s*\{[^}]*"url"\s*:\s*"([^"]+)""""),
            Regex(""""contentUrl"\s*:\s*"([^"]+\.mp4[^"]*)"""")
        )

        val videoUrl = videoPatterns.firstNotNullOfOrNull { regex ->
            unescapeUrl(regex.find(html)?.groupValues?.getOrNull(1))
        } ?: extractMetaTag(html, "og:video:secure_url")
            ?: extractMetaTag(html, "og:video")

        if (videoUrl.isNullOrBlank() || !videoUrl.startsWith("http")) return null

        val thumb = unescapeUrl(Regex(""""thumbnail_src"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1))
            ?: unescapeUrl(Regex(""""display_url"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1))
            ?: extractMetaTag(html, "og:image")

        val username = Regex("""class="UsernameText"[^>]*>([^<]+)<""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex(""""username"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?: "Instagram"

        val caption = extractMetaTag(html, "og:description")
            ?: extractMetaTag(html, "og:title")
            ?: "Instagram Reel"

        return ExtractedVideoInfo(
            platform = SocialPlatform.INSTAGRAM,
            sourceUrl = originalUrl,
            resolvedPageUrl = resolvedUrl,
            title = caption.take(140).ifBlank { "Instagram Reel" },
            author = if (username.startsWith("@") || username == "Instagram") username else "@$username",
            durationSec = 0,
            thumbnailUrl = thumb,
            hdDownloadUrl = videoUrl,
            sdDownloadUrl = videoUrl,
            audioUrl = null,
            extractionMethod = methodLabel
        )
    }

    private fun extractInstagramShortcode(url: String): String? {
        val match = Regex("""instagram\.com/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)""").find(url)
        return match?.groupValues?.getOrNull(1)
    }

    // =========================================================================
    // DOWNLOAD STREAM TO APP STORAGE + MEDIASTORE GALLERY
    // =========================================================================
    suspend fun downloadVideoToDevice(
        info: ExtractedVideoInfo,
        preferHd: Boolean = true,
        onProgress: (bytesRead: Long, totalBytes: Long, activeThreads: Int) -> Unit
    ): Result<DownloadedVideoResult> = withContext(Dispatchers.IO) {
        try {
            val videosDir = File(context.filesDir, "videos").apply { mkdirs() }
            val safePlatform = info.platform.name.lowercase(Locale.US)
            val fileName = "Gizmooh_${safePlatform}_${System.currentTimeMillis()}.mp4"
            val localFile = File(videosDir, fileName)

            val shouldMuxAdaptiveHd = preferHd &&
                info.requiresAudioMux &&
                !info.hdDownloadUrl.isNullOrBlank() &&
                !info.audioUrl.isNullOrBlank()

            var downloadedOk = false
            if (shouldMuxAdaptiveHd) {
                val tempVideo = File(context.cacheDir, "mux_vid_${System.currentTimeMillis()}.mp4")
                val tempAudio = File(context.cacheDir, "mux_aud_${System.currentTimeMillis()}.m4a")
                try {
                    val vidBytes = AtomicLong(0L)
                    val audBytes = AtomicLong(0L)
                    val vidTotal = AtomicLong(0L)
                    val audTotal = AtomicLong(0L)
                    val vidThreads = AtomicInteger(1)
                    val audThreads = AtomicInteger(1)

                    fun emitCombined() {
                        val copied = vidBytes.get() + audBytes.get()
                        val vTot = vidTotal.get()
                        val aTot = audTotal.get()
                        val total = if (vTot > 0L && aTot > 0L) vTot + aTot
                        else if (vTot > 0L) (vTot * 1.15).toLong()
                        else -1L
                        onProgress(copied, total, vidThreads.get() + audThreads.get())
                    }

                    coroutineScope {
                        val vidJob = async {
                            fastDownloader.downloadToFileFast(
                                url = info.hdDownloadUrl!!,
                                referer = info.resolvedPageUrl,
                                destFile = tempVideo,
                                threads = 6
                            ) { copied, total, threads ->
                                vidBytes.set(copied)
                                if (total > 0L) vidTotal.set(total)
                                vidThreads.set(threads)
                                emitCombined()
                            }
                        }
                        val audJob = async {
                            fastDownloader.downloadToFileFast(
                                url = info.audioUrl!!,
                                referer = info.resolvedPageUrl,
                                destFile = tempAudio,
                                threads = 2
                            ) { copied, total, threads ->
                                audBytes.set(copied)
                                if (total > 0L) audTotal.set(total)
                                audThreads.set(threads)
                                emitCombined()
                            }
                        }
                        awaitAll(vidJob, audJob)
                    }
                    Mp4TrackMuxer.muxVideoAndAudio(tempVideo, tempAudio, localFile)
                    downloadedOk = localFile.length() >= 1024L
                } catch (e: CancellationException) {
                    throw e
                } catch (muxErr: Exception) {
                    Log.w(TAG, "Adaptive MP4 mux failed, falling back to direct stream: ${muxErr.message}")
                    runCatching { localFile.delete() }
                } finally {
                    runCatching { tempVideo.delete() }
                    runCatching { tempAudio.delete() }
                }
            }

            if (!downloadedOk) {
                val fallbackUrl = if (preferHd && !shouldMuxAdaptiveHd) {
                    info.hdDownloadUrl ?: info.sdDownloadUrl
                } else {
                    info.sdDownloadUrl ?: info.hdDownloadUrl
                } ?: return@withContext Result.failure(IllegalStateException("No downloadable video URL found."))

                fastDownloader.downloadToFileFast(
                    url = fallbackUrl,
                    referer = info.resolvedPageUrl,
                    destFile = localFile,
                    threads = 6,
                    onProgress = onProgress
                )
            }

            if (localFile.length() < 1024L) {
                localFile.delete()
                return@withContext Result.failure(
                    IllegalStateException("Downloaded file was invalid or blocked by platform.")
                )
            }

            val galleryUri = saveCopyToMediaStore(localFile, fileName)
            val resultUri = galleryUri?.toString() ?: Uri.fromFile(localFile).toString()

            Result.success(
                DownloadedVideoResult(
                    localFilePath = localFile.absolutePath,
                    contentUriString = resultUri,
                    gallerySaved = galleryUri != null,
                    fileSizeBytes = localFile.length(),
                    fileName = fileName
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            Result.failure(e)
        }
    }

    private fun saveCopyToMediaStore(sourceFile: File, fileName: String): Uri? {
        return runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/GizmoohDownloader")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val update = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(itemUri, update, null, null)
            }
            itemUri
        }.onFailure {
            Log.w(TAG, "MediaStore save skipped/failed: ${it.message}")
        }.getOrNull()
    }

    suspend fun downloadAudioToDevice(
        info: ExtractedVideoInfo
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val audioUrl = info.audioUrl?.takeIf { it.startsWith("http") }
                ?: return@withContext Result.failure(IllegalStateException("No audio track URL available for this clip."))

            val req = Request.Builder()
                .url(audioUrl)
                .header("User-Agent", NetworkClient.DESKTOP_USER_AGENT)
                .header("Referer", info.resolvedPageUrl)
                .get()
                .build()

            val isM4a = audioUrl.contains("mime=audio%2Fmp4", ignoreCase = true) ||
                audioUrl.contains("mime=audio/mp4", ignoreCase = true) ||
                audioUrl.contains(".m4a", ignoreCase = true)
            val ext = if (isM4a) "m4a" else "mp3"
            val mime = if (isM4a) "audio/mp4" else "audio/mpeg"

            val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
            val fileName = "Gizmooh_Audio_${System.currentTimeMillis()}.$ext"
            val localFile = File(audioDir, fileName)

            fastDownloader.downloadToFileFast(
                url = audioUrl,
                referer = info.resolvedPageUrl,
                destFile = localFile,
                threads = 4
            ) { _, _, _ -> }

            val savedToMusic = saveAudioToMediaStore(localFile, fileName, mime)
            Result.success(
                if (savedToMusic) "Audio saved to Music/GizmoohDownloader ($fileName)"
                else "Audio saved locally ($fileName)"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Audio download error", e)
            Result.failure(e)
        }
    }

    private fun saveAudioToMediaStore(sourceFile: File, fileName: String, mimeType: String): Boolean {
        return runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/GizmoohDownloader")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val itemUri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceFile).use { input -> input.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    itemUri,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            true
        }.getOrDefault(false)
    }

    // =========================================================================
    // REGEX & ESCAPE HELPERS
    // =========================================================================
    private fun extractMetaTag(html: String, propertyName: String): String? {
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
        val raw = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
        return unescapeText(raw)
    }

    private fun unescapeUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u0025", "%")
            .replace("\\u003d", "=")
            .replace("\\u003c", "<")
            .replace("\\u003e", ">")
            .replace("&amp;", "&")
            .trim()
    }

    private fun unescapeText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\\n", " ")
            .trim()
    }
}

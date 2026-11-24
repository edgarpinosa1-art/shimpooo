package com.example.data.network

import com.example.data.model.TranscriptLanguageMode
import com.example.data.model.TranscriptSegment
import com.example.data.model.VideoTranscript
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object TranscriptSerializer {

    fun toJson(transcript: VideoTranscript): String {
        return JSONObject().apply {
            put("videoTitle", transcript.videoTitle)
            put("sourceUriString", transcript.sourceUriString)
            put("geminiModelUsed", transcript.geminiModelUsed)
            put("languageMode", transcript.languageMode.name)
            put("detectedLanguage", transcript.detectedLanguage)
            put("overallSummary", transcript.overallSummary)
            put("fullPlainTranscript", transcript.fullPlainTranscript)
            put("createdAt", transcript.createdAt)
            put("segments", JSONArray().apply {
                transcript.segments.forEach { seg ->
                    put(JSONObject().apply {
                        put("startTime", seg.startTime)
                        put("endTime", seg.endTime)
                        put("speaker", seg.speaker)
                        put("text", seg.text)
                    })
                }
            })
        }.toString()
    }

    fun fromGeminiOrStoredJson(
        jsonString: String,
        id: Long = 0L,
        videoTitle: String,
        sourceUriString: String,
        geminiModelUsed: String,
        languageMode: TranscriptLanguageMode,
        createdAt: Long = System.currentTimeMillis()
    ): VideoTranscript {
        val json = JSONObject(jsonString)
        val segArray = json.optJSONArray("segments") ?: JSONArray()
        val segments = buildList {
            for (i in 0 until segArray.length()) {
                val obj = segArray.optJSONObject(i) ?: continue
                val text = obj.optString("text", "").trim()
                if (text.isNotEmpty()) {
                    add(
                        TranscriptSegment(
                            startTime = obj.optString("startTime", "00:00").trim(),
                            endTime = obj.optString("endTime", "00:05").trim(),
                            speaker = obj.optString("speaker", "Speaker 1").ifBlank { "Speaker 1" },
                            text = text
                        )
                    )
                }
            }
        }

        val resolvedMode = TranscriptLanguageMode.entries.firstOrNull {
            it.name == json.optString("languageMode", languageMode.name)
        } ?: languageMode

        val rawPlain = json.optString("fullPlainTranscript", "").trim()
        val plain = rawPlain.ifBlank {
            segments.joinToString(" ") { it.text }
        }

        return VideoTranscript(
            id = id,
            videoTitle = json.optString("videoTitle", videoTitle).ifBlank { videoTitle },
            sourceUriString = json.optString("sourceUriString", sourceUriString).ifBlank { sourceUriString },
            geminiModelUsed = json.optString("geminiModelUsed", geminiModelUsed).ifBlank { geminiModelUsed },
            languageMode = resolvedMode,
            detectedLanguage = json.optString("detectedLanguage", "Auto-detected").ifBlank { "Auto-detected" },
            overallSummary = json.optString("overallSummary", "").trim(),
            segments = segments,
            fullPlainTranscript = plain,
            createdAt = json.optLong("createdAt", createdAt)
        )
    }

    fun formatTimestampedText(transcript: VideoTranscript): String {
        return buildString {
            appendLine("=== COMPLETE TRANSCRIPT: ${transcript.videoTitle} ===")
            appendLine("Language: ${transcript.detectedLanguage} (${transcript.languageMode.label})")
            if (transcript.overallSummary.isNotBlank()) {
                appendLine()
                appendLine("Summary: ${transcript.overallSummary}")
            }
            appendLine()
            transcript.segments.forEach { seg ->
                appendLine("[${seg.startTime} - ${seg.endTime}] ${seg.speaker}: ${seg.text}")
            }
        }.trim()
    }

    fun formatSrtSubtitles(segments: List<TranscriptSegment>): String {
        if (segments.isEmpty()) return ""
        return buildString {
            segments.forEachIndexed { index, seg ->
                appendLine(index + 1)
                appendLine("${toSrtTimestamp(seg.startTime)} --> ${toSrtTimestamp(seg.endTime)}")
                appendLine(seg.text.trim())
                appendLine()
            }
        }.trim()
    }

    fun toSrtTimestamp(raw: String): String {
        val cleaned = raw.trim().replace('.', ',')
        val mainPart = cleaned.substringBefore(',')
        val msPart = cleaned.substringAfter(',', missingDelimiterValue = "000")
            .filter { it.isDigit() }
            .padEnd(3, '0')
            .take(3)

        val parts = mainPart.split(':').mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        val (hours, minutes, seconds) = when (parts.size) {
            3 -> Triple(parts[0], parts[1].coerceIn(0, 59), parts[2].coerceIn(0, 59))
            2 -> Triple(0, parts[0].coerceIn(0, 59), parts[1].coerceIn(0, 59))
            1 -> Triple(0, parts[0] / 60, parts[0] % 60)
            else -> Triple(0, 0, 0)
        }
        return String.format(Locale.US, "%02d:%02d:%02d,%s", hours, minutes, seconds, msPart)
    }

    fun formatClockTimestamp(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun buildTimeWindows(
        durationMs: Long,
        singlePassThresholdMs: Long = 8L * 60L * 1000L,
        chunkSizeMs: Long = 6L * 60L * 1000L
    ): List<Pair<Long, Long>> {
        if (durationMs <= 0L || durationMs <= singlePassThresholdMs) {
            return listOf(0L to durationMs.coerceAtLeast(0L))
        }
        val windows = mutableListOf<Pair<Long, Long>>()
        var start = 0L
        while (start < durationMs) {
            val end = minOf(start + chunkSizeMs, durationMs)
            windows.add(start to end)
            start = end
        }
        return windows
    }

    fun mergeChunks(
        chunks: List<VideoTranscript>,
        videoTitle: String,
        sourceUriString: String,
        geminiModelUsed: String,
        languageMode: TranscriptLanguageMode
    ): VideoTranscript {
        val mergedSegments = mutableListOf<TranscriptSegment>()
        chunks.forEach { chunk ->
            chunk.segments.forEach { seg ->
                val last = mergedSegments.lastOrNull()
                if (last == null || last.startTime != seg.startTime || last.text != seg.text) {
                    mergedSegments.add(seg)
                }
            }
        }
        val detectedLang = chunks.firstOrNull { it.detectedLanguage.isNotBlank() }?.detectedLanguage
            ?: "Auto-detected"
        val summary = chunks.mapNotNull { it.overallSummary.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(" ")
        val fullPlain = mergedSegments.joinToString(" ") { it.text }
            .ifBlank { chunks.joinToString(" ") { it.fullPlainTranscript } }

        return VideoTranscript(
            videoTitle = videoTitle,
            sourceUriString = sourceUriString,
            geminiModelUsed = geminiModelUsed,
            languageMode = languageMode,
            detectedLanguage = detectedLang,
            overallSummary = summary,
            segments = mergedSegments,
            fullPlainTranscript = fullPlain
        )
    }
}

package com.example.data.dubbing

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.example.data.model.DubLineItem
import java.nio.ByteBuffer

/**
 * Checks whether an MP4/MKV video already contains an embedded subtitle / timed-text track
 * (e.g., tx3g / mov_text / WebVTT / SubRip) so the app can extract dialogue lines offline for ₱0!
 */
class EmbeddedSubtitleExtractor(private val context: Context) {

    fun extractEmbeddedLines(sourceUriString: String): List<DubLineItem> {
        val extractor = MediaExtractor()
        try {
            val uri = Uri.parse(sourceUriString)
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                extractor.setDataSource(uri.path!!)
            } else {
                extractor.setDataSource(context, uri, null)
            }

            var subTrackIdx = -1
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()
                if (mime.startsWith("text/") ||
                    mime.contains("subrip") ||
                    mime.contains("webvtt") ||
                    mime.contains("tx3g") ||
                    mime.contains("ttml")
                ) {
                    subTrackIdx = i
                    break
                }
            }
            if (subTrackIdx == -1) return emptyList()

            extractor.selectTrack(subTrackIdx)
            val buffer = ByteBuffer.allocate(64 * 1024)
            val items = mutableListOf<DubLineItem>()
            var idCounter = 1L

            while (items.size < 2000) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                val startUs = extractor.sampleTime.coerceAtLeast(0L)
                val startMs = startUs / 1000L
                val rawBytes = ByteArray(size)
                buffer.rewind()
                buffer.get(rawBytes, 0, size)

                val decoded = decodeSubtitleSampleText(rawBytes)
                if (decoded.isNotBlank()) {
                    val prev = items.lastOrNull()
                    if (prev != null && prev.endMs > startMs) {
                        items[items.lastIndex] = prev.copy(
                            endMs = (startMs - 40L).coerceAtLeast(prev.startMs + 450L)
                        )
                    }
                    items.add(
                        DubLineItem(
                            id = idCounter++,
                            startMs = startMs,
                            endMs = startMs + 3000L,
                            speaker = "Speaker 1",
                            originalText = decoded,
                            filipinoText = decoded
                        )
                    )
                }
                extractor.advance()
            }
            return items
        } catch (_: Exception) {
            return emptyList()
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun decodeSubtitleSampleText(raw: ByteArray): String {
        if (raw.isEmpty()) return ""
        // MP4 tx3g timed text often begins with 2-byte big-endian UTF-8 text length prefix
        val candidate = if (raw.size >= 2) {
            val prefixLen = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
            if (prefixLen in 1..(raw.size - 2)) {
                String(raw, 2, prefixLen, Charsets.UTF_8)
            } else {
                String(raw, Charsets.UTF_8)
            }
        } else {
            String(raw, Charsets.UTF_8)
        }
        return candidate
            .replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""[\u0000-\u001F]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}

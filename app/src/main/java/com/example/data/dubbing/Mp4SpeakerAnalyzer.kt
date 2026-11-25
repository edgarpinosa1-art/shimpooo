package com.example.data.dubbing

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.example.data.model.DubLineItem
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Mp4SpeakerAnalysisResult(
    val refinedLines: List<DubLineItem>,
    val speakerPitchHz: Map<String, Float>,
    val speakerGender: Map<String, String>,
    val summary: String
)

object SpeakerAcousticClustering {

    private const val MIN_VOICE_HZ = 75f
    private const val MAX_VOICE_HZ = 350f
    private const val SEMITONE_CLUSTER_GAP = 3.2f

    fun toSemitones(freqHz: Float): Float {
        return (12.0 * (ln(freqHz.coerceIn(50f, 500f) / 100.0) / ln(2.0))).toFloat()
    }

    /**
     * Estimates median fundamental pitch (F0 in Hz) across voiced 30ms frames using normalized
     * autocorrelation. Returns null for unvoiced / silence / pure music segments.
     */
    fun estimatePitchHz(monoSamples: ShortArray, sampleRate: Int): Float? {
        if (monoSamples.size < (sampleRate * 0.045f).toInt() || sampleRate < 8000) return null

        val frameLen = (sampleRate * 0.032f).roundToInt().coerceIn(256, 2048)
        val hop = (frameLen / 2).coerceAtLeast(128)
        val minLag = (sampleRate / MAX_VOICE_HZ).roundToInt().coerceAtLeast(8)
        val maxLag = (sampleRate / MIN_VOICE_HZ).roundToInt().coerceAtMost(frameLen - 4)
        if (maxLag <= minLag) return null

        val framePitches = mutableListOf<Float>()
        var offset = 0
        while (offset + frameLen <= monoSamples.size) {
            var energy = 0.0
            for (i in 0 until frameLen) {
                val s = monoSamples[offset + i].toDouble() / 32768.0
                energy += s * s
            }
            val rms = sqrt(energy / frameLen).toFloat()
            if (rms >= 0.015f) {
                var bestLag = -1
                var bestNormCorr = 0f

                for (lag in minLag..maxLag) {
                    var cross = 0.0
                    var e0 = 0.0
                    var e1 = 0.0
                    val count = frameLen - lag
                    var i = 0
                    while (i < count) {
                        val a = monoSamples[offset + i].toDouble()
                        val b = monoSamples[offset + i + lag].toDouble()
                        cross += a * b
                        e0 += a * a
                        e1 += b * b
                        i += 2
                    }
                    val denom = sqrt(e0 * e1)
                    if (denom > 1.0) {
                        val normCorr = (cross / denom).toFloat()
                        if (normCorr > bestNormCorr) {
                            bestNormCorr = normCorr
                            bestLag = lag
                        }
                    }
                }

                if (bestLag > 0 && bestNormCorr >= 0.45f) {
                    val hz = sampleRate.toFloat() / bestLag.toFloat()
                    if (hz in MIN_VOICE_HZ..MAX_VOICE_HZ) {
                        framePitches.add(hz)
                    }
                }
            }
            offset += hop
        }

        if (framePitches.isEmpty()) return null
        val sorted = framePitches.sorted()
        return sorted[sorted.size / 2]
    }

    fun inferGenderFromPitchHz(pitchHz: Float?): String {
        if (pitchHz == null || pitchHz <= 0f) return ""
        return when {
            pitchHz < 162f -> "Male"
            pitchHz >= 182f -> "Female"
            else -> "Neutral"
        }
    }

    /**
     * Combines timestamped dialogue lines with measured acoustic F0 pitches from the MP4 audio.
     * - If the script only has a single generic speaker label (e.g. "Speaker 1"), automatically
     *   clusters speakers by vocal register gaps (>= 3.2 semitones) from MP4 audio directly!
     * - If the script already differentiates speakers, enriches and locks each character's gender
     *   and pitch from the actual audio.
     */
    fun refineSpeakersFromAudio(
        lines: List<DubLineItem>,
        linePitches: List<Float?>
    ): Mp4SpeakerAnalysisResult {
        if (lines.isEmpty()) {
            return Mp4SpeakerAnalysisResult(emptyList(), emptyMap(), emptyMap(), "")
        }

        val canonicalPerLine = lines.map { DubTimelineMath.canonicalizeSpeakerName(it.speaker) }
        val existingDistinct = canonicalPerLine.distinct()

        // Case A: Script already distinguishes multiple characters -> enrich with acoustic F0 & gender
        if (existingDistinct.size >= 2) {
            val pitchesBySpeaker = linkedMapOf<String, MutableList<Float>>()
            lines.indices.forEach { idx ->
                val pitch = linePitches.getOrNull(idx)
                if (pitch != null) {
                    pitchesBySpeaker.getOrPut(canonicalPerLine[idx]) { mutableListOf() }.add(pitch)
                }
            }

            val finalNameMap = linkedMapOf<String, String>()
            val speakerPitchMap = linkedMapOf<String, Float>()
            val speakerGenderMap = linkedMapOf<String, String>()

            for (spk in existingDistinct) {
                val measured = pitchesBySpeaker[spk].orEmpty().sorted()
                val medianHz = if (measured.isNotEmpty()) measured[measured.size / 2] else 0f
                val lower = spk.lowercase(Locale.US)
                val alreadyHasGender = lower.contains("male") || lower.contains("female") ||
                    lower.contains("lalaki") || lower.contains("babae")
                val acousticGender = inferGenderFromPitchHz(medianHz.takeIf { it > 0f })

                val finalSpeakerName = if (!alreadyHasGender && (acousticGender == "Male" || acousticGender == "Female")) {
                    "$spk ($acousticGender)"
                } else {
                    spk
                }
                finalNameMap[spk] = finalSpeakerName
                if (medianHz > 0f) speakerPitchMap[finalSpeakerName] = medianHz
                val resolvedGender = when {
                    finalSpeakerName.lowercase(Locale.US).contains("female") ||
                        finalSpeakerName.lowercase(Locale.US).contains("babae") -> "Female"
                    finalSpeakerName.lowercase(Locale.US).contains("male") ||
                        finalSpeakerName.lowercase(Locale.US).contains("lalaki") -> "Male"
                    acousticGender.isNotBlank() -> acousticGender
                    else -> ""
                }
                if (resolvedGender.isNotBlank()) speakerGenderMap[finalSpeakerName] = resolvedGender
            }

            val updatedLines = lines.mapIndexed { idx, line ->
                line.copy(speaker = finalNameMap[canonicalPerLine[idx]] ?: canonicalPerLine[idx])
            }
            val summary = buildCastSummary(updatedLines.map { it.speaker }.distinct(), speakerPitchMap, speakerGenderMap)
            return Mp4SpeakerAnalysisResult(updatedLines, speakerPitchMap, speakerGenderMap, summary)
        }

        // Case B: Single/generic speaker label (e.g. Whisper STT or untagged SRT) -> auto-detect speakers from MP4 pitch!
        val voicedIndices = lines.indices.filter { linePitches.getOrNull(it) != null }
        if (voicedIndices.size < 2) {
            val singlePitch = voicedIndices.firstOrNull()?.let { linePitches[it] } ?: 0f
            val gender = inferGenderFromPitchHz(singlePitch.takeIf { it > 0f })
            val label = if (gender == "Male" || gender == "Female") "Speaker 1 ($gender)" else "Speaker 1"
            val updated = lines.map { it.copy(speaker = label) }
            val pMap = if (singlePitch > 0f) mapOf(label to singlePitch) else emptyMap()
            val gMap = if (gender.isNotBlank()) mapOf(label to gender) else emptyMap()
            return Mp4SpeakerAnalysisResult(
                refinedLines = updated,
                speakerPitchHz = pMap,
                speakerGender = gMap,
                summary = buildCastSummary(listOf(label), pMap, gMap)
            )
        }

        // Cluster voiced pitches in semitone space sorted from lowest to highest
        data class VoicedPoint(val lineIdx: Int, val hz: Float, val semi: Float)
        val sortedPoints = voicedIndices
            .map { idx ->
                val hz = linePitches[idx]!!
                VoicedPoint(idx, hz, toSemitones(hz))
            }
            .sortedBy { it.semi }

        val rawClusters = mutableListOf<MutableList<VoicedPoint>>()
        for (pt in sortedPoints) {
            val currentCluster = rawClusters.lastOrNull()
            if (currentCluster == null) {
                rawClusters.add(mutableListOf(pt))
            } else {
                val clusterMedianSemi = currentCluster[currentCluster.size / 2].semi
                if (pt.semi - clusterMedianSemi >= SEMITONE_CLUSTER_GAP && rawClusters.size < 4) {
                    rawClusters.add(mutableListOf(pt))
                } else {
                    currentCluster.add(pt)
                }
            }
        }

        // Merge tiny 1-line outlier clusters back into nearest cluster if >= 3 clusters to prevent false splits
        while (rawClusters.size > 2 && rawClusters.any { it.size == 1 }) {
            val tinyIdx = rawClusters.indexOfFirst { it.size == 1 }
            val tiny = rawClusters.removeAt(tinyIdx)
            val ptSemi = tiny.first().semi
            val nearest = rawClusters.minByOrNull { cluster ->
                abs(cluster[cluster.size / 2].semi - ptSemi)
            }
            nearest?.addAll(tiny)
        }

        // Map each cluster centroid pitch & assign chronological character IDs
        val clusterCentroids = rawClusters.map { cluster ->
            val hzSorted = cluster.map { it.hz }.sorted()
            hzSorted[hzSorted.size / 2]
        }
        val lineToClusterIdx = IntArray(lines.size) { -1 }
        rawClusters.forEachIndexed { cIdx, cluster ->
            cluster.forEach { pt -> lineToClusterIdx[pt.lineIdx] = cIdx }
        }

        // Fill unvoiced lines by copying nearest preceding (or following) voiced speaker
        var lastAssigned = lineToClusterIdx.firstOrNull { it >= 0 } ?: 0
        for (i in lineToClusterIdx.indices) {
            if (lineToClusterIdx[i] >= 0) {
                lastAssigned = lineToClusterIdx[i]
            } else {
                lineToClusterIdx[i] = lastAssigned
            }
        }

        // Number characters in chronological order of first appearance
        val clusterOrderToCharNum = linkedMapOf<Int, Int>()
        for (cIdx in lineToClusterIdx) {
            if (cIdx !in clusterOrderToCharNum) {
                clusterOrderToCharNum[cIdx] = clusterOrderToCharNum.size + 1
            }
        }

        val clusterNames = linkedMapOf<Int, String>()
        val speakerPitchMap = linkedMapOf<String, Float>()
        val speakerGenderMap = linkedMapOf<String, String>()

        clusterOrderToCharNum.forEach { (cIdx, charNum) ->
            val hz = clusterCentroids.getOrElse(cIdx) { 0f }
            val gender = inferGenderFromPitchHz(hz.takeIf { it > 0f })
            val name = when {
                clusterOrderToCharNum.size == 1 && (gender == "Male" || gender == "Female") -> "Speaker 1 ($gender)"
                clusterOrderToCharNum.size == 1 -> "Speaker 1"
                gender == "Male" || gender == "Female" -> "Character $charNum ($gender)"
                else -> "Character $charNum"
            }
            clusterNames[cIdx] = name
            if (hz > 0f) speakerPitchMap[name] = hz
            if (gender.isNotBlank()) speakerGenderMap[name] = gender
        }

        val refinedLines = lines.mapIndexed { idx, line ->
            line.copy(speaker = clusterNames[lineToClusterIdx[idx]] ?: "Speaker 1")
        }
        val distinctFinal = refinedLines.map { it.speaker }.distinct()
        val summary = buildCastSummary(distinctFinal, speakerPitchMap, speakerGenderMap)
        return Mp4SpeakerAnalysisResult(refinedLines, speakerPitchMap, speakerGenderMap, summary)
    }

    private fun buildCastSummary(
        speakers: List<String>,
        pitches: Map<String, Float>,
        genders: Map<String, String>
    ): String {
        if (speakers.isEmpty()) return ""
        val details = speakers.take(4).joinToString(" · ") { spk ->
            val g = genders[spk]
            val hz = pitches[spk]
            when {
                !g.isNullOrBlank() && hz != null && hz > 0f -> "$g ~${hz.roundToInt()}Hz"
                !g.isNullOrBlank() -> g
                hz != null && hz > 0f -> "~${hz.roundToInt()}Hz"
                else -> spk
            }
        }
        val noun = if (speakers.size == 1) "speaker" else "speakers"
        return "Auto-detected ${speakers.size} $noun in MP4 ($details)"
    }
}

class Mp4SpeakerAnalyzer(private val context: Context) {

    fun analyzeMp4Speakers(
        sourceUriString: String,
        lines: List<DubLineItem>
    ): Mp4SpeakerAnalysisResult {
        if (lines.isEmpty()) {
            return Mp4SpeakerAnalysisResult(emptyList(), emptyMap(), emptyMap(), "")
        }

        val lineBuffers = Array(lines.size) { ArrayList<Short>(8000) }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            val uri = Uri.parse(sourceUriString)
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                extractor.setDataSource(uri.path!!)
            } else {
                extractor.setDataSource(context, uri, null)
            }

            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrack == -1 || audioFormat == null) {
                return SpeakerAcousticClustering.refineSpeakersFromAudio(lines, List(lines.size) { null })
            }

            extractor.selectTrack(audioTrack)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }

            var srcRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
            } else 44100
            var srcChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else 1
            var isFloatPcm = false

            val maxEndMs = lines.maxOf { it.endMs } + 500L
            val targetRate = 16000
            var totalOutSamples = 0L
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val size = extractor.readSampleData(inBuf, 0)
                            val sampleTimeMs = extractor.sampleTime / 1000L
                            if (size < 0 || sampleTimeMs > maxEndMs) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            srcChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                            fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ) {
                            isFloatPcm = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val mono16k = decodeMono16k(outBuf, srcRate, srcChannels, isFloatPcm)
                            val chunkStartMs = (totalOutSamples * 1000L) / targetRate
                            val chunkEndMs = ((totalOutSamples + mono16k.size) * 1000L) / targetRate

                            for (lIdx in lines.indices) {
                                val line = lines[lIdx]
                                if (chunkEndMs >= line.startMs && chunkStartMs <= line.endMs && lineBuffers[lIdx].size < targetRate * 4) {
                                    for (s in mono16k) {
                                        if (lineBuffers[lIdx].size >= targetRate * 4) break
                                        lineBuffers[lIdx].add(s)
                                    }
                                }
                            }
                            totalOutSamples += mono16k.size
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val linePitches = lineBuffers.map { list ->
                if (list.isEmpty()) null
                else SpeakerAcousticClustering.estimatePitchHz(list.toShortArray(), targetRate)
            }
            return SpeakerAcousticClustering.refineSpeakersFromAudio(lines, linePitches)
        } catch (_: Exception) {
            return SpeakerAcousticClustering.refineSpeakersFromAudio(lines, List(lines.size) { null })
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun decodeMono16k(
        buffer: ByteBuffer,
        srcRate: Int,
        channels: Int,
        isFloat: Boolean
    ): ShortArray {
        val dup = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        val monoInput = if (isFloat) {
            val fb = dup.asFloatBuffer()
            val frames = fb.remaining() / channels
            ShortArray(frames) {
                var sum = 0f
                for (c in 0 until channels) sum += fb.get()
                ((sum / channels).coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            }
        } else {
            val sb = dup.asShortBuffer()
            val frames = sb.remaining() / channels
            ShortArray(frames) {
                var sum = 0L
                for (c in 0 until channels) sum += sb.get().toLong()
                (sum / channels).coerceIn(-32768L, 32767L).toShort()
            }
        }
        if (monoInput.isEmpty() || srcRate == 16000) return monoInput
        return WavPcmTools.resampleMonoLinear(monoInput, srcRate, 16000)
    }
}

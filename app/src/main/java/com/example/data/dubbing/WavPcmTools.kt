package com.example.data.dubbing

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tanh

data class WavPcmData(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int
)

data class TimedPcmClip(
    val startSample: Int,
    val endSample: Int,
    val monoSamples: ShortArray
)

object WavPcmTools {

    fun readWavFile(file: File): WavPcmData {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "WAV file too small (${bytes.size} bytes)" }

        var sampleRate = 22050
        var channels = 1
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, pos + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
            val payloadStart = pos + 8
            if (chunkSize < 0 || payloadStart + chunkSize > bytes.size) break

            when (chunkId) {
                "fmt " -> if (chunkSize >= 16) {
                    val bb = ByteBuffer.wrap(bytes, payloadStart, chunkSize).order(ByteOrder.LITTLE_ENDIAN)
                    bb.short // audioFormat
                    channels = bb.short.toInt().coerceAtLeast(1)
                    sampleRate = bb.int.coerceAtLeast(8000)
                    bb.int // byteRate
                    bb.short // blockAlign
                    bitsPerSample = bb.short.toInt().coerceAtLeast(8)
                }
                "data" -> {
                    dataOffset = payloadStart
                    dataSize = chunkSize
                    break
                }
            }
            pos = payloadStart + chunkSize + (chunkSize and 1)
        }

        if (dataOffset == -1) {
            dataOffset = 44
            dataSize = (bytes.size - 44).coerceAtLeast(0)
        }

        val samples: ShortArray = if (bitsPerSample == 8) {
            ShortArray(dataSize) { i ->
                val unsigned = bytes[dataOffset + i].toInt() and 0xFF
                ((unsigned - 128) shl 8).toShort()
            }
        } else {
            val count = dataSize / 2
            val bb = ByteBuffer.wrap(bytes, dataOffset, count * 2).order(ByteOrder.LITTLE_ENDIAN)
            ShortArray(count) { bb.short }
        }

        return WavPcmData(samples = samples, sampleRate = sampleRate, channels = channels)
    }

    fun writeMonoWavFile(file: File, monoSamples: ShortArray, sampleRate: Int) {
        val dataSize = monoSamples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // Mono
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        val pcmBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in monoSamples) {
            pcmBuffer.putShort(s)
        }

        FileOutputStream(file).use { out ->
            out.write(header)
            out.write(pcmBuffer.array())
        }
    }

    fun floatArrayToPcm16(floats: FloatArray): ShortArray {
        if (floats.isEmpty()) return ShortArray(0)
        var peak = 0f
        for (v in floats) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        val gain = if (peak > 0.98f) 0.95f / peak else 1.0f
        return ShortArray(floats.size) { idx ->
            val scaled = (floats[idx] * gain * 32767f).roundToInt()
            scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    fun toMono(samples: ShortArray, channels: Int): ShortArray {
        if (channels <= 1 || samples.isEmpty()) return samples
        val frames = samples.size / channels
        return ShortArray(frames) { frame ->
            var sum = 0
            val base = frame * channels
            for (c in 0 until channels) {
                sum += samples[base + c].toInt()
            }
            (sum / channels).toShort()
        }
    }

    fun resampleMonoLinear(samples: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (samples.isEmpty() || srcRate == dstRate || srcRate <= 0 || dstRate <= 0) return samples
        val outLen = ((samples.size.toLong() * dstRate.toLong()) / srcRate.toLong()).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        val step = srcRate.toDouble() / dstRate.toDouble()
        for (i in 0 until outLen) {
            val srcPos = i * step
            val idx0 = srcPos.toInt().coerceIn(0, samples.lastIndex)
            val idx1 = (idx0 + 1).coerceIn(0, samples.lastIndex)
            val frac = srcPos - idx0
            val interp = samples[idx0] * (1.0 - frac) + samples[idx1] * frac
            out[i] = interp.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    fun trimDigitalSilence(
        samples: ShortArray,
        sampleRate: Int,
        threshold: Int = 220,
        padMs: Int = 20
    ): ShortArray {
        if (samples.isEmpty()) return samples
        var first = -1
        var last = -1
        for (i in samples.indices) {
            if (abs(samples[i].toInt()) > threshold) {
                first = i
                break
            }
        }
        if (first == -1) return samples
        for (i in samples.lastIndex downTo first) {
            if (abs(samples[i].toInt()) > threshold) {
                last = i
                break
            }
        }
        val pad = ((sampleRate.toLong() * padMs) / 1000L).toInt()
        val startIdx = (first - pad).coerceAtLeast(0)
        val endIdx = (last + pad).coerceAtMost(samples.lastIndex)
        return samples.copyOfRange(startIdx, endIdx + 1)
    }

    /**
     * Pitch-preserving WSOLA time-scaler so speeding up or slowing down dubbed speech
     * keeps the voice natural while fitting exact lip-sync mouth movement windows.
     */
    fun wsolaTimeScale(samples: ShortArray, sampleRate: Int, speedFactor: Float): ShortArray {
        if (samples.size < 1024 || abs(speedFactor - 1.0f) < 0.03f) return samples
        val clampedSpeed = speedFactor.coerceIn(0.75f, 1.55f)

        val winSize = ((sampleRate * 0.040f).roundToInt() and 0xFFFE).coerceIn(512, 4096)
        val halfWin = winSize / 2
        val synthesisHop = halfWin
        val analysisHop = (synthesisHop * clampedSpeed).roundToInt().coerceAtLeast(64)
        val searchRadius = (winSize / 4).coerceAtLeast(64)

        val hann = FloatArray(winSize) { i ->
            (0.5 * (1.0 - kotlin.math.cos(2.0 * Math.PI * i / winSize))).toFloat()
        }

        val estimatedOutLen = ((samples.size / clampedSpeed).roundToInt() + winSize).coerceAtLeast(winSize)
        val outAccum = FloatArray(estimatedOutLen)
        val normAccum = FloatArray(estimatedOutLen)

        var inPos = 0
        var outPos = 0

        // Copy first window directly
        val firstCopy = min(winSize, samples.size)
        for (i in 0 until firstCopy) {
            outAccum[i] += samples[i] * hann[i]
            normAccum[i] += hann[i]
        }
        outPos += synthesisHop
        inPos += analysisHop

        while (inPos + winSize < samples.size && outPos + winSize < estimatedOutLen) {
            // Find best correlation offset near expected inPos to avoid phase discontinuities
            var bestOffset = 0
            var bestScore = Float.NEGATIVE_INFINITY
            val overlapCheckStart = (outPos - synthesisHop).coerceAtLeast(0)
            val compareLen = min(halfWin, samples.size - inPos)

            val minDelta = (-searchRadius).coerceAtLeast(-inPos)
            val maxDelta = searchRadius.coerceAtMost(samples.size - winSize - inPos)

            var delta = minDelta
            while (delta <= maxDelta) {
                var corr = 0f
                val candStart = inPos + delta
                var k = 0
                while (k < compareLen) {
                    val existing = outAccum[outPos + k]
                    val candidate = samples[candStart + k].toFloat()
                    corr += existing * candidate
                    k += 4 // stride 4 for crisp mobile speed
                }
                if (corr > bestScore) {
                    bestScore = corr
                    bestOffset = delta
                }
                delta += 4
            }

            val alignedStart = (inPos + bestOffset).coerceIn(0, samples.size - winSize)
            for (i in 0 until winSize) {
                val w = hann[i]
                outAccum[outPos + i] += samples[alignedStart + i] * w
                normAccum[outPos + i] += w
            }

            outPos += synthesisHop
            inPos += analysisHop
        }

        val finalLen = (outPos + halfWin).coerceAtMost(estimatedOutLen)
        return ShortArray(finalLen) { i ->
            val norm = normAccum[i]
            val valFloat = if (norm > 1e-4f) outAccum[i] / norm else outAccum[i]
            valFloat.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Mixes time-aligned Filipino dubbed clips onto original background audio with smooth
     * per-sample envelope ducking (so BGM/SFX dips smoothly while the dubber speaks).
     */
    fun mixDubClipsIntoStereoTimeline(
        originalStereoPcm: ShortArray?,
        totalDurationSamplesPerChannel: Int,
        sampleRate: Int,
        dubClips: List<TimedPcmClip>,
        backgroundGainDuringSpeech: Float
    ): ShortArray {
        val totalMonoSamples = totalDurationSamplesPerChannel.coerceAtLeast(1)
        val duckEnvelope = FloatArray(totalMonoSamples) { 1.0f }

        // Mark speech windows for smooth ducking
        for (clip in dubClips) {
            val s = clip.startSample.coerceIn(0, totalMonoSamples - 1)
            val e = (clip.startSample + clip.monoSamples.size).coerceIn(s, totalMonoSamples)
            for (i in s until e) {
                duckEnvelope[i] = backgroundGainDuringSpeech
            }
        }

        // Smooth ducking transitions (~80ms attack/release) to avoid abrupt volume pumping
        val smoothWindow = (sampleRate * 0.08f).roundToInt().coerceAtLeast(1)
        var currentGain = 1.0f
        val alpha = 1.0f / smoothWindow.toFloat()
        for (i in 0 until totalMonoSamples) {
            val target = duckEnvelope[i]
            currentGain += (target - currentGain) * alpha
            duckEnvelope[i] = currentGain
        }

        val dubTrack = FloatArray(totalMonoSamples)
        val fadeSamples = (sampleRate * 0.020f).roundToInt().coerceAtLeast(1)
        for (clip in dubClips) {
            val src = clip.monoSamples
            val len = src.size
            for (i in 0 until len) {
                val destIdx = clip.startSample + i
                if (destIdx !in 0 until totalMonoSamples) continue
                val fadeIn = if (i < fadeSamples) i.toFloat() / fadeSamples else 1.0f
                val fadeOut = if (len - 1 - i < fadeSamples) (len - 1 - i).toFloat() / fadeSamples else 1.0f
                val env = min(fadeIn, fadeOut).coerceIn(0f, 1f)
                dubTrack[destIdx] += (src[i].toFloat() / 32768f) * env * 1.15f
            }
        }

        val stereoOut = ShortArray(totalMonoSamples * 2)
        for (i in 0 until totalMonoSamples) {
            val duck = duckEnvelope[i]
            val origL = if (originalStereoPcm != null && (i * 2) < originalStereoPcm.size) {
                (originalStereoPcm[i * 2].toFloat() / 32768f) * duck
            } else 0f
            val origR = if (originalStereoPcm != null && (i * 2 + 1) < originalStereoPcm.size) {
                (originalStereoPcm[i * 2 + 1].toFloat() / 32768f) * duck
            } else origL

            val dub = dubTrack[i]
            val mixedL = softClipSample(origL + dub)
            val mixedR = softClipSample(origR + dub)
            stereoOut[i * 2] = mixedL
            stereoOut[i * 2 + 1] = mixedR
        }
        return stereoOut
    }

    /**
     * Duration-preserving pitch shift using WSOLA + linear resampling so single-speaker
     * open-source TTS models (like facebook/mms-tts-tgl) produce distinct voices per speaker.
     */
    fun pitchShiftMono(samples: ShortArray, sampleRate: Int, pitchFactor: Float): ShortArray {
        val clampedPitch = pitchFactor.coerceIn(0.74f, 1.36f)
        if (samples.size < 1024 || abs(clampedPitch - 1.0f) < 0.03f) return samples
        // Compensate duration with WSOLA (1/pitchFactor), then resample to shift pitch cleanly
        val timeCompensated = wsolaTimeScale(samples, sampleRate, 1.0f / clampedPitch)
        val virtualSrcRate = (sampleRate * clampedPitch).roundToInt().coerceAtLeast(8000)
        return resampleMonoLinear(timeCompensated, virtualSrcRate, sampleRate)
    }

    /**
     * Single-pass combined lip-sync tempo fitting + character pitch lock.
     * Avoids double WSOLA windowing so character voice timbre stays crisp and consistent.
     */
    fun timeAndPitchScaleMono(
        samples: ShortArray,
        sampleRate: Int,
        tempoSpeedFactor: Float,
        pitchFactor: Float
    ): ShortArray {
        if (samples.size < 1024) return samples
        val clampedTempo = tempoSpeedFactor.coerceIn(0.78f, 1.50f)
        val clampedPitch = pitchFactor.coerceIn(0.74f, 1.36f)
        val pitchActive = abs(clampedPitch - 1.0f) >= 0.03f
        val tempoActive = abs(clampedTempo - 1.0f) >= 0.04f

        if (!pitchActive && !tempoActive) return samples
        if (!pitchActive) return wsolaTimeScale(samples, sampleRate, clampedTempo)

        // Combined WSOLA speed = tempo / pitch, then single resample by pitch
        val wsolaSpeed = (clampedTempo / clampedPitch).coerceIn(0.65f, 1.75f)
        val timeAdjusted = if (abs(wsolaSpeed - 1.0f) >= 0.03f) {
            wsolaTimeScale(samples, sampleRate, wsolaSpeed)
        } else {
            samples
        }
        val virtualSrcRate = (sampleRate * clampedPitch).roundToInt().coerceAtLeast(8000)
        return resampleMonoLinear(timeAdjusted, virtualSrcRate, sampleRate)
    }

    data class DuckingFilterState(var currentGain: Float = 1.0f)

    /**
     * Streaming block mixer for long-form videos (>8 minutes) so even 60-minute videos
     * mix with <10 MB RAM while keeping smooth ducking envelope across block boundaries.
     */
    fun mixDubBlockIntoStereo(
        originalStereoBlock: ShortArray?,
        blockStartSample: Int,
        blockLengthSamples: Int,
        sampleRate: Int,
        overlappingClips: List<TimedPcmClip>,
        backgroundGainDuringSpeech: Float,
        duckingState: DuckingFilterState
    ): ShortArray {
        val blockLen = blockLengthSamples.coerceAtLeast(1)
        val duckEnvelope = FloatArray(blockLen) { 1.0f }

        for (clip in overlappingClips) {
            val relStart = (clip.startSample - blockStartSample).coerceIn(0, blockLen - 1)
            val relEnd = (clip.startSample + clip.monoSamples.size - blockStartSample).coerceIn(relStart, blockLen)
            for (i in relStart until relEnd) {
                duckEnvelope[i] = backgroundGainDuringSpeech
            }
        }

        val smoothWindow = (sampleRate * 0.08f).roundToInt().coerceAtLeast(1)
        val alpha = 1.0f / smoothWindow.toFloat()
        var currentGain = duckingState.currentGain
        for (i in 0 until blockLen) {
            currentGain += (duckEnvelope[i] - currentGain) * alpha
            duckEnvelope[i] = currentGain
        }
        duckingState.currentGain = currentGain

        val dubTrack = FloatArray(blockLen)
        val fadeSamples = (sampleRate * 0.020f).roundToInt().coerceAtLeast(1)
        for (clip in overlappingClips) {
            val src = clip.monoSamples
            val len = src.size
            for (i in 0 until len) {
                val destRel = (clip.startSample + i) - blockStartSample
                if (destRel !in 0 until blockLen) continue
                val fadeIn = if (i < fadeSamples) i.toFloat() / fadeSamples else 1.0f
                val fadeOut = if (len - 1 - i < fadeSamples) (len - 1 - i).toFloat() / fadeSamples else 1.0f
                val env = min(fadeIn, fadeOut).coerceIn(0f, 1f)
                dubTrack[destRel] += (src[i].toFloat() / 32768f) * env * 1.15f
            }
        }

        val stereoOut = ShortArray(blockLen * 2)
        for (i in 0 until blockLen) {
            val duck = duckEnvelope[i]
            val origL = if (originalStereoBlock != null && (i * 2) < originalStereoBlock.size) {
                (originalStereoBlock[i * 2].toFloat() / 32768f) * duck
            } else 0f
            val origR = if (originalStereoBlock != null && (i * 2 + 1) < originalStereoBlock.size) {
                (originalStereoBlock[i * 2 + 1].toFloat() / 32768f) * duck
            } else origL

            val dub = dubTrack[i]
            stereoOut[i * 2] = softClipSample(origL + dub)
            stereoOut[i * 2 + 1] = softClipSample(origR + dub)
        }
        return stereoOut
    }

    private fun softClipSample(normalized: Float): Short {
        val shaped = if (abs(normalized) > 0.92f) {
            tanh(normalized.toDouble()).toFloat()
        } else {
            normalized
        }
        return (shaped * 32767f).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}

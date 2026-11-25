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

    /**
     * 4-tap Catmull-Rom cubic interpolation for clean vocal upsampling (16kHz / 22kHz -> 44.1kHz)
     * without the harsh metallic aliasing caused by linear upsampling.
     */
    fun resampleMonoCubic(samples: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (samples.size < 4 || srcRate == dstRate || srcRate <= 0 || dstRate <= 0) {
            return resampleMonoLinear(samples, srcRate, dstRate)
        }
        val outLen = ((samples.size.toLong() * dstRate.toLong()) / srcRate.toLong()).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        val step = srcRate.toDouble() / dstRate.toDouble()
        val last = samples.lastIndex

        for (i in 0 until outLen) {
            val pos = i * step
            val p1 = pos.toInt().coerceIn(0, last)
            val t = (pos - p1).toFloat()
            val p0 = (p1 - 1).coerceAtLeast(0)
            val p2 = (p1 + 1).coerceAtMost(last)
            val p3 = (p1 + 2).coerceAtMost(last)

            val y0 = samples[p0].toFloat()
            val y1 = samples[p1].toFloat()
            val y2 = samples[p2].toFloat()
            val y3 = samples[p3].toFloat()

            val t2 = t * t
            val t3 = t2 * t
            val valFloat = 0.5f * (
                (2f * y1) +
                    (-y0 + y2) * t +
                    (2f * y0 - 5f * y1 + 4f * y2 - y3) * t2 +
                    (-y0 + 3f * y1 - 3f * y2 + y3) * t3
                )
            out[i] = valFloat.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /**
     * Broadcast studio vocal mastering chain:
     * 1) 85Hz high-pass filter to remove sub-rumble / DC thump
     * 2) Optional gentle ~6.8kHz warmth low-pass for 16kHz VITS/MMS neural synthesis to tame digital buzz
     * 3) Gentle RMS vocal loudness leveling + soft limiter so dialogue sits punchy & clear over BGM
     */
    fun masterDubVoiceClip(
        samples: ShortArray,
        sampleRate: Int,
        applyDeBuzzFilter: Boolean = false
    ): ShortArray {
        if (samples.size < 64) return samples

        val f = FloatArray(samples.size) { samples[it].toFloat() / 32768f }

        // 1) 85Hz 1st-order high-pass filter
        val rcHp = 1.0f / (2.0f * Math.PI.toFloat() * 85f)
        val dt = 1.0f / sampleRate.toFloat()
        val alphaHp = rcHp / (rcHp + dt)
        var prevX = f[0]
        var prevY = 0f
        for (i in f.indices) {
            val x = f[i]
            val y = alphaHp * (prevY + x - prevX)
            prevX = x
            prevY = y
            f[i] = y
        }

        // 2) Warm low-pass (~7.0kHz) to polish 16kHz neural TTS buzz
        if (applyDeBuzzFilter) {
            val rcLp = 1.0f / (2.0f * Math.PI.toFloat() * 7000f)
            val alphaLp = dt / (rcLp + dt)
            var lp = f[0]
            for (i in f.indices) {
                lp += alphaLp * (f[i] - lp)
                f[i] = lp
            }
        }

        // 3) Measure active speech RMS (ignoring silent frames) for consistent vocal loudness
        var energySum = 0.0
        var activeCount = 0
        for (v in f) {
            if (abs(v) > 0.015f) {
                energySum += v * v
                activeCount++
            }
        }
        val rms = if (activeCount > 32) kotlin.math.sqrt(energySum / activeCount).toFloat() else 0f
        val targetRms = 0.21f
        val gain = if (rms > 0.005f) (targetRms / rms).coerceIn(0.65f, 2.6f) else 1.0f

        return ShortArray(f.size) { idx ->
            softClipSample(f[idx] * gain)
        }
    }

    fun trimDigitalSilence(
        samples: ShortArray,
        sampleRate: Int,
        threshold: Int = 220,
        padMs: Int = 24
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
        val clampedSpeed = speedFactor.coerceIn(0.80f, 1.38f)

        val winSize = ((sampleRate * 0.044f).roundToInt() and 0xFFFE).coerceIn(512, 4096)
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

        val firstCopy = min(winSize, samples.size)
        for (i in 0 until firstCopy) {
            outAccum[i] += samples[i] * hann[i]
            normAccum[i] += hann[i]
        }
        outPos += synthesisHop
        inPos += analysisHop

        while (inPos + winSize < samples.size && outPos + winSize < estimatedOutLen) {
            var bestOffset = 0
            var bestScore = Float.NEGATIVE_INFINITY
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
                    k += 4
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

    data class DuckingFilterState(var currentGain: Float = 1.0f)

    data class VocalStripFilterState(
        var midBassLp: Float = 0f,
        var sideEnergySmooth: Float = 0f,
        var midHighEnergySmooth: Float = 0f
    )

    /**
     * Mixes time-aligned Filipino dubbed clips onto original background audio with optional
     * real Mid/Side center-vocal stripping so original foreign speech does NOT talk over the dub.
     */
    fun mixDubClipsIntoStereoTimeline(
        originalStereoPcm: ShortArray?,
        totalDurationSamplesPerChannel: Int,
        sampleRate: Int,
        dubClips: List<TimedPcmClip>,
        backgroundGainDuringSpeech: Float,
        stripCenterVocals: Boolean = false
    ): ShortArray {
        return mixDubBlockIntoStereo(
            originalStereoBlock = originalStereoPcm,
            blockStartSample = 0,
            blockLengthSamples = totalDurationSamplesPerChannel.coerceAtLeast(1),
            sampleRate = sampleRate,
            overlappingClips = dubClips,
            backgroundGainDuringSpeech = backgroundGainDuringSpeech,
            duckingState = DuckingFilterState(1.0f),
            stripCenterVocals = stripCenterVocals,
            vocalStripState = VocalStripFilterState()
        )
    }

    /**
     * Duration-preserving pitch shift using WSOLA + cubic resampling.
     */
    fun pitchShiftMono(samples: ShortArray, sampleRate: Int, pitchFactor: Float): ShortArray {
        val clampedPitch = pitchFactor.coerceIn(0.82f, 1.24f)
        if (samples.size < 1024 || abs(clampedPitch - 1.0f) < 0.03f) return samples
        val timeCompensated = wsolaTimeScale(samples, sampleRate, 1.0f / clampedPitch)
        val virtualSrcRate = (sampleRate * clampedPitch).roundToInt().coerceAtLeast(8000)
        return resampleMonoCubic(timeCompensated, virtualSrcRate, sampleRate)
    }

    /**
     * Single-pass combined lip-sync tempo fitting + character pitch lock.
     */
    fun timeAndPitchScaleMono(
        samples: ShortArray,
        sampleRate: Int,
        tempoSpeedFactor: Float,
        pitchFactor: Float
    ): ShortArray {
        if (samples.size < 1024) return samples
        val clampedTempo = tempoSpeedFactor.coerceIn(0.84f, 1.32f)
        val clampedPitch = pitchFactor.coerceIn(0.84f, 1.20f)
        val pitchActive = abs(clampedPitch - 1.0f) >= 0.03f
        val tempoActive = abs(clampedTempo - 1.0f) >= 0.04f

        if (!pitchActive && !tempoActive) return samples
        if (!pitchActive) return wsolaTimeScale(samples, sampleRate, clampedTempo)

        val wsolaSpeed = (clampedTempo / clampedPitch).coerceIn(0.76f, 1.48f)
        val timeAdjusted = if (abs(wsolaSpeed - 1.0f) >= 0.03f) {
            wsolaTimeScale(samples, sampleRate, wsolaSpeed)
        } else {
            samples
        }
        val virtualSrcRate = (sampleRate * clampedPitch).roundToInt().coerceAtLeast(8000)
        return resampleMonoCubic(timeAdjusted, virtualSrcRate, sampleRate)
    }

    /**
     * Streaming block mixer with smooth speech-window ducking AND optional Mid/Side + 175Hz
     * crossover center-vocal removal (preserves stereo BGM & sub-bass while silencing center dialogue).
     */
    fun mixDubBlockIntoStereo(
        originalStereoBlock: ShortArray?,
        blockStartSample: Int,
        blockLengthSamples: Int,
        sampleRate: Int,
        overlappingClips: List<TimedPcmClip>,
        backgroundGainDuringSpeech: Float,
        duckingState: DuckingFilterState,
        stripCenterVocals: Boolean = false,
        vocalStripState: VocalStripFilterState = VocalStripFilterState()
    ): ShortArray {
        val blockLen = blockLengthSamples.coerceAtLeast(1)
        val speechActiveMask = FloatArray(blockLen) { 0f }

        // Pad vocal suppression slightly (+/- 120ms around dubbed line) so breaths/tails of original voice don't bleed
        val padSamples = if (stripCenterVocals) (sampleRate * 0.12f).roundToInt() else 0
        for (clip in overlappingClips) {
            val relStart = (clip.startSample - padSamples - blockStartSample).coerceIn(0, blockLen - 1)
            val relEnd = (clip.startSample + clip.monoSamples.size + padSamples - blockStartSample).coerceIn(relStart, blockLen)
            for (i in relStart until relEnd) {
                speechActiveMask[i] = 1f
            }
        }

        val smoothWindow = (sampleRate * 0.08f).roundToInt().coerceAtLeast(1)
        val alphaEnv = 1.0f / smoothWindow.toFloat()
        val envelope = FloatArray(blockLen)
        var currentGain = duckingState.currentGain
        for (i in 0 until blockLen) {
            val target = if (speechActiveMask[i] > 0.5f) backgroundGainDuringSpeech else 1.0f
            currentGain += (target - currentGain) * alphaEnv
            envelope[i] = currentGain
        }
        duckingState.currentGain = currentGain

        val dubTrack = FloatArray(blockLen)
        val fadeSamples = (sampleRate * 0.018f).roundToInt().coerceAtLeast(1)
        for (clip in overlappingClips) {
            val src = clip.monoSamples
            val len = src.size
            for (i in 0 until len) {
                val destRel = (clip.startSample + i) - blockStartSample
                if (destRel !in 0 until blockLen) continue
                val fadeIn = if (i < fadeSamples) i.toFloat() / fadeSamples else 1.0f
                val fadeOut = if (len - 1 - i < fadeSamples) (len - 1 - i).toFloat() / fadeSamples else 1.0f
                val env = min(fadeIn, fadeOut).coerceIn(0f, 1f)
                dubTrack[destRel] += (src[i].toFloat() / 32768f) * env * 1.18f
            }
        }

        // 175Hz crossover & energy tracker for stereo Mid/Side vocal cancellation
        val dt = 1.0f / sampleRate.coerceAtLeast(8000).toFloat()
        val rcBass = 1.0f / (2.0f * Math.PI.toFloat() * 175f)
        val alphaBass = dt / (rcBass + dt)
        val alphaEnergy = 1.0f / (sampleRate * 0.05f).coerceAtLeast(1f)

        var midBassLp = vocalStripState.midBassLp
        var sideEnergy = vocalStripState.sideEnergySmooth
        var midHighEnergy = vocalStripState.midHighEnergySmooth

        val stereoOut = ShortArray(blockLen * 2)
        for (i in 0 until blockLen) {
            val rawL = if (originalStereoBlock != null && (i * 2) < originalStereoBlock.size) {
                originalStereoBlock[i * 2].toFloat() / 32768f
            } else 0f
            val rawR = if (originalStereoBlock != null && (i * 2 + 1) < originalStereoBlock.size) {
                originalStereoBlock[i * 2 + 1].toFloat() / 32768f
            } else rawL

            val envVal = envelope[i]
            val speechBlend = ((1.0f - envVal) / (1.0f - backgroundGainDuringSpeech).coerceAtLeast(0.01f))
                .coerceIn(0f, 1f)

            val bgmL: Float
            val bgmR: Float

            if (!stripCenterVocals) {
                bgmL = rawL * envVal
                bgmR = rawR * envVal
            } else {
                val mid = 0.5f * (rawL + rawR)
                val side = 0.5f * (rawL - rawR)
                midBassLp += alphaBass * (mid - midBassLp)
                val midHigh = mid - midBassLp

                sideEnergy += alphaEnergy * ((side * side) - sideEnergy)
                midHighEnergy += alphaEnergy * ((midHigh * midHigh) - midHighEnergy)

                // If audio has genuine stereo content (music/SFX in side channel), keep stereo side
                // while canceling dead-center vocals (midHigh). If audio is dual-mono (side ~ 0),
                // silence center vocals cleanly during speech so original voice does not clash.
                val stereoRatio = if (midHighEnergy > 1e-7f) {
                    (sideEnergy / midHighEnergy).coerceIn(0f, 1f)
                } else 0f
                val hasStereoMusic = stereoRatio > 0.03f

                // Keep subtle vocals when there's zero dubbed speech (intro/pauses),
                // strip 97% of center dialogue during speech (and keep BGM stereo side alive).
                val centerHighKeep = (1.0f - 0.97f * speechBlend).coerceIn(0.02f, 0.85f)
                val bassKeep = (0.75f - 0.42f * speechBlend).coerceIn(0.28f, 0.75f)
                val sideKeep = if (hasStereoMusic) {
                    (0.95f - 0.25f * speechBlend).coerceIn(0.65f, 0.95f)
                } else {
                    0f
                }

                // Smooth mono fallback gain if L == R (so original mono dialogue doesn't leak)
                val strippedMid = (midBassLp * bassKeep) + (midHigh * centerHighKeep)
                val monoFallback = mid * envVal
                val finalMid = if (hasStereoMusic) strippedMid else (strippedMid * 0.25f + monoFallback * 0.75f)
                val finalSide = side * sideKeep

                bgmL = finalMid + finalSide
                bgmR = finalMid - finalSide
            }

            val dub = dubTrack[i]
            stereoOut[i * 2] = softClipSample(bgmL + dub)
            stereoOut[i * 2 + 1] = softClipSample(bgmR + dub)
        }

        vocalStripState.midBassLp = midBassLp
        vocalStripState.sideEnergySmooth = sideEnergy
        vocalStripState.midHighEnergySmooth = midHighEnergy

        return stereoOut
    }

    private fun softClipSample(normalized: Float): Short {
        val shaped = if (abs(normalized) > 0.90f) {
            tanh(normalized.toDouble()).toFloat()
        } else {
            normalized
        }
        return (shaped * 32767f).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}

package com.example.data.dubbing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class SpeechVadSegment(
    val startMs: Long,
    val endMs: Long,
    val mono16k: ShortArray
)

/**
 * Adaptive Voice Activity Detection (VAD) & natural-pause dialogue segmenter.
 * Splits long MP4 audio tracks into clean speech turns (0.6s .. 18s) ready for Whisper ONNX,
 * skipping silent/music-only gaps to avoid hallucinations and keep exact lip-sync timestamps.
 */
object AudioSpeechVadSegmenter {

    fun segmentSpeechWindows(
        mono16k: ShortArray,
        sampleRate: Int = 16000,
        baseOffsetMs: Long = 0L
    ): List<SpeechVadSegment> {
        if (mono16k.size < sampleRate / 2) return emptyList()

        val frameSamples = (sampleRate * 0.030f).roundToInt().coerceAtLeast(160) // 30ms frames
        val frameMs = 30L
        val numFrames = mono16k.size / frameSamples
        if (numFrames < 4) return emptyList()

        val rmsFrames = FloatArray(numFrames)
        for (f in 0 until numFrames) {
            val start = f * frameSamples
            var sumSq = 0.0
            for (i in 0 until frameSamples) {
                val s = mono16k[start + i].toDouble() / 32768.0
                sumSq += s * s
            }
            rmsFrames[f] = sqrt(sumSq / frameSamples).toFloat()
        }

        // Estimate adaptive noise floor from quieter 25th percentile frames
        val nonTiny = rmsFrames.filter { it > 0.0005f }.sorted()
        if (nonTiny.isEmpty()) return emptyList()
        val p25 = nonTiny[(nonTiny.size * 0.25f).toInt().coerceIn(0, nonTiny.lastIndex)]
        val peak = nonTiny.last()
        val threshold = max(p25 * 2.1f, peak * 0.12f).coerceIn(0.008f, 0.048f)

        // Smooth speech mask: bridge pauses shorter than 420ms (14 frames)
        val isVoiced = BooleanArray(numFrames) { rmsFrames[it] >= threshold }
        val maxBridgeFrames = 14
        var f = 0
        while (f < numFrames) {
            if (!isVoiced[f]) {
                val gapStart = f
                while (f < numFrames && !isVoiced[f]) f++
                val gapEnd = f
                if (gapStart > 0 && gapEnd < numFrames && (gapEnd - gapStart) <= maxBridgeFrames) {
                    for (k in gapStart until gapEnd) isVoiced[k] = true
                }
            } else {
                f++
            }
        }

        // Collect contiguous speech regions; split overly long monologues (>18s) at lowest energy valley
        val rawRanges = mutableListOf<Pair<Int, Int>>()
        var idx = 0
        val minFrames = 18 // ~540ms
        val maxFrames = 600 // ~18s (well within Whisper 30s context)
        while (idx < numFrames) {
            if (!isVoiced[idx]) {
                idx++
                continue
            }
            val startF = idx
            while (idx < numFrames && isVoiced[idx]) idx++
            val endF = idx

            if (endF - startF < minFrames) continue

            var cursor = startF
            while (cursor < endF) {
                if (endF - cursor <= maxFrames) {
                    rawRanges.add(cursor to endF)
                    break
                }
                // Find quietest dip between 11s and 18s to make a natural sentence split
                val searchStart = (cursor + 360).coerceAtMost(endF - 1)
                val searchEnd = (cursor + maxFrames).coerceAtMost(endF - 1)
                var bestSplit = searchEnd
                var bestEnergy = Float.MAX_VALUE
                for (k in searchStart..searchEnd) {
                    if (rmsFrames[k] < bestEnergy) {
                        bestEnergy = rmsFrames[k]
                        bestSplit = k
                    }
                }
                rawRanges.add(cursor to (bestSplit + 1).coerceAtMost(endF))
                cursor = (bestSplit + 1).coerceAtMost(endF)
            }
        }

        val padSamples = (sampleRate * 0.14f).roundToInt()
        return rawRanges.mapNotNull { (sf, ef) ->
            val rawStartSample = sf * frameSamples
            val rawEndSample = (ef * frameSamples).coerceAtMost(mono16k.size)
            val sSample = (rawStartSample - padSamples).coerceAtLeast(0)
            val eSample = (rawEndSample + padSamples).coerceAtMost(mono16k.size)
            if (eSample - sSample < sampleRate / 2) return@mapNotNull null

            val segStartMs = baseOffsetMs + ((rawStartSample.toLong() * 1000L) / sampleRate)
            val segEndMs = baseOffsetMs + ((rawEndSample.toLong() * 1000L) / sampleRate)
            SpeechVadSegment(
                startMs = segStartMs,
                endMs = segEndMs.coerceAtLeast(segStartMs + frameMs * minFrames),
                mono16k = mono16k.copyOfRange(sSample, eSample)
            )
        }
    }
}

/**
 * Exact OpenAI Whisper Log-Mel Spectrogram extractor in pure Kotlin.
 * Computes [1, nMels, 3000] features matching OpenAI's `log_mel_spectrogram`:
 * 16kHz audio -> 30s (480,000 samples) -> Hann STFT (n_fft=400, hop=160) -> Slaney Mel -> (log10 + 4)/4.
 */
object WhisperMelSpectrogram {

    const val SAMPLE_RATE = 16000
    const val N_FFT = 400
    const val HOP_LENGTH = 160
    const val CHUNK_SAMPLES = 480_000 // 30 seconds * 16kHz
    const val N_FRAMES = 3000
    private const val N_FREQS = N_FFT / 2 + 1 // 201

    private val hannWindow: FloatArray by lazy {
        FloatArray(N_FFT) { n ->
            (0.5 * (1.0 - cos(2.0 * PI * n / N_FFT))).toFloat()
        }
    }

    private val cosTable: Array<FloatArray> by lazy {
        Array(N_FREQS) { k ->
            FloatArray(N_FFT) { n ->
                cos(2.0 * PI * k * n / N_FFT).toFloat()
            }
        }
    }

    private val sinTable: Array<FloatArray> by lazy {
        Array(N_FREQS) { k ->
            FloatArray(N_FFT) { n ->
                sin(2.0 * PI * k * n / N_FFT).toFloat()
            }
        }
    }

    private val melFilters80: Array<FloatArray> by lazy { buildSlaneyMelFilterbank(80) }
    private val melFilters128: Array<FloatArray> by lazy { buildSlaneyMelFilterbank(128) }

    fun computeLogMel(samples16k: ShortArray, nMels: Int = 80): FloatArray {
        val filters = if (nMels == 128) melFilters128 else melFilters80
        val effectiveMels = filters.size

        val activeSamples = samples16k.size.coerceAtMost(CHUNK_SAMPLES)
        val halfFft = N_FFT / 2 // 200
        val padded = FloatArray(CHUNK_SAMPLES + N_FFT)
        for (i in 0 until activeSamples) {
            padded[halfFft + i] = samples16k[i].toFloat() / 32768f
        }
        // Reflection pad left edge for torch.stft(center=True)
        for (i in 0 until halfFft) {
            val reflectIdx = (halfFft - i).coerceAtMost(activeSamples - 1).coerceAtLeast(0)
            padded[i] = if (activeSamples > 0) padded[halfFft + reflectIdx] else 0f
        }

        // Only compute STFT on frames containing non-zero speech/audio (huge CPU speedup on short clips!)
        val activeFrames = if (activeSamples == 0) {
            1
        } else {
            ((halfFft + activeSamples) / HOP_LENGTH + 2).coerceIn(1, N_FRAMES)
        }

        val melEnergies = FloatArray(effectiveMels * N_FRAMES)
        val window = hannWindow
        val cosT = cosTable
        val sinT = sinTable
        val windowed = FloatArray(N_FFT)
        val power = FloatArray(N_FREQS)

        for (t in 0 until activeFrames) {
            val offset = t * HOP_LENGTH
            for (n in 0 until N_FFT) {
                windowed[n] = padded[offset + n] * window[n]
            }
            for (k in 0 until N_FREQS) {
                var re = 0f
                var im = 0f
                val ck = cosT[k]
                val sk = sinT[k]
                var n = 0
                while (n < N_FFT) {
                    val w0 = windowed[n]
                    val w1 = windowed[n + 1]
                    val w2 = windowed[n + 2]
                    val w3 = windowed[n + 3]
                    re += w0 * ck[n] + w1 * ck[n + 1] + w2 * ck[n + 2] + w3 * ck[n + 3]
                    im -= w0 * sk[n] + w1 * sk[n + 1] + w2 * sk[n + 2] + w3 * sk[n + 3]
                    n += 4
                }
                power[k] = re * re + im * im
            }

            for (m in 0 until effectiveMels) {
                val fm = filters[m]
                var sum = 0f
                for (k in 0 until N_FREQS) {
                    val w = fm[k]
                    if (w != 0f) sum += w * power[k]
                }
                melEnergies[m * N_FRAMES + t] = sum
            }
        }

        var maxLog = Float.NEGATIVE_INFINITY
        for (i in melEnergies.indices) {
            val clamped = max(melEnergies[i], 1e-10f)
            val lg = log10(clamped)
            melEnergies[i] = lg
            if (lg > maxLog) maxLog = lg
        }

        val floorLog = maxLog - 8.0f
        for (i in melEnergies.indices) {
            val clipped = max(melEnergies[i], floorLog)
            melEnergies[i] = (clipped + 4.0f) / 4.0f
        }
        return melEnergies
    }

    private fun hzToMelSlaney(freqHz: Double): Double {
        val fMin = 0.0
        val fSp = 200.0 / 3.0
        var mel = (freqHz - fMin) / fSp
        val minLogHz = 1000.0
        val minLogMel = (minLogHz - fMin) / fSp
        val logStep = ln(6.4) / 27.0
        if (freqHz >= minLogHz) {
            mel = minLogMel + ln(freqHz / minLogHz) / logStep
        }
        return mel
    }

    private fun melToHzSlaney(mel: Double): Double {
        val fMin = 0.0
        val fSp = 200.0 / 3.0
        var freq = fMin + fSp * mel
        val minLogHz = 1000.0
        val minLogMel = (minLogHz - fMin) / fSp
        val logStep = ln(6.4) / 27.0
        if (mel >= minLogMel) {
            freq = minLogHz * exp(logStep * (mel - minLogMel))
        }
        return freq
    }

    private fun buildSlaneyMelFilterbank(nMels: Int): Array<FloatArray> {
        val fMin = 0.0
        val fMax = SAMPLE_RATE / 2.0 // 8000 Hz
        val fftFreqs = DoubleArray(N_FREQS) { k -> k * (fMax / (N_FREQS - 1)) }

        val minMel = hzToMelSlaney(fMin)
        val maxMel = hzToMelSlaney(fMax)
        val melPoints = DoubleArray(nMels + 2) { m ->
            minMel + (maxMel - minMel) * m / (nMels + 1)
        }
        val hzPoints = DoubleArray(nMels + 2) { m -> melToHzSlaney(melPoints[m]) }

        return Array(nMels) { m ->
            val fLeft = hzPoints[m]
            val fCenter = hzPoints[m + 1]
            val fRight = hzPoints[m + 2]
            val enorm = 2.0 / (fRight - fLeft).coerceAtLeast(1e-9)

            FloatArray(N_FREQS) { k ->
                val f = fftFreqs[k]
                val lower = (f - fLeft) / (fCenter - fLeft).coerceAtLeast(1e-9)
                val upper = (fRight - f) / (fRight - fCenter).coerceAtLeast(1e-9)
                (max(0.0, min(lower, upper)) * enorm).toFloat()
            }
        }
    }
}

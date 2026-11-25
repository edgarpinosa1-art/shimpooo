package com.example.data.dubbing

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.data.model.DubLineItem
import com.example.data.model.DubSourceLanguage
import com.example.data.model.WhisperBrainTier
import com.example.data.network.NetworkClient
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "WhisperOnnxEngine"

/**
 * Heavyweight, 100% Open-Source On-Device Speech Recognition & Auto-Translation Brain
 * powered by OpenAI Whisper Base Multilingual (INT8 ONNX) running locally via ONNX Runtime.
 * Zero API keys, zero quotas, handles English, Japanese, Korean, Chinese, Spanish & Tagalog offline.
 */
class WhisperOnnxEngine(private val context: Context) {

    private val client = NetworkClient.httpClient
    private val moshi = Moshi.Builder().build()

    private fun modelDir(tier: WhisperBrainTier): File =
        File(context.filesDir, "models/${tier.folderName}").apply { mkdirs() }

    private fun encoderFile(tier: WhisperBrainTier): File = File(modelDir(tier), "encoder.int8.onnx")
    private fun decoderFile(tier: WhisperBrainTier): File = File(modelDir(tier), "decoder.int8.onnx")
    private fun tokensFile(tier: WhisperBrainTier): File = File(modelDir(tier), "tokens.txt")

    private var cachedEnv: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var loadedTier: WhisperBrainTier? = null
    private var tokenBytesMap: Map<Int, ByteArray>? = null
    private var metaConfig: WhisperMetaConfig = WhisperMetaConfig()
    private var offsetUsesInt32: Boolean = false

    private data class WhisperMetaConfig(
        val nMels: Int = 80,
        val nTextLayer: Int = 6,
        val nTextCtx: Int = 448,
        val nTextState: Int = 512,
        val sot: Long = 50258L,
        val eot: Long = 50257L,
        val translateTask: Long = 50358L,
        val transcribeTask: Long = 50359L,
        val noTimestamps: Long = 50363L,
        val langCodeToToken: Map<String, Long> = emptyMap(),
        val langTokenToCode: Map<Long, String> = emptyMap()
    )

    fun isModelReady(tier: WhisperBrainTier = WhisperBrainTier.LARGE_V3_TURBO): Boolean {
        return encoderFile(tier).exists() && encoderFile(tier).length() > 5_000_000L &&
            decoderFile(tier).exists() && decoderFile(tier).length() > 10_000_000L &&
            tokensFile(tier).exists() && tokensFile(tier).length() > 10_000L
    }

    fun getInstalledSizeMb(tier: WhisperBrainTier = WhisperBrainTier.LARGE_V3_TURBO): Double {
        if (!isModelReady(tier)) return 0.0
        val bytes = encoderFile(tier).length() + decoderFile(tier).length() + tokensFile(tier).length()
        return bytes / (1024.0 * 1024.0)
    }

    fun deleteModel(tier: WhisperBrainTier = WhisperBrainTier.LARGE_V3_TURBO) {
        if (loadedTier == tier) closeSessions()
        runCatching { encoderFile(tier).delete() }
        runCatching { decoderFile(tier).delete() }
        runCatching { tokensFile(tier).delete() }
    }

    fun closeSessions() {
        runCatching { encoderSession?.close() }
        runCatching { decoderSession?.close() }
        encoderSession = null
        decoderSession = null
        loadedTier = null
        tokenBytesMap = null
    }

    suspend fun downloadModel(
        tier: WhisperBrainTier = WhisperBrainTier.LARGE_V3_TURBO,
        onProgress: (stage: String, fraction: Float?) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val dir = modelDir(tier)
        val encTarget = encoderFile(tier)
        val decTarget = decoderFile(tier)
        val tokTarget = tokensFile(tier)
        try {
            onProgress("Resolving ${tier.label} ONNX (${tier.approxSizeLabel})...", 0.02f)
            val urls = resolveWhisperDownloadUrls(tier)
                ?: return@withContext Result.failure(
                    IllegalStateException("Hindi ma-reach ang Hugging Face ${tier.label} repository. Suriin ang internet connection.")
                )

            val (encUrl, decUrl, tokUrl) = urls

            onProgress("Downloading ${tier.label} tokens vocabulary...", 0.05f)
            val tmpTok = File(dir, "tokens.txt.part")
            downloadFile(tokUrl, tmpTok) { _, _ -> }
            if (tmpTok.length() < 5_000L) throw IllegalStateException("Whisper tokens download failed.")
            if (tokTarget.exists()) tokTarget.delete()
            tmpTok.renameTo(tokTarget)

            onProgress("Downloading ${tier.label} Encoder (INT8 ONNX)...", 0.10f)
            val tmpEnc = File(dir, "encoder.int8.onnx.part")
            downloadFile(encUrl, tmpEnc) { copied, total ->
                val frac = if (total > 0L) (0.10f + 0.45f * (copied.toFloat() / total.toFloat())).coerceIn(0.10f, 0.55f) else 0.30f
                val mb = String.format(Locale.US, "%.1f", copied / (1024.0 * 1024.0))
                val totMb = if (total > 0L) String.format(Locale.US, "%.1f", total / (1024.0 * 1024.0)) else "?"
                onProgress("Downloading Encoder ($mb / $totMb MB)...", frac)
            }
            if (tmpEnc.length() < 5_000_000L) throw IllegalStateException("Whisper encoder download incomplete.")
            if (encTarget.exists()) encTarget.delete()
            tmpEnc.renameTo(encTarget)

            onProgress("Downloading ${tier.label} Decoder (INT8 ONNX)...", 0.56f)
            val tmpDec = File(dir, "decoder.int8.onnx.part")
            downloadFile(decUrl, tmpDec) { copied, total ->
                val frac = if (total > 0L) (0.56f + 0.41f * (copied.toFloat() / total.toFloat())).coerceIn(0.56f, 0.97f) else 0.75f
                val mb = String.format(Locale.US, "%.1f", copied / (1024.0 * 1024.0))
                val totMb = if (total > 0L) String.format(Locale.US, "%.1f", total / (1024.0 * 1024.0)) else "?"
                onProgress("Downloading Brain Decoder ($mb / $totMb MB)...", frac)
            }
            if (tmpDec.length() < 10_000_000L) throw IllegalStateException("Whisper decoder download incomplete.")
            if (decTarget.exists()) decTarget.delete()
            tmpDec.renameTo(decTarget)

            onProgress("Initializing ${tier.label} ONNX Runtime session...", 0.99f)
            ensureSessionsLoaded(tier)
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "Whisper model download/init failed", t)
            runCatching { File(dir, "tokens.txt.part").delete() }
            runCatching { File(dir, "encoder.int8.onnx.part").delete() }
            runCatching { File(dir, "decoder.int8.onnx.part").delete() }
            Result.failure(if (t is Exception) t else IllegalStateException("Kulang sa RAM o storage para sa ${tier.label}: ${t.message}"))
        }
    }

    suspend fun transcribeMp4ToLines(
        sourceUriString: String,
        sourceHint: DubSourceLanguage,
        tier: WhisperBrainTier = WhisperBrainTier.LARGE_V3_TURBO,
        onProgress: (String, Float?) -> Unit
    ): Result<List<DubLineItem>> = withContext(Dispatchers.IO) {
        try {
            if (!isModelReady(tier)) {
                return@withContext Result.failure(
                    IllegalStateException("Hindi pa naka-download ang ${tier.label} model.")
                )
            }
            onProgress("Loading ${tier.label}...", 0.03f)
            ensureSessionsLoaded(tier)

            onProgress("Decoding MP4 audio & finding dialogue turns...", 0.06f)
            val windows = extractVadSegmentsFromMp4(sourceUriString)
            if (windows.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Walang malinaw na boses na na-detect sa loob ng MP4.")
                )
            }

            val items = mutableListOf<DubLineItem>()
            var nextId = 1L
            for ((idx, seg) in windows.withIndex()) {
                val frac = 0.08f + 0.16f * (idx.toFloat() / windows.size.toFloat())
                onProgress("Whisper AI listening (${idx + 1}/${windows.size})...", frac)

                val text = decodeSegment(seg.mono16k, sourceHint, tier).trim()
                if (text.isNotBlank() && !isWhisperNoiseArtifact(text)) {
                    items.add(
                        DubLineItem(
                            id = nextId++,
                            startMs = seg.startMs,
                            endMs = seg.endMs,
                            speaker = "Speaker 1",
                            originalText = text,
                            filipinoText = text
                        )
                    )
                }
            }

            if (items.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Walang na-transcribe na dialogue mula sa video.")
                )
            }
            Result.success(items)
        } catch (t: Throwable) {
            Log.e(TAG, "Whisper ONNX transcription failed", t)
            Result.failure(if (t is Exception) t else IllegalStateException("Whisper AI runtime error: ${t.message}"))
        }
    }

    private fun decodeSegment(
        mono16k: ShortArray,
        sourceHint: DubSourceLanguage,
        tier: WhisperBrainTier
    ): String {
        val env = cachedEnv ?: return ""
        val enc = encoderSession ?: return ""
        val dec = decoderSession ?: return ""
        val meta = metaConfig

        val mel = WhisperMelSpectrogram.computeLogMel(mono16k, meta.nMels)
        val melTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(mel),
            longArrayOf(1L, meta.nMels.toLong(), WhisperMelSpectrogram.N_FRAMES.toLong())
        )

        var crossK: OnnxTensor? = null
        var crossV: OnnxTensor? = null
        return try {
            val encOut = enc.run(mapOf("mel" to melTensor))
            try {
                // Extract cross K/V as raw float buffers so we control lifecycle safely across decoder steps
                val rawCrossK = encOut.get("n_layer_cross_k").orElse(null)?.value ?: encOut[0].value
                val rawCrossV = encOut.get("n_layer_cross_v").orElse(null)?.value ?: encOut[1].value
                val crossInfoK = encOut.get("n_layer_cross_k").orElse(null)?.info as? TensorInfo
                    ?: encOut[0].info as TensorInfo
                val crossShape = crossInfoK.shape

                crossK = OnnxTensor.createTensor(env, FloatBuffer.wrap(flattenToFloatArray(rawCrossK)), crossShape)
                crossV = OnnxTensor.createTensor(env, FloatBuffer.wrap(flattenToFloatArray(rawCrossV)), crossShape)
            } finally {
                runCatching { encOut.close() }
            }

            val ck = crossK ?: return ""
            val cv = crossV ?: return ""

            // Determine language token via Whisper's acoustic logits if AUTO_DETECT
            val forcedCode = when (sourceHint) {
                DubSourceLanguage.ENGLISH -> "en"
                DubSourceLanguage.JAPANESE -> "ja"
                else -> null
            }
            val detectedCode = forcedCode ?: detectLanguageCode(env, dec, ck, cv, meta)
            val langToken = meta.langCodeToToken[detectedCode]
                ?: meta.langCodeToToken["en"]
                ?: (meta.sot + 1L)

            // Large-v3-Turbo is strictly optimized for transcribe; ML Kit natively translates EN/JA/KO/ZH/ES/etc. -> Tagalog!
            val mlKitSupportedCodes = setOf(
                "en", "tl", "ja", "ko", "zh", "es", "fr", "de", "it",
                "pt", "ru", "id", "ms", "th", "vi", "ar", "hi", "nl", "tr", "pl", "uk"
            )
            val taskToken = if (tier == WhisperBrainTier.LARGE_V3_TURBO || detectedCode in mlKitSupportedCodes) {
                meta.transcribeTask
            } else {
                meta.translateTask
            }

            val prompt = longArrayOf(meta.sot, langToken, taskToken, meta.noTimestamps)
            val generatedIds = runGreedyDecoder(env, dec, ck, cv, prompt, meta)
            decodeTokenIds(generatedIds)
        } finally {
            runCatching { melTensor.close() }
            runCatching { crossK?.close() }
            runCatching { crossV?.close() }
        }
    }

    private fun detectLanguageCode(
        env: OrtEnvironment,
        dec: OrtSession,
        crossK: OnnxTensor,
        crossV: OnnxTensor,
        meta: WhisperMetaConfig
    ): String {
        if (meta.langTokenToCode.isEmpty()) return "en"
        val cacheShape = longArrayOf(
            meta.nTextLayer.toLong(),
            1L,
            meta.nTextCtx.toLong(),
            meta.nTextState.toLong()
        )
        val zeros = FloatArray(meta.nTextLayer * meta.nTextCtx * meta.nTextState)
        val selfK = OnnxTensor.createTensor(env, FloatBuffer.wrap(zeros), cacheShape)
        val selfV = OnnxTensor.createTensor(env, FloatBuffer.wrap(zeros), cacheShape)
        val tok = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(meta.sot)), longArrayOf(1L, 1L))
        val off = createOffsetTensor(env, 0L)

        return try {
            val out = dec.run(
                mapOf(
                    "tokens" to tok,
                    "in_n_layer_self_k_cache" to selfK,
                    "in_n_layer_self_v_cache" to selfV,
                    "n_layer_cross_k" to crossK,
                    "n_layer_cross_v" to crossV,
                    "offset" to off
                )
            )
            out.use { res ->
                val logits = extractLastStepLogits(res[0].value)
                var bestToken = -1L
                var bestVal = Float.NEGATIVE_INFINITY
                for ((tokId, _) in meta.langTokenToCode) {
                    val idx = tokId.toInt()
                    if (idx in logits.indices && logits[idx] > bestVal) {
                        bestVal = logits[idx]
                        bestToken = tokId
                    }
                }
                meta.langTokenToCode[bestToken] ?: "en"
            }
        } catch (_: Exception) {
            "en"
        } finally {
            runCatching { selfK.close() }
            runCatching { selfV.close() }
            runCatching { tok.close() }
            runCatching { off.close() }
        }
    }

    private fun runGreedyDecoder(
        env: OrtEnvironment,
        dec: OrtSession,
        crossK: OnnxTensor,
        crossV: OnnxTensor,
        initialPrompt: LongArray,
        meta: WhisperMetaConfig
    ): List<Int> {
        val cacheShape = longArrayOf(
            meta.nTextLayer.toLong(),
            1L,
            meta.nTextCtx.toLong(),
            meta.nTextState.toLong()
        )
        val cacheElements = meta.nTextLayer * meta.nTextCtx * meta.nTextState
        var curSelfK = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(cacheElements)), cacheShape)
        var curSelfV = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(cacheElements)), cacheShape)

        val generated = mutableListOf<Int>()
        var currentTokens = initialPrompt
        var currentOffset = 0L
        val maxGenSteps = (meta.nTextCtx - initialPrompt.size - 4).coerceIn(32, 160)

        try {
            for (step in 0 until maxGenSteps) {
                val tokTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(currentTokens),
                    longArrayOf(1L, currentTokens.size.toLong())
                )
                val offTensor = createOffsetTensor(env, currentOffset)

                var nextToken = meta.eot.toInt()
                var nextSelfK: OnnxTensor? = null
                var nextSelfV: OnnxTensor? = null

                try {
                    val out = dec.run(
                        mapOf(
                            "tokens" to tokTensor,
                            "in_n_layer_self_k_cache" to curSelfK,
                            "in_n_layer_self_v_cache" to curSelfV,
                            "n_layer_cross_k" to crossK,
                            "n_layer_cross_v" to crossV,
                            "offset" to offTensor
                        )
                    )
                    out.use { res ->
                        val logits = extractLastStepLogits(res[0].value)
                        nextToken = pickBestTextToken(logits, meta.eot.toInt(), generated)

                        if (nextToken != meta.eot.toInt()) {
                            val rawOutK = res.get("out_n_layer_self_k_cache").orElse(null)?.value ?: res[1].value
                            val rawOutV = res.get("out_n_layer_self_v_cache").orElse(null)?.value ?: res[2].value
                            nextSelfK = OnnxTensor.createTensor(env, FloatBuffer.wrap(flattenToFloatArray(rawOutK)), cacheShape)
                            nextSelfV = OnnxTensor.createTensor(env, FloatBuffer.wrap(flattenToFloatArray(rawOutV)), cacheShape)
                        }
                    }
                } finally {
                    runCatching { tokTensor.close() }
                    runCatching { offTensor.close() }
                }

                if (nextToken == meta.eot.toInt()) break
                generated.add(nextToken)

                // Guard against Whisper repeat-loop hallucinations
                if (hasRepetitionLoop(generated)) break

                runCatching { curSelfK.close() }
                runCatching { curSelfV.close() }
                curSelfK = nextSelfK ?: break
                curSelfV = nextSelfV ?: break

                currentOffset += currentTokens.size
                currentTokens = longArrayOf(nextToken.toLong())
            }
        } finally {
            runCatching { curSelfK.close() }
            runCatching { curSelfV.close() }
        }

        return generated
    }

    private fun pickBestTextToken(logits: FloatArray, eotId: Int, history: List<Int>): Int {
        var bestId = eotId
        var bestVal = Float.NEGATIVE_INFINITY
        val limit = eotId.coerceAtMost(logits.lastIndex)
        val prevTok = history.lastOrNull() ?: -1

        for (i in 0..limit) {
            var score = logits[i]
            // Gentle penalty on immediate duplicate token to keep speech crisp
            if (i == prevTok && i != eotId) {
                score -= 1.2f
            }
            if (score > bestVal) {
                bestVal = score
                bestId = i
            }
        }
        return bestId
    }

    private fun hasRepetitionLoop(tokens: List<Int>): Boolean {
        val n = tokens.size
        if (n >= 6 && tokens[n - 1] == tokens[n - 2] && tokens[n - 2] == tokens[n - 3] && tokens[n - 3] == tokens[n - 4]) {
            return true
        }
        if (n >= 9) {
            val a = tokens.subList(n - 3, n)
            val b = tokens.subList(n - 6, n - 3)
            val c = tokens.subList(n - 9, n - 6)
            if (a == b && b == c) return true
        }
        return false
    }

    private fun decodeTokenIds(ids: List<Int>): String {
        val map = tokenBytesMap ?: return ""
        val baos = ByteArrayOutputStream()
        for (id in ids) {
            val bytes = map[id] ?: continue
            baos.write(bytes)
        }
        return baos.toByteArray().toString(Charsets.UTF_8)
            .replace(Regex("""\[[^\]]+]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun extractVadSegmentsFromMp4(sourceUriString: String): List<SpeechVadSegment> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val segments = mutableListOf<SpeechVadSegment>()

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
            if (audioTrack == -1 || audioFormat == null) return emptyList()

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

            val windowMaxSamples = 16000 * 120 // 2-minute streaming blocks -> tiny RAM!
            val currentWindow = ArrayList<Short>(windowMaxSamples)
            var currentWindowBaseMs = 0L
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
                            if (size < 0) {
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
                            for (s in mono16k) {
                                currentWindow.add(s)
                            }
                            totalOutSamples += mono16k.size

                            if (currentWindow.size >= windowMaxSamples) {
                                segments.addAll(
                                    AudioSpeechVadSegmenter.segmentSpeechWindows(
                                        mono16k = currentWindow.toShortArray(),
                                        sampleRate = 16000,
                                        baseOffsetMs = currentWindowBaseMs
                                    )
                                )
                                currentWindow.clear()
                                currentWindowBaseMs = (totalOutSamples * 1000L) / 16000L
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (currentWindow.isNotEmpty()) {
                segments.addAll(
                    AudioSpeechVadSegmenter.segmentSpeechWindows(
                        mono16k = currentWindow.toShortArray(),
                        sampleRate = 16000,
                        baseOffsetMs = currentWindowBaseMs
                    )
                )
            }
            return segments
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

    @Synchronized
    private fun ensureSessionsLoaded(tier: WhisperBrainTier) {
        if (loadedTier == tier && encoderSession != null && decoderSession != null && tokenBytesMap != null) return
        if (loadedTier != null && loadedTier != tier) closeSessions()

        val env = cachedEnv ?: OrtEnvironment.getEnvironment().also { cachedEnv = it }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
        }
        val enc = env.createSession(encoderFile(tier).absolutePath, opts)
        val dec = env.createSession(decoderFile(tier).absolutePath, opts)

        val meta = enc.metadata.customMetadata
        val langToks = meta["all_language_tokens"]?.split(',')?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()
        val langCodes = meta["all_language_codes"]?.split(',')?.map { it.trim() }.orEmpty()
        val c2t = linkedMapOf<String, Long>()
        val t2c = linkedMapOf<Long, String>()
        for (i in 0 until minOf(langToks.size, langCodes.size)) {
            if (langCodes[i].isNotBlank()) {
                c2t[langCodes[i]] = langToks[i]
                t2c[langToks[i]] = langCodes[i]
            }
        }

        metaConfig = WhisperMetaConfig(
            nMels = meta["n_mels"]?.toIntOrNull()?.coerceIn(40, 128) ?: 80,
            nTextLayer = meta["n_text_layer"]?.toIntOrNull()?.coerceIn(2, 32) ?: 6,
            nTextCtx = meta["n_text_ctx"]?.toIntOrNull()?.coerceIn(64, 1024) ?: 448,
            nTextState = meta["n_text_state"]?.toIntOrNull()?.coerceIn(128, 2048) ?: 512,
            sot = meta["sot"]?.toLongOrNull() ?: 50258L,
            eot = meta["eot"]?.toLongOrNull() ?: 50257L,
            translateTask = meta["translate"]?.toLongOrNull() ?: 50358L,
            transcribeTask = meta["transcribe"]?.toLongOrNull() ?: 50359L,
            noTimestamps = meta["no_timestamps"]?.toLongOrNull() ?: 50363L,
            langCodeToToken = c2t,
            langTokenToCode = t2c
        )

        val offsetInfo = dec.inputInfo["offset"]?.info as? TensorInfo
        offsetUsesInt32 = offsetInfo?.type == OnnxJavaType.INT32

        tokenBytesMap = parseBase64TokensFile(tokensFile(tier))
        encoderSession = enc
        decoderSession = dec
        loadedTier = tier
    }

    private fun createOffsetTensor(env: OrtEnvironment, value: Long): OnnxTensor {
        return if (offsetUsesInt32) {
            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(value.toInt())), longArrayOf(1L))
        } else {
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(value)), longArrayOf(1L))
        }
    }

    private fun extractLastStepLogits(raw: Any?): FloatArray {
        val arr3 = raw as? Array<*> ?: return FloatArray(0)
        val batch0 = arr3.firstOrNull() as? Array<*> ?: return FloatArray(0)
        return (batch0.lastOrNull() as? FloatArray) ?: FloatArray(0)
    }

    private fun flattenToFloatArray(raw: Any?): FloatArray {
        val out = ArrayList<Float>(32768)
        fun walk(node: Any?) {
            when (node) {
                is FloatArray -> for (v in node) out.add(v)
                is Array<*> -> for (child in node) walk(child)
            }
        }
        walk(raw)
        return out.toFloatArray()
    }

    private fun resolveWhisperDownloadUrls(tier: WhisperBrainTier): Triple<String, String, String>? {
        val reposToCheck = listOf(tier.repoId)
        for (repo in reposToCheck) {
            val tree = fetchTreeJson("https://huggingface.co/api/models/$repo/tree/main")
            if (tree != null) {
                val paths = tree.mapNotNull { (it as? Map<*, *>)?.get("path")?.toString() }
                val enc = paths.firstOrNull { it.endsWith("encoder.int8.onnx") }
                    ?: paths.firstOrNull { it.endsWith("encoder.onnx") }
                val dec = paths.firstOrNull { it.endsWith("decoder.int8.onnx") }
                    ?: paths.firstOrNull { it.endsWith("decoder.onnx") }
                val tok = paths.firstOrNull { it.endsWith("tokens.txt") }
                if (enc != null && dec != null && tok != null) {
                    val base = "https://huggingface.co/$repo/resolve/main"
                    return Triple("$base/$enc", "$base/$dec", "$base/$tok")
                }
            }
        }
        val prefix = when (tier) {
            WhisperBrainTier.LARGE_V3_TURBO -> "turbo"
            WhisperBrainTier.SMALL -> "small"
            WhisperBrainTier.BASE -> "base"
        }
        val baseUrl = "https://huggingface.co/${tier.repoId}/resolve/main"
        return Triple(
            "$baseUrl/$prefix-encoder.int8.onnx",
            "$baseUrl/$prefix-decoder.int8.onnx",
            "$baseUrl/$prefix-tokens.txt"
        )
    }

    private fun fetchTreeJson(url: String): List<*>? {
        return runCatching {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                moshi.adapter(List::class.java).fromJson(body)
            }
        }.getOrNull()
    }

    private fun downloadFile(
        url: String,
        dest: File,
        onProgress: (copied: Long, total: Long) -> Unit
    ) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkClient.DESKTOP_USER_AGENT)
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Download failed HTTP ${resp.code} for $url")
            }
            val body = resp.body ?: throw IllegalStateException("Empty download body")
            val total = body.contentLength()
            var copied = 0L
            val buf = ByteArray(32 * 1024)
            body.byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    while (true) {
                        val r = input.read(buf)
                        if (r == -1) break
                        output.write(buf, 0, r)
                        copied += r
                        onProgress(copied, total)
                    }
                }
            }
        }
    }

    companion object {
        fun parseBase64TokensFile(file: File): Map<Int, ByteArray> {
            return parseBase64TokensText(file.readText(Charsets.UTF_8))
        }

        fun parseBase64TokensText(raw: String): Map<Int, ByteArray> {
            val map = HashMap<Int, ByteArray>(52000)
            for (line in raw.lineSequence()) {
                if (line.isBlank()) continue
                val parts = line.trim().split(' ')
                if (parts.size >= 2) {
                    val b64 = parts[0]
                    val id = parts.last().toIntOrNull() ?: continue
                    val decoded = runCatching { decodeBase64(b64) }
                        .getOrElse { b64.toByteArray(Charsets.UTF_8) }
                    map[id] = decoded
                }
            }
            return map
        }

        private fun decodeBase64(input: String): ByteArray {
            val clean = input.trim().replace("=", "")
            val out = ByteArrayOutputStream((clean.length * 3) / 4 + 3)
            var buffer = 0
            var bits = 0
            for (ch in clean) {
                val val6 = when (ch) {
                    in 'A'..'Z' -> ch.code - 'A'.code
                    in 'a'..'z' -> ch.code - 'a'.code + 26
                    in '0'..'9' -> ch.code - '0'.code + 52
                    '+' -> 62
                    '/' -> 63
                    else -> continue
                }
                buffer = (buffer shl 6) or val6
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write((buffer shr bits) and 0xFF)
                }
            }
            return out.toByteArray()
        }

        fun isWhisperNoiseArtifact(text: String): Boolean {
            val clean = text.trim().lowercase(Locale.US)
            if (clean.length < 2) return true
            return clean in setOf(
                "thank you.", "thanks for watching.", "you", "bye.", "bye",
                "music", "applause", "silence", ".", "...", "mbc"
            )
        }
    }
}

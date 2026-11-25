package com.example.data.dubbing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.example.data.network.NetworkClient
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale

private const val TAG = "MmsTagalogOnnx"

class MmsTagalogOnnxEngine(private val context: Context) {

    private val client = NetworkClient.httpClient
    private val moshi = Moshi.Builder().build()

    private val modelDir: File
        get() = File(context.filesDir, "models/mms-tgl").apply { mkdirs() }

    private val modelFile: File
        get() = File(modelDir, "model.onnx")

    private val tokensFile: File
        get() = File(modelDir, "tokens.txt")

    private var cachedEnv: OrtEnvironment? = null
    private var cachedSession: OrtSession? = null
    private var cachedTokenMap: Map<String, Long>? = null
    private var cachedSampleRate: Int = 16000
    private var cachedAddBlank: Boolean = true

    fun isModelReady(): Boolean {
        return modelFile.exists() && modelFile.length() > 1_000_000L &&
            tokensFile.exists() && tokensFile.length() > 20L
    }

    fun getInstalledSizeMb(): Double {
        if (!isModelReady()) return 0.0
        return (modelFile.length() + tokensFile.length()) / (1024.0 * 1024.0)
    }

    fun deleteModel() {
        closeSession()
        runCatching { modelFile.delete() }
        runCatching { tokensFile.delete() }
    }

    fun closeSession() {
        runCatching { cachedSession?.close() }
        cachedSession = null
        cachedTokenMap = null
    }

    suspend fun downloadModel(
        onProgress: (stage: String, fraction: Float?) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            onProgress("Resolving open-source MMS Tagalog ONNX files...", null)
            val resolved = resolveHuggingFaceDownloadUrls()
                ?: return@withContext Result.failure(
                    IllegalStateException("Hindi ma-reach ang Hugging Face model repository. Suriin ang internet connection.")
                )

            val (onnxUrl, tokenUrl) = resolved

            onProgress("Downloading Tagalog tokens vocabulary...", 0.05f)
            val tempTokens = File(modelDir, "tokens.txt.part")
            downloadFile(tokenUrl, tempTokens) { _, _ -> }
            if (tempTokens.length() < 10L) {
                throw IllegalStateException("Downloaded tokens file was empty.")
            }
            if (tokensFile.exists()) tokensFile.delete()
            tempTokens.renameTo(tokensFile)

            onProgress("Downloading Meta MMS-TTS Tagalog ONNX (~62 MB)...", 0.10f)
            val tempModel = File(modelDir, "model.onnx.part")
            downloadFile(onnxUrl, tempModel) { copied, total ->
                val frac = if (total > 0L) {
                    (0.10f + 0.88f * (copied.toFloat() / total.toFloat())).coerceIn(0.10f, 0.98f)
                } else null
                val mbCopied = String.format(Locale.US, "%.1f", copied / (1024.0 * 1024.0))
                val mbTotal = if (total > 0L) String.format(Locale.US, "%.1f", total / (1024.0 * 1024.0)) else "?"
                onProgress("Downloading MMS Tagalog ONNX ($mbCopied / $mbTotal MB)...", frac)
            }

            if (tempModel.length() < 1_000_000L) {
                throw IllegalStateException("Downloaded ONNX model was incomplete.")
            }
            if (modelFile.exists()) modelFile.delete()
            tempModel.renameTo(modelFile)

            onProgress("Verifying ONNX Runtime session...", 0.99f)
            ensureSessionLoaded()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "MMS model download failed", e)
            runCatching { File(modelDir, "model.onnx.part").delete() }
            runCatching { File(modelDir, "tokens.txt.part").delete() }
            Result.failure(e)
        }
    }

    suspend fun synthesizeToWav(
        text: String,
        speedFactor: Float = 1.0f,
        destWavFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            ensureSessionLoaded()
            val env = cachedEnv ?: throw IllegalStateException("ORT environment unavailable.")
            val session = cachedSession ?: throw IllegalStateException("MMS ONNX session not initialized.")
            val tokenMap = cachedTokenMap ?: throw IllegalStateException("MMS tokens not loaded.")

            val normalizedText = normalizeTagalogForTts(text)
            val tokenIds = encodeTextToTokenIds(normalizedText, tokenMap, cachedAddBlank)
            if (tokenIds.isEmpty()) {
                // Produce brief 300ms silence if line had only punctuation
                val silence = ShortArray((cachedSampleRate * 0.3f).toInt())
                WavPcmTools.writeMonoWavFile(destWavFile, silence, cachedSampleRate)
                return@withContext Result.success(destWavFile)
            }

            val inputNames = session.inputNames.toSet()
            val xKey = when {
                "x" in inputNames -> "x"
                "input" in inputNames -> "input"
                else -> inputNames.first()
            }
            val lenKey = when {
                "x_length" in inputNames -> "x_length"
                "input_lengths" in inputNames -> "input_lengths"
                else -> null
            }

            val tensors = mutableListOf<OnnxTensor>()
            try {
                val feedMap = mutableMapOf<String, OnnxTensor>()

                val xTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(tokenIds),
                    longArrayOf(1L, tokenIds.size.toLong())
                ).also { tensors.add(it) }
                feedMap[xKey] = xTensor

                if (lenKey != null) {
                    val lenTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(longArrayOf(tokenIds.size.toLong())),
                        longArrayOf(1L)
                    ).also { tensors.add(it) }
                    feedMap[lenKey] = lenTensor
                }

                // In VITS: balanced noise scales give natural Tagalog inflection while preserving consistent timbre
                val lengthScale = (1.0f / speedFactor.coerceIn(0.82f, 1.32f))
                if ("noise_scale" in inputNames) {
                    val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.56f)), longArrayOf(1L))
                        .also { tensors.add(it) }
                    feedMap["noise_scale"] = t
                }
                if ("length_scale" in inputNames) {
                    val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(lengthScale)), longArrayOf(1L))
                        .also { tensors.add(it) }
                    feedMap["length_scale"] = t
                }
                if ("noise_scale_w" in inputNames) {
                    val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.78f)), longArrayOf(1L))
                        .also { tensors.add(it) }
                    feedMap["noise_scale_w"] = t
                }
                if ("sid" in inputNames) {
                    val t = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0L)), longArrayOf(1L))
                        .also { tensors.add(it) }
                    feedMap["sid"] = t
                }

                session.run(feedMap).use { outputs ->
                    val rawOutput = outputs[0].value
                    val waveform = extractFloatWaveform(rawOutput)
                    val pcm = WavPcmTools.floatArrayToPcm16(waveform)
                    WavPcmTools.writeMonoWavFile(destWavFile, pcm, cachedSampleRate)
                }
            } finally {
                tensors.forEach { runCatching { it.close() } }
            }

            Result.success(destWavFile)
        } catch (e: Exception) {
            Log.e(TAG, "MMS ONNX synthesis error", e)
            Result.failure(e)
        }
    }

    @Synchronized
    private fun ensureSessionLoaded() {
        if (!isModelReady()) {
            throw IllegalStateException("Hindi pa naka-download ang MMS Tagalog ONNX model.")
        }
        if (cachedSession != null && cachedTokenMap != null) return

        val env = cachedEnv ?: OrtEnvironment.getEnvironment().also { cachedEnv = it }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        }
        val session = env.createSession(modelFile.absolutePath, opts)
        val meta = session.metadata.customMetadata
        cachedSampleRate = meta["sample_rate"]?.toIntOrNull()?.coerceIn(8000, 48000) ?: 16000
        cachedAddBlank = (meta["add_blank"]?.toIntOrNull() ?: 1) != 0
        cachedTokenMap = loadTokenMap(tokensFile)
        cachedSession = session
    }

    private fun loadTokenMap(file: File): Map<String, Long> {
        val raw = file.readText(Charsets.UTF_8)
        if (raw.trimStart().startsWith("{")) {
            val map = runCatching { moshi.adapter(Map::class.java).fromJson(raw) }.getOrNull()
            if (map != null) {
                return buildMap {
                    for ((k, v) in map) {
                        val keyStr = k?.toString() ?: continue
                        val idVal = (v as? Number)?.toLong() ?: continue
                        put(keyStr, idVal)
                    }
                }
            }
        }
        return buildMap {
            raw.lines().forEach { line ->
                if (line.isBlank()) return@forEach
                val lastSpace = line.lastIndexOf(' ')
                if (lastSpace > 0) {
                    val token = line.substring(0, lastSpace)
                    val id = line.substring(lastSpace + 1).trim().toLongOrNull()
                    if (id != null) put(token, id)
                } else {
                    val id = line.trim().toLongOrNull()
                    if (id != null) put(" ", id)
                }
            }
        }
    }

    private fun resolveHuggingFaceDownloadUrls(): Pair<String, String>? {
        // 1) Inspect willwade/mms-tts-multilingual-models-onnx/tgl via HuggingFace Tree API
        val willwadeTree = fetchTreeJson("https://huggingface.co/api/models/willwade/mms-tts-multilingual-models-onnx/tree/main/tgl")
        if (willwadeTree != null) {
            var onnxPath: String? = null
            var tokensPath: String? = null
            for (item in willwadeTree) {
                val path = (item as? Map<*, *>)?.get("path")?.toString() ?: continue
                when {
                    path.endsWith(".onnx", ignoreCase = true) && !path.contains("quant", ignoreCase = true) -> {
                        onnxPath = path
                    }
                    path.endsWith("tokens.txt", ignoreCase = true) -> {
                        tokensPath = path
                    }
                }
            }
            if (onnxPath == null) {
                onnxPath = willwadeTree
                    .mapNotNull { (it as? Map<*, *>)?.get("path")?.toString() }
                    .firstOrNull { it.endsWith(".onnx", ignoreCase = true) }
            }
            if (onnxPath != null && tokensPath != null) {
                return "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/$onnxPath" to
                    "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/$tokensPath"
            }
        }

        // 2) Fallback: check Xenova/mms-tts-tgl tree
        val xenovaOnnx = "https://huggingface.co/Xenova/mms-tts-tgl/resolve/main/onnx/model.onnx"
        val xenovaVocab = "https://huggingface.co/Xenova/mms-tts-tgl/resolve/main/vocab.json"
        if (headOk(xenovaOnnx) && headOk(xenovaVocab)) {
            return xenovaOnnx to xenovaVocab
        }

        // 3) Direct known willwade paths fallback
        val directPairs = listOf(
            "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/tgl/model.onnx" to
                "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/tgl/tokens.txt",
            "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/tgl/tgl.onnx" to
                "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/tgl/tokens.txt"
        )
        return directPairs.firstOrNull { (m, t) -> headOk(m) && headOk(t) }
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

    private fun headOk(url: String): Boolean {
        val headPassed = runCatching {
            val req = Request.Builder().url(url).head().build()
            client.newCall(req).execute().use { it.isSuccessful || it.code == 302 }
        }.getOrDefault(false)
        if (headPassed) return true

        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .get()
                .build()
            client.newCall(req).execute().use { it.isSuccessful || it.code == 206 }
        }.getOrDefault(false)
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
            val buf = ByteArray(16 * 1024)
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

    private fun extractFloatWaveform(raw: Any?): FloatArray {
        return when (raw) {
            is FloatArray -> raw
            is Array<*> -> {
                val first = raw.firstOrNull()
                when (first) {
                    is FloatArray -> first
                    is Array<*> -> (first.firstOrNull() as? FloatArray) ?: FloatArray(0)
                    else -> FloatArray(0)
                }
            }
            else -> FloatArray(0)
        }
    }

    companion object {
        fun normalizeTagalogForTts(raw: String): String {
            return raw
                .replace("&", " at ")
                .replace("%", " porsyento ")
                .replace("₱", " piso ")
                .replace("$", " dolyar ")
                .replace(Regex("""\[[^\]]+]"""), " ") // strip [Music] cues
                .replace(Regex("""\s+"""), " ")
                .trim()
                .lowercase(Locale.US)
        }

        fun encodeTextToTokenIds(
            text: String,
            tokenMap: Map<String, Long>,
            addBlank: Boolean
        ): LongArray {
            val spaceId = tokenMap[" "] ?: tokenMap["<space>"]
            val blankId = tokenMap["<eps>"] ?: tokenMap["_"] ?: 0L
            val rawIds = mutableListOf<Long>()

            for (ch in text) {
                val key = ch.toString()
                val mapped = tokenMap[key] ?: if (ch.isWhitespace()) spaceId else null
                if (mapped != null) {
                    rawIds.add(mapped)
                }
            }
            if (rawIds.isEmpty()) return LongArray(0)
            if (!addBlank) return rawIds.toLongArray()

            val interleaved = LongArray(rawIds.size * 2 + 1)
            var idx = 0
            interleaved[idx++] = blankId
            for (id in rawIds) {
                interleaved[idx++] = id
                interleaved[idx++] = blankId
            }
            return interleaved
        }
    }
}

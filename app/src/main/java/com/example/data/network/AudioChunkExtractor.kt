package com.example.data.network

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import com.example.data.dubbing.WavPcmTools
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class ExtractedAudioChunk(
    val file: File,
    val mimeType: String,
    val formatExt: String,
    val startOffsetMs: Long,
    val durationMs: Long
)

class AudioChunkExtractor(private val context: Context) {

    companion object {
        private const val WHISPER_SAFE_CHUNK_MS = 6L * 60L * 1000L // 6 minutes
        private const val CHAT_AUDIO_SAFE_CHUNK_MS = 3L * 60L * 1000L // 3 minutes (~5.7 MB WAV)
        private const val MAX_CHUNK_FILE_BYTES = 20L * 1024L * 1024L
        private const val TARGET_WAV_SAMPLE_RATE = 16000
    }

    fun extractAudioChunks(
        uriString: String,
        durationHintMs: Long,
        workDir: File,
        forceWavForChatAudio: Boolean,
        onStageUpdate: (String) -> Unit
    ): List<ExtractedAudioChunk> {
        if (!workDir.exists()) workDir.mkdirs()
        val uri = Uri.parse(uriString)
        val effectiveDurationMs = resolveDurationMs(uri, durationHintMs)

        if (!forceWavForChatAudio) {
            onStageUpdate("Extracting fast audio chunks (<25 MB per part)...")
            val fastM4a = runCatching {
                extractFastAacM4aChunks(uri, effectiveDurationMs, workDir)
            }.getOrNull()

            if (!fastM4a.isNullOrEmpty() && fastM4a.all { it.file.length() in 1L..MAX_CHUNK_FILE_BYTES }) {
                return fastM4a
            }
            fastM4a?.forEach { runCatching { it.file.delete() } }
        }

        val chunkMs = if (forceWavForChatAudio) CHAT_AUDIO_SAFE_CHUNK_MS else WHISPER_SAFE_CHUNK_MS
        onStageUpdate("Decoding compact 16kHz speech audio chunks...")
        return decodeToMonoWavChunks(uri, effectiveDurationMs, chunkMs, workDir, onStageUpdate)
    }

    private fun extractFastAacM4aChunks(
        uri: Uri,
        effectiveDurationMs: Long,
        workDir: File
    ): List<ExtractedAudioChunk>? {
        val extractor = MediaExtractor()
        try {
            setExtractorDataSource(extractor, uri)
            val audioTrackIndex = findAudioTrack(extractor) ?: return null
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime != MediaFormat.MIMETYPE_AUDIO_AAC) {
                return null
            }

            val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                format.getInteger(MediaFormat.KEY_BIT_RATE).coerceAtLeast(32_000)
            } else {
                192_000
            }
            // Keep each chunk safely <= ~16 MB
            val maxByBitrateMs = ((16L * 1024L * 1024L * 8L * 1000L) / bitrate).coerceAtLeast(60_000L)
            val windowMs = minOf(WHISPER_SAFE_CHUNK_MS, maxByBitrateMs)
            val totalDurationMs = effectiveDurationMs.coerceAtLeast(
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION) / 1000L
                } else 0L
            )

            extractor.selectTrack(audioTrackIndex)
            val bufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
            } else {
                256 * 1024
            }
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            val chunks = mutableListOf<ExtractedAudioChunk>()
            var chunkIndex = 0
            var currentChunkStartUs = -1L
            var firstSampleInChunkUs = -1L
            var lastSampleInChunkUs = -1L
            var muxer: MediaMuxer? = null
            var muxTrackIdx = -1
            var currentFile: File? = null

            fun finishChunk() {
                muxer?.let { m ->
                    runCatching { m.stop() }
                    runCatching { m.release() }
                }
                muxer = null
                val f = currentFile
                if (f != null && f.exists() && f.length() > 1024L && firstSampleInChunkUs >= 0L) {
                    val durMs = ((lastSampleInChunkUs - firstSampleInChunkUs) / 1000L).coerceAtLeast(1000L)
                    chunks.add(
                        ExtractedAudioChunk(
                            file = f,
                            mimeType = "audio/mp4",
                            formatExt = "m4a",
                            startOffsetMs = (firstSampleInChunkUs / 1000L).coerceAtLeast(0L),
                            durationMs = durMs
                        )
                    )
                } else {
                    f?.delete()
                }
                currentFile = null
                firstSampleInChunkUs = -1L
                lastSampleInChunkUs = -1L
            }

            fun startNewChunk(startUs: Long) {
                val outFile = File(workDir, "audio_chunk_${chunkIndex++}.m4a")
                val m = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val tIdx = m.addTrack(format)
                m.start()
                muxer = m
                muxTrackIdx = tIdx
                currentFile = outFile
                currentChunkStartUs = startUs
                firstSampleInChunkUs = startUs
                lastSampleInChunkUs = startUs
            }

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                if (muxer == null) {
                    startNewChunk(sampleTimeUs)
                } else if (sampleTimeUs - currentChunkStartUs >= windowMs * 1000L) {
                    finishChunk()
                    startNewChunk(sampleTimeUs)
                }

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = (sampleTimeUs - firstSampleInChunkUs).coerceAtLeast(0L)
                bufferInfo.flags = extractor.sampleFlags
                muxer?.writeSampleData(muxTrackIdx, buffer, bufferInfo)
                lastSampleInChunkUs = sampleTimeUs

                extractor.advance()
            }
            finishChunk()

            if (chunks.isEmpty() && totalDurationMs > 0L) return null
            return chunks
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun decodeToMonoWavChunks(
        uri: Uri,
        effectiveDurationMs: Long,
        windowMs: Long,
        workDir: File,
        onStageUpdate: (String) -> Unit
    ): List<ExtractedAudioChunk> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            setExtractorDataSource(extractor, uri)
            val audioTrackIndex = findAudioTrack(extractor)
                ?: throw IllegalStateException("Walang audio track na nakita sa video file.")
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Hindi matukoy ang audio codec ng video.")

            extractor.selectTrack(audioTrackIndex)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            var inputSampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            var inputChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else 1
            var pcmEncodingBits = 16

            val chunks = mutableListOf<ExtractedAudioChunk>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            var chunkStartMs = 0L
            var chunkIndex = 0
            val currentChunkShorts = ArrayList<Short>(TARGET_WAV_SAMPLE_RATE * 60)

            fun flushWavChunk(endMsHint: Long) {
                if (currentChunkShorts.isEmpty()) return
                val outFile = File(workDir, "audio_chunk_${chunkIndex++}.wav")
                val arr = ShortArray(currentChunkShorts.size) { currentChunkShorts[it] }
                WavPcmTools.writeMonoWavFile(outFile, arr, TARGET_WAV_SAMPLE_RATE)
                val durMs = ((arr.size * 1000L) / TARGET_WAV_SAMPLE_RATE).coerceAtLeast(
                    (endMsHint - chunkStartMs).coerceAtLeast(500L)
                )
                chunks.add(
                    ExtractedAudioChunk(
                        file = outFile,
                        mimeType = "audio/wav",
                        formatExt = "wav",
                        startOffsetMs = chunkStartMs,
                        durationMs = durMs
                    )
                )
                currentChunkShorts.clear()
                chunkStartMs += durMs
            }

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            inputSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            inputChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                            outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ) {
                            val enc = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            pcmEncodingBits = if (enc == android.media.AudioFormat.ENCODING_PCM_FLOAT) 32 else 16
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val monoChunk = decodePcmFrameTo16kMono(
                                buffer = outBuf,
                                inputSampleRate = inputSampleRate,
                                channels = inputChannels,
                                bits = pcmEncodingBits
                            )
                            for (s in monoChunk) {
                                currentChunkShorts.add(s)
                            }

                            val currentChunkDurationMs = (currentChunkShorts.size * 1000L) / TARGET_WAV_SAMPLE_RATE
                            if (currentChunkDurationMs >= windowMs) {
                                val pct = if (effectiveDurationMs > 0L) {
                                    (((chunkStartMs + currentChunkDurationMs) * 100L) / effectiveDurationMs).toInt().coerceIn(5, 95)
                                } else null
                                onStageUpdate(
                                    if (pct != null) "Preparing audio chunks ($pct%)..." else "Preparing audio chunk ${chunkIndex + 1}..."
                                )
                                flushWavChunk(chunkStartMs + currentChunkDurationMs)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            flushWavChunk(effectiveDurationMs)

            if (chunks.isEmpty()) {
                throw IllegalStateException("Walang readable speech audio sa napiling video.")
            }
            return chunks
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun decodePcmFrameTo16kMono(
        buffer: ByteBuffer,
        inputSampleRate: Int,
        channels: Int,
        bits: Int
    ): ShortArray {
        val duplicate = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        val monoInput: ShortArray = if (bits == 32) {
            val floatBuf = duplicate.asFloatBuffer()
            val totalFrames = floatBuf.remaining() / channels
            ShortArray(totalFrames) { frame ->
                var acc = 0f
                for (c in 0 until channels) {
                    acc += floatBuf.get()
                }
                ((acc / channels).coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            }
        } else {
            val shortBuf = duplicate.asShortBuffer()
            val totalFrames = shortBuf.remaining() / channels
            ShortArray(totalFrames) { frame ->
                var acc = 0L
                for (c in 0 until channels) {
                    acc += shortBuf.get().toLong()
                }
                (acc / channels).coerceIn(-32768L, 32767L).toShort()
            }
        }

        if (monoInput.isEmpty() || inputSampleRate == TARGET_WAV_SAMPLE_RATE) {
            return monoInput
        }
        val ratio = inputSampleRate.toDouble() / TARGET_WAV_SAMPLE_RATE.toDouble()
        val outLen = (monoInput.size / ratio).roundToInt().coerceAtLeast(1)
        return ShortArray(outLen) { idx ->
            val srcPos = idx * ratio
            val i0 = srcPos.toInt().coerceIn(0, monoInput.lastIndex)
            val i1 = (i0 + 1).coerceIn(0, monoInput.lastIndex)
            val frac = srcPos - i0
            val v = monoInput[i0] * (1.0 - frac) + monoInput[i1] * frac
            v.roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun setExtractorDataSource(extractor: MediaExtractor, uri: Uri) {
        if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
            extractor.setDataSource(uri.path!!)
        } else {
            extractor.setDataSource(context, uri, null)
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun resolveDurationMs(uri: Uri, durationHintMs: Long): Long {
        if (durationHintMs > 0L) return durationHintMs
        val mmr = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                mmr.setDataSource(uri.path!!)
            } else {
                mmr.setDataSource(context, uri)
            }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }
}

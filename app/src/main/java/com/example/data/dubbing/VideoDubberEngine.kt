package com.example.data.dubbing

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.data.model.DubAudioDuckingMode
import com.example.data.model.DubLineItem
import com.example.data.model.DubVoiceEngine
import com.example.data.model.DubVoiceOption
import com.example.data.model.DubbedVideoResult
import com.example.data.network.Mp4TrackMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

private const val TAG = "VideoDubberEngine"
private const val TARGET_SAMPLE_RATE = 44100
private const val CODEC_TIMEOUT_US = 10_000L
private const val STREAM_BLOCK_FRAMES = TARGET_SAMPLE_RATE * 10 // 10-second constant-memory block

class VideoDubberEngine(
    private val context: Context,
    private val mmsEngine: MmsTagalogOnnxEngine,
    private val androidTts: AndroidTtsSynthesizer
) {

    private data class TimedWavClip(
        val startSample: Int,
        val endSample: Int,
        val wavFile: File
    )

    suspend fun renderDubbedVideo(
        sourceUriString: String,
        sourceDurationMs: Long,
        lines: List<DubLineItem>,
        voiceEngine: DubVoiceEngine,
        androidVoiceId: String,
        availableAndroidVoices: List<DubVoiceOption> = emptyList(),
        lockedSpeakerProfiles: Map<String, com.example.data.model.SpeakerVoiceProfile> = emptyMap(),
        voicePitch: Float,
        baseSpeechRate: Float,
        duckingMode: DubAudioDuckingMode,
        multiSpeakerEnabled: Boolean = true,
        onStageUpdate: (String, Float?) -> Unit
    ): Result<DubbedVideoResult> = withContext(Dispatchers.IO) {
        val validLines = lines.filter { it.filipinoText.isNotBlank() }.sortedBy { it.startMs }
        if (validLines.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException("Walang Filipino script lines na idu-dub.")
            )
        }

        if (voiceEngine == DubVoiceEngine.MMS_ONNX_TAGALOG && !mmsEngine.isModelReady()) {
            return@withContext Result.failure(
                IllegalStateException("I-download muna ang Open-Source MMS Tagalog model (o piliin ang Android Neural Filipino voice).")
            )
        }

        val timestamp = System.currentTimeMillis()
        val tempInputVideo = File(context.cacheDir, "dub_src_$timestamp.mp4")
        val tempAacAudio = File(context.cacheDir, "dub_aac_$timestamp.m4a")
        val tempWavFiles = mutableListOf<File>()

        val dubbedDir = File(context.filesDir, "videos").apply { mkdirs() }
        val fileName = "Gizmooh_Dubbed_fil_$timestamp.mp4"
        val outputMp4 = File(dubbedDir, fileName)

        try {
            onStageUpdate("Preparing source video...", 0.04f)
            copyUriToTempFile(Uri.parse(sourceUriString), tempInputVideo)

            val probedVideoDurationMs = probeVideoDurationMs(tempInputVideo)
            val lastLineEndMs = validLines.maxOfOrNull { it.endMs } ?: 1000L
            val effectiveDurationMs = maxOf(
                sourceDurationMs,
                probedVideoDurationMs,
                lastLineEndMs + 400L
            )
            val totalMonoSamples = ((effectiveDurationMs * TARGET_SAMPLE_RATE) / 1000L)
                .toInt()
                .coerceAtLeast(TARGET_SAMPLE_RATE)

            val resolvedCast = DubTimelineMath.buildLockedCharacterCast(
                lines = validLines,
                existingCast = lockedSpeakerProfiles,
                basePitch = voicePitch,
                availableVoices = availableAndroidVoices,
                selectedVoiceId = androidVoiceId
            )
            val timedWavClips = mutableListOf<TimedWavClip>()

            for ((idx, line) in validLines.withIndex()) {
                val frac = 0.08f + 0.56f * (idx.toFloat() / validLines.size.toFloat())
                val canonicalSpeaker = DubTimelineMath.canonicalizeSpeakerName(line.speaker)
                val profile = if (multiSpeakerEnabled && resolvedCast.size > 1) {
                    resolvedCast[canonicalSpeaker]
                } else null

                val speakerLabel = if (profile != null) "${profile.speakerKey} · " else ""
                onStageUpdate(
                    "Synthesizing ${speakerLabel}line ${idx + 1}/${validLines.size} (${DubTimelineMath.formatMsToTimestamp(line.startMs)})...",
                    frac
                )

                val lineWav = File(context.cacheDir, "dub_line_${timestamp}_$idx.wav").also {
                    tempWavFiles.add(it)
                }

                val targetPitch = profile?.pitchMultiplier ?: voicePitch
                val targetVoiceId = profile?.androidVoiceId ?: androidVoiceId

                val synthResult = when (voiceEngine) {
                    DubVoiceEngine.MMS_ONNX_TAGALOG -> mmsEngine.synthesizeToWav(
                        text = line.filipinoText,
                        speedFactor = baseSpeechRate,
                        destWavFile = lineWav
                    )
                    DubVoiceEngine.ANDROID_FIL_PH -> androidTts.synthesizeToWav(
                        text = line.filipinoText,
                        voiceId = targetVoiceId,
                        pitch = targetPitch,
                        speechRate = baseSpeechRate,
                        destWavFile = lineWav
                    )
                }

                if (synthResult.isFailure) {
                    throw synthResult.exceptionOrNull()
                        ?: IllegalStateException("Voice synthesis failed at line ${idx + 1}")
                }

                val wavData = WavPcmTools.readWavFile(lineWav)
                val mono = WavPcmTools.toMono(wavData.samples, wavData.channels)
                val resampled = WavPcmTools.resampleMonoLinear(mono, wavData.sampleRate, TARGET_SAMPLE_RATE)
                val trimmed = WavPcmTools.trimDigitalSilence(resampled, TARGET_SAMPLE_RATE)

                if (trimmed.isNotEmpty()) {
                    val nextStartMs = validLines.getOrNull(idx + 1)?.startMs ?: Long.MAX_VALUE
                    val maxAllowedWindowMs = minOf(
                        line.durationMs,
                        (nextStartMs - line.startMs - 50L).coerceAtLeast(300L)
                    ).coerceAtLeast(350L)

                    val actualDurMs = ((trimmed.size.toLong() * 1000L) / TARGET_SAMPLE_RATE).coerceAtLeast(1L)
                    val tempoRatio = DubTimelineMath.computeTempoRatioForWindow(
                        ttsDurationMs = actualDurMs,
                        targetWindowMs = maxAllowedWindowMs
                    )

                    // Single-pass WSOLA + resample so locked character pitch & voice timbre stay 100% consistent
                    val mmsPitchToApply = if (voiceEngine == DubVoiceEngine.MMS_ONNX_TAGALOG) targetPitch else 1.0f
                    val timeFitted = WavPcmTools.timeAndPitchScaleMono(
                        samples = trimmed,
                        sampleRate = TARGET_SAMPLE_RATE,
                        tempoSpeedFactor = tempoRatio,
                        pitchFactor = mmsPitchToApply
                    )

                    val startSample = ((line.startMs * TARGET_SAMPLE_RATE) / 1000L)
                        .toInt()
                        .coerceIn(0, totalMonoSamples - 1)
                    val maxSamplesBeforeNext = ((maxAllowedWindowMs * TARGET_SAMPLE_RATE) / 1000L)
                        .toInt()
                        .coerceAtLeast(1)
                    val clipped = if (timeFitted.size > maxSamplesBeforeNext) {
                        timeFitted.copyOfRange(0, maxSamplesBeforeNext)
                    } else {
                        timeFitted
                    }

                    // Persist fitted 44.1kHz mono WAV to keep long-form heap usage tiny (<10MB)
                    WavPcmTools.writeMonoWavFile(lineWav, clipped, TARGET_SAMPLE_RATE)
                    timedWavClips.add(
                        TimedWavClip(
                            startSample = startSample,
                            endSample = (startSample + clipped.size).coerceAtMost(totalMonoSamples),
                            wavFile = lineWav
                        )
                    )
                }
            }

            onStageUpdate("Streaming audio ducking & AAC encode...", 0.66f)
            streamMixAndEncodeToM4a(
                sourceVideo = tempInputVideo,
                includeOriginalAudio = duckingMode != DubAudioDuckingMode.MUTE_ORIGINAL,
                totalMonoSamples = totalMonoSamples,
                timedWavClips = timedWavClips,
                backgroundGainDuringSpeech = duckingMode.backgroundGainDuringSpeech,
                outputM4a = tempAacAudio,
                onBlockProgress = { blockFrac ->
                    onStageUpdate(
                        "Mixing & encoding long-form audio (${(blockFrac * 100).toInt()}%)...",
                        0.66f + (0.24f * blockFrac)
                    )
                }
            )

            onStageUpdate("Muxing lossless HD video + Filipino audio...", 0.92f)
            Mp4TrackMuxer.muxVideoAndAudio(
                videoFile = tempInputVideo,
                audioFile = tempAacAudio,
                outputFile = outputMp4
            )

            if (!outputMp4.exists() || outputMp4.length() < 1024L) {
                throw IllegalStateException("Dubbed MP4 output was invalid.")
            }

            val galleryUri = saveCopyToMediaStore(outputMp4, fileName)
            val resultUri = galleryUri?.toString() ?: Uri.fromFile(outputMp4).toString()

            onStageUpdate("Dubbed video ready!", 1.0f)
            Result.success(
                DubbedVideoResult(
                    localFilePath = outputMp4.absolutePath,
                    contentUriString = resultUri,
                    gallerySaved = galleryUri != null,
                    fileSizeBytes = outputMp4.length(),
                    fileName = fileName,
                    engineUsed = voiceEngine.label,
                    segmentCount = timedWavClips.size
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Dub rendering failed", e)
            runCatching { outputMp4.delete() }
            Result.failure(e)
        } finally {
            runCatching { tempInputVideo.delete() }
            runCatching { tempAacAudio.delete() }
            tempWavFiles.forEach { f -> runCatching { f.delete() } }
        }
    }

    private fun probeVideoDurationMs(videoFile: File): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(videoFile.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }

    private class StreamingOriginalAudioReader(videoFile: File) {
        private val extractor = MediaExtractor()
        private var decoder: MediaCodec? = null
        private var srcSampleRate = TARGET_SAMPLE_RATE
        private var srcChannels = 2
        private var inputDone = false
        private var outputDone = false
        private val pendingStereo = ArrayList<Short>(STREAM_BLOCK_FRAMES * 2)

        init {
            runCatching {
                extractor.setDataSource(videoFile.absolutePath)
                var audioTrackIndex = -1
                var audioFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        audioFormat = fmt
                        break
                    }
                }
                if (audioTrackIndex != -1 && audioFormat != null) {
                    extractor.selectTrack(audioTrackIndex)
                    val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
                    srcSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                    srcChannels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    decoder = MediaCodec.createDecoderByType(mime).apply {
                        configure(audioFormat, null, null, 0)
                        start()
                    }
                } else {
                    outputDone = true
                }
            }.onFailure {
                outputDone = true
            }
        }

        fun readStereoFrames(frameCount: Int): ShortArray? {
            val dec = decoder ?: return null
            val neededShorts = frameCount * 2

            while (pendingStereo.size < neededShorts && !outputDone) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = dec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val bufferInfo = MediaCodec.BufferInfo()
                val outIdx = dec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = dec.outputFormat
                        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            srcSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            srcChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = dec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size >= 2) {
                            val bytes = ByteArray(bufferInfo.size)
                            outBuf.position(bufferInfo.offset)
                            outBuf.get(bytes)
                            val shortCount = bytes.size / 2
                            val bb = ByteBuffer.wrap(bytes, 0, shortCount * 2).order(ByteOrder.LITTLE_ENDIAN)
                            val raw = ShortArray(shortCount) { bb.short }
                            val uniformStereo = toUniformStereo44100(raw, srcChannels, srcSampleRate)
                            for (s in uniformStereo) pendingStereo.add(s)
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (pendingStereo.isEmpty()) return null
            val takeShorts = minOf(neededShorts, pendingStereo.size)
            val result = ShortArray(neededShorts)
            for (i in 0 until takeShorts) {
                result[i] = pendingStereo[i]
            }
            if (takeShorts == pendingStereo.size) {
                pendingStereo.clear()
            } else {
                val remainder = pendingStereo.subList(takeShorts, pendingStereo.size).toList()
                pendingStereo.clear()
                pendingStereo.addAll(remainder)
            }
            return result
        }

        fun close() {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun streamMixAndEncodeToM4a(
        sourceVideo: File,
        includeOriginalAudio: Boolean,
        totalMonoSamples: Int,
        timedWavClips: List<TimedWavClip>,
        backgroundGainDuringSpeech: Float,
        outputM4a: File,
        onBlockProgress: (Float) -> Unit
    ) {
        if (outputM4a.exists()) outputM4a.delete()

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, TARGET_SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        val muxer = MediaMuxer(outputM4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val reader = if (includeOriginalAudio) StreamingOriginalAudioReader(sourceVideo) else null
        val duckingState = WavPcmTools.DuckingFilterState(1.0f)
        val bufferInfo = MediaCodec.BufferInfo()
        var encodedFramesTotal = 0L

        fun drainEncoder(drainEndOfStream: Boolean) {
            while (true) {
                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0 && muxerStarted) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!drainEndOfStream) break
                    }
                    else -> if (!drainEndOfStream) break
                }
            }
        }

        fun feedStereoPcmBlock(stereoBlock: ShortArray) {
            val totalBytes = stereoBlock.size * 2
            val bb = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in stereoBlock) bb.putShort(s)
            val rawBytes = bb.array()

            var byteCursor = 0
            while (byteCursor < totalBytes) {
                val inIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inIdx >= 0) {
                    val inBuf = encoder.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        val toWrite = minOf(totalBytes - byteCursor, inBuf.capacity(), 4096)
                        val ptsUs = (encodedFramesTotal * 1_000_000L) / TARGET_SAMPLE_RATE
                        inBuf.put(rawBytes, byteCursor, toWrite)
                        byteCursor += toWrite
                        encodedFramesTotal += (toWrite / 4L)
                        encoder.queueInputBuffer(inIdx, 0, toWrite, ptsUs, 0)
                    }
                }
                drainEncoder(drainEndOfStream = false)
            }
        }

        try {
            var blockStart = 0
            while (blockStart < totalMonoSamples) {
                val blockLen = minOf(STREAM_BLOCK_FRAMES, totalMonoSamples - blockStart)
                val origBlock = reader?.readStereoFrames(blockLen)

                val overlappingClips = timedWavClips
                    .filter { it.endSample > blockStart && it.startSample < (blockStart + blockLen) }
                    .mapNotNull { wavClip ->
                        runCatching {
                            val wav = WavPcmTools.readWavFile(wavClip.wavFile)
                            TimedPcmClip(
                                startSample = wavClip.startSample,
                                endSample = wavClip.endSample,
                                monoSamples = wav.samples
                            )
                        }.getOrNull()
                    }

                val mixedBlock = WavPcmTools.mixDubBlockIntoStereo(
                    originalStereoBlock = origBlock,
                    blockStartSample = blockStart,
                    blockLengthSamples = blockLen,
                    sampleRate = TARGET_SAMPLE_RATE,
                    overlappingClips = overlappingClips,
                    backgroundGainDuringSpeech = backgroundGainDuringSpeech,
                    duckingState = duckingState
                )
                feedStereoPcmBlock(mixedBlock)

                blockStart += blockLen
                onBlockProgress((blockStart.toFloat() / totalMonoSamples.toFloat()).coerceIn(0f, 1f))
            }

            // Signal EOS to AAC encoder
            var eosSent = false
            while (!eosSent) {
                val inIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inIdx >= 0) {
                    val ptsUs = (encodedFramesTotal * 1_000_000L) / TARGET_SAMPLE_RATE
                    encoder.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosSent = true
                } else {
                    drainEncoder(drainEndOfStream = false)
                }
            }
            drainEncoder(drainEndOfStream = true)
        } finally {
            reader?.close()
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun copyUriToTempFile(uri: Uri, destFile: File) {
        if (uri.scheme == "file") {
            val src = File(uri.path ?: "")
            if (src.exists()) {
                src.inputStream().use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }
                return
            }
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Cannot open selected video file.")
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
                FileInputStream(sourceFile).use { input -> input.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    itemUri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            itemUri
        }.getOrNull()
    }

    companion object {
        private fun toUniformStereo44100(
            samples: ShortArray,
            srcChannels: Int,
            srcRate: Int
        ): ShortArray {
            val frames = samples.size / srcChannels
            if (frames <= 0) return ShortArray(0)

            val left = ShortArray(frames) { f -> samples[f * srcChannels] }
            val right = if (srcChannels >= 2) {
                ShortArray(frames) { f -> samples[f * srcChannels + 1] }
            } else {
                left
            }

            val resampledL = WavPcmTools.resampleMonoLinear(left, srcRate, TARGET_SAMPLE_RATE)
            val resampledR = if (srcChannels >= 2) {
                WavPcmTools.resampleMonoLinear(right, srcRate, TARGET_SAMPLE_RATE)
            } else {
                resampledL
            }

            val outFrames = minOf(resampledL.size, resampledR.size)
            return ShortArray(outFrames * 2).apply {
                for (i in 0 until outFrames) {
                    this[i * 2] = resampledL[i]
                    this[i * 2 + 1] = resampledR[i]
                }
            }
        }
    }
}

package com.example.data.network

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object Mp4TrackMuxer {

    fun muxVideoAndAudio(videoFile: File, audioFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoTrackIdx = findTrackIndex(videoExtractor, "video/")
            val audioTrackIdx = findTrackIndex(audioExtractor, "audio/")

            require(videoTrackIdx >= 0) { "No video track found to mux." }
            require(audioTrackIdx >= 0) { "No audio track found to mux." }

            videoExtractor.selectTrack(videoTrackIdx)
            audioExtractor.selectTrack(audioTrackIdx)

            val videoFormat = videoExtractor.getTrackFormat(videoTrackIdx)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIdx)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val maxVideoBuf = videoFormat.getSafeMaxInputSize(1024 * 1024)
            val maxAudioBuf = audioFormat.getSafeMaxInputSize(256 * 1024)
            val buffer = ByteBuffer.allocate(maxOf(maxVideoBuf, maxAudioBuf))
            val bufferInfo = MediaCodec.BufferInfo()

            copySamples(videoExtractor, muxer, muxerVideoTrack, buffer, bufferInfo)
            copySamples(audioExtractor, muxer, muxerAudioTrack, buffer, bufferInfo)
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun findTrackIndex(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun MediaFormat.getSafeMaxInputSize(fallback: Int): Int {
        return if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(fallback)
        } else {
            fallback
        }
    }

    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerTrackIndex: Int,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        var lastPtsUs = -1L
        while (true) {
            bufferInfo.offset = 0
            bufferInfo.size = extractor.readSampleData(buffer, 0)
            if (bufferInfo.size < 0) {
                bufferInfo.size = 0
                break
            }
            val sampleTime = extractor.sampleTime
            if (sampleTime < 0) break
            // Ensure strictly non-decreasing timestamps for MediaMuxer stability
            bufferInfo.presentationTimeUs = if (sampleTime <= lastPtsUs) lastPtsUs + 1L else sampleTime
            lastPtsUs = bufferInfo.presentationTimeUs
            bufferInfo.flags = extractor.sampleFlags
            muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }
}

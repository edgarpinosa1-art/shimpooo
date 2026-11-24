package com.example.data.network

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "FastParallelDownloader"

data class ByteRangeChunk(
    val index: Int,
    val startByte: Long,
    val endByte: Long
) {
    val expectedLength: Long
        get() = (endByte - startByte + 1L).coerceAtLeast(0L)
}

object FastDownloadPlanner {

    fun planChunks(
        totalBytes: Long,
        maxThreads: Int = 6,
        minChunkSizeBytes: Long = 512L * 1024L
    ): List<ByteRangeChunk> {
        if (totalBytes <= 0L) return emptyList()
        val cappedThreads = maxThreads.coerceIn(1, 8)
        if (totalBytes < minChunkSizeBytes * 2L || cappedThreads == 1) {
            return listOf(ByteRangeChunk(index = 0, startByte = 0L, endByte = totalBytes - 1L))
        }

        val effectiveThreads = minOf(
            cappedThreads,
            (totalBytes / minChunkSizeBytes).toInt().coerceAtLeast(1)
        )
        val baseSize = totalBytes / effectiveThreads
        val chunks = ArrayList<ByteRangeChunk>(effectiveThreads)
        var cursor = 0L
        for (i in 0 until effectiveThreads) {
            val end = if (i == effectiveThreads - 1) {
                totalBytes - 1L
            } else {
                cursor + baseSize - 1L
            }
            chunks.add(ByteRangeChunk(index = i, startByte = cursor, endByte = end))
            cursor = end + 1L
        }
        return chunks
    }

    fun parseTotalBytesFromContentRange(headerValue: String?): Long? {
        if (headerValue.isNullOrBlank()) return null
        // Example: "bytes 0-0/104857600" or "bytes 0-1023/104857600"
        val slashPart = headerValue.substringAfter('/', missingDelimiterValue = "").trim()
        if (slashPart == "*" || slashPart.isEmpty()) return null
        return slashPart.toLongOrNull()?.takeIf { it > 0L }
    }
}

data class FastDownloadResult(
    val totalBytes: Long,
    val connectionsUsed: Int
)

class FastParallelDownloader(
    private val client: OkHttpClient = NetworkClient.httpClient
) {
    companion object {
        private const val DEFAULT_PARALLEL_THREADS = 6
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val STREAM_OUT_BUFFER_BYTES = 256 * 1024
        private const val MAX_CHUNK_RETRIES = 3
    }

    suspend fun downloadToFileFast(
        url: String,
        referer: String,
        destFile: File,
        threads: Int = DEFAULT_PARALLEL_THREADS,
        onProgress: (bytesCopied: Long, totalBytes: Long, activeThreads: Int) -> Unit
    ): FastDownloadResult = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()

        // Step 1: Probe range support using Range: bytes=0-0 (avoids extra HEAD blocks on CDNs)
        val probeReq = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkClient.DESKTOP_USER_AGENT)
            .header("Referer", referer)
            .header("Range", "bytes=0-0")
            .get()
            .build()

        client.newCall(probeReq).execute().use { probeResp ->
            if (probeResp.code == 206) {
                val totalBytes = FastDownloadPlanner.parseTotalBytesFromContentRange(
                    probeResp.header("Content-Range")
                )
                if (totalBytes != null && totalBytes >= 1024L * 1024L) {
                    // Close the 1-byte probe response before launching parallel workers
                    probeResp.close()
                    val chunks = FastDownloadPlanner.planChunks(
                        totalBytes = totalBytes,
                        maxThreads = threads
                    )
                    if (chunks.size > 1) {
                        Log.d(TAG, "Starting ${chunks.size}x parallel range download ($totalBytes bytes)")
                        runCatching {
                            executeParallelChunks(
                                url = url,
                                referer = referer,
                                destFile = destFile,
                                totalBytes = totalBytes,
                                chunks = chunks,
                                onProgress = onProgress
                            )
                            return@withContext FastDownloadResult(
                                totalBytes = totalBytes,
                                connectionsUsed = chunks.size
                            )
                        }.onFailure { err ->
                            if (err is CancellationException) throw err
                            Log.w(TAG, "Parallel range download interrupted, falling back to single fast stream: ${err.message}")
                        }
                    }
                }
            } else if (probeResp.isSuccessful) {
                // Server ignored Range and already started sending full HTTP 200 stream — consume directly!
                val totalBytes = probeResp.body?.contentLength() ?: -1L
                streamResponseToFile(probeResp, destFile, totalBytes) { copied, total ->
                    onProgress(copied, total, 1)
                }
                return@withContext FastDownloadResult(
                    totalBytes = destFile.length(),
                    connectionsUsed = 1
                )
            }
        }

        // Step 2: Clean fast single-stream fallback (64KB read + 256KB buffered write)
        val fallbackReq = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkClient.DESKTOP_USER_AGENT)
            .header("Referer", referer)
            .get()
            .build()

        client.newCall(fallbackReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Download failed (HTTP ${resp.code}). Link may have expired; extract again.")
            }
            val totalBytes = resp.body?.contentLength() ?: -1L
            streamResponseToFile(resp, destFile, totalBytes) { copied, total ->
                onProgress(copied, total, 1)
            }
            FastDownloadResult(
                totalBytes = destFile.length(),
                connectionsUsed = 1
            )
        }
    }

    private suspend fun executeParallelChunks(
        url: String,
        referer: String,
        destFile: File,
        totalBytes: Long,
        chunks: List<ByteRangeChunk>,
        onProgress: (bytesCopied: Long, totalBytes: Long, activeThreads: Int) -> Unit
    ) = coroutineScope {
        RandomAccessFile(destFile, "rw").use { raf ->
            raf.setLength(totalBytes)
        }

        val copiedAtomic = AtomicLong(0L)
        val lastEmitMs = AtomicLong(0L)
        val activeThreads = chunks.size

        onProgress(0L, totalBytes, activeThreads)

        val jobs = chunks.map { chunk ->
            async(Dispatchers.IO) {
                downloadChunkWithRetry(
                    url = url,
                    referer = referer,
                    destFile = destFile,
                    chunk = chunk,
                    activeThreads = activeThreads,
                    copiedAtomic = copiedAtomic,
                    lastEmitMs = lastEmitMs,
                    totalBytes = totalBytes,
                    onProgress = onProgress
                )
            }
        }
        jobs.awaitAll()
        onProgress(totalBytes, totalBytes, activeThreads)
    }

    private suspend fun downloadChunkWithRetry(
        url: String,
        referer: String,
        destFile: File,
        chunk: ByteRangeChunk,
        activeThreads: Int,
        copiedAtomic: AtomicLong,
        lastEmitMs: AtomicLong,
        totalBytes: Long,
        onProgress: (bytesCopied: Long, totalBytes: Long, activeThreads: Int) -> Unit
    ) {
        var cursorByte = chunk.startByte
        var lastError: Exception? = null

        for (attempt in 0..MAX_CHUNK_RETRIES) {
            if (cursorByte > chunk.endByte) return
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", NetworkClient.DESKTOP_USER_AGENT)
                    .header("Referer", referer)
                    .header("Range", "bytes=$cursorByte-${chunk.endByte}")
                    .get()
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (resp.code != 206 && !(resp.isSuccessful && cursorByte == 0L)) {
                        throw IllegalStateException("Range chunk HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IllegalStateException("Empty chunk body")
                    val buffer = ByteArray(IO_BUFFER_BYTES)

                    RandomAccessFile(destFile, "rw").use { raf ->
                        raf.seek(cursorByte)
                        body.byteStream().use { input ->
                            while (cursorByte <= chunk.endByte) {
                                val remaining = (chunk.endByte - cursorByte + 1L).coerceAtMost(buffer.size.toLong()).toInt()
                                val read = input.read(buffer, 0, remaining)
                                if (read == -1) break
                                raf.write(buffer, 0, read)
                                cursorByte += read
                                val nowTotal = copiedAtomic.addAndGet(read.toLong())

                                val now = System.currentTimeMillis()
                                val prev = lastEmitMs.get()
                                if (now - prev >= 90L && lastEmitMs.compareAndSet(prev, now)) {
                                    onProgress(nowTotal.coerceAtMost(totalBytes), totalBytes, activeThreads)
                                }
                            }
                        }
                    }
                }
                if (cursorByte > chunk.endByte) {
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_CHUNK_RETRIES) {
                    delay(350L * (attempt + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("Chunk ${chunk.index} incomplete.")
    }

    private fun streamResponseToFile(
        resp: Response,
        destFile: File,
        totalBytes: Long,
        onProgress: (bytesCopied: Long, totalBytes: Long) -> Unit
    ) {
        val body = resp.body ?: throw IllegalStateException("Empty video response.")
        var bytesCopied = 0L
        var lastEmitMs = 0L
        val buffer = ByteArray(IO_BUFFER_BYTES)

        body.byteStream().use { input ->
            BufferedOutputStream(FileOutputStream(destFile), STREAM_OUT_BUFFER_BYTES).use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    bytesCopied += read
                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs >= 90L) {
                        lastEmitMs = now
                        onProgress(bytesCopied, totalBytes)
                    }
                }
                output.flush()
            }
        }
        onProgress(bytesCopied, if (totalBytes > 0L) totalBytes else bytesCopied)
    }
}

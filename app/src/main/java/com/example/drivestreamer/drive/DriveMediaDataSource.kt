package com.example.drivestreamer.drive

import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One shared HTTP client for reading tags and cover art, with real
 * timeouts. (Sharing it also lets requests reuse one connection to Google
 * rather than setting up a fresh secure connection for every file.)
 */
object DriveHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** Drive answered with an error status. */
class DriveHttpException(val code: Int) : IOException("Drive returned HTTP $code") {
    /** Worth another try later: throttling, or a hiccup on Google's side. */
    val isTransient: Boolean get() = code == 429 || code == 403 || code >= 500
}

/** The tag data sits further into the file than we're willing to read. */
class TooFarIntoFileException : IOException("Tag data is too far into the file")

/**
 * Lets Android's tag reader (MediaMetadataRetriever) read a Drive file
 * through *our* HTTP client instead of fetching the URL itself.
 *
 * Why this exists: handing the platform a URL means the platform does the
 * networking, with no timeout and no way for us to interrupt it. A single
 * stalled request could freeze a library scan forever, ignore the Cancel
 * button, and survive the app being swiped away. Here every request has a
 * timeout, [cancel] aborts the in-flight request immediately, and the total
 * number of bytes read per file is capped so one huge or oddly laid-out file
 * can't turn into a huge download.
 *
 * Bytes are fetched in chunks with HTTP Range requests, as the reader asks
 * for them, so only the part of the file holding the tags is transferred.
 */
class DriveMediaDataSource(
    private val client: OkHttpClient,
    private val fileId: String,
    private val accessToken: String,
    private val maxBytes: Long
) : MediaDataSource() {

    companion object {
        private const val CHUNK_BYTES = 256 * 1024
    }

    @Volatile private var cancelled = false
    @Volatile private var activeCall: Call? = null

    /** The error that stopped a read, if one did. Tells the caller *why* the platform gave up. */
    @Volatile var failure: IOException? = null
        private set

    /** True if the failure was a passing network/throttling problem rather than a property of the file. */
    val isTemporaryFailure: Boolean
        get() = when (val f = failure) {
            null -> false
            is TooFarIntoFileException -> false
            is DriveHttpException -> f.isTransient
            else -> true // timeouts, dropped connections, cancelled requests
        }

    private var totalSize = -1L
    private var chunkStart = -1L
    private var chunk = ByteArray(0)
    private var bytesFetched = 0L

    /** Aborts any request in progress and refuses further ones. Safe to call from any thread. */
    fun cancel() {
        cancelled = true
        activeCall?.cancel()
    }

    @Synchronized
    override fun getSize(): Long {
        if (chunkStart < 0) loadChunk(0)
        return totalSize
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0) return -1

        var copied = 0
        var pos = position
        while (copied < size) {
            if (totalSize in 0..pos) break // past the end of the file
            if (chunkStart < 0 || pos < chunkStart || pos >= chunkStart + chunk.size) {
                loadChunk(pos)
            }
            val inChunk = (pos - chunkStart).toInt()
            if (inChunk < 0 || inChunk >= chunk.size) break // nothing more to read here
            val n = minOf(size - copied, chunk.size - inChunk)
            System.arraycopy(chunk, inChunk, buffer, offset + copied, n)
            copied += n
            pos += n
        }
        return if (copied == 0) -1 else copied
    }

    override fun close() {
        cancel()
    }

    private fun loadChunk(start: Long) {
        try {
            fetch(start)
        } catch (e: IOException) {
            failure = e
            throw e
        }
    }

    private fun fetch(start: Long) {
        if (cancelled) throw IOException("Cancelled")
        if (bytesFetched >= maxBytes) throw TooFarIntoFileException()

        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $accessToken")
            .header("Range", "bytes=$start-${start + CHUNK_BYTES - 1}")
            .build()
        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                when (response.code) {
                    206 -> {
                        parseTotal(response.header("Content-Range"))?.let { totalSize = it }
                        store(start, response.body?.byteStream())
                    }
                    200 -> {
                        // The server ignored our Range header and is sending the
                        // file from the beginning. Usable only if the beginning
                        // is what we asked for.
                        if (start != 0L) throw IOException("Server ignored the Range request")
                        response.body?.contentLength()?.takeIf { it >= 0 }?.let { totalSize = it }
                        store(start, response.body?.byteStream())
                    }
                    416 -> {
                        // Asked for bytes past the end of the file.
                        chunkStart = start
                        chunk = ByteArray(0)
                        if (totalSize < 0) totalSize = start
                    }
                    else -> throw DriveHttpException(response.code)
                }
            }
        } finally {
            activeCall = null
        }
    }

    /** Reads up to one chunk from [stream] (and no more, even if the server sends the whole file). */
    private fun store(start: Long, stream: java.io.InputStream?) {
        if (stream == null) throw IOException("Empty response")
        val bytes = ByteArray(CHUNK_BYTES)
        var read = 0
        while (read < CHUNK_BYTES) {
            val n = stream.read(bytes, read, CHUNK_BYTES - read)
            if (n == -1) break
            read += n
        }
        chunk = if (read == CHUNK_BYTES) bytes else bytes.copyOf(read)
        chunkStart = start
        bytesFetched += read
    }

    /** "bytes 0-262143/9876543" -> 9876543 (null if the total isn't given). */
    private fun parseTotal(contentRange: String?): Long? =
        contentRange?.substringAfterLast('/')?.toLongOrNull()
}

/**
 * Opens a [MediaMetadataRetriever] on [source] and runs [block] with it.
 *
 * The retriever's reads are blocking native calls that coroutine
 * cancellation can't interrupt on its own. So a helper coroutine waits for
 * cancellation and, when it arrives, aborts the network request; the
 * blocked read then fails straight away and the whole thing unwinds. That
 * is what makes both Cancel and timeouts actually work.
 */
internal suspend fun <T> withRetriever(
    source: DriveMediaDataSource,
    block: (MediaMetadataRetriever) -> T
): T = withContext(Dispatchers.IO) {
    val watcher = launch {
        try {
            awaitCancellation()
        } finally {
            source.cancel()
        }
    }
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(source)
        block(retriever)
    } finally {
        watcher.cancel()
        retriever.release()
        source.close()
    }
}
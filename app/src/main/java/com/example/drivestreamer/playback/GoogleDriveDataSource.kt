package com.example.drivestreamer.playback

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.example.drivestreamer.auth.TokenProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * Streams a single Drive file's bytes on demand, using HTTP Range headers
 * so ExoPlayer can seek without re-downloading from the start.
 *
 * Token handling: pulls from TokenProvider, which caches + proactively
 * refreshes. If a request still comes back 401 (e.g. token expired right
 * at the edge of the refresh window, or was revoked), we invalidate and
 * retry once with a forced-fresh token before giving up.
 */
class GoogleDriveDataSource(
    private val tokenProvider: TokenProvider
) : DataSource {

    private val client = OkHttpClient()
    private var response: Response? = null
    private var inputStream: InputStream? = null
    private var bytesRemaining: Long = 0
    private var dataSpec: DataSpec? = null

    override fun open(spec: DataSpec): Long {
        dataSpec = spec
        val fileId = spec.uri.lastPathSegment
            ?: throw IOException("Missing Drive file id in URI: ${spec.uri}")

        val resp = executeWithRetry(spec, fileId)
        response = resp
        val body = resp.body ?: throw IOException("Empty response body for file $fileId")
        inputStream = body.byteStream()
        bytesRemaining = body.contentLength().takeIf { it >= 0 }
            ?: (spec.length.takeIf { it != C.LENGTH_UNSET.toLong() } ?: C.LENGTH_UNSET.toLong())

        return bytesRemaining
    }

    private fun executeWithRetry(spec: DataSpec, fileId: String): Response {
        var attempt = 0

        while (true) {
            attempt++
            val token = tokenProvider.blockingGetToken(forceRefresh = attempt > 1)

            val requestBuilder = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .header("Authorization", "Bearer $token")

            if (spec.position > 0 || spec.length != C.LENGTH_UNSET.toLong()) {
                val rangeEnd = if (spec.length != C.LENGTH_UNSET.toLong())
                    spec.position + spec.length - 1 else ""
                requestBuilder.header("Range", "bytes=${spec.position}-$rangeEnd")
            }

            val resp = client.newCall(requestBuilder.build()).execute()

            if (resp.isSuccessful) return resp

            val isAuthError = resp.code == 401
            resp.close()

            if (isAuthError && attempt < 2) {
                // Token was stale/revoked — loop around and force a fresh one.
                continue
            }

            throw HttpDataSource.InvalidResponseCodeException(
                resp.code, null, null, emptyMap(), spec, ByteArray(0)
            )
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val stream = inputStream ?: throw IOException("DataSource not open")
        val read = stream.read(buffer, offset, length)
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        return read
    }

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        // Not implemented — add if you want bandwidth-based ABR logic later.
    }

    override fun getUri() = dataSpec?.uri

    override fun close() {
        inputStream?.close()
        response?.close()
        inputStream = null
        response = null
    }

    class Factory(private val tokenProvider: TokenProvider) : DataSource.Factory {
        override fun createDataSource(): DataSource = GoogleDriveDataSource(tokenProvider)
    }
}

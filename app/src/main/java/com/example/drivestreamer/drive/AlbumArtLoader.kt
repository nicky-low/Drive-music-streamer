package com.example.drivestreamer.drive

import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pulls embedded cover art (ID3 APIC frames, FLAC PICTURE blocks, etc.)
 * directly out of the audio file itself, rather than looking it up from
 * an external metadata service — this keeps it accurate to your actual
 * files and needs no extra API keys.
 *
 * The file is read from Drive through [DriveMediaDataSource], so the
 * request has a timeout, can be cancelled, and reads only as much of the
 * file as the tag block needs.
 */
class AlbumArtLoader {

    companion object {
        // Small in-memory cache so replaying/revisiting a track doesn't
        // re-fetch and re-parse the same art.
        //
        // LruCache rejects null values, so "this track has no embedded
        // art" is recorded as an empty array sentinel rather than null —
        // that way we still remember the absence and don't refetch a
        // known-untagged file every time it scrolls into view.
        private val cache = LruCache<String, ByteArray>(40)
        private val NO_ART = ByteArray(0)

        // Embedded art can be several megabytes; this is a ceiling, not a target.
        private const val MAX_ART_BYTES = 16L * 1024 * 1024
        private const val TIMEOUT_MS = 30_000L

        /** Drops every cached image (and "no art" marker), e.g. on sign-out. */
        fun clearCache() {
            cache.evictAll()
        }
    }

    private class Found(val bytes: ByteArray?)

    suspend fun fetchEmbeddedArt(fileId: String, accessToken: String): ByteArray? {
        cache.get(fileId)?.let { cached ->
            return if (cached.isEmpty()) null else cached
        }

        val source = DriveMediaDataSource(DriveHttp.client, fileId, accessToken, MAX_ART_BYTES)
        val found: Found? = try {
            withTimeoutOrNull(TIMEOUT_MS) {
                Found(withRetriever(source) { it.embeddedPicture })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The platform couldn't read it. A passing network problem
            // shouldn't be remembered as "this track has no art".
            if (source.isTemporaryFailure) return null else Found(null)
        }

        // Timed out: don't record anything, so it's tried again next time.
        if (found == null) return null

        cache.put(fileId, found.bytes ?: NO_ART)
        return found.bytes
    }
}
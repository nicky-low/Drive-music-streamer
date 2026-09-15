package com.example.drivestreamer.drive

import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pulls embedded cover art (ID3 APIC frames, FLAC PICTURE blocks, etc.)
 * directly out of the audio file itself, rather than looking it up from
 * an external metadata service — this keeps it accurate to your actual
 * files and needs no extra API keys.
 *
 * MediaMetadataRetriever supports HTTP(S) sources with custom headers,
 * so we can point it straight at the authenticated Drive media URL and
 * let the platform handle the range-fetching needed to find the tag.
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
    }

    suspend fun fetchEmbeddedArt(fileId: String, accessToken: String): ByteArray? =
        withContext(Dispatchers.IO) {
            cache.get(fileId)?.let { cached ->
                return@withContext if (cached.isEmpty()) null else cached
            }

            val retriever = MediaMetadataRetriever()
            val bytes = try {
                val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
                retriever.setDataSource(url, mapOf("Authorization" to "Bearer $accessToken"))
                retriever.embeddedPicture
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }

            cache.put(fileId, bytes ?: NO_ART)
            bytes
        }
}

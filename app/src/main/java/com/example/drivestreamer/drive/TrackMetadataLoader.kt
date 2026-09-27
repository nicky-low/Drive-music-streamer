package com.example.drivestreamer.drive

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads embedded artist/title tags (ID3, Vorbis comments, etc.) directly
 * from the audio file via MediaMetadataRetriever, the same technique
 * AlbumArtLoader uses for cover art — pointed at the authenticated Drive
 * stream, no separate download step.
 *
 * Returns null for a field the file simply doesn't have tagged; callers
 * should fall back to filename parsing (TrackNameParser) in that case.
 */
class TrackMetadataLoader {

    data class Tags(val artist: String?, val title: String?)

    suspend fun fetchTags(fileId: String, accessToken: String): Tags =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
                retriever.setDataSource(url, mapOf("Authorization" to "Bearer $accessToken"))
                val artist = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                val title = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                Tags(artist, title)
            } catch (e: Exception) {
                // No tags, corrupt/unsupported file, network hiccup — any
                // of these just means "nothing usable", not a hard failure.
                Tags(null, null)
            } finally {
                retriever.release()
            }
        }
}
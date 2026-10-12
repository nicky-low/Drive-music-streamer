package com.example.drivestreamer.drive

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads embedded artist/title tags (ID3, Vorbis comments, etc.) directly
 * from the audio file via MediaMetadataRetriever, reading the file from
 * Drive through [DriveMediaDataSource] — so every request has a timeout,
 * can be cancelled instantly, and reads only as much of the file as the
 * tags need.
 *
 * Returns null for a field the file simply doesn't have tagged; callers
 * should fall back to filename parsing (TrackNameParser) in that case.
 *
 * A passing problem (Drive throttling, a dropped connection, a stall) is
 * retried a couple of times. If it still doesn't work, the result is
 * flagged [Tags.failed] — different from "this file has no tags" — so the
 * caller knows not to remember it as a final answer.
 */
class TrackMetadataLoader {

    data class Tags(
        val artist: String?,
        val title: String?,
        /** True if we couldn't read the file at all, as opposed to it having no tags. */
        val failed: Boolean = false
    )

    companion object {
        // Cover art is embedded in the same tag block as the text tags, so
        // this has to allow for it. It's a cap, not a target: most files
        // need a small fraction of this.
        private const val MAX_TAG_BYTES = 6L * 1024 * 1024
        private const val ATTEMPT_TIMEOUT_MS = 40_000L
        private const val MAX_ATTEMPTS = 3
    }

    private sealed interface Outcome {
        data class Done(val tags: Tags) : Outcome
        object Retry : Outcome
    }

    suspend fun fetchTags(fileId: String, accessToken: String): Tags {
        var backoffMs = 1_500L
        for (attempt in 1..MAX_ATTEMPTS) {
            val outcome = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) { readOnce(fileId, accessToken) }
            if (outcome is Outcome.Done) return outcome.tags
            // Timed out, or hit a temporary network/throttling problem.
            if (attempt < MAX_ATTEMPTS) {
                delay(backoffMs)
                backoffMs *= 3
            }
        }
        return Tags(artist = null, title = null, failed = true)
    }

    private suspend fun readOnce(fileId: String, accessToken: String): Outcome {
        val source = DriveMediaDataSource(DriveHttp.client, fileId, accessToken, MAX_TAG_BYTES)
        return try {
            val tags = withRetriever(source) { retriever ->
                Tags(
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?.trim()?.takeIf { it.isNotEmpty() },
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.trim()?.takeIf { it.isNotEmpty() }
                )
            }
            Outcome.Done(tags)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The platform couldn't read the file. Was it a temporary
            // network problem (worth retrying), or the file itself (no
            // tags, unsupported format, tags too far in) — in which case
            // "nothing usable" is the final answer?
            if (source.isTemporaryFailure) Outcome.Retry else Outcome.Done(Tags(null, null))
        }
    }
}
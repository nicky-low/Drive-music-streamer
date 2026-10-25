package com.example.drivestreamer.playback

import com.example.drivestreamer.drive.Album
import com.example.drivestreamer.drive.Track

/**
 * Helpers for queues that aren't "the whole library" — such as shuffling
 * everything by one artist.
 *
 * Such a queue is remembered as just its list of track IDs, so it can be
 * rebuilt later (after the car restarts, say) even though the app only keeps
 * the library itself on disk, not each queue built from it.
 */
object QueueScope {

    /**
     * The tracks for [ids], in that order. Any ID no longer in the library
     * (the music has since been removed or rescanned) is skipped.
     */
    fun tracksForIds(albums: List<Album>, ids: List<String>): List<Track> {
        val byId = HashMap<String, Track>()
        for (album in albums) {
            for (track in album.tracks) byId[track.fileId] = track
        }
        return ids.mapNotNull { byId[it] }
    }
}

package com.example.drivestreamer.playback

import com.example.drivestreamer.drive.Album
import com.example.drivestreamer.drive.Track
import java.text.Normalizer

/**
 * Search for Android Auto's search box: finds folders and songs whose names
 * contain every word typed, ignoring case and accents ("beyonce" finds
 * "Beyoncé"). A song also matches on its artist and folder name.
 */
object LibrarySearch {

    class Result(val albums: List<Album>, val tracks: List<Track>) {
        val size: Int get() = albums.size + tracks.size
    }

    private val ACCENT_MARKS = Regex("\\p{Mn}+")

    fun normalize(text: String): String =
        ACCENT_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

    /** Kept short on purpose: it's a list read in a car. */
    fun search(albums: List<Album>, query: String, maxAlbums: Int = 10, maxTracks: Int = 40): Result {
        val terms = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return Result(emptyList(), emptyList())

        val albumHits = albums
            .filter { album -> normalize(album.name).let { n -> terms.all { n.contains(it) } } }
            .take(maxAlbums)

        val trackHits = ArrayList<Pair<Int, Track>>()
        for (album in albums) {
            for (track in album.tracks) {
                val haystack = normalize("${track.displayTitle} ${track.displayArtist} ${track.albumName}")
                if (terms.all { haystack.contains(it) }) {
                    // Songs whose title starts with what was typed rank first.
                    val rank = if (normalize(track.displayTitle).startsWith(terms[0])) 0 else 1
                    trackHits.add(rank to track)
                }
            }
        }
        val tracks = trackHits.sortedBy { it.first }.take(maxTracks).map { it.second }
        return Result(albumHits, tracks)
    }
}

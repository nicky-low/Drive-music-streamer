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

    /** An artist matching the search. [key] identifies them (see [tracksForArtist]); [name] is for display. */
    class ArtistMatch(val key: String, val name: String, val trackCount: Int)

    class Result(
        val albums: List<Album>,
        val tracks: List<Track>,
        val artists: List<ArtistMatch> = emptyList()
    ) {
        /** How many rows the results list has: two actions per artist, then folders, then songs. */
        val size: Int get() = artists.size * 2 + albums.size + tracks.size
    }

    private fun artistKey(track: Track): String = normalize(track.displayArtist.trim())

    /** Every song by the artist with this [key], in library order. */
    fun tracksForArtist(albums: List<Album>, key: String): List<Track> {
        if (key.isEmpty()) return emptyList()
        val out = ArrayList<Track>()
        for (album in albums) {
            for (track in album.tracks) if (artistKey(track) == key) out.add(track)
        }
        return out
    }

    private val ACCENT_MARKS = Regex("\\p{Mn}+")

    fun normalize(text: String): String =
        ACCENT_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

    /** Kept short on purpose: it's a list read in a car. */
    fun search(
        albums: List<Album>,
        query: String,
        maxAlbums: Int = 10,
        maxTracks: Int = 40,
        maxArtists: Int = 3
    ): Result {
        val terms = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return Result(emptyList(), emptyList())

        val albumHits = albums
            .filter { album -> normalize(album.name).let { n -> terms.all { n.contains(it) } } }
            .take(maxAlbums)

        val trackHits = ArrayList<Pair<Int, Track>>()
        val artistCounts = LinkedHashMap<String, Int>()
        val artistSpellings = HashMap<String, HashMap<String, Int>>()
        for (album in albums) {
            for (track in album.tracks) {
                // Artists whose name contains every typed word.
                val key = artistKey(track)
                if (key.isNotEmpty() && terms.all { key.contains(it) }) {
                    artistCounts[key] = (artistCounts[key] ?: 0) + 1
                    val spellings = artistSpellings.getOrPut(key) { HashMap() }
                    val shown = track.displayArtist.trim()
                    spellings[shown] = (spellings[shown] ?: 0) + 1
                }

                val haystack = normalize("${track.displayTitle} ${track.displayArtist} ${track.albumName}")
                if (terms.all { haystack.contains(it) }) {
                    // Songs whose title starts with what was typed rank first.
                    val rank = if (normalize(track.displayTitle).startsWith(terms[0])) 0 else 1
                    trackHits.add(rank to track)
                }
            }
        }
        val tracks = trackHits.sortedBy { it.first }.take(maxTracks).map { it.second }
        val artists = artistCounts.entries
            // Names that start with what was typed first (stable).
            .sortedBy { if (it.key.startsWith(terms[0])) 0 else 1 }
            .take(maxArtists)
            .map { (key, count) ->
                val name = artistSpellings[key]?.entries?.maxByOrNull { it.value }?.key ?: key
                ArtistMatch(key, name, count)
            }
        return Result(albumHits, tracks, artists)
    }
}

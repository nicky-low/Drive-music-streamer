package com.example.drivestreamer.drive

/**
 * Turns a raw Drive filename like "03 - Fleetwood Mac - Dreams.mp3" into
 * a clean (artist, title) pair for display, without needing to fetch and
 * parse ID3/FLAC tags (which would mean a network round-trip per track
 * just to build the library list).
 *
 * Handles the common conventions:
 *   "Artist - Title.mp3"
 *   "01 - Artist - Title.mp3"
 *   "01. Artist - Title.mp3"
 *   "Title.mp3"                 (no artist segment found)
 *
 * Falls back to using the whole (cleaned) filename as the title with an
 * empty artist when nothing matches — callers should treat an empty
 * artist as "unknown" and just show the title.
 */
object TrackNameParser {

    private val TRACK_NUMBER_PREFIX = Regex("""^\s*\d{1,3}[.\-_)\s]+""")
    private val SEPARATORS = listOf(" - ", " – ", " — ")

    fun parse(rawFileName: String): Pair<String, String> {
        val withoutExtension = rawFileName.substringBeforeLast('.', rawFileName)
        val withoutTrackNumber = withoutExtension.replaceFirst(TRACK_NUMBER_PREFIX, "").trim()

        for (separator in SEPARATORS) {
            val index = withoutTrackNumber.indexOf(separator)
            if (index > 0) {
                val artist = withoutTrackNumber.substring(0, index).trim()
                val title = withoutTrackNumber.substring(index + separator.length).trim()
                if (artist.isNotEmpty() && title.isNotEmpty()) return artist to title
            }
        }
        return "" to withoutTrackNumber
    }
}
package com.example.drivestreamer.playback

import android.os.Bundle
import androidx.media3.common.MediaMetadata
import com.example.drivestreamer.drive.Track

/**
 * Builds the metadata attached to every queued track, in one place so the
 * app, the service and Android Auto all describe a track the same way.
 *
 * The *artist* field carries "Artist • Album" (see [Track.artistWithAlbum]).
 * Android Auto's now-playing screen, the notification and the lock screen
 * show the artist line but not the album, so this is how the album name gets
 * onto them. The plain artist is kept in [MediaMetadata.extras] for the
 * phone's own screens, which show the album on a separate line and would
 * otherwise repeat it.
 */
object TrackMediaMetadata {

    private const val KEY_PLAIN_ARTIST = "drivestreamer.plain_artist"

    fun builderFor(track: Track): MediaMetadata.Builder =
        MediaMetadata.Builder()
            .setTitle(track.displayTitle)
            .setArtist(track.artistWithAlbum.ifBlank { null })
            .setAlbumTitle(track.albumName)
            .setExtras(Bundle().apply { putString(KEY_PLAIN_ARTIST, track.displayArtist) })

    /** Just the artist, for screens that show the album separately. */
    fun artistOf(metadata: MediaMetadata): String =
        metadata.extras?.getString(KEY_PLAIN_ARTIST) ?: metadata.artist?.toString().orEmpty()
}

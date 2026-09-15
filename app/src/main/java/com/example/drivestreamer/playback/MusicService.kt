package com.example.drivestreamer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.example.drivestreamer.auth.AuthManager
import com.example.drivestreamer.auth.TokenProvider
import com.example.drivestreamer.drive.Album
import com.example.drivestreamer.drive.AlbumArtLoader
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * This service is the bridge to Android Auto. Once it's registered
 * (see AndroidManifest's <service> + automotive_app_desc.xml), Android Auto
 * will bind to it, call onGetLibraryRoot / onGetChildren to build its
 * browsing UI, and route play/pause/skip commands through the MediaSession.
 *
 * Token handling: the service owns its own TokenProvider rather than
 * reading a token MainActivity fetched once — Google Play Services
 * persists sign-in state on-device, so the service can independently
 * confirm who's signed in and fetch/refresh tokens itself, even if it's
 * started fresh by Android Auto without MainActivity having run first
 * (e.g. after a reboot, or if Auto launches the app directly).
 */
class MusicService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private lateinit var tokenProvider: TokenProvider
    private val albumArtLoader = AlbumArtLoader()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()

        val authManager = AuthManager(applicationContext)
        tokenProvider = TokenProvider(authManager)

        // Reconnect to whichever account is signed in on this device —
        // works even if MainActivity hasn't run yet this process.
        GoogleSignIn.getLastSignedInAccount(applicationContext)?.let {
            tokenProvider.setAccount(it)
        }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    buildCachingDataSourceFactory()
                )
            )
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.let { fetchAndAttachArt(it) }
            }
        })

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .build()
    }

    /**
     * Fetches embedded art for the track that just started playing and
     * merges it into that MediaItem's metadata. Playback starts immediately
     * and isn't blocked on this — art appears a moment later once fetched,
     * updating the lock screen / notification / Android Auto display and
     * (via onMediaMetadataChanged) our own now-playing screen.
     */
    private fun fetchAndAttachArt(mediaItem: MediaItem) {
        val fileId = mediaItem.mediaId
        serviceScope.launch {
            val token = tokenProvider.getToken()
            val artBytes = albumArtLoader.fetchEmbeddedArt(fileId, token) ?: return@launch

            // Bail if the user has already skipped to a different track
            // by the time this finishes.
            if (player.currentMediaItem?.mediaId != fileId) return@launch

            val updatedMetadata = mediaItem.mediaMetadata.buildUpon()
                .setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build()
            val updatedItem = mediaItem.buildUpon()
                .setMediaMetadata(updatedMetadata)
                .build()

            player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
        }
    }

    /**
     * Wraps the raw Drive data source in a disk cache: reads are served
     * from disk if we already have the bytes, and any bytes fetched from
     * the network are written to disk as they stream past, so the next
     * play of the same track (or same range, e.g. re-seeking) is instant
     * and needs no network at all.
     */
    private fun buildCachingDataSourceFactory(): DataSource.Factory {
        val cache = PlaybackCache.get(applicationContext)
        val upstreamFactory = GoogleDriveDataSource.Factory(tokenProvider)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            // If disk cache I/O itself fails (corrupt entry, full disk),
            // fall back to streaming from Drive rather than failing playback.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        mediaSession

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Drive Music")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
            val items = if (parentId == "root") {
                MusicLibraryHolder.albums.map { album -> albumToMediaItem(album) }
            } else {
                val album = MusicLibraryHolder.albums.find { it.folderId == parentId }
                album?.tracks?.map { track ->
                    MediaItem.Builder()
                        .setMediaId(track.fileId)
                        .setUri("drive://file/${track.fileId}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(track.title)
                                .setAlbumTitle(track.albumName)
                                .setArtworkUri(AlbumArtContentProvider.uriFor(track.fileId))
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build()
                        )
                        .build()
                } ?: emptyList()
            }
            return Futures.immediateFuture(
                LibraryResult.ofItemList(com.google.common.collect.ImmutableList.copyOf(items), params)
            )
        }

        private fun albumToMediaItem(album: Album): MediaItem =
            MediaItem.Builder()
                .setMediaId(album.folderId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(album.name)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .apply {
                            // Use the first track's embedded art as the
                            // album's thumbnail — Drive folders don't carry
                            // their own cover image, so this is the closest
                            // stand-in without an extra metadata source.
                            album.tracks.firstOrNull()?.let { firstTrack ->
                                setArtworkUri(AlbumArtContentProvider.uriFor(firstTrack.fileId))
                            }
                        }
                        .build()
                )
                .build()
    }
}

/**
 * Simple in-memory bridge between MainActivity (where library loading
 * happens) and the service (which needs the track list to browse/stream).
 * Fine for a personal single-user app. The access token itself no longer
 * lives here — see TokenProvider, which each component fetches its own
 * instance of.
 */
object MusicLibraryHolder {
    var albums: List<Album> = emptyList()
}

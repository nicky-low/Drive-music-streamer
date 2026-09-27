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

    private fun buildCachingDataSourceFactory(): DataSource.Factory {
        val cache = PlaybackCache.get(applicationContext)
        val upstreamFactory = GoogleDriveDataSource.Factory(tokenProvider)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun fetchAndAttachArt(mediaItem: MediaItem) {
        val fileId = mediaItem.mediaId
        serviceScope.launch {
            val token = tokenProvider.getToken()
            val artBytes = albumArtLoader.fetchEmbeddedArt(fileId, token) ?: return@launch

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
                                .setTitle(track.displayTitle)
                                .setArtist(track.displayArtist.ifBlank { null })
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
                            album.tracks.firstOrNull()?.let { firstTrack ->
                                setArtworkUri(AlbumArtContentProvider.uriFor(firstTrack.fileId))
                            }
                        }
                        .build()
                )
                .build()
    }
}

object MusicLibraryHolder {
    var albums: List<Album> = emptyList()
}
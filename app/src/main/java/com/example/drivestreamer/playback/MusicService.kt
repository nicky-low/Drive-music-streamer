package com.example.drivestreamer.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import com.example.drivestreamer.drive.LibraryCacheStore
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        tokenProvider = TokenProvider(authManager) {
            GoogleSignIn.getLastSignedInAccount(applicationContext)
        }

        // Also try eagerly now — harmless if it's too early (the lazy
        // resolver above covers that case), useful if sign-in already
        // happened before this service was created.
        GoogleSignIn.getLastSignedInAccount(applicationContext)?.let {
            tokenProvider.setAccount(it)
        }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    buildCachingDataSourceFactory()
                )
            )
            .setAudioAttributes(
                // This is the actual fix for "plays at the same time as
                // whatever was already playing": without an explicit,
                // correctly-typed AudioAttributes + handleAudioFocus=true,
                // ExoPlayer doesn't reliably request focus, so the OS never
                // tells the other app to pause. USAGE_MEDIA + CONTENT_TYPE_MUSIC
                // is what marks this as "real" foreground music playback
                // rather than e.g. a notification sound.
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.let { fetchAndAttachArt(it) }
            }
        })

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .build()

        // Android Auto can start this service cold (after a reboot or the
        // process being killed) without LibraryActivity ever running, in
        // which case MusicLibraryHolder is empty. Warm it from the on-disk
        // cache right away so the first browse request is usually instant.
        // onGetChildren also awaits this (see below), so a browse request
        // that arrives mid-load waits instead of seeing an empty library.
        serviceScope.launch { MusicLibraryHolder.ensureLoaded(applicationContext) }

        // When the library is replaced (fresh scan) or cleared (sign-out),
        // tell any connected browser — Android Auto — to re-fetch the root
        // list instead of showing a stale one.
        MusicLibraryHolder.addListener(libraryChangedListener)
    }

    private val libraryChangedListener: () -> Unit = {
        mediaSession.notifyChildrenChanged("root", MusicLibraryHolder.albums.size, null)
    }

    /** Bridges a suspend block to the ListenableFuture the Media3 callbacks expect. */
    private fun <T> futureOf(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val job = serviceScope.launch {
            try {
                future.set(block())
            } catch (e: CancellationException) {
                future.cancel(false)
                throw e
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        future.addListener(
            { if (future.isCancelled) job.cancel() },
            MoreExecutors.directExecutor()
        )
        return future
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
        MusicLibraryHolder.removeListener(libraryChangedListener)
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
            return futureOf {
                // No-op if already in memory; otherwise reads the cache.
                MusicLibraryHolder.ensureLoaded(applicationContext)
                val albums = MusicLibraryHolder.albums

                val items = if (parentId == "root") {
                    albums.map { album -> albumToMediaItem(album) }
                } else {
                    val album = albums.find { it.folderId == parentId }
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
                LibraryResult.ofItemList(
                    com.google.common.collect.ImmutableList.copyOf(items), params
                )
            }
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
    @Volatile
    var albums: List<Album> = emptyList()
        private set

    private val loadMutex = Mutex()
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    /** Called whenever [albums] is replaced or cleared (not on a cache warm-up). */
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    /** A fresh scan finished: swap in the new library and tell listeners. */
    fun replace(newAlbums: List<Album>) {
        albums = newAlbums
        listeners.forEach { it() }
    }

    /** Sign-out: empty the library. Waits for any in-flight cache read first. */
    suspend fun clear() {
        loadMutex.withLock { replace(emptyList()) }
    }

    /**
     * Fills [albums] from the on-disk cache if nothing is in memory yet.
     * Safe to call repeatedly and from several places at once: later
     * callers wait on the mutex, then see the already-loaded list. A fresh
     * scan finishing in the meantime wins over the cache.
     */
    suspend fun ensureLoaded(context: Context) {
        if (albums.isNotEmpty()) return
        loadMutex.withLock {
            if (albums.isNotEmpty()) return
            val cached = LibraryCacheStore(context.applicationContext).load() ?: return
            if (albums.isEmpty()) albums = cached.albums
        }
    }
}
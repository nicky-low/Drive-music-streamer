package com.example.drivestreamer.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.TaskStackBuilder
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
import com.example.drivestreamer.drive.Track
import com.example.drivestreamer.ui.NowPlayingActivity
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
            // We stream over the network, so keep the CPU and Wi-Fi awake
            // while playing with the screen off (needs WAKE_LOCK). Only held
            // during playback — released on pause/stop.
            .setWakeMode(C.WAKE_MODE_NETWORK)
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

        // Tapping the media notification (or the lock-screen player) opens
        // Now Playing, with Library underneath it so Back goes somewhere
        // sensible. The parent chain comes from NowPlayingActivity's
        // parentActivityName in the manifest.
        val openNowPlaying = TaskStackBuilder.create(this)
            .addNextIntentWithParentStack(Intent(this, NowPlayingActivity::class.java))
            .getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .apply { openNowPlaying?.let { setSessionActivity(it) } }
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

    /** The one place a [Track] becomes a playable, browsable MediaItem. */
    private fun trackToMediaItem(track: Track): MediaItem =
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

    /**
     * Turns an ID-only item (all Android Auto sends when you tap a song)
     * into one ExoPlayer can play. Items that already have a URI, like
     * the ones our own app sends, pass through untouched.
     */
    private fun resolveMediaItem(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item
        for (album in MusicLibraryHolder.albums) {
            album.tracks.firstOrNull { it.fileId == item.mediaId }
                ?.let { return trackToMediaItem(it) }
        }
        throw UnsupportedOperationException("Unknown media id: ${item.mediaId}")
    }

    /**
     * The queue to play when asked for a single id: the whole album that
     * contains the track, starting at that track (so Next/Previous work
     * in Auto). Also accepts an album id, starting from its first track.
     */
    private fun queueFor(mediaId: String): Pair<List<MediaItem>, Int>? {
        val albums = MusicLibraryHolder.albums
        for (album in albums) {
            val index = album.tracks.indexOfFirst { it.fileId == mediaId }
            if (index >= 0) return album.tracks.map { trackToMediaItem(it) } to index
        }
        albums.firstOrNull { it.folderId == mediaId && it.tracks.isNotEmpty() }
            ?.let { return it.tracks.map { t -> trackToMediaItem(t) } to 0 }
        return null
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

    /**
     * The app's task was swiped away from recents.
     *
     * Playing: Media3's default keeps the service running, so music carries
     * on. Paused: the default tries to stop the service, but our own
     * in-app controller (PlaybackClient) is still bound to it, which blocks
     * the stop and leaves a paused service + notification lingering. So
     * when nothing is playing, drop that controller first. Android Auto, if
     * connected, stays bound on its own and keeps the service alive.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) {
            PlaybackClient.releaseAll()
        }
        super.onTaskRemoved(rootIntent)
    }

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
                    album?.tracks?.map { trackToMediaItem(it) } ?: emptyList()
                }
                LibraryResult.ofItemList(
                    com.google.common.collect.ImmutableList.copyOf(items), params
                )
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> = futureOf {
            MusicLibraryHolder.ensureLoaded(applicationContext)
            val albums = MusicLibraryHolder.albums
            val item: MediaItem? = when {
                mediaId == "root" -> MediaItem.Builder()
                    .setMediaId("root")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Drive Music")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build()
                    )
                    .build()
                else -> albums.firstOrNull { it.folderId == mediaId }?.let { albumToMediaItem(it) }
                    ?: albums.asSequence()
                        .flatMap { it.tracks.asSequence() }
                        .firstOrNull { it.fileId == mediaId }
                        ?.let { trackToMediaItem(it) }
            }
            if (item != null) LibraryResult.ofItem(item, null)
            else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        // Android Auto asks to play a song by sending only its id, with no
        // URI. Media3's default refuses such items, which is what shows up
        // as "can't load your selection". Fill in the playable details
        // from the library instead.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> = futureOf {
            MusicLibraryHolder.ensureLoaded(applicationContext)
            mediaItems.map { resolveMediaItem(it) }.toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = futureOf {
            MusicLibraryHolder.ensureLoaded(applicationContext)
            val single = mediaItems.singleOrNull()
            val queue = if (single != null && single.localConfiguration == null) {
                queueFor(single.mediaId)
            } else null

            if (queue != null) {
                MediaSession.MediaItemsWithStartPosition(queue.first, queue.second, C.TIME_UNSET)
            } else {
                MediaSession.MediaItemsWithStartPosition(
                    mediaItems.map { resolveMediaItem(it) }, startIndex, startPositionMs
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
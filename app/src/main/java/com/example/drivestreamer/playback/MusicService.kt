package com.example.drivestreamer.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.drivestreamer.auth.AuthManager
import com.example.drivestreamer.auth.TokenProvider
import com.example.drivestreamer.drive.Album
import com.example.drivestreamer.drive.AlbumArtLoader
import com.example.drivestreamer.drive.LibraryCacheStore
import com.example.drivestreamer.drive.Track
import com.example.drivestreamer.ui.MainActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.common.collect.ImmutableList
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

// Shuffle and repeat buttons shown by Android Auto (and in the phone's media
// notification). They're "custom commands": Auto sends the action name back
// to us when tapped, and onCustomCommand below changes the player.
private const val ACTION_TOGGLE_SHUFFLE = "com.example.drivestreamer.TOGGLE_SHUFFLE"
private const val ACTION_CYCLE_REPEAT = "com.example.drivestreamer.CYCLE_REPEAT"
private val SHUFFLE_COMMAND = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
private val REPEAT_COMMAND = SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY)

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
        // the app on its Now Playing tab. The request code (100) keeps this
        // PendingIntent distinct from the library-load notification's, which
        // targets the same activity: PendingIntents that differ only in
        // their extras would otherwise be treated as one and overwrite
        // each other.
        val openNowPlaying = PendingIntent.getActivity(
            this, 100,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_NOW_PLAYING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openNowPlaying)
            .build()

        // Shuffle/repeat can also be changed from the phone's Now Playing
        // tab; refresh the buttons so Auto always shows the real state.
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                refreshCustomLayout()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                refreshCustomLayout()
            }
        })

        // Android Auto can start this service cold (after a reboot or the
        // process being killed) without the app's UI ever running, in
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

    /** The shuffle and repeat buttons, with icons reflecting the current state. */
    private fun buildCustomLayout(): ImmutableList<CommandButton> {
        val shuffleOn = player.shuffleModeEnabled
        val shuffleButton = CommandButton.Builder(
            if (shuffleOn) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        )
            .setSessionCommand(SHUFFLE_COMMAND)
            .setDisplayName(if (shuffleOn) "Shuffle: on" else "Shuffle: off")
            .build()

        val (repeatIcon, repeatLabel) = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL to "Repeat: all"
            Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE to "Repeat: one"
            else -> CommandButton.ICON_REPEAT_OFF to "Repeat: off"
        }
        val repeatButton = CommandButton.Builder(repeatIcon)
            .setSessionCommand(REPEAT_COMMAND)
            .setDisplayName(repeatLabel)
            .build()

        return ImmutableList.of(shuffleButton, repeatButton)
    }

    private fun refreshCustomLayout() {
        mediaSession.setCustomLayout(buildCustomLayout())
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

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Allow our two custom commands on top of the normal ones, and
            // hand the controller (Android Auto included) the buttons.
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SHUFFLE_COMMAND)
                .add(REPEAT_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(buildCustomLayout())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_TOGGLE_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                // Same cycle as the phone's repeat button: off -> all -> one -> off.
                ACTION_CYCLE_REPEAT -> player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                else -> return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
            // The player listener refreshes the buttons once the change lands.
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

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

package com.example.drivestreamer.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.withContext

// Shuffle and repeat buttons shown by Android Auto (and in the phone's media
// notification). They're "custom commands": Auto sends the action name back
// to us when tapped, and onCustomCommand below changes the player.
private const val ACTION_TOGGLE_SHUFFLE = "com.example.drivestreamer.TOGGLE_SHUFFLE"
private const val ACTION_CYCLE_REPEAT = "com.example.drivestreamer.CYCLE_REPEAT"
private val SHUFFLE_COMMAND = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
private val REPEAT_COMMAND = SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY)

// The "Continue listening" row at the top of Android Auto's browse list, and
// the root Android asks for when it wants to know what was played last (the
// "recent" contract, used for resuming from the notification shade, after a
// reboot, etc.).
private const val RESUME_ID = "resume"
private const val RECENT_ROOT_ID = "recent"

// How often the playback position is saved while music is playing.
private const val SAVE_INTERVAL_MS = 10_000L

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
    private lateinit var stateStore: PlaybackStateStore

    // Saves the playback position every so often while playing, so a
    // sudden stop (car switched off, phone disconnected) loses very little.
    private val saveHandler = Handler(Looper.getMainLooper())
    private val periodicSave = object : Runnable {
        override fun run() {
            savePlaybackState()
            if (player.isPlaying) saveHandler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    /** The saved track expanded into a queue, ready to hand to the player. */
    private class SavedQueue(
        val items: List<MediaItem>,
        val index: Int,
        val state: PlaybackStateStore.Saved
    )

    override fun onCreate() {
        super.onCreate()
        stateStore = PlaybackStateStore(applicationContext)

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

        // Remember what's playing, so it can be picked up again later —
        // e.g. when the car starts and Android Auto asks us to play.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                savePlaybackState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                savePlaybackState()
                saveHandler.removeCallbacks(periodicSave)
                if (isPlaying) saveHandler.postDelayed(periodicSave, SAVE_INTERVAL_MS)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) savePlaybackState()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                savePlaybackState()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                savePlaybackState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) savePlaybackState()
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

    // --- Remembering and resuming playback -----------------------------

    private fun savePlaybackState() {
        // Nothing to remember if nothing's loaded, or if signed out (stopping
        // playback during sign-out fires events that would otherwise write
        // the state straight back after it was cleared).
        val item = player.currentMediaItem ?: return
        if (GoogleSignIn.getLastSignedInAccount(applicationContext) == null) return

        // A queue that played to its end should resume from the start of the
        // last track, not from its final second.
        val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
        stateStore.save(
            mediaId = item.mediaId,
            positionMs = position.coerceAtLeast(0L),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode
        )
    }

    /**
     * The last-played track expanded to the whole-library queue it was
     * playing from, or null if there's nothing saved or the track is no
     * longer in the library.
     */
    private suspend fun loadSavedQueue(): SavedQueue? {
        val saved = stateStore.load() ?: return null
        MusicLibraryHolder.ensureLoaded(applicationContext)
        val queue = withContext(Dispatchers.Default) { queueFor(saved.mediaId) } ?: return null
        return SavedQueue(queue.first, queue.second, saved)
    }

    private fun applyShuffleAndRepeat(state: PlaybackStateStore.Saved) {
        player.shuffleModeEnabled = state.shuffle
        player.repeatMode = state.repeatMode
    }

    /** What to play when asked to resume: the saved queue, at the saved position. */
    private suspend fun resumeRequest(): MediaSession.MediaItemsWithStartPosition {
        val saved = loadSavedQueue() ?: throw UnsupportedOperationException("Nothing to resume")
        applyShuffleAndRepeat(saved.state)
        return MediaSession.MediaItemsWithStartPosition(saved.items, saved.index, saved.state.positionMs)
    }

    /**
     * Puts the last track back in the player, paused and ready, if nothing
     * is loaded. Done when Android Auto connects, so the app opens showing
     * where you left off instead of an empty player.
     */
    private fun restoreLastPlayback() {
        if (player.mediaItemCount > 0) return
        serviceScope.launch {
            val saved = loadSavedQueue() ?: return@launch
            if (player.mediaItemCount > 0) return@launch // something else got there first
            applyShuffleAndRepeat(saved.state)
            player.setMediaItems(saved.items, saved.index, saved.state.positionMs)
            player.prepare() // ready to play, but not playing
        }
    }

    /** The saved track as a browsable item, or null if there isn't one. */
    private fun savedTrack(): Track? {
        val saved = stateStore.load() ?: return null
        for (album in MusicLibraryHolder.albums) {
            album.tracks.firstOrNull { it.fileId == saved.mediaId }?.let { return it }
        }
        return null
    }

    /**
     * "Continue listening" row for the top of the browse list.
     *
     * Offered whenever there's a saved track, even while music is playing.
     * Android Auto can reuse a folder list it fetched earlier, so hiding the
     * row during playback could leave it missing later, exactly when it's
     * needed. Tapping it always resumes the *latest* saved position, whatever
     * the row's label said when it was drawn.
     */
    private fun resumeItem(): MediaItem? {
        val track = savedTrack() ?: return null
        return MediaItem.Builder()
            .setMediaId(RESUME_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Continue listening")
                    .setSubtitle(track.displayLabel)
                    .setArtworkUri(AlbumArtContentProvider.uriFor(track.fileId))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
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
     * The queue to play when asked for a single id (all Android Auto sends
     * when you tap a song): the whole library in folder order, starting at
     * that song. This matches what tapping a song in the app does, so Next
     * carries on into the following folder and Shuffle covers everything.
     * An album id is accepted too, starting from that album's first track.
     *
     * Returns null if the id isn't in the library.
     */
    private fun queueFor(mediaId: String): Pair<List<MediaItem>, Int>? {
        val albums = MusicLibraryHolder.albums

        // Where the requested track (or, failing that, album) sits in the
        // flattened library.
        var startIndex = -1
        var offset = 0
        for (album in albums) {
            val inAlbum = album.tracks.indexOfFirst { it.fileId == mediaId }
            if (inAlbum >= 0) {
                startIndex = offset + inAlbum
                break
            }
            offset += album.tracks.size
        }
        if (startIndex < 0) {
            offset = 0
            for (album in albums) {
                if (album.folderId == mediaId && album.tracks.isNotEmpty()) {
                    startIndex = offset
                    break
                }
                offset += album.tracks.size
            }
        }
        if (startIndex < 0) return null

        return albums.flatMap { it.tracks }.map { trackToMediaItem(it) } to startIndex
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
        savePlaybackState()
        if (!isPlaybackOngoing) {
            PlaybackClient.releaseAll()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        savePlaybackState()
        saveHandler.removeCallbacksAndMessages(null)
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

        override fun onPostConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ) {
            // A Media3-style controller from another app connected (Android
            // Auto does NOT come through here — see onGetLibraryRoot). If
            // nothing is queued, bring back the last track, paused. Our own
            // screens are skipped: the phone app opens to an empty player.
            if (controller.packageName != packageName) restoreLastPlayback()
        }

        // A "play" arrived while nothing is loaded — typically Android Auto
        // starting music automatically as the car starts, or a Bluetooth
        // play button. Without this, Media3 has nothing to play and does
        // nothing. Hand it the saved queue at the saved position.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = futureOf {
            resumeRequest()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Android Auto connects as a "media browser", and Media3 does not
            // report that kind of connection through onPostConnect — this
            // call is the one that does happen, every time Auto connects.
            // So this is where we bring back the last track (paused), making
            // the app open on where you left off instead of an empty player.
            //
            // Not for the special "recent" root: that's just a question
            // ("what was played last?") that the system also asks at times
            // like boot, and shouldn't start loading music by itself.
            if (browser.packageName != packageName && params?.isRecent != true) {
                restoreLastPlayback()
            }

            // Android sometimes asks for a special "recent" root: just the
            // last thing played, used to offer resuming it (from the
            // notification shade after a reboot, for instance).
            val rootItem = MediaItem.Builder()
                .setMediaId(if (params?.isRecent == true) RECENT_ROOT_ID else "root")
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

                val items = if (parentId == RECENT_ROOT_ID) {
                    listOfNotNull(savedTrack()?.let { trackToMediaItem(it) })
                } else if (parentId == "root") {
                    // "Continue listening" first (when there's something to
                    // continue), then the folders.
                    listOfNotNull(resumeItem()) + albums.map { album -> albumToMediaItem(album) }
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
                mediaId == RESUME_ID -> resumeItem()
                mediaId == RECENT_ROOT_ID || mediaId == "root" -> MediaItem.Builder()
                    .setMediaId(mediaId)
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

            // The "Continue listening" row: the saved queue, at the saved position.
            if (single?.mediaId == RESUME_ID) return@futureOf resumeRequest()

            val queue = if (single != null && single.localConfiguration == null) {
                // A few thousand items to build: keep it off the main thread.
                withContext(Dispatchers.Default) { queueFor(single.mediaId) }
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

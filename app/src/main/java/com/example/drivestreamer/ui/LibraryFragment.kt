package com.example.drivestreamer.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.example.drivestreamer.R
import com.example.drivestreamer.drive.Album
import com.example.drivestreamer.drive.Track
import com.example.drivestreamer.playback.LibraryLoadState
import com.example.drivestreamer.playback.MusicLibraryHolder
import com.example.drivestreamer.playback.PlaybackClient
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

/**
 * The Library tab: folders (albums), and the songs inside the one you
 * open. The library itself comes from MusicLibraryHolder — the same copy
 * Android Auto browses — so this tab and Auto can never disagree, and a
 * fresh scan started from Settings shows up here by itself.
 *
 * Inside a folder, the track that's currently playing is highlighted.
 */
class LibraryFragment : Fragment(R.layout.fragment_library) {

    companion object {
        private const val KEY_OPEN_ALBUM = "open_album"
    }

    private var albums: List<Album> = emptyList()
    private var openAlbumIndex: Int? = null

    // Album to reopen once the library is available again, after a
    // rotation or the app being recreated.
    private var pendingOpenAlbum = -1

    // The "no library yet" message waits until we've checked the on-disk
    // cache, so it doesn't flash up for a moment on every launch.
    private var cacheChecked = false

    // Captures the album list's exact scroll offset before navigating
    // into a folder, so coming back restores it instead of resetting to
    // the top — the annoying part of a long folder list otherwise.
    private var albumListScrollState: Parcelable? = null

    // Which track is playing right now (for the highlight in a folder).
    private var controller: MediaController? = null
    private var playingMediaId: String? = null
    private var trackAdapter: TrackAdapter? = null

    private lateinit var backButton: ImageButton
    private lateinit var titleText: TextView
    private lateinit var listView: ListView
    private lateinit var emptyState: View
    private lateinit var loadingBanner: View
    private lateinit var loadingText: TextView
    private lateinit var loadingBar: LinearProgressIndicator

    private val libraryChangedListener: () -> Unit = {
        // A fresh scan finished, or the library was cleared on sign-out.
        if (view != null) {
            cacheChecked = true
            albums = MusicLibraryHolder.albums
            albumListScrollState = null
            pendingOpenAlbum = -1
            showAlbumList()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val id = player.currentMediaItem?.mediaId
            if (id != playingMediaId) {
                playingMediaId = id
                trackAdapter?.notifyDataSetChanged()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        backButton = view.findViewById(R.id.backButton)
        titleText = view.findViewById(R.id.listTitleText)
        listView = view.findViewById(R.id.trackListView)
        emptyState = view.findViewById(R.id.emptyState)
        loadingBanner = view.findViewById(R.id.loadingBanner)
        loadingText = view.findViewById(R.id.loadingText)
        loadingBar = view.findViewById(R.id.loadingBar)

        backButton.setOnClickListener { showAlbumList() }
        view.findViewById<Button>(R.id.emptySettingsButton).setOnClickListener {
            (activity as? MainActivity)?.showSettings()
        }

        pendingOpenAlbum = savedInstanceState?.getInt(KEY_OPEN_ALBUM, -1) ?: -1

        // Whatever's already in memory shows immediately...
        albums = MusicLibraryHolder.albums
        cacheChecked = albums.isNotEmpty()
        renderCurrent()
        MusicLibraryHolder.addListener(libraryChangedListener)

        // ...otherwise fall back to the on-disk cache (no network, no
        // waiting on tag reads).
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            MusicLibraryHolder.ensureLoaded(appContext)
            val loaded = MusicLibraryHolder.albums
            cacheChecked = true
            if (albums.isEmpty()) {
                albums = loaded
                renderCurrent()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LibraryLoadState.status.collect { renderLoading(it) }
            }
        }

        // Follow what's playing, to highlight it inside a folder.
        PlaybackClient.connect(appContext) { c ->
            if (this.view == null) return@connect
            controller = c
            c.addListener(playerListener)
            playingMediaId = c.currentMediaItem?.mediaId
            trackAdapter?.notifyDataSetChanged()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_OPEN_ALBUM, openAlbumIndex ?: -1)
    }

    override fun onDestroyView() {
        MusicLibraryHolder.removeListener(libraryChangedListener)
        controller?.removeListener(playerListener)
        controller = null
        trackAdapter = null
        super.onDestroyView()
    }

    /** Back button: close the open folder. Returns false if none was open. */
    fun handleBack(): Boolean {
        if (view == null || openAlbumIndex == null) return false
        showAlbumList()
        return true
    }

    private fun renderLoading(status: LibraryLoadState.Status) {
        if (status is LibraryLoadState.Status.Loading) {
            loadingBanner.visibility = View.VISIBLE
            if (status.total > 0) {
                loadingText.text = "Loading library… ${status.done} / ${status.total}"
                loadingBar.visibility = View.VISIBLE
                loadingBar.max = status.total
                loadingBar.setProgressCompat(status.done, true)
            } else {
                loadingText.text = "Finding your music…"
                loadingBar.visibility = View.GONE
            }
        } else {
            loadingBanner.visibility = View.GONE
        }
    }

    /** Re-open the folder we were in before a rotation, else show the list. */
    private fun renderCurrent() {
        val index = pendingOpenAlbum
        pendingOpenAlbum = -1
        if (index in albums.indices) {
            showTracksForAlbum(index, saveScroll = false)
        } else {
            showAlbumList()
        }
    }

    // --- Folder/album navigation -------------------------------------

    private fun showAlbumList() {
        openAlbumIndex = null
        trackAdapter = null
        backButton.visibility = View.GONE
        titleText.text = "Library"
        emptyState.visibility =
            if (albums.isEmpty() && cacheChecked) View.VISIBLE else View.GONE

        listView.adapter = AlbumAdapter(albums)
        listView.setOnItemClickListener { _, _, position, _ -> showTracksForAlbum(position) }

        // Restore exact scroll offset captured when we last left this
        // list — posted so it applies after the adapter's own layout pass.
        albumListScrollState?.let { state ->
            listView.post { listView.onRestoreInstanceState(state) }
        }
    }

    private fun showTracksForAlbum(albumIndex: Int, saveScroll: Boolean = true) {
        // Capture the album list's current scroll position before we
        // replace its content with this album's tracks.
        if (saveScroll) albumListScrollState = listView.onSaveInstanceState()

        val album = albums.getOrNull(albumIndex) ?: return
        openAlbumIndex = albumIndex
        backButton.visibility = View.VISIBLE
        titleText.text = album.name
        emptyState.visibility = View.GONE

        val adapter = TrackAdapter(album.tracks)
        trackAdapter = adapter
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val flatIndex = albums.take(albumIndex).sumOf { it.tracks.size } + position
            playTrackAt(flatIndex)
        }
    }

    // --- Rows ----------------------------------------------------------

    private inner class AlbumAdapter(private val items: List<Album>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_album, parent, false)
            val album = items[position]
            val tile = row.findViewById<TextView>(R.id.albumTile)
            tile.text = initialFor(album.name)
            tile.background = tileBackground(album.name)
            row.findViewById<TextView>(R.id.albumName).text = album.name
            return row
        }
    }

    private inner class TrackAdapter(private val items: List<Track>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_track, parent, false)
            val track = items[position]
            val isPlaying = track.fileId == playingMediaId
            val ctx = parent.context

            val number = row.findViewById<TextView>(R.id.trackNumber)
            val playingIcon = row.findViewById<ImageView>(R.id.playingIcon)
            number.text = (position + 1).toString()
            number.visibility = if (isPlaying) View.INVISIBLE else View.VISIBLE
            playingIcon.visibility = if (isPlaying) View.VISIBLE else View.GONE

            val title = row.findViewById<TextView>(R.id.trackName)
            title.text = track.displayTitle
            title.setTextColor(
                ContextCompat.getColor(ctx, if (isPlaying) R.color.accent else R.color.text_primary)
            )

            val artist = row.findViewById<TextView>(R.id.trackArtistName)
            artist.text = track.displayArtist
            artist.visibility = if (track.displayArtist.isBlank()) View.GONE else View.VISIBLE
            return row
        }
    }

    /** First letter or digit of the name, for the folder's tile. */
    private fun initialFor(name: String): String =
        name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "♪"

    /** A rounded tile whose colour is derived from the name, so each folder is recognisable. */
    private fun tileBackground(name: String): GradientDrawable {
        val hue = (name.hashCode() and 0x7fffffff) % 360
        val color = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.52f))
        val radius = 12f * resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
    }

    // --- Playback ------------------------------------------------------

    private fun playTrackAt(flatIndex: Int) {
        val allTracks = albums.flatMap { it.tracks }
        if (allTracks.isEmpty()) return

        val mediaItems = allTracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.fileId)
                .setUri("drive://file/${track.fileId}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.displayTitle)
                        .setArtist(track.displayArtist.ifBlank { null })
                        .setAlbumTitle(track.albumName)
                        .build()
                )
                .build()
        }

        PlaybackClient.connect(requireContext().applicationContext) { c ->
            c.setMediaItems(mediaItems, flatIndex, 0L)
            c.prepare()
            c.play()
            (activity as? MainActivity)?.showNowPlaying()
        }
    }
}
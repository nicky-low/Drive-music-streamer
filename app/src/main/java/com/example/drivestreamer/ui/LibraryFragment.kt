package com.example.drivestreamer.ui

import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.drivestreamer.R
import com.example.drivestreamer.drive.Album
import com.example.drivestreamer.playback.LibraryLoadState
import com.example.drivestreamer.playback.MusicLibraryHolder
import com.example.drivestreamer.playback.PlaybackClient
import kotlinx.coroutines.launch

/**
 * The Library tab: folders (albums), and the songs inside the one you
 * open. The library itself comes from MusicLibraryHolder — the same copy
 * Android Auto browses — so this tab and Auto can never disagree, and a
 * fresh scan started from Settings shows up here by itself.
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

    private lateinit var backButton: Button
    private lateinit var listTitleText: TextView
    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private lateinit var loadingText: TextView

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        backButton = view.findViewById(R.id.backButton)
        listTitleText = view.findViewById(R.id.listTitleText)
        listView = view.findViewById(R.id.trackListView)
        emptyText = view.findViewById(R.id.emptyText)
        loadingText = view.findViewById(R.id.loadingText)

        backButton.setOnClickListener { showAlbumList() }

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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_OPEN_ALBUM, openAlbumIndex ?: -1)
    }

    override fun onDestroyView() {
        MusicLibraryHolder.removeListener(libraryChangedListener)
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
            loadingText.visibility = View.VISIBLE
            loadingText.text = if (status.total > 0) {
                "Loading library… ${status.done} / ${status.total}"
            } else {
                "Finding your music…"
            }
        } else {
            loadingText.visibility = View.GONE
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
        backButton.visibility = View.GONE
        listTitleText.text = "Albums"
        emptyText.visibility =
            if (albums.isEmpty() && cacheChecked) View.VISIBLE else View.GONE

        val labels = albums.map { it.name }
        listView.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)
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
        listTitleText.text = album.name
        emptyText.visibility = View.GONE

        val labels = album.tracks.map { it.displayLabel }
        listView.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)
        listView.setOnItemClickListener { _, _, position, _ ->
            val flatIndex = albums.take(albumIndex).sumOf { it.tracks.size } + position
            playTrackAt(flatIndex)
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

        PlaybackClient.connect(requireContext().applicationContext) { controller ->
            controller.setMediaItems(mediaItems, flatIndex, 0L)
            controller.prepare()
            controller.play()
            (activity as? MainActivity)?.showNowPlaying()
        }
    }
}

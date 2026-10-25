package com.example.drivestreamer.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.random.Random

// ---- Search support (private to this file) ------------------------------

private const val MAX_TRACK_RESULTS = 200
private const val MAX_ARTIST_RESULTS = 20
private val ACCENT_MARKS = Regex("\\p{Mn}+")

/** Lower-case and strip accents, so "beyonce" finds "Beyoncé". */
private fun normalize(text: String): String =
    ACCENT_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

/** One song, pre-processed for fast matching. [flatIndex] is its place in the whole-library queue. */
private class SearchEntry(
    val track: Track,
    val flatIndex: Int,
    val title: String,
    val haystack: String
)

/**
 * An artist and all of their songs in the library. [key] is the normalised
 * name used for matching ("Beyoncé", "BEYONCE" and "beyonce" are one artist);
 * [name] is the spelling that appears most often, for display.
 */
private class ArtistEntry(val key: String, val name: String, val tracks: List<Track>)

private class SearchIndex(
    val entries: List<SearchEntry>,
    val albumKeys: List<String>,
    val artists: List<ArtistEntry>
)

private sealed interface ResultRow {
    data class Header(val text: String) : ResultRow
    data class ArtistRow(val artist: ArtistEntry) : ResultRow
    data class AlbumRow(val albumIndex: Int) : ResultRow
    data class TrackRow(val entry: SearchEntry) : ResultRow
}

/**
 * What a search found: the rows to show (capped, so the list stays light),
 * and *every* matching song, so "shuffle all" plays all of them and not
 * just the ones on screen.
 */
private class SearchOutcome(val rows: List<ResultRow>, val allHits: List<SearchEntry>)

private fun buildIndex(albums: List<Album>): SearchIndex {
    val entries = ArrayList<SearchEntry>()
    val tracksByArtist = LinkedHashMap<String, MutableList<Track>>()
    val spellings = HashMap<String, HashMap<String, Int>>()
    var flat = 0
    for (album in albums) {
        for (track in album.tracks) {
            val artist = track.displayArtist.trim()
            if (artist.isNotEmpty()) {
                val key = normalize(artist)
                tracksByArtist.getOrPut(key) { ArrayList() }.add(track)
                val counts = spellings.getOrPut(key) { HashMap() }
                counts[artist] = (counts[artist] ?: 0) + 1
            }
            entries.add(
                SearchEntry(
                    track = track,
                    flatIndex = flat,
                    title = normalize(track.displayTitle),
                    haystack = normalize("${track.displayTitle} ${track.displayArtist} ${track.albumName}")
                )
            )
            flat++
        }
    }
    val artists = tracksByArtist.map { (key, tracks) ->
        val name = spellings[key]?.entries?.maxByOrNull { it.value }?.key ?: key
        ArtistEntry(key, name, tracks)
    }
    return SearchIndex(entries, albums.map { normalize(it.name) }, artists)
}

/**
 * Every typed word has to appear somewhere in an artist's name, a folder
 * name, or a song's title/artist/folder.
 */
private fun computeResults(index: SearchIndex, terms: List<String>): SearchOutcome {
    val artistHits = index.artists
        .filter { a -> terms.all { a.key.contains(it) } }
        // Artists whose name starts with what you typed come first (stable).
        .sortedBy { if (it.key.startsWith(terms[0])) 0 else 1 }
        .take(MAX_ARTIST_RESULTS)
    val albumHits = index.albumKeys.indices.filter { i ->
        terms.all { index.albumKeys[i].contains(it) }
    }
    val trackHits = index.entries.filter { e -> terms.all { e.haystack.contains(it) } }
    // Songs whose title starts with what you typed come first (sort is stable).
    val ranked = trackHits.sortedBy { if (it.title.startsWith(terms[0])) 0 else 1 }
    val shown = ranked.take(MAX_TRACK_RESULTS)

    val rows = ArrayList<ResultRow>()
    if (artistHits.isNotEmpty()) {
        rows.add(ResultRow.Header("Artists"))
        artistHits.forEach { rows.add(ResultRow.ArtistRow(it)) }
    }
    if (albumHits.isNotEmpty()) {
        rows.add(ResultRow.Header("Folders"))
        albumHits.forEach { rows.add(ResultRow.AlbumRow(it)) }
    }
    if (shown.isNotEmpty()) {
        rows.add(ResultRow.Header("Songs"))
        shown.forEach { rows.add(ResultRow.TrackRow(it)) }
        if (ranked.size > shown.size) {
            rows.add(ResultRow.Header("Showing ${shown.size} of ${ranked.size} songs — keep typing to narrow it down"))
        }
    }
    return SearchOutcome(rows, ranked)
}

/**
 * The Library tab: folders (albums), and the songs inside the one you
 * open. The library itself comes from MusicLibraryHolder — the same copy
 * Android Auto browses — so this tab and Auto can never disagree, and a
 * fresh scan started from Settings shows up here by itself.
 *
 * The search icon opens a search box that looks across every folder name
 * and every song at once. Opening a folder from the results and pressing
 * Back returns to the same results.
 *
 * Inside a folder, the track that's currently playing is highlighted.
 */
class LibraryFragment : Fragment(R.layout.fragment_library) {

    companion object {
        private const val KEY_OPEN_ALBUM = "open_album"
        private const val KEY_SEARCH_OPEN = "search_open"
        private const val KEY_QUERY = "search_query"
    }

    private var albums: List<Album> = emptyList()
    private var openAlbumIndex: Int? = null

    // Album to reopen once the library is available again, after a
    // rotation or the app being recreated.
    private var pendingOpenAlbum = -1

    // The "no library yet" message waits until we've checked the on-disk
    // cache, so it doesn't flash up for a moment on every launch.
    private var cacheChecked = false

    // Captures the parent list's (folders or search results) exact scroll
    // offset before navigating into a folder, so coming back restores it
    // instead of resetting to the top.
    private var parentListScrollState: Parcelable? = null

    // Which track is playing right now (for the highlight).
    private var controller: MediaController? = null
    private var playingMediaId: String? = null
    private var trackAdapter: TrackAdapter? = null
    private var resultsAdapter: ResultsAdapter? = null

    // Search state.
    private var searchOpen = false
    private var query = ""
    private var suppressWatcher = false
    private var searchJob: Job? = null
    private var indexDeferred: Deferred<SearchIndex>? = null
    private var lastResults: List<ResultRow> = emptyList()
    private var lastResultsQuery = ""

    // Every song matching the current search (not just the ones shown), for
    // "Shuffle all".
    private var lastAllHits: List<SearchEntry> = emptyList()

    private lateinit var backButton: ImageButton
    private lateinit var searchButton: ImageButton
    private lateinit var searchRow: View
    private lateinit var searchInput: EditText
    private lateinit var searchClear: ImageButton
    private lateinit var shuffleResultsButton: Button
    private lateinit var titleText: TextView
    private lateinit var listView: ListView
    private lateinit var emptyState: View
    private lateinit var noResultsText: TextView
    private lateinit var loadingBanner: View
    private lateinit var loadingText: TextView
    private lateinit var loadingBar: LinearProgressIndicator

    private val libraryChangedListener: () -> Unit = {
        // A fresh scan finished, or the library was cleared on sign-out.
        if (view != null) {
            cacheChecked = true
            setAlbums(MusicLibraryHolder.albums)
            parentListScrollState = null
            pendingOpenAlbum = -1
            showTopLevel()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val id = player.currentMediaItem?.mediaId
            if (id != playingMediaId) {
                playingMediaId = id
                trackAdapter?.notifyDataSetChanged()
                resultsAdapter?.notifyDataSetChanged()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        backButton = view.findViewById(R.id.backButton)
        searchButton = view.findViewById(R.id.searchButton)
        searchRow = view.findViewById(R.id.searchRow)
        searchInput = view.findViewById(R.id.searchInput)
        searchClear = view.findViewById(R.id.searchClear)
        shuffleResultsButton = view.findViewById(R.id.shuffleResultsButton)
        titleText = view.findViewById(R.id.listTitleText)
        listView = view.findViewById(R.id.trackListView)
        emptyState = view.findViewById(R.id.emptyState)
        noResultsText = view.findViewById(R.id.noResultsText)
        loadingBanner = view.findViewById(R.id.loadingBanner)
        loadingText = view.findViewById(R.id.loadingText)
        loadingBar = view.findViewById(R.id.loadingBar)

        backButton.setOnClickListener { showTopLevel() }
        searchButton.setOnClickListener { toggleSearch() }
        searchClear.setOnClickListener { searchInput.setText("") }
        shuffleResultsButton.setOnClickListener {
            hideKeyboard()
            playShuffled(lastAllHits.map { it.track })
        }
        view.findViewById<Button>(R.id.emptySettingsButton).setOnClickListener {
            (activity as? MainActivity)?.showSettings()
        }

        // Search box: restore state first, then start listening for typing.
        searchOpen = savedInstanceState?.getBoolean(KEY_SEARCH_OPEN, false) ?: false
        query = savedInstanceState?.getString(KEY_QUERY).orEmpty()
        searchInput.setText(query)
        searchClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressWatcher) onQueryChanged(s?.toString().orEmpty())
            }
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        // Scrolling the results means you're done typing: put the keyboard away.
        listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
            }

            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {}
        })

        pendingOpenAlbum = savedInstanceState?.getInt(KEY_OPEN_ALBUM, -1) ?: -1

        // Whatever's already in memory shows immediately...
        setAlbums(MusicLibraryHolder.albums)
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
                setAlbums(loaded)
                renderCurrent()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LibraryLoadState.status.collect { renderLoading(it) }
            }
        }

        // Follow what's playing, to highlight it.
        PlaybackClient.connect(appContext) { c ->
            if (this.view == null) return@connect
            controller = c
            c.addListener(playerListener)
            playingMediaId = c.currentMediaItem?.mediaId
            trackAdapter?.notifyDataSetChanged()
            resultsAdapter?.notifyDataSetChanged()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_OPEN_ALBUM, openAlbumIndex ?: -1)
        outState.putBoolean(KEY_SEARCH_OPEN, searchOpen)
        outState.putString(KEY_QUERY, query)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // Switching to another tab with the keyboard up: put it away.
        if (hidden && view != null) hideKeyboard()
    }

    override fun onDestroyView() {
        MusicLibraryHolder.removeListener(libraryChangedListener)
        controller?.removeListener(playerListener)
        controller = null
        searchJob?.cancel()
        indexDeferred?.cancel()
        indexDeferred = null
        trackAdapter = null
        resultsAdapter = null
        super.onDestroyView()
    }

    /**
     * Back button. Closes an open folder first (returning to the folder
     * list or search results it came from), then closes the search box.
     * Returns false if there was nothing to close.
     */
    fun handleBack(): Boolean {
        if (view == null) return false
        if (openAlbumIndex != null) {
            showTopLevel()
            return true
        }
        if (searchOpen) {
            closeSearch()
            return true
        }
        return false
    }

    // --- Library data -------------------------------------------------

    /** Swap in a new library and throw away anything computed from the old one. */
    private fun setAlbums(newAlbums: List<Album>) {
        albums = newAlbums
        indexDeferred?.cancel()
        indexDeferred = null
        lastResults = emptyList()
        lastResultsQuery = ""
        lastAllHits = emptyList()
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

    /** Re-open the folder we were in before a rotation, else show the top level. */
    private fun renderCurrent() {
        val index = pendingOpenAlbum
        pendingOpenAlbum = -1
        if (index in albums.indices) {
            showTracksForAlbum(index, saveScroll = false)
        } else {
            showTopLevel()
        }
    }

    // --- Search ----------------------------------------------------------

    private fun toggleSearch() {
        if (searchOpen) closeSearch() else openSearch()
    }

    private fun openSearch() {
        searchOpen = true
        applyHeaderState()
        searchInput.requestFocus()
        showKeyboard()
    }

    private fun closeSearch() {
        searchOpen = false
        searchJob?.cancel()
        query = ""
        suppressWatcher = true
        searchInput.setText("")
        suppressWatcher = false
        searchClear.visibility = View.GONE
        hideKeyboard()
        showAlbumList()
    }

    private fun onQueryChanged(text: String) {
        // Android re-sets the box's text when restoring state after a
        // rotation; that isn't a real edit, and acting on it could close
        // a folder the user had open.
        if (text == query) return
        query = text
        searchClear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        if (text.isBlank()) {
            searchJob?.cancel()
            lastResultsQuery = ""
            showAlbumList()
        } else {
            runSearch()
        }
    }

    /** The searchable text for the whole library, built once (in the background) on first use. */
    private fun ensureIndex(): Deferred<SearchIndex> {
        indexDeferred?.let { return it }
        val snapshot = albums
        val deferred = viewLifecycleOwner.lifecycleScope.async(Dispatchers.Default) {
            buildIndex(snapshot)
        }
        indexDeferred = deferred
        return deferred
    }

    private fun runSearch() {
        searchJob?.cancel()
        val typed = query
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(120) // wait for a pause in typing
            val terms = normalize(typed).split(' ').filter { it.isNotEmpty() }
            if (terms.isEmpty()) return@launch

            val index = ensureIndex().await()
            val outcome = withContext(Dispatchers.Default) { computeResults(index, terms) }

            // Typing may have moved on, or search been closed, while we worked.
            if (!searchOpen || typed != query) return@launch
            lastResults = outcome.rows
            lastAllHits = outcome.allHits
            lastResultsQuery = typed
            if (openAlbumIndex == null) renderResults(outcome.rows)
        }
    }

    // --- What's on screen ----------------------------------------------

    /** The header controls depend on whether a folder is open and whether search is open. */
    private fun applyHeaderState() {
        val inFolder = openAlbumIndex != null
        backButton.visibility = if (inFolder) View.VISIBLE else View.GONE
        searchButton.visibility = if (inFolder) View.GONE else View.VISIBLE
        searchRow.visibility = if (searchOpen && !inFolder) View.VISIBLE else View.GONE
    }

    /** Folder list, or the search results if a search is active. */
    private fun showTopLevel() {
        if (searchOpen && query.isNotBlank()) {
            if (lastResultsQuery == query) {
                renderResults(lastResults)
            } else {
                showAlbumList()
                runSearch()
            }
        } else {
            showAlbumList()
        }
    }

    private fun showAlbumList() {
        openAlbumIndex = null
        trackAdapter = null
        resultsAdapter = null
        titleText.text = "Library"
        noResultsText.visibility = View.GONE
        shuffleResultsButton.visibility = View.GONE
        emptyState.visibility =
            if (albums.isEmpty() && cacheChecked) View.VISIBLE else View.GONE
        applyHeaderState()

        listView.adapter = AlbumAdapter(albums)
        listView.setOnItemClickListener { _, _, position, _ ->
            hideKeyboard()
            showTracksForAlbum(position)
        }
        restoreParentScroll()
    }

    private fun renderResults(rows: List<ResultRow>) {
        openAlbumIndex = null
        trackAdapter = null
        titleText.text = "Library"
        emptyState.visibility = View.GONE
        noResultsText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        noResultsText.text = "No matches for “${query.trim()}”"
        applyHeaderState()

        // One button to shuffle everything that matched, whatever kind of
        // result it was (an artist, a folder name, a word in a title...).
        val hitCount = lastAllHits.size
        shuffleResultsButton.visibility = if (hitCount > 0) View.VISIBLE else View.GONE
        shuffleResultsButton.text =
            if (hitCount == 1) "Play this song" else "Shuffle all $hitCount songs"

        val adapter = ResultsAdapter(rows)
        resultsAdapter = adapter
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            when (val row = rows.getOrNull(position)) {
                is ResultRow.ArtistRow -> {
                    hideKeyboard()
                    playShuffled(row.artist.tracks)
                }
                is ResultRow.AlbumRow -> {
                    hideKeyboard()
                    showTracksForAlbum(row.albumIndex)
                }
                is ResultRow.TrackRow -> {
                    hideKeyboard()
                    playTrackAt(row.entry.flatIndex)
                }
                else -> Unit // section headers aren't tappable
            }
        }
        restoreParentScroll()
    }

    private fun showTracksForAlbum(albumIndex: Int, saveScroll: Boolean = true) {
        // Remember where the list we're leaving (folders or search results)
        // was scrolled to, so Back lands in the same place.
        if (saveScroll) parentListScrollState = listView.onSaveInstanceState()

        val album = albums.getOrNull(albumIndex) ?: return
        openAlbumIndex = albumIndex
        resultsAdapter = null
        titleText.text = album.name
        emptyState.visibility = View.GONE
        noResultsText.visibility = View.GONE
        shuffleResultsButton.visibility = View.GONE
        applyHeaderState()

        val adapter = TrackAdapter(album.tracks)
        trackAdapter = adapter
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val flatIndex = albums.take(albumIndex).sumOf { it.tracks.size } + position
            playTrackAt(flatIndex)
        }
    }

    /** Applied once, after the adapter's own layout pass. */
    private fun restoreParentScroll() {
        val state = parentListScrollState ?: return
        parentListScrollState = null
        listView.post { listView.onRestoreInstanceState(state) }
    }

    // --- Keyboard --------------------------------------------------------

    private fun showKeyboard() {
        searchInput.post {
            context?.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        context?.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    // --- Rows ----------------------------------------------------------

    private fun bindAlbumRow(row: View, album: Album) {
        val tile = row.findViewById<TextView>(R.id.albumTile)
        tile.text = initialFor(album.name)
        tile.background = tileBackground(album.name)
        row.findViewById<TextView>(R.id.albumName).text = album.name
    }

    private inner class AlbumAdapter(private val items: List<Album>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_album, parent, false)
            bindAlbumRow(row, items[position])
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

    /** Search results: section headers, folders and songs, three kinds of row. */
    private inner class ResultsAdapter(private val rows: List<ResultRow>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 4
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is ResultRow.Header -> 0
            is ResultRow.ArtistRow -> 1
            is ResultRow.AlbumRow -> 2
            is ResultRow.TrackRow -> 3
        }

        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = rows[position] !is ResultRow.Header

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val row = rows[position]) {
                is ResultRow.Header -> {
                    val view = convertView
                        ?: layoutInflater.inflate(R.layout.item_section_header, parent, false)
                    (view as TextView).text = row.text
                    view
                }
                is ResultRow.ArtistRow -> {
                    val view = convertView
                        ?: layoutInflater.inflate(R.layout.item_artist, parent, false)
                    val tile = view.findViewById<TextView>(R.id.artistTile)
                    tile.text = initialFor(row.artist.name)
                    tile.background = tileBackground(row.artist.name, circle = true)
                    view.findViewById<TextView>(R.id.artistName).text = row.artist.name
                    view
                }
                is ResultRow.AlbumRow -> {
                    val view = convertView
                        ?: layoutInflater.inflate(R.layout.item_album, parent, false)
                    bindAlbumRow(view, albums[row.albumIndex])
                    view
                }
                is ResultRow.TrackRow -> {
                    val view = convertView
                        ?: layoutInflater.inflate(R.layout.item_track_hit, parent, false)
                    val track = row.entry.track
                    val isPlaying = track.fileId == playingMediaId
                    val ctx = parent.context

                    val title = view.findViewById<TextView>(R.id.hitTitle)
                    title.text = track.displayTitle
                    title.setTextColor(
                        ContextCompat.getColor(ctx, if (isPlaying) R.color.accent else R.color.text_primary)
                    )
                    view.findViewById<ImageView>(R.id.hitIcon).setColorFilter(
                        ContextCompat.getColor(ctx, if (isPlaying) R.color.accent else R.color.text_secondary)
                    )
                    view.findViewById<TextView>(R.id.hitSubtitle).text =
                        if (track.displayArtist.isBlank()) track.albumName
                        else "${track.displayArtist} · ${track.albumName}"
                    view
                }
            }
        }
    }

    /** First letter or digit of the name, for the folder's tile. */
    private fun initialFor(name: String): String =
        name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "♪"

    /** A rounded tile whose colour is derived from the name, so each folder is recognisable. */
    private fun tileBackground(name: String, circle: Boolean = false): GradientDrawable {
        val hue = (name.hashCode() and 0x7fffffff) % 360
        val color = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.52f))
        // Artists get round tiles, folders rounded squares; a radius bigger
        // than half the tile's size is what makes it a circle.
        val radius = (if (circle) 40f else 12f) * resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
    }

    // --- Playback ------------------------------------------------------

    /** The item the app queues for a track. (Cover art is attached once playback starts.) */
    private fun mediaItemFor(track: Track): MediaItem =
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

    /** Plays the whole library in folder order, starting at [flatIndex]. */
    private fun playTrackAt(flatIndex: Int) {
        val allTracks = albums.flatMap { it.tracks }
        if (allTracks.isEmpty()) return

        val mediaItems = allTracks.map { mediaItemFor(it) }

        PlaybackClient.connect(requireContext().applicationContext) { c ->
            c.setMediaItems(mediaItems, flatIndex, 0L)
            c.prepare()
            c.play()
            (activity as? MainActivity)?.showNowPlaying()
        }
    }

    /**
     * Shuffles just [tracks] — the queue is only these songs, not the whole
     * library — starting from a random one. Shuffle mode is switched on, so
     * the rest of the set also comes in random order.
     */
    private fun playShuffled(tracks: List<Track>) {
        if (tracks.isEmpty()) return

        val mediaItems = tracks.map { mediaItemFor(it) }
        val start = Random.nextInt(mediaItems.size)

        PlaybackClient.connect(requireContext().applicationContext) { c ->
            // Shuffle first, so the queue is shuffled from the very start.
            c.shuffleModeEnabled = true
            c.setMediaItems(mediaItems, start, 0L)
            c.prepare()
            c.play()
            (activity as? MainActivity)?.showNowPlaying()
        }
    }
}

package com.example.drivestreamer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.drivestreamer.R
import com.example.drivestreamer.auth.AuthManager
import com.example.drivestreamer.auth.TokenProvider
import com.example.drivestreamer.drive.Album
import com.example.drivestreamer.playback.LibraryLoadService
import com.example.drivestreamer.playback.LibraryLoadState
import com.example.drivestreamer.playback.PlaybackClient
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.launch

/**
 * The folder/album browsing screen. Sign-in itself now happens in
 * LoginActivity, which only hands off here once it's confirmed — this
 * screen assumes a signed-in account exists, with a defensive redirect
 * back to LoginActivity in the (unlikely) case it doesn't.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var authManager: AuthManager
    private lateinit var tokenProvider: TokenProvider
    private var account: GoogleSignInAccount? = null

    private var albums: List<Album> = emptyList()
    private var openAlbumIndex: Int? = null

    // Captures the album list's exact scroll offset before navigating
    // into a folder, so coming back restores it instead of resetting to
    // the top — the annoying part of a long folder list otherwise.
    private var albumListScrollState: Parcelable? = null

    private lateinit var loadLibraryButton: Button
    private lateinit var loadingRow: LinearLayout
    private lateinit var loadingStatusText: TextView
    private lateinit var cancelLoadButton: Button
    private lateinit var backButton: Button
    private lateinit var listTitleText: TextView
    private lateinit var listView: ListView
    private lateinit var accountText: TextView

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Loading still works without it — just no visible progress notification. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        authManager = AuthManager(this)
        tokenProvider = TokenProvider(authManager)
        account = authManager.lastSignedInAccount()

        if (account == null) {
            // Shouldn't normally happen (LoginActivity guarantees this),
            // but handle gracefully rather than crashing or silently
            // failing every action on this screen.
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        tokenProvider.setAccount(account!!)

        loadLibraryButton = findViewById(R.id.loadLibraryButton)
        loadingRow = findViewById(R.id.loadingRow)
        loadingStatusText = findViewById(R.id.loadingStatusText)
        cancelLoadButton = findViewById(R.id.cancelLoadButton)
        backButton = findViewById(R.id.backButton)
        listTitleText = findViewById(R.id.listTitleText)
        listView = findViewById(R.id.trackListView)
        accountText = findViewById(R.id.accountText)

        accountText.text = account?.email ?: ""
        findViewById<Button>(R.id.signOutButton).setOnClickListener { signOut() }

        loadLibraryButton.setOnClickListener { startLibraryLoad() }
        backButton.setOnClickListener { showAlbumList() }
        cancelLoadButton.setOnClickListener {
            val cancelIntent = Intent(this, LibraryLoadService::class.java)
                .setAction(LibraryLoadService.ACTION_CANCEL)
            startService(cancelIntent)
        }

        onBackPressedDispatcher.addCallback(this) {
            if (openAlbumIndex != null) {
                showAlbumList()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        PlaybackClient.connect(this) { /* ready */ }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LibraryLoadState.status.collect { status -> render(status) }
            }
        }
    }

    private fun signOut() {
        authManager.signOut()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun render(status: LibraryLoadState.Status) {
        when (status) {
            is LibraryLoadState.Status.Idle -> setLoadingUi(false)
            is LibraryLoadState.Status.Loading -> {
                setLoadingUi(true)
                loadingStatusText.text = if (status.total > 0) {
                    "Reading tags… ${status.done} / ${status.total}"
                } else {
                    "Finding your music…"
                }
            }
            is LibraryLoadState.Status.Complete -> {
                setLoadingUi(false)
                albums = status.albums
                if (albums.isEmpty()) {
                    Toast.makeText(
                        this, "Loaded, but found no audio files in that folder", Toast.LENGTH_LONG
                    ).show()
                }
                albumListScrollState = null
                showAlbumList()
            }
            is LibraryLoadState.Status.Error -> {
                setLoadingUi(false)
                Toast.makeText(this, "Failed to load library: ${status.message}", Toast.LENGTH_LONG).show()
            }
            is LibraryLoadState.Status.Cancelled -> {
                setLoadingUi(false)
                Toast.makeText(this, "Loading cancelled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setLoadingUi(loading: Boolean) {
        loadLibraryButton.isEnabled = !loading
        loadingRow.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun startLibraryLoad() {
        val folderId = findViewById<EditText>(R.id.folderIdInput).text.toString().trim()
        if (folderId.isEmpty()) {
            Toast.makeText(this, "Paste a folder ID first", Toast.LENGTH_SHORT).show()
            return
        }

        maybeRequestNotificationPermission()

        val intent = Intent(this, LibraryLoadService::class.java)
            .putExtra(LibraryLoadService.EXTRA_FOLDER_ID, folderId)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // --- Folder/album navigation -------------------------------------

    private fun showAlbumList() {
        openAlbumIndex = null
        backButton.visibility = View.GONE
        listTitleText.text = "Albums"

        val labels = albums.map { "${it.name} (${it.tracks.size})" }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        listView.setOnItemClickListener { _, _, position, _ -> showTracksForAlbum(position) }

        // Restore exact scroll offset captured when we last left this
        // list — posted so it applies after the adapter's own layout pass.
        albumListScrollState?.let { state ->
            listView.post { listView.onRestoreInstanceState(state) }
        }
    }

    private fun showTracksForAlbum(albumIndex: Int) {
        // Capture the album list's current scroll position before we
        // replace its content with this album's tracks.
        albumListScrollState = listView.onSaveInstanceState()

        val album = albums.getOrNull(albumIndex) ?: return
        openAlbumIndex = albumIndex
        backButton.visibility = View.VISIBLE
        listTitleText.text = album.name

        val labels = album.tracks.map { it.displayLabel }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
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

        PlaybackClient.connect(this) { controller ->
            controller.setMediaItems(mediaItems, flatIndex, 0L)
            controller.prepare()
            controller.play()
            startActivity(Intent(this, NowPlayingActivity::class.java))
        }
    }
}
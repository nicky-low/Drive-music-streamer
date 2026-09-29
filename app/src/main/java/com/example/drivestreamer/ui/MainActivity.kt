package com.example.drivestreamer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var authManager: AuthManager
    private lateinit var tokenProvider: TokenProvider
    private var account: GoogleSignInAccount? = null

    // Full library, and which album (if any) is currently open —
    // null means "show the album list", non-null means "show that
    // album's tracks".
    private var albums: List<Album> = emptyList()
    private var openAlbumIndex: Int? = null

    private lateinit var loadLibraryButton: Button
    private lateinit var loadingRow: LinearLayout
    private lateinit var loadingStatusText: TextView
    private lateinit var cancelLoadButton: Button
    private lateinit var backButton: Button
    private lateinit var listTitleText: TextView
    private lateinit var listView: ListView

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Loading still works without it — just no visible progress notification. */ }

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        authManager.handleSignInResult(result.data).fold(
            onSuccess = { acct ->
                account = acct
                tokenProvider.setAccount(acct)
                Toast.makeText(this, "Signed in as ${acct.email}", Toast.LENGTH_SHORT).show()
            },
            onFailure = { e ->
                val message = if (e is ApiException) {
                    "Sign-in failed: code ${e.statusCode} " +
                        "(${CommonStatusCodes.getStatusCodeString(e.statusCode)})"
                } else {
                    "Sign-in failed: ${e.message}"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        authManager = AuthManager(this)
        tokenProvider = TokenProvider(authManager)
        account = authManager.lastSignedInAccount()
        account?.let { tokenProvider.setAccount(it) }

        loadLibraryButton = findViewById(R.id.loadLibraryButton)
        loadingRow = findViewById(R.id.loadingRow)
        loadingStatusText = findViewById(R.id.loadingStatusText)
        cancelLoadButton = findViewById(R.id.cancelLoadButton)
        backButton = findViewById(R.id.backButton)
        listTitleText = findViewById(R.id.listTitleText)
        listView = findViewById(R.id.trackListView)

        findViewById<Button>(R.id.signInButton)
            .setOnClickListener { signInLauncher.launch(authManager.signInIntent()) }

        loadLibraryButton.setOnClickListener { startLibraryLoad() }
        backButton.setOnClickListener { showAlbumList() }
        cancelLoadButton.setOnClickListener {
            // Sends the cancel action to the already-running service —
            // its own coroutine catches the resulting cancellation and
            // handles cleanup, so this is just "ask it to stop".
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

        // Reflect whatever LibraryLoadService is doing (or already did),
        // even if it started before this activity existed this time
        // around — repeatOnLifecycle means we stop collecting while
        // backgrounded and pick the latest value straight back up on
        // return, rather than missing updates or leaking a collector.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LibraryLoadState.status.collect { status -> render(status) }
            }
        }
    }

    private fun render(status: LibraryLoadState.Status) {
        when (status) {
            is LibraryLoadState.Status.Idle -> {
                setLoadingUi(false)
            }
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
        if (account == null) {
            Toast.makeText(this, "Sign in first", Toast.LENGTH_SHORT).show()
            return
        }
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
    }

    private fun showTracksForAlbum(albumIndex: Int) {
        val album = albums.getOrNull(albumIndex) ?: return
        openAlbumIndex = albumIndex
        backButton.visibility = View.VISIBLE
        listTitleText.text = album.name

        val labels = album.tracks.map { it.displayLabel }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        listView.setOnItemClickListener { _, _, position, _ ->
            // Full-library flat index, so the queued playlist still
            // spans every album (needed for auto-advance/shuffle across
            // album boundaries), not just the one currently open.
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
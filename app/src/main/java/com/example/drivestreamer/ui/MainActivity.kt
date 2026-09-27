package com.example.drivestreamer.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.drivestreamer.R
import com.example.drivestreamer.auth.AuthManager
import com.example.drivestreamer.auth.TokenProvider
import com.example.drivestreamer.drive.Album
import com.example.drivestreamer.drive.DriveLibraryRepository
import com.example.drivestreamer.playback.MusicLibraryHolder
import com.example.drivestreamer.playback.PlaybackClient
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var authManager: AuthManager
    private lateinit var tokenProvider: TokenProvider
    private val repository = DriveLibraryRepository()
    private var account: GoogleSignInAccount? = null
    private var loadedAlbums: List<Album> = emptyList()

    private lateinit var loadLibraryButton: Button
    private lateinit var loadingRow: LinearLayout
    private lateinit var loadingStatusText: TextView

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

        findViewById<Button>(R.id.signInButton)
            .setOnClickListener { signInLauncher.launch(authManager.signInIntent()) }

        loadLibraryButton.setOnClickListener { loadLibrary() }

        PlaybackClient.connect(this) { /* ready */ }
    }

    private fun loadLibrary() {
        val currentAccount = account ?: run {
            Toast.makeText(this, "Sign in first", Toast.LENGTH_SHORT).show()
            return
        }
        val folderId = findViewById<EditText>(R.id.folderIdInput).text.toString().trim()
        if (folderId.isEmpty()) {
            Toast.makeText(this, "Paste a folder ID first", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true, "Finding your music…")

        lifecycleScope.launch {
            try {
                val token = tokenProvider.getToken()
                val albums = repository.loadLibrary(token, folderId) { done, total ->
                    // Called on the Main dispatcher (see repository), safe
                    // to touch views directly.
                    loadingStatusText.text = "Reading tags… $done / $total"
                }
                loadedAlbums = albums
                MusicLibraryHolder.albums = albums

                if (albums.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Loaded, but found no audio files in that folder",
                        Toast.LENGTH_LONG
                    ).show()
                }

                val trackLabels = albums.flatMap { it.tracks }.map { it.displayLabel }
                val listView = findViewById<ListView>(R.id.trackListView)
                listView.adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_list_item_1,
                    trackLabels
                )
                listView.setOnItemClickListener { _, _, position, _ ->
                    playTrackAt(position)
                }
            } catch (e: retrofit2.HttpException) {
                val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                Toast.makeText(
                    this@MainActivity,
                    "Drive error ${e.code()}: ${body ?: e.message()}",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Failed to load library: ${e::class.simpleName} — ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean, initialMessage: String = "") {
        loadLibraryButton.isEnabled = !loading
        loadingRow.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) loadingStatusText.text = initialMessage
    }

    private fun playTrackAt(flatIndex: Int) {
        val allTracks = loadedAlbums.flatMap { it.tracks }
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
package com.example.drivestreamer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
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
                // Long + repeatable on screen rather than buried in logcat,
                // since logcat isn't easily reachable from an Acode-only setup.
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

        findViewById<Button>(R.id.signInButton)
            .setOnClickListener { signInLauncher.launch(authManager.signInIntent()) }

        findViewById<Button>(R.id.loadLibraryButton)
            .setOnClickListener { loadLibrary() }

        // Connect early so playback starts instantly on the first tap
        // rather than waiting for the binder connection at that point.
        PlaybackClient.connect(this) { /* ready */ }
    }

    private fun loadLibrary() {
        val currentAccount = account ?: return
        val folderId = findViewById<EditText>(R.id.folderIdInput).text.toString().trim()
        if (folderId.isEmpty()) return

        lifecycleScope.launch {
            val token = tokenProvider.getToken()
            val albums = repository.loadLibrary(token, folderId)
            loadedAlbums = albums
            MusicLibraryHolder.albums = albums

            val trackTitles = albums.flatMap { it.tracks }.map { "${it.albumName} — ${it.title}" }
            val listView = findViewById<ListView>(R.id.trackListView)
            listView.adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_list_item_1,
                trackTitles
            )
            listView.setOnItemClickListener { _, _, position, _ ->
                playTrackAt(position)
            }
        }
    }

    private fun playTrackAt(flatIndex: Int) {
        val allTracks = loadedAlbums.flatMap { it.tracks }
        val track = allTracks.getOrNull(flatIndex) ?: return
        val mediaItem = MediaItem.Builder()
            .setMediaId(track.fileId)
            .setUri("drive://file/${track.fileId}")
            .build()

        PlaybackClient.connect(this) { controller ->
            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()
            startActivity(Intent(this, NowPlayingActivity::class.java))
        }
    }
}

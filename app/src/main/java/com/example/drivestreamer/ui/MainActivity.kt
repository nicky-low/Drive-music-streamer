package com.example.drivestreamer.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import com.example.drivestreamer.R
import com.example.drivestreamer.auth.AuthManager
import com.example.drivestreamer.playback.PlaybackClient
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * The app's main screen: three tabs along the bottom (Library, Now
 * Playing, Settings), each a fragment. All three stay alive and are
 * shown/hidden, so switching tabs keeps scroll positions and open
 * folders exactly as you left them.
 *
 * Also owns the pieces shared by every tab: the mini-player strip and
 * the back-button behaviour. LoginActivity remains the launcher and
 * hands off here once signed in.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Open on a specific tab, e.g. from a notification tap. */
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_LIBRARY = "library"
        const val TAB_NOW_PLAYING = "now_playing"
        const val TAB_SETTINGS = "settings"

        private const val TAG_LIBRARY = "tab_library"
        private const val TAG_NOW_PLAYING = "tab_now_playing"
        private const val TAG_SETTINGS = "tab_settings"
        private const val STATE_TAB = "current_tab"
    }

    private val authManager by lazy { AuthManager(this) }

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var miniPlayer: View
    private lateinit var miniArt: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlayPause: ImageButton

    // The artwork bytes behind the mini-player thumbnail, so repeated
    // player events for the same track don't re-decode the image.
    private var lastMiniArtBytes: ByteArray? = null

    private var currentTabId = R.id.nav_library

    private val miniPlayerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateMiniPlayer(player)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (authManager.lastSignedInAccount() == null) {
            goToLogin()
            return
        }
        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottomNav)
        miniPlayer = findViewById(R.id.miniPlayer)
        miniArt = findViewById(R.id.miniArt)
        miniTitle = findViewById(R.id.miniTitle)
        miniArtist = findViewById(R.id.miniArtist)
        miniPlayPause = findViewById(R.id.miniPlayPause)

        currentTabId = savedInstanceState?.getInt(STATE_TAB, R.id.nav_library) ?: R.id.nav_library

        // First launch: add all three tabs once. After a rotation or
        // process restore, the FragmentManager has already brought them
        // back, so only fresh launches add them.
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, LibraryFragment(), TAG_LIBRARY)
                .add(R.id.fragmentContainer, NowPlayingFragment(), TAG_NOW_PLAYING)
                .add(R.id.fragmentContainer, SettingsFragment(), TAG_SETTINGS)
                .commitNow()
        }

        // Select the tab *before* attaching the listener, so this doesn't
        // fire it; showTab() below applies the initial state explicitly.
        bottomNav.selectedItemId = currentTabId
        bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        showTab(currentTabId)

        miniPlayer.setOnClickListener { selectTab(R.id.nav_now_playing) }
        miniPlayPause.setOnClickListener {
            PlaybackClient.current()?.let { c -> if (c.isPlaying) c.pause() else c.play() }
        }

        // Back: leave another tab for Library first; on Library, close an
        // open folder; only then leave the app. One callback decides all
        // of it, so the order is the same however the activity was started.
        onBackPressedDispatcher.addCallback(this) {
            val library = supportFragmentManager.findFragmentByTag(TAG_LIBRARY) as? LibraryFragment
            when {
                currentTabId != R.id.nav_library -> selectTab(R.id.nav_library)
                library?.handleBack() == true -> Unit
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        // Also reflects playback that was already going when the app was
        // opened (e.g. reopening after swiping it from recents).
        PlaybackClient.connect(this) { controller ->
            controller.addListener(miniPlayerListener)
            updateMiniPlayer(controller)
        }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Signed out while we were in the background (or the sign-out
        // finished after we'd already left the screen): go to sign-in.
        if (!isFinishing && authManager.lastSignedInAccount() == null) goToLogin()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTabId)
    }

    override fun onDestroy() {
        PlaybackClient.current()?.removeListener(miniPlayerListener)
        super.onDestroy()
    }

    // --- Tabs ----------------------------------------------------------

    /** Called by fragments, e.g. after tapping a song. */
    fun showNowPlaying() = selectTab(R.id.nav_now_playing)

    fun showSettings() = selectTab(R.id.nav_settings)

    private fun selectTab(itemId: Int) {
        if (bottomNav.selectedItemId != itemId) bottomNav.selectedItemId = itemId
    }

    private fun tagFor(itemId: Int): String = when (itemId) {
        R.id.nav_now_playing -> TAG_NOW_PLAYING
        R.id.nav_settings -> TAG_SETTINGS
        else -> TAG_LIBRARY
    }

    private fun showTab(itemId: Int) {
        currentTabId = itemId
        val wanted = tagFor(itemId)
        val tx = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        for (tag in listOf(TAG_LIBRARY, TAG_NOW_PLAYING, TAG_SETTINGS)) {
            val fragment = supportFragmentManager.findFragmentByTag(tag) ?: continue
            if (tag == wanted) tx.show(fragment) else tx.hide(fragment)
        }
        tx.commit()
        updateMiniPlayer(PlaybackClient.current())
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val tab = intent.getStringExtra(EXTRA_OPEN_TAB) ?: return
        // Consume it, so a rotation doesn't re-trigger the jump.
        intent.removeExtra(EXTRA_OPEN_TAB)
        when (tab) {
            TAB_NOW_PLAYING -> selectTab(R.id.nav_now_playing)
            TAB_SETTINGS -> selectTab(R.id.nav_settings)
            TAB_LIBRARY -> selectTab(R.id.nav_library)
        }
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    // --- Mini-player -----------------------------------------------------

    /** Shown when something is queued, except on the Now Playing tab itself. */
    private fun updateMiniPlayer(player: Player?) {
        if (player == null ||
            player.currentMediaItem == null ||
            currentTabId == R.id.nav_now_playing
        ) {
            miniPlayer.visibility = View.GONE
            return
        }
        miniPlayer.visibility = View.VISIBLE
        val metadata = player.mediaMetadata
        miniTitle.text = metadata.title ?: "Unknown title"
        val artist = metadata.artist?.toString().orEmpty()
        miniArtist.text = artist
        miniArtist.visibility = if (artist.isBlank()) View.GONE else View.VISIBLE
        val showPause = player.isPlaying ||
            (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        miniPlayPause.setImageResource(if (showPause) R.drawable.ic_np_pause else R.drawable.ic_np_play)
        updateMiniArt(metadata.artworkData)
    }

    /** Sets the mini-player thumbnail, skipping the decode if it's the picture already shown. */
    private fun updateMiniArt(bytes: ByteArray?) {
        val previous = lastMiniArtBytes
        if (bytes === previous ||
            (bytes != null && previous != null && bytes.contentEquals(previous))
        ) return
        lastMiniArtBytes = bytes

        val bitmap: Bitmap? = bytes?.let { ArtBitmaps.decodeScaled(it, 160) }
        if (bitmap != null) miniArt.setImageBitmap(bitmap) else miniArt.setImageDrawable(null)
    }
}
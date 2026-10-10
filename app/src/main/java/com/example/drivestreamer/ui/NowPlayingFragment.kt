package com.example.drivestreamer.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.example.drivestreamer.R
import com.example.drivestreamer.playback.PlaybackClient

/**
 * The Now Playing tab: current track with live progress, transport
 * controls, and shuffle/repeat toggles. Talks to the same MediaController
 * as the rest of the app (via PlaybackClient), so state here reflects
 * whatever's actually playing — including changes made from Android Auto.
 *
 * The background is a gradient taken from the current album art (see
 * ArtColors) that cross-fades when the track changes.
 *
 * The tab stays alive while hidden, so the once-every-half-second
 * progress ticker is only run while this tab is actually on screen.
 */
class NowPlayingFragment : Fragment(R.layout.fragment_now_playing) {

    private var controller: MediaController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressUpdater: Runnable? = null
    private var viewAlive = false

    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var albumTitle: TextView
    private lateinit var elapsedTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: ImageButton
    private lateinit var albumArt: ImageView
    private lateinit var albumArtPlaceholder: ImageView
    private lateinit var shuffleButton: ImageButton
    private lateinit var repeatButton: ImageButton

    // Background gradient. currentColors is kept on the fragment so a
    // re-created view starts from where the old one was.
    private var currentColors = ArtColors.DEFAULT_GRADIENT.copyOf()
    private lateinit var backgroundGradient: GradientDrawable
    private var colorAnimator: ValueAnimator? = null

    // The artwork bytes we last drew, so repeated metadata events for the
    // same track don't re-decode the image.
    private var lastArtBytes: ByteArray? = null

    private var userIsSeeking = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewAlive = true

        trackTitle = view.findViewById(R.id.trackTitle)
        trackArtist = view.findViewById(R.id.trackArtist)
        albumTitle = view.findViewById(R.id.albumTitle)
        elapsedTime = view.findViewById(R.id.elapsedTime)
        totalTime = view.findViewById(R.id.totalTime)
        seekBar = view.findViewById(R.id.seekBar)
        playPauseButton = view.findViewById(R.id.playPauseButton)
        albumArt = view.findViewById(R.id.albumArt)
        albumArtPlaceholder = view.findViewById(R.id.albumArtPlaceholder)
        shuffleButton = view.findViewById(R.id.shuffleButton)
        repeatButton = view.findViewById(R.id.repeatButton)

        backgroundGradient = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, currentColors)
        view.findViewById<View>(R.id.nowPlayingRoot).background = backgroundGradient

        view.findViewById<ImageButton>(R.id.previousButton).setOnClickListener {
            controller?.seekToPrevious()
        }
        view.findViewById<ImageButton>(R.id.nextButton).setOnClickListener {
            controller?.seekToNext()
        }
        playPauseButton.setOnClickListener {
            controller?.let { c -> if (c.isPlaying) c.pause() else c.play() }
        }
        shuffleButton.setOnClickListener {
            controller?.let { c -> c.shuffleModeEnabled = !c.shuffleModeEnabled }
        }
        repeatButton.setOnClickListener {
            controller?.let { c -> c.repeatMode = nextRepeatMode(c.repeatMode) }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) elapsedTime.text = formatMillis(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { userIsSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                userIsSeeking = false
                controller?.seekTo(sb?.progress?.toLong() ?: 0L)
            }
        })

        PlaybackClient.connect(requireContext().applicationContext) { c ->
            // The tab may have been torn down while the connection was
            // being built (rotation, sign-out): don't touch its views.
            if (!viewAlive) return@connect
            controller = c
            c.addListener(playerListener)
            updateFromController(c)
            updateProgressTicker()
        }
    }

    override fun onResume() {
        super.onResume()
        updateProgressTicker()
    }

    override fun onPause() {
        super.onPause()
        updateProgressTicker()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        updateProgressTicker()
    }

    override fun onDestroyView() {
        viewAlive = false
        stopProgressTicker()
        colorAnimator?.cancel()
        colorAnimator = null
        lastArtBytes = null
        controller?.removeListener(playerListener)
        controller = null
        super.onDestroyView()
    }

    // --- Progress ticker (only while this tab is visible) --------------

    private fun updateProgressTicker() {
        val shouldRun = viewAlive && isResumed && !isHidden && controller != null
        if (shouldRun) startProgressTicker() else stopProgressTicker()
    }

    private fun startProgressTicker() {
        if (progressUpdater != null) return
        val updater = object : Runnable {
            override fun run() {
                controller?.let { c ->
                    if (!userIsSeeking) {
                        seekBar.progress = c.currentPosition.toInt()
                        elapsedTime.text = formatMillis(c.currentPosition)
                    }
                }
                mainHandler.postDelayed(this, 500)
            }
        }
        progressUpdater = updater
        mainHandler.post(updater)
    }

    private fun stopProgressTicker() {
        progressUpdater?.let { mainHandler.removeCallbacks(it) }
        progressUpdater = null
    }

    // --- Player state -> UI ------------------------------------------------

    /** OFF -> ALL -> ONE -> OFF */
    private fun nextRepeatMode(current: Int): Int = when (current) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            applyMetadata(mediaMetadata, fallbackTitle = "Unknown title")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon(isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            context?.let {
                Toast.makeText(it, "Playback error: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            controller?.let { seekBar.max = it.duration.coerceAtLeast(0).toInt() }
            totalTime.text = formatMillis(controller?.duration ?: 0L)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            updateShuffleButton(shuffleModeEnabled)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateRepeatButton(repeatMode)
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        playPauseButton.setImageResource(if (isPlaying) R.drawable.ic_np_pause else R.drawable.ic_np_play)
    }

    private fun updateShuffleButton(enabled: Boolean) {
        shuffleButton.alpha = if (enabled) 1f else 0.4f
    }

    private fun updateRepeatButton(repeatMode: Int) {
        repeatButton.setImageResource(
            if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_np_repeat_one else R.drawable.ic_np_repeat
        )
        repeatButton.alpha = if (repeatMode == Player.REPEAT_MODE_OFF) 0.4f else 1f
    }

    private fun applyMetadata(mediaMetadata: MediaMetadata, fallbackTitle: String) {
        trackTitle.text = mediaMetadata.title ?: fallbackTitle
        val artist = mediaMetadata.artist?.toString().orEmpty()
        trackArtist.text = artist
        trackArtist.visibility = if (artist.isBlank()) View.GONE else View.VISIBLE
        albumTitle.text = mediaMetadata.albumTitle ?: ""
        applyArt(mediaMetadata)
    }

    private fun applyArt(mediaMetadata: MediaMetadata) {
        val artBytes = mediaMetadata.artworkData
        // Same picture we already drew (the controller hands back a fresh
        // copy of the bytes each time, so compare contents, not identity).
        val previous = lastArtBytes
        if (artBytes === previous ||
            (artBytes != null && previous != null && artBytes.contentEquals(previous))
        ) return
        lastArtBytes = artBytes

        val bitmap = artBytes?.let { ArtBitmaps.decodeScaled(it, 800) }
        if (bitmap == null) {
            albumArt.setImageDrawable(null)
            albumArtPlaceholder.visibility = View.VISIBLE
            animateBackgroundTo(ArtColors.DEFAULT_GRADIENT)
            return
        }

        // Fade the new cover in rather than snapping to it.
        albumArtPlaceholder.visibility = View.GONE
        albumArt.alpha = 0f
        albumArt.setImageBitmap(bitmap)
        albumArt.animate().alpha(1f).setDuration(250).start()

        animateBackgroundTo(ArtColors.gradientFor(bitmap))
    }

    /** Cross-fades the background gradient from where it is now to [target]. */
    private fun animateBackgroundTo(target: IntArray) {
        colorAnimator?.cancel()
        val from = currentColors.copyOf()
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                val mixed = IntArray(from.size) { i ->
                    ColorUtils.blendARGB(from[i], target[i], fraction)
                }
                currentColors = mixed
                backgroundGradient.colors = mixed
            }
            start()
        }
    }

    private fun updateFromController(c: MediaController) {
        applyMetadata(c.mediaMetadata, fallbackTitle = "Nothing playing")
        updatePlayPauseIcon(c.isPlaying)
        seekBar.max = c.duration.coerceAtLeast(0).toInt()
        totalTime.text = formatMillis(c.duration)
        updateShuffleButton(c.shuffleModeEnabled)
        updateRepeatButton(c.repeatMode)
    }

    private fun formatMillis(ms: Long): String {
        // A live duration of "unknown" is a huge negative number; show 0:00.
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}
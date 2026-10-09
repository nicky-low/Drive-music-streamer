package com.example.drivestreamer.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
 * The tab stays alive while hidden, so the once-every-half-second
 * progress ticker is only run while this tab is actually on screen.
 */
class NowPlayingFragment : Fragment(R.layout.fragment_now_playing) {

    private var controller: MediaController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressUpdater: Runnable? = null
    private var viewAlive = false

    private lateinit var trackTitle: TextView
    private lateinit var albumTitle: TextView
    private lateinit var elapsedTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: Button
    private lateinit var albumArt: ImageView
    private lateinit var shuffleButton: Button
    private lateinit var repeatButton: Button

    private var userIsSeeking = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewAlive = true

        trackTitle = view.findViewById(R.id.trackTitle)
        albumTitle = view.findViewById(R.id.albumTitle)
        elapsedTime = view.findViewById(R.id.elapsedTime)
        totalTime = view.findViewById(R.id.totalTime)
        seekBar = view.findViewById(R.id.seekBar)
        playPauseButton = view.findViewById(R.id.playPauseButton)
        albumArt = view.findViewById(R.id.albumArt)
        shuffleButton = view.findViewById(R.id.shuffleButton)
        repeatButton = view.findViewById(R.id.repeatButton)

        view.findViewById<Button>(R.id.previousButton).setOnClickListener {
            controller?.seekToPrevious()
        }
        view.findViewById<Button>(R.id.nextButton).setOnClickListener {
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
            trackTitle.text = mediaMetadata.title ?: "Unknown title"
            albumTitle.text = mediaMetadata.albumTitle ?: ""
            applyArt(mediaMetadata)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playPauseButton.text = if (isPlaying) "⏸" else "▶"
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
            shuffleButton.alpha = if (shuffleModeEnabled) 1f else 0.5f
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateRepeatButton(repeatMode)
        }
    }

    private fun updateRepeatButton(repeatMode: Int) {
        when (repeatMode) {
            Player.REPEAT_MODE_OFF -> {
                repeatButton.text = "🔁"
                repeatButton.alpha = 0.5f
            }
            Player.REPEAT_MODE_ALL -> {
                repeatButton.text = "🔁"
                repeatButton.alpha = 1f
            }
            Player.REPEAT_MODE_ONE -> {
                repeatButton.text = "🔂"
                repeatButton.alpha = 1f
            }
        }
    }

    private fun applyArt(mediaMetadata: MediaMetadata) {
        val artBytes = mediaMetadata.artworkData
        if (artBytes != null) {
            val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
            albumArt.setImageBitmap(bitmap)
        } else {
            albumArt.setImageDrawable(null)
        }
    }

    private fun updateFromController(c: MediaController) {
        trackTitle.text = c.mediaMetadata.title ?: "Nothing playing"
        albumTitle.text = c.mediaMetadata.albumTitle ?: ""
        playPauseButton.text = if (c.isPlaying) "⏸" else "▶"
        seekBar.max = c.duration.coerceAtLeast(0).toInt()
        totalTime.text = formatMillis(c.duration)
        applyArt(c.mediaMetadata)
        shuffleButton.alpha = if (c.shuffleModeEnabled) 1f else 0.5f
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

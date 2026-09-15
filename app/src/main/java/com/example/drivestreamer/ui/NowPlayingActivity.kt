package com.example.drivestreamer.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.example.drivestreamer.R
import com.example.drivestreamer.playback.PlaybackClient

/**
 * Shows the currently playing track with live progress, and exposes
 * play/pause/skip. Talks to the same MediaController the library screen
 * uses (via PlaybackClient), so this reflects whatever's actually playing
 * — including if playback was started/changed from Android Auto.
 */
class NowPlayingActivity : AppCompatActivity() {

    private var controller: MediaController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressUpdater: Runnable? = null

    private lateinit var trackTitle: TextView
    private lateinit var albumTitle: TextView
    private lateinit var elapsedTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: Button
    private lateinit var albumArt: ImageView

    private var userIsSeeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        trackTitle = findViewById(R.id.trackTitle)
        albumTitle = findViewById(R.id.albumTitle)
        elapsedTime = findViewById(R.id.elapsedTime)
        totalTime = findViewById(R.id.totalTime)
        seekBar = findViewById(R.id.seekBar)
        playPauseButton = findViewById(R.id.playPauseButton)
        albumArt = findViewById(R.id.albumArt)

        findViewById<Button>(R.id.previousButton).setOnClickListener {
            controller?.seekToPrevious()
        }
        findViewById<Button>(R.id.nextButton).setOnClickListener {
            controller?.seekToNext()
        }
        playPauseButton.setOnClickListener {
            controller?.let { c -> if (c.isPlaying) c.pause() else c.play() }
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

        PlaybackClient.connect(this) { c ->
            controller = c
            c.addListener(playerListener)
            updateFromController(c)
            startProgressUpdates()
        }
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

        override fun onPlaybackStateChanged(playbackState: Int) {
            controller?.let { seekBar.max = it.duration.coerceAtLeast(0).toInt() }
            totalTime.text = formatMillis(controller?.duration ?: 0L)
        }
    }

    private fun applyArt(mediaMetadata: MediaMetadata) {
        val artBytes = mediaMetadata.artworkData
        if (artBytes != null) {
            val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
            albumArt.setImageBitmap(bitmap)
        } else {
            // No embedded art on this track, or it hasn't been fetched yet
            // (MusicService attaches it a moment after playback starts).
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
    }

    private fun startProgressUpdates() {
        progressUpdater = object : Runnable {
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
        mainHandler.post(progressUpdater!!)
    }

    private fun formatMillis(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    override fun onDestroy() {
        progressUpdater?.let { mainHandler.removeCallbacks(it) }
        controller?.removeListener(playerListener)
        // Don't release the controller here — PlaybackClient owns its
        // lifecycle so playback keeps going if the user backs out of
        // this screen while a track is still playing.
        super.onDestroy()
    }
}

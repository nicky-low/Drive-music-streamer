package com.example.drivestreamer.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors

/**
 * One MediaController shared across activities, so navigating from the
 * library screen to the now-playing screen doesn't tear down and rebuild
 * the connection to MusicService (which would interrupt playback state).
 */
object PlaybackClient {

    private var controller: MediaController? = null
    private val listeners = mutableListOf<(MediaController) -> Unit>()

    fun connect(context: Context, onReady: (MediaController) -> Unit) {
        controller?.let {
            onReady(it)
            return
        }
        listeners.add(onReady)

        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context.applicationContext, sessionToken).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            listeners.forEach { it(c) }
            listeners.clear()
        }, MoreExecutors.directExecutor())
    }

    fun current(): MediaController? = controller

    /** Call from the top-level Application or last activity's onDestroy, not per-screen. */
    fun releaseAll() {
        controller?.release()
        controller = null
    }
}

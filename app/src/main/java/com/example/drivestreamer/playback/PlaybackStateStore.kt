package com.example.drivestreamer.playback

import android.content.Context
import java.io.File

/**
 * Remembers what was playing — which track, how far into it, and the
 * shuffle/repeat settings — so playback can be picked up again after the
 * service or the whole app has been shut down.
 *
 * Normally only the *track* is stored, not the queue: the queue is "the whole
 * library, starting here", which can be rebuilt from the cached library, so
 * there's no need to save thousands of entries. The exception is a queue
 * that's only part of the library (e.g. shuffling one artist): then its list
 * of track IDs is kept too, in a small file, so resuming gets the same set
 * back rather than everything.
 */
class PlaybackStateStore(context: Context) {

    data class Saved(
        val mediaId: String,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int
    )

    companion object {
        private const val PREFS = "playback_state"
        private const val KEY_ID = "media_id"
        private const val KEY_POSITION = "position_ms"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT = "repeat_mode"
        private const val QUEUE_FILE = "playback_queue.txt"

        /** Used on sign-out. */
        fun clear(context: Context) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
            File(context.applicationContext.filesDir, QUEUE_FILE).delete()
        }
    }

    private val queueFile = File(context.applicationContext.filesDir, QUEUE_FILE)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(mediaId: String, positionMs: Long, shuffle: Boolean, repeatMode: Int) {
        prefs.edit()
            .putString(KEY_ID, mediaId)
            .putLong(KEY_POSITION, positionMs)
            .putBoolean(KEY_SHUFFLE, shuffle)
            .putInt(KEY_REPEAT, repeatMode)
            .apply()
    }

    fun load(): Saved? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        return Saved(
            mediaId = id,
            positionMs = prefs.getLong(KEY_POSITION, 0L),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = prefs.getInt(KEY_REPEAT, 0)
        )
    }

    /**
     * Remembers the queue's track IDs, or — with null — that the queue is
     * simply the whole library (so nothing needs storing). Does file I/O, so
     * call it off the main thread.
     */
    fun saveQueue(ids: List<String>?) {
        if (ids == null) {
            queueFile.delete()
            return
        }
        // Write to a temp file first so being killed mid-write can't leave
        // a half-written list behind.
        val tmp = File(queueFile.path + ".tmp")
        tmp.writeText(ids.joinToString("\n"))
        if (!tmp.renameTo(queueFile)) {
            queueFile.delete()
            tmp.renameTo(queueFile)
        }
    }

    /** The saved queue's track IDs, or null if the queue was the whole library. Does file I/O. */
    fun loadQueue(): List<String>? {
        if (!queueFile.exists()) return null
        return try {
            queueFile.readLines().filter { it.isNotEmpty() }.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }
}

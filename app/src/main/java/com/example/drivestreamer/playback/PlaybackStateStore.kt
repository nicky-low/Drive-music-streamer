package com.example.drivestreamer.playback

import android.content.Context

/**
 * Remembers what was playing — which track, how far into it, and the
 * shuffle/repeat settings — so playback can be picked up again after the
 * service or the whole app has been shut down.
 *
 * Only the *track* is stored, not the queue: the queue is always "the whole
 * library, starting here", which can be rebuilt from the cached library, so
 * there's no need to save thousands of entries.
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

        /** Used on sign-out. */
        fun clear(context: Context) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
    }

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
}

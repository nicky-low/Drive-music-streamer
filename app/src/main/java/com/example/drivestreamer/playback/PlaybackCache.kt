package com.example.drivestreamer.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * A single on-disk cache of previously-streamed audio bytes, shared by
 * every playback session. This is what makes replaying a track — or
 * anything you've listened to recently — work without a network call,
 * and lets already-cached tracks keep playing through a dead zone.
 *
 * Important: SimpleCache can only have ONE open instance per directory
 * at a time (it takes a lock file), so this must stay a singleton rather
 * than being constructed per-service-instance.
 */
object PlaybackCache {

    // Cap the cache at 1.5GB. Adjust to taste — this is disk space, not
    // your Drive quota, so it's purely a "how much of your library do
    // you want available offline at once" knob.
    private const val MAX_CACHE_BYTES = 1_500L * 1024 * 1024

    @Volatile private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        return cache ?: synchronized(this) {
            cache ?: buildCache(context.applicationContext).also { cache = it }
        }
    }

    private fun buildCache(context: Context): SimpleCache {
        val cacheDir = File(context.cacheDir, "drive_audio_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)
        val databaseProvider = StandaloneDatabaseProvider(context)
        return SimpleCache(cacheDir, evictor, databaseProvider)
    }
}

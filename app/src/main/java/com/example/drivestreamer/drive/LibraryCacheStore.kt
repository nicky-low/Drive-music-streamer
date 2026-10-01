package com.example.drivestreamer.drive

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CachedLibrary(
    val folderId: String,
    val savedAtMillis: Long,
    val albums: List<Album>
)

/**
 * Persists the parsed library (already-tagged tracks, already-sorted
 * albums) to a local JSON file in app-internal storage, so reopening the
 * app doesn't mean rereading ID3 tags for thousands of tracks from Drive
 * every single time. Only an explicit "Load library" tap re-scans Drive;
 * everything else reads this cache.
 *
 * Internal storage (not cacheDir) is deliberate here — unlike the audio
 * byte cache in PlaybackCache, this is small (just text/metadata) and
 * something the user would be annoyed to lose to OS cache-clearing under
 * storage pressure.
 */
class LibraryCacheStore(context: Context) {

    private val file = File(context.filesDir, "library_cache.json")
    private val gson = Gson()

    suspend fun save(folderId: String, albums: List<Album>) = withContext(Dispatchers.IO) {
        val cached = CachedLibrary(folderId, System.currentTimeMillis(), albums)
        try {
            file.writeText(gson.toJson(cached))
        } catch (e: Exception) {
            // Losing the cache isn't fatal — worst case, next launch just
            // re-scans from Drive instead of loading instantly.
        }
    }

    suspend fun load(): CachedLibrary? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            val type = object : TypeToken<CachedLibrary>() {}.type
            gson.fromJson<CachedLibrary>(file.readText(), type)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
    }
}

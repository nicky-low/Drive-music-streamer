package com.example.drivestreamer.drive

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the artist/title already read for each file, on disk.
 *
 * Reading tags for thousands of tracks takes a long time, and a scan can be
 * interrupted — cancelled, the connection dropping, the phone killing the
 * app. Without this, every interruption meant starting again from track
 * one. With it, a scan saves its progress as it goes, and the next "Load
 * library" skips everything already read and carries on from where it
 * stopped.
 *
 * Entries are keyed by Drive file ID together with the file's last-modified
 * time, so a file you've since re-tagged is read again rather than served
 * stale.
 */
class TagCache(context: Context) {

    data class Entry(
        val modified: String? = null,
        val artist: String? = null,
        val title: String? = null
    )

    companion object {
        private const val FILE_NAME = "tag_cache.json"

        /** Used on sign-out. */
        fun delete(context: Context) {
            File(context.filesDir, FILE_NAME).delete()
        }
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val gson = Gson()
    private val entries = ConcurrentHashMap<String, Entry>()
    private val saveLock = Any()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext
        try {
            val type = object : TypeToken<Map<String, Entry>>() {}.type
            val loaded: Map<String, Entry>? = gson.fromJson(file.readText(), type)
            loaded?.let { entries.putAll(it) }
        } catch (e: Exception) {
            // Unreadable or corrupt: just start from scratch.
        }
    }

    /** The saved tags for this exact version of the file, or null if we have none. */
    fun get(fileId: String, modified: String?): Entry? =
        entries[fileId]?.takeIf { it.modified == modified }

    fun put(fileId: String, modified: String?, artist: String?, title: String?) {
        entries[fileId] = Entry(modified, artist, title)
    }

    /** Forget files that no longer exist in the library. */
    fun retainOnly(fileIds: Set<String>) {
        entries.keys.retainAll(fileIds)
    }

    /** Written to a temp file first, so a crash mid-save can't leave a half-written cache. */
    suspend fun save() = withContext(Dispatchers.IO) {
        synchronized(saveLock) {
            try {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(gson.toJson(entries))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } catch (e: Exception) {
                // Losing the cache costs time on the next scan, nothing more.
            }
        }
    }
}
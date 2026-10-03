package com.example.drivestreamer.auth

import android.content.Context
import android.content.Intent
import com.example.drivestreamer.drive.AlbumArtLoader
import com.example.drivestreamer.drive.LibraryCacheStore
import com.example.drivestreamer.playback.LibraryLoadService
import com.example.drivestreamer.playback.LibraryLoadState
import com.example.drivestreamer.playback.MusicLibraryHolder
import com.example.drivestreamer.playback.PlaybackCache
import com.example.drivestreamer.playback.PlaybackClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Signs out and wipes everything the previous account left on the device:
 * the cached library, the in-memory library Android Auto browses, cached
 * cover art, and the cached audio bytes. Call from the main thread.
 *
 * Every step is best-effort and independent: a failure in one never stops
 * the sign-out itself from completing.
 */
object SignOutCleanup {

    suspend fun run(context: Context, authManager: AuthManager) {
        val app = context.applicationContext

        // 1. Stop an in-flight library scan so it can't finish later and
        //    write the old account's library back to disk. Only send the
        //    cancel if a scan is running: the service doesn't stop itself
        //    when asked to cancel something that isn't there.
        if (LibraryLoadState.status.value is LibraryLoadState.Status.Loading) {
            runCatching {
                app.startService(
                    Intent(app, LibraryLoadService::class.java)
                        .setAction(LibraryLoadService.ACTION_CANCEL)
                )
                withTimeoutOrNull(3_000) {
                    LibraryLoadState.status.first { it !is LibraryLoadState.Status.Loading }
                }
            }
        }

        // 2. Stop playback and empty the queue, before the audio cache is
        //    cleared underneath it.
        runCatching {
            PlaybackClient.current()?.let { controller ->
                controller.stop()
                controller.clearMediaItems()
            }
        }

        // 3. Google sign-out, awaited. Must come before step 4 so nothing
        //    can re-resolve the still-signed-in account in between.
        runCatching { withTimeoutOrNull(5_000) { authManager.signOutAndAwait() } }

        // 4. Make every TokenProvider in the process forget its account.
        TokenProvider.invalidateAll()

        // 5. Local data. Cache file first, then the in-memory copy, so a
        //    browse request can't reload the file we're about to delete.
        runCatching { LibraryCacheStore(app).clear() }
        runCatching { MusicLibraryHolder.clear() }
        AlbumArtLoader.clearCache()
        LibraryLoadState.update(LibraryLoadState.Status.Idle)
        runCatching { PlaybackCache.clear(app) }
    }
}
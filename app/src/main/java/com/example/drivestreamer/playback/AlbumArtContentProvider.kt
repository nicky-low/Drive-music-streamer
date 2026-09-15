package com.example.drivestreamer.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.drivestreamer.auth.AuthManager
import com.example.drivestreamer.auth.TokenProvider
import com.example.drivestreamer.drive.AlbumArtLoader
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

/**
 * Serves embedded album art as a content:// URI, keyed by Drive file ID:
 *   content://com.example.drivestreamer.albumart/{fileId}
 *
 * Why a provider instead of just attaching artworkData to every browse
 * item up front: MediaLibraryService.onGetChildren has to return a whole
 * page of items in one response, and fetching + decoding art for every
 * track before that response goes out would make the browse list slow
 * to open, especially on a big library. Handing back a URI instead lets
 * the browsing client (Android Auto, or our own app) resolve each item's
 * art only when it actually needs to draw it — so cost is roughly
 * proportional to what's on screen, not the whole list.
 *
 * Uses the same AlbumArtLoader (and its in-memory cache) as MusicService,
 * so art fetched here for a browse row is instantly available again if
 * that track is then played, and vice versa.
 */
class AlbumArtContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.example.drivestreamer.albumart"

        fun uriFor(fileId: String): Uri =
            Uri.parse("content://$AUTHORITY/$fileId")
    }

    private val albumArtLoader = AlbumArtLoader()
    private var tokenProvider: TokenProvider? = null

    override fun onCreate(): Boolean {
        val context = context ?: return false
        val authManager = AuthManager(context.applicationContext)
        val provider = TokenProvider(authManager)
        GoogleSignIn.getLastSignedInAccount(context.applicationContext)?.let {
            provider.setAccount(it)
        }
        tokenProvider = provider
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val fileId = uri.lastPathSegment
            ?: throw FileNotFoundException("No file id in $uri")
        val provider = tokenProvider
            ?: throw FileNotFoundException("Not signed in — no token provider available")

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        // Fetch + write happens on a background thread so we don't block
        // the caller (the binder thread that called openFile). If the
        // track has no embedded art, we close the pipe with nothing
        // written — callers should treat that as "no artwork" and fall
        // back to a placeholder, same as elsewhere in the app.
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                try {
                    val token = runBlocking { provider.getToken() }
                    val bytes = runBlocking { albumArtLoader.fetchEmbeddedArt(fileId, token) }
                    bytes?.let { out.write(it) }
                } catch (e: Exception) {
                    // Swallow — an empty pipe just means no art, which is
                    // a normal, expected outcome for untagged files.
                }
            }
        }.start()

        return readSide
    }

    // Only file access is needed for artwork — the rest of the
    // ContentProvider surface is unused, so these are minimal stubs.
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String = "image/*"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ) = 0
}

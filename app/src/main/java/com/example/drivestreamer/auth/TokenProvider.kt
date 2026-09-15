package com.example.drivestreamer.auth

import android.util.Log
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Wraps AuthManager with caching + proactive refresh, so playback doesn't
 * hit expired tokens mid-track. Drive OAuth access tokens are typically
 * valid ~1 hour; we refresh a bit early to stay safe.
 *
 * Two entry points:
 *  - suspend getToken(): for callers already on a coroutine (e.g. UI/library load)
 *  - blockingGetToken(): for ExoPlayer's DataSource, which opens on a
 *    background loading thread but isn't coroutine-based
 */
class TokenProvider(private val authManager: AuthManager) {

    companion object {
        private const val TAG = "TokenProvider"
        // Refresh if the cached token is older than this, even if it
        // hasn't technically expired yet — avoids racing expiry mid-stream.
        private const val REFRESH_MARGIN_MS = 45 * 60 * 1000L // 45 minutes
    }

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedAt: Long = 0L
    @Volatile private var account: GoogleSignInAccount? = null
    private val mutex = Mutex()

    fun setAccount(account: GoogleSignInAccount) {
        this.account = account
        // Force a fresh fetch next time — new sign-in, old cache is invalid.
        cachedToken = null
    }

    suspend fun getToken(forceRefresh: Boolean = false): String {
        val acct = account ?: throw IllegalStateException("No signed-in account set on TokenProvider")

        mutex.withLock {
            val isStale = forceRefresh ||
                cachedToken == null ||
                (System.currentTimeMillis() - cachedAt) > REFRESH_MARGIN_MS

            if (isStale) {
                if (forceRefresh) {
                    cachedToken?.let { stale ->
                        try {
                            authManager.invalidateToken(stale)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to invalidate stale token, continuing anyway", e)
                        }
                    }
                }
                val fresh = authManager.getAccessToken(acct)
                cachedToken = fresh
                cachedAt = System.currentTimeMillis()
            }
        }
        return cachedToken!!
    }

    /**
     * Blocking variant for use on non-coroutine background threads
     * (ExoPlayer's DataSource.open runs on a loading thread, not the
     * main thread, so blocking here is safe — just never call this
     * from the main thread).
     */
    fun blockingGetToken(forceRefresh: Boolean = false): String =
        kotlinx.coroutines.runBlocking { getToken(forceRefresh) }
}

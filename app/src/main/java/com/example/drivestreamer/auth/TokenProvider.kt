package com.example.drivestreamer.auth

import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps AuthManager with caching + proactive refresh, so playback doesn't
 * hit expired tokens mid-track. Drive OAuth access tokens are typically
 * valid ~1 hour; we refresh a bit early to stay safe.
 *
 * [accountResolver] is a fallback, checked lazily the moment a token is
 * actually requested — not just once at construction time. This matters
 * because MusicService connects (and builds its TokenProvider) the
 * instant MainActivity launches, which on a fresh install can happen
 * BEFORE the user has signed in yet. Without this fallback, that one
 * early check finding "not signed in" would be final — the service
 * would never look again, even after sign-in later succeeds in the same
 * session. Re-checking at point of use fixes that: by the time playback
 * actually starts, sign-in has long since completed.
 */
class TokenProvider(
    private val authManager: AuthManager,
    private val accountResolver: () -> GoogleSignInAccount? = { null }
) {

    companion object {
        private const val TAG = "TokenProvider"
        private const val REFRESH_MARGIN_MS = 45 * 60 * 1000L // 45 minutes

        // Bumped on sign-out. Every TokenProvider in the process (the
        // service's, the art provider's, ...) notices the change the next
        // time it's asked for a token, and drops its cached account and
        // token instead of carrying on as the previous user. Only touched
        // from the main thread, once per sign-out.
        @Volatile private var sessionEpoch = 0L

        fun invalidateAll() {
            sessionEpoch++
        }
    }

    @Volatile private var epochSeen: Long = sessionEpoch

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedAt: Long = 0L
    @Volatile private var account: GoogleSignInAccount? = null
    private val mutex = Mutex()

    fun setAccount(account: GoogleSignInAccount) {
        this.account = account
        cachedToken = null
    }

    suspend fun getToken(forceRefresh: Boolean = false): String {
        // A sign-out happened since we last looked: forget the old
        // account/token. The resolver below then finds the new account,
        // or nothing at all if nobody is signed in.
        val epoch = sessionEpoch
        if (epochSeen != epoch) {
            account = null
            cachedToken = null
            epochSeen = epoch
        }

        // Lazy fallback: if nothing was set eagerly (or the eager check
        // ran too early, before sign-in completed), try resolving again
        // right now rather than failing immediately.
        val acct = account ?: accountResolver()?.also { account = it }
            ?: throw IllegalStateException("No signed-in account available yet")

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
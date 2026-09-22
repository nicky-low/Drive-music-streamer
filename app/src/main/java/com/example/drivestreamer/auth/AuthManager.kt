package com.example.drivestreamer.auth

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Requests ONLY drive.readonly — read access to files, no write/delete,
 * and no broader "drive" (full account management) scope.
 *
 * IMPORTANT: sign in with your BUFFER account (the second Google account
 * that only has your shared music folder in it), not your main account.
 * That way even a bug or a compromised token can't expose your real Drive.
 */
class AuthManager(private val context: Context) {

    companion object {
        // TODO: replace with a scope constant — kept as literal so it's
        // obvious at a glance this is the narrow, read-only scope.
        const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    }

    private val signInOptions: GoogleSignInOptions by lazy {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_READONLY_SCOPE))
            .build()
    }

    private val client: GoogleSignInClient by lazy {
        GoogleSignIn.getClient(context, signInOptions)
    }

    fun signInIntent(): Intent = client.signInIntent

    fun lastSignedInAccount(): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    fun handleSignInResult(data: Intent?): Result<GoogleSignInAccount> {
        return try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            Result.success(account)
        } catch (e: ApiException) {
            // This is the crash fix: previously we called task.result directly,
            // which throws (and crashes the app) on any sign-in failure —
            // most commonly a package name / SHA-1 mismatch between the app
            // and the OAuth client registered in Cloud Console (status code
            // 10, DEVELOPER_ERROR), or the account not being on the OAuth
            // consent screen's test users list.
            Result.failure(e)
        }
    }

    /**
     * Fetches a fresh OAuth access token for API calls.
     * Must be called off the main thread — it can block on network/IO.
     */
    suspend fun getAccessToken(account: GoogleSignInAccount): String =
        withContext(Dispatchers.IO) {
            val androidAccount = Account(account.email, "com.google")
            GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$DRIVE_READONLY_SCOPE")
        }

    /**
     * Tells Google Play Services the given token is bad (e.g. we got a 401
     * from Drive using it), so the next getAccessToken() call is forced to
     * fetch a genuinely new one instead of handing back the same stale token.
     */
    suspend fun invalidateToken(token: String) = withContext(Dispatchers.IO) {
        GoogleAuthUtil.clearToken(context, token)
    }

    fun signOut() {
        client.signOut()
    }
}

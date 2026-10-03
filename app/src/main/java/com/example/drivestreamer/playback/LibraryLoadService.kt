package com.example.drivestreamer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.drivestreamer.R
import com.example.drivestreamer.auth.AuthManager
import com.example.drivestreamer.auth.TokenProvider
import com.example.drivestreamer.drive.DriveLibraryRepository
import com.example.drivestreamer.drive.LibraryCacheStore
import com.example.drivestreamer.ui.LibraryActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the library load — potentially thousands of per-track tag
 * requests on a big library — as a foreground service, so it survives
 * the screen turning off, the user switching to another app, or
 * MainActivity being destroyed/recreated.
 *
 * This service owns its own sign-in/token lookup (same pattern as
 * MusicService) rather than depending on MainActivity for it, precisely
 * so it can keep running with MainActivity gone.
 */
class LibraryLoadService : Service() {

    companion object {
        const val EXTRA_FOLDER_ID = "folder_id"
        const val ACTION_CANCEL = "com.example.drivestreamer.action.CANCEL_LOAD"
        private const val CHANNEL_ID = "library_loading"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFY_EVERY_N_TRACKS = 5
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var tokenProvider: TokenProvider
    private val repository = DriveLibraryRepository()
    private var loadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val authManager = AuthManager(applicationContext)
        tokenProvider = TokenProvider(authManager) {
            GoogleSignIn.getLastSignedInAccount(applicationContext)
        }
        GoogleSignIn.getLastSignedInAccount(applicationContext)?.let {
            tokenProvider.setAccount(it)
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // Just cancel the job — its own catch/finally (below) handles
            // updating LibraryLoadState and stopping the service, so
            // there's one single place that does that cleanup rather than
            // duplicating it here too.
            loadJob?.cancel()
            return START_NOT_STICKY
        }

        val folderId = intent?.getStringExtra(EXTRA_FOLDER_ID)
        if (folderId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Finding your music…", 0, 0))
        LibraryLoadState.update(LibraryLoadState.Status.Loading(0, 0))

        loadJob = serviceScope.launch {
            try {
                val albums = repository.loadLibrary(tokenProvider, folderId) { done, total ->
                    LibraryLoadState.update(LibraryLoadState.Status.Loading(done, total))
                    if (done % NOTIFY_EVERY_N_TRACKS == 0 || done == total) {
                        updateNotification("Reading tags…", done, total)
                    }
                }
                MusicLibraryHolder.replace(albums)
                LibraryCacheStore(applicationContext).save(folderId, albums)
                LibraryLoadState.update(LibraryLoadState.Status.Complete(albums))
            } catch (e: kotlinx.coroutines.CancellationException) {
                LibraryLoadState.update(LibraryLoadState.Status.Cancelled)
            } catch (e: Exception) {
                LibraryLoadState.update(
                    LibraryLoadState.Status.Error(e.message ?: e::class.simpleName ?: "Unknown error")
                )
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        // Deliberately not START_STICKY: if the OS kills this process
        // under memory pressure, restarting with no folder ID to resume
        // isn't useful — the user just taps "Load library" again.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Library loading",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while your Drive music library loads"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String, done: Int, total: Int): Notification {
        val contentIntent = Intent(this, LibraryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, LibraryLoadService::class.java).setAction(ACTION_CANCEL)
        val cancelPendingIntent = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Loading your music library")
            .setContentText(if (total > 0) "$status $done / $total" else status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
        if (total > 0) builder.setProgress(total, done, false)
        return builder.build()
    }

    private fun updateNotification(status: String, done: Int, total: Int) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(status, done, total)
        )
    }
}
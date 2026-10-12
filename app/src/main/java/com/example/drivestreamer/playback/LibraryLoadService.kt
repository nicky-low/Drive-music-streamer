package com.example.drivestreamer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.drivestreamer.R
import com.example.drivestreamer.auth.AuthManager
import com.example.drivestreamer.auth.TokenProvider
import com.example.drivestreamer.drive.DriveLibraryRepository
import com.example.drivestreamer.drive.LibraryCacheStore
import com.example.drivestreamer.drive.TagCache
import com.example.drivestreamer.ui.MainActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        // Notifications are rate-limited by Android; once a second is plenty.
        private const val NOTIFY_INTERVAL_MS = 1_000L

        // If Cancel is tapped and the scan still hasn't wound down after
        // this long, shut the service down anyway.
        private const val CANCEL_FAILSAFE_MS = 8_000L
    }

    // Deliberately NOT the main thread. The scan does a lot of work —
    // thousands of tasks, a big sort, JSON saving — and on the main thread
    // any stall in it froze the UI, including the Cancel button that was
    // supposed to stop it. Anything that touches the UI or the media
    // session switches to Main explicitly where needed.
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var lastNotifyAt = 0L
    @Volatile private var lastDone = 0
    @Volatile private var lastTotal = 0
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
            val job = loadJob
            job?.cancel()

            // Cancelling normally takes effect within moments. But if
            // something is stuck, never leave an unkillable notification
            // behind: if the job still hasn't finished shortly after, shut
            // the service down regardless.
            if (job != null) {
                serviceScope.launch {
                    delay(CANCEL_FAILSAFE_MS)
                    if (!job.isCompleted) {
                        withContext(Dispatchers.Main) {
                            LibraryLoadState.update(LibraryLoadState.Status.Cancelled)
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
            return START_NOT_STICKY
        }

        // Already scanning (e.g. a double tap): don't start a second scan
        // alongside the first. A started foreground service still has to
        // call startForeground, so re-post the current notification.
        if (loadJob?.isActive == true) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Reading tags…", lastDone, lastTotal)
            )
            return START_NOT_STICKY
        }

        val folderId = intent?.getStringExtra(EXTRA_FOLDER_ID)
        if (folderId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Finding your music…", 0, 0))
        LibraryLoadState.update(LibraryLoadState.Status.Loading(0, 0))

        lastNotifyAt = 0L
        lastDone = 0
        lastTotal = 0

        loadJob = serviceScope.launch {
            try {
                val tagCache = TagCache(applicationContext)
                val albums = repository.loadLibrary(tokenProvider, folderId, tagCache) { done, total ->
                    lastDone = done
                    lastTotal = total
                    LibraryLoadState.update(LibraryLoadState.Status.Loading(done, total))
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotifyAt >= NOTIFY_INTERVAL_MS || done == total) {
                        lastNotifyAt = now
                        updateNotification("Reading tags…", done, total)
                    }
                }
                // The in-memory library notifies the UI and Android Auto,
                // so that part has to happen on the main thread.
                withContext(Dispatchers.Main) { MusicLibraryHolder.replace(albums) }
                LibraryCacheStore(applicationContext).save(folderId, albums)
                LibraryLoadState.update(LibraryLoadState.Status.Complete(albums))
            } catch (e: kotlinx.coroutines.CancellationException) {
                LibraryLoadState.update(LibraryLoadState.Status.Cancelled)
            } catch (e: Exception) {
                LibraryLoadState.update(
                    LibraryLoadState.Status.Error(e.message ?: e::class.simpleName ?: "Unknown error")
                )
            } finally {
                // NonCancellable: this must run even when we got here
                // because of a cancel.
                withContext(NonCancellable + Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
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
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_SETTINGS)
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
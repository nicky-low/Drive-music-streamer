package com.example.drivestreamer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.drivestreamer.R
import com.example.drivestreamer.auth.AuthManager
import com.example.drivestreamer.auth.SignOutCleanup
import com.example.drivestreamer.drive.LibraryCacheStore
import com.example.drivestreamer.playback.LibraryLoadService
import com.example.drivestreamer.playback.LibraryLoadState
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Settings tab: who you're signed in as (and Sign out), which Drive
 * folder holds your music, and the Load library button with its progress.
 * The loading itself runs in LibraryLoadService; this tab just starts it
 * and shows its state.
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    companion object {
        // Sign-out must run to completion even if the screen is rotated
        // or backgrounded halfway through, so it can't use a scope tied
        // to this fragment's lifecycle.
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        // The last finished/failed/cancelled status we already told the
        // user about. A StateFlow replays its latest value every time the
        // app returns to the foreground; without this, the same toast
        // would pop up again each time. Cleared when a new load starts.
        @Volatile
        private var lastHandledTerminal: LibraryLoadState.Status? = null

        private val FOLDER_PATH_ID = Regex("/folders/([A-Za-z0-9_-]+)")
        private val QUERY_ID = Regex("[?&]id=([A-Za-z0-9_-]+)")
    }

    private lateinit var authManager: AuthManager
    private lateinit var signOutButton: Button
    private lateinit var folderInput: EditText
    private lateinit var cacheInfoText: TextView
    private lateinit var loadLibraryButton: Button
    private lateinit var loadingRow: LinearLayout
    private lateinit var loadingStatusText: TextView
    private lateinit var loadingBar: LinearProgressIndicator

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Loading still works without it — just no visible progress notification. */ }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        authManager = AuthManager(requireContext())

        signOutButton = view.findViewById(R.id.signOutButton)
        folderInput = view.findViewById(R.id.folderIdInput)
        cacheInfoText = view.findViewById(R.id.cacheInfoText)
        loadLibraryButton = view.findViewById(R.id.loadLibraryButton)
        loadingRow = view.findViewById(R.id.loadingRow)
        loadingStatusText = view.findViewById(R.id.loadingStatusText)
        loadingBar = view.findViewById(R.id.loadingBar)

        val email = authManager.lastSignedInAccount()?.email ?: ""
        view.findViewById<TextView>(R.id.accountText).text = email
        // The round avatar just shows the account's first letter.
        view.findViewById<TextView>(R.id.accountAvatar).text =
            email.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

        signOutButton.setOnClickListener { signOut() }
        loadLibraryButton.setOnClickListener { startLibraryLoad() }
        view.findViewById<Button>(R.id.cancelLoadButton).setOnClickListener {
            val ctx = requireContext()
            ctx.startService(
                Intent(ctx, LibraryLoadService::class.java)
                    .setAction(LibraryLoadService.ACTION_CANCEL)
            )
        }

        // Pre-fill the folder and show how old the cached library is.
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val cached = LibraryCacheStore(appContext).load() ?: return@launch
            if (folderInput.text.isNullOrBlank()) folderInput.setText(cached.folderId)
            // Don't overwrite the text of a load that's running or just ended.
            if (LibraryLoadState.status.value is LibraryLoadState.Status.Idle) {
                val relativeTime = DateUtils.getRelativeTimeSpanString(
                    cached.savedAtMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                )
                cacheInfoText.text =
                    "Library cached from $relativeTime — tap Load library to refresh"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LibraryLoadState.status.collect { render(it) }
            }
        }
    }

    // --- Sign out ----------------------------------------------------------

    private fun signOut() {
        signOutButton.isEnabled = false
        signOutButton.text = "Signing out…"

        val appContext = requireContext().applicationContext
        val auth = AuthManager(appContext)
        appScope.launch {
            SignOutCleanup.run(appContext, auth)
            // CLEAR_TASK drops MainActivity (whatever state it's in) so
            // Back from the sign-in screen can't return to it.
            appContext.startActivity(
                Intent(appContext, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
    }

    // --- Library loading -----------------------------------------------

    private fun render(status: LibraryLoadState.Status) {
        val ctx = context ?: return
        when (status) {
            is LibraryLoadState.Status.Idle -> setLoadingUi(false)
            is LibraryLoadState.Status.Loading -> {
                lastHandledTerminal = null
                setLoadingUi(true)
                cacheInfoText.text = ""
                if (status.total > 0) {
                    loadingStatusText.text = "Reading tags… ${status.done} / ${status.total}"
                    loadingBar.visibility = View.VISIBLE
                    loadingBar.max = status.total
                    loadingBar.setProgressCompat(status.done, true)
                } else {
                    loadingStatusText.text = "Finding your music…"
                    loadingBar.visibility = View.GONE
                }
            }
            is LibraryLoadState.Status.Complete -> {
                setLoadingUi(false)
                cacheInfoText.text = "Just updated"
                if (status !== lastHandledTerminal) {
                    lastHandledTerminal = status
                    val message = if (status.albums.isEmpty()) {
                        "Loaded, but found no audio files in that folder"
                    } else {
                        "Library loaded — ${status.albums.size} folders"
                    }
                    Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                }
            }
            is LibraryLoadState.Status.Error -> {
                setLoadingUi(false)
                if (status !== lastHandledTerminal) {
                    lastHandledTerminal = status
                    Toast.makeText(
                        ctx, "Failed to load library: ${status.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
            is LibraryLoadState.Status.Cancelled -> {
                setLoadingUi(false)
                if (status !== lastHandledTerminal) {
                    lastHandledTerminal = status
                    Toast.makeText(ctx, "Loading cancelled", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setLoadingUi(loading: Boolean) {
        loadLibraryButton.isEnabled = !loading
        loadingRow.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun startLibraryLoad() {
        val folderId = extractFolderId(folderInput.text.toString().trim())
        if (folderId.isEmpty()) {
            Toast.makeText(requireContext(), "Paste a folder link or ID first", Toast.LENGTH_SHORT).show()
            return
        }
        // Show exactly what will be used, if a full link was pasted.
        if (folderInput.text.toString().trim() != folderId) folderInput.setText(folderId)

        maybeRequestNotificationPermission()

        val ctx = requireContext()
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, LibraryLoadService::class.java)
                .putExtra(LibraryLoadService.EXTRA_FOLDER_ID, folderId)
        )
    }

    /**
     * Accepts either a bare folder ID or a pasted Drive link such as
     * https://drive.google.com/drive/folders/<id>?usp=sharing and returns
     * just the ID.
     */
    private fun extractFolderId(input: String): String {
        FOLDER_PATH_ID.find(input)?.let { return it.groupValues[1] }
        QUERY_ID.find(input)?.let { return it.groupValues[1] }
        return input
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
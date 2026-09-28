package com.example.drivestreamer.playback

import com.example.drivestreamer.drive.Album
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The actual loading happens in LibraryLoadService, which keeps running
 * even if MainActivity is destroyed (screen off, backgrounded, rotated,
 * or reclaimed by the OS). This StateFlow is how the activity reflects
 * that ongoing/finished state whenever it happens to be visible — a
 * StateFlow always replays its latest value to a new collector, so
 * reopening the app mid-load (or after it's finished) shows the right
 * thing immediately, with no separate "check if still loading" call.
 */
object LibraryLoadState {

    sealed class Status {
        data object Idle : Status()
        data class Loading(val done: Int, val total: Int) : Status()
        data class Complete(val albums: List<Album>) : Status()
        data class Error(val message: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    fun update(newStatus: Status) {
        _status.value = newStatus
    }
}
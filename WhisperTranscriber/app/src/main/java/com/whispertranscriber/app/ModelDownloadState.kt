package com.whispertranscriber.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process channel between [ModelDownloadService] (producer) and the UI (consumer). The
 * download runs in a foreground service so it survives the app being backgrounded — large
 * models can take many minutes on a mobile connection, and a plain Activity-scoped coroutine
 * gets starved by Doze/App Standby network restrictions the moment the screen locks.
 *
 * A StateFlow always replays its latest value to new collectors, so the UI reflects an
 * in-progress (or just-finished) download correctly even if the Activity wasn't around to see
 * every intermediate update.
 */
object ModelDownloadState {

    sealed class Status {
        object Idle : Status()
        data class Downloading(val modelId: String, val percent: Int) : Status()
        data class Completed(val modelId: String) : Status()
        data class Failed(val modelId: String, val message: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun update(newStatus: Status) {
        _status.value = newStatus
    }
}

package com.whispertranscriber.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process channel between [TranscriptionService] (producer) and the UI (consumer), mirroring
 * [ModelDownloadState]. Running the batch in a foreground service means it keeps making progress
 * while the app is backgrounded or the screen is off, instead of being interrupted the moment the
 * Activity is stopped/destroyed — which was silently losing overnight multi-hundred-file folder
 * batches entirely, with no trace once the app was reopened.
 */
object TranscriptionState {

    sealed class Status {
        object Idle : Status()
        data class Running(val results: List<TranscriptionResult>, val currentIndex: Int, val total: Int) : Status()
        data class Finished(val results: List<TranscriptionResult>, val outputDir: String?) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun update(newStatus: Status) {
        _status.value = newStatus
    }
}

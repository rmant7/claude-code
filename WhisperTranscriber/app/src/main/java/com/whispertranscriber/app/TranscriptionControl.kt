package com.whispertranscriber.app

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets the UI ask a running [TranscriptionService] batch to stop. whisper_full()/whisper_full_parallel()
 * are synchronous blocking native calls with no coroutine suspension points, so cancelling the
 * service's Job alone would only take effect between files, not during one — [activeTranscriber]
 * gives immediate access to call [Transcriber.cancel] (native abort_callback) on whatever file is
 * transcribing right now, while [stopRequested] stops the loop from starting the next file.
 */
object TranscriptionControl {
    val stopRequested = AtomicBoolean(false)

    @Volatile
    var activeTranscriber: Transcriber? = null

    fun reset() {
        stopRequested.set(false)
        activeTranscriber = null
    }

    fun requestStop() {
        stopRequested.set(true)
        activeTranscriber?.cancel()
    }
}

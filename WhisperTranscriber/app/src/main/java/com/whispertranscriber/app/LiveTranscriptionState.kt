package com.whispertranscriber.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process channel between [LiveTranscriptionService] and the dictation UI.
 *
 * Live transcription has two kinds of text at once, and they are kept apart deliberately:
 * [finalizedText] is everything from utterances the speaker has already finished (settled, never
 * revised), while [partialText] is the current in-progress utterance, which is re-transcribed from
 * scratch every refresh and therefore *changes* as more of the sentence is heard. Merging them into
 * one string would make the tail of the transcript visibly rewrite itself.
 */
object LiveTranscriptionState {

    data class Status(
        val isRecording: Boolean = false,
        val isLoadingModel: Boolean = false,
        val finalizedText: String = "",
        val partialText: String = "",
        val error: String? = null
    ) {
        /** What to show as the whole transcript right now. */
        val displayText: String
            get() = listOf(finalizedText, partialText).filter { it.isNotBlank() }.joinToString(" ")
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun update(transform: (Status) -> Status) {
        _status.value = transform(_status.value)
    }

    fun reset() {
        _status.value = Status()
    }
}

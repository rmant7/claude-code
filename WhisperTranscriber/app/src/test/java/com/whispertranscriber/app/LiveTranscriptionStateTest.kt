package com.whispertranscriber.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The split between settled and in-progress text is the part of live transcription most likely to
 * go subtly wrong, since the partial half is replaced wholesale on every refresh.
 */
class LiveTranscriptionStateTest {

    @After
    fun tearDown() = LiveTranscriptionState.reset()

    @Test
    fun `display text joins finalized and partial without stray spacing`() {
        LiveTranscriptionState.reset()
        assertEquals("", LiveTranscriptionState.status.value.displayText)

        LiveTranscriptionState.update { it.copy(partialText = "hello") }
        assertEquals("hello", LiveTranscriptionState.status.value.displayText)

        LiveTranscriptionState.update { it.copy(finalizedText = "hello there", partialText = "") }
        assertEquals("hello there", LiveTranscriptionState.status.value.displayText)

        LiveTranscriptionState.update { it.copy(partialText = "and more") }
        assertEquals("hello there and more", LiveTranscriptionState.status.value.displayText)
    }

    @Test
    fun `partial text is replaced rather than appended, so revisions do not duplicate`() {
        LiveTranscriptionState.reset()
        LiveTranscriptionState.update { it.copy(partialText = "the qui") }
        LiveTranscriptionState.update { it.copy(partialText = "the quick brown") }
        assertEquals("the quick brown", LiveTranscriptionState.status.value.displayText)
    }

    @Test
    fun `reset clears everything including a reported error`() {
        LiveTranscriptionState.update {
            it.copy(isRecording = true, finalizedText = "x", partialText = "y", error = "boom")
        }
        LiveTranscriptionState.reset()
        val status = LiveTranscriptionState.status.value
        assertEquals("", status.displayText)
        assertEquals(null, status.error)
        assertEquals(false, status.isRecording)
    }
}

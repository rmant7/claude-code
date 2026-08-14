package com.whispertranscriber.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * The energy measure is what decides when an utterance has ended, so it has to separate speech-like
 * signal from room tone by a wide margin — a threshold sitting between them is only meaningful if
 * the two are actually orders of magnitude apart.
 */
class MicrophoneRecorderTest {

    @Test
    fun `silence measures as effectively zero energy`() {
        assertEquals(0f, MicrophoneRecorder.meanSquare(FloatArray(1024)), 1e-9f)
    }

    @Test
    fun `empty input does not divide by zero`() {
        assertEquals(0f, MicrophoneRecorder.meanSquare(FloatArray(0)), 1e-9f)
    }

    @Test
    fun `a full-scale tone measures near one half, matching mean-square of a sine`() {
        val tone = FloatArray(16000) { sin(2.0 * Math.PI * 440.0 * it / 16000.0).toFloat() }
        assertEquals(0.5f, MicrophoneRecorder.meanSquare(tone), 0.01f)
    }

    @Test
    fun `speech-level signal sits far above quiet room tone`() {
        val speechLike = FloatArray(16000) { sin(2.0 * Math.PI * 200.0 * it / 16000.0).toFloat() * 0.2f }
        val roomTone = FloatArray(16000) { sin(2.0 * Math.PI * 200.0 * it / 16000.0).toFloat() * 0.002f }

        val speechEnergy = MicrophoneRecorder.meanSquare(speechLike)
        val quietEnergy = MicrophoneRecorder.meanSquare(roomTone)

        assertTrue("speech energy $speechEnergy should dwarf room tone $quietEnergy",
            speechEnergy > quietEnergy * 1000)
    }
}

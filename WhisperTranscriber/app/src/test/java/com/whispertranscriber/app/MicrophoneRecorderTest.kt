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

    // These specifically regression-test the shipped bug: a *fixed* absolute energy threshold
    // (0.0004) never triggered on real speech, because real mic input can sit well under any single
    // constant depending on device gain and distance from the mic. isVoiced() must catch quiet
    // speech as long as it is meaningfully louder than *this room's own* measured noise floor.

    @Test
    fun `quiet speech in a quiet room is still detected relative to that room's own floor`() {
        // Absolute level far below the old fixed 0.0004 threshold that shipped broken.
        val quietRoomFloor = 0.0000001f
        val quietSpeechEnergy = 0.00001f // ~100x the floor, but still under the old fixed threshold
        assertTrue(MicrophoneRecorder.isVoiced(quietSpeechEnergy, quietRoomFloor))
    }

    @Test
    fun `energy at or below the noise floor is not voiced`() {
        assertTrue(!MicrophoneRecorder.isVoiced(0.001f, noiseFloor = 0.001f))
        assertTrue(!MicrophoneRecorder.isVoiced(0.0005f, noiseFloor = 0.001f))
    }

    @Test
    fun `a near-zero noise floor cannot be trivially triggered by tiny fluctuations`() {
        // Without a floor-under-the-floor, multiplying an ~0 noiseFloor by any factor is still ~0,
        // so the faintest fluctuation would incorrectly count as speech in a near-silent room.
        assertTrue(!MicrophoneRecorder.isVoiced(energy = 1e-9f, noiseFloor = 0f))
    }

    @Test
    fun `loud speech against a loud room is still voiced`() {
        assertTrue(MicrophoneRecorder.isVoiced(energy = 0.5f, noiseFloor = 0.01f))
    }
}

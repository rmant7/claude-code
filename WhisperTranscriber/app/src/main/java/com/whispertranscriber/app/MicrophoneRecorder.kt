package com.whispertranscriber.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException

/**
 * Captures microphone audio as 16 kHz mono float PCM — the format whisper.cpp wants — so live
 * dictation skips the decode path used for files entirely and feeds inference directly.
 *
 * The caller is expected to drain [read] in a tight loop on its own thread: AudioRecord holds a
 * fixed-size ring buffer, and if it isn't emptied faster than the mic fills it the oldest audio is
 * silently overwritten. That is why the buffer is requested several times larger than the reported
 * minimum — inference runs on another thread and can stall the reader briefly, and the slack is
 * what keeps those stalls from punching holes in the recording.
 */
class MicrophoneRecorder {

    private var record: AudioRecord? = null

    val isRecording: Boolean get() = record?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before constructing this
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            throw IOException("This device cannot record 16 kHz mono audio")
        }

        val audioRecord = AudioRecord(
            // VOICE_RECOGNITION asks the platform for the ASR-friendly path: no aggressive AGC or
            // noise suppression tuned for phone calls, which tend to hurt recognition accuracy.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            ENCODING,
            minBuffer * BUFFER_SLACK_FACTOR
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IOException("Could not open the microphone")
        }
        audioRecord.startRecording()
        record = audioRecord
    }

    /**
     * Blocks until some audio is available, returning it as float samples in -1..1, or null once
     * recording has stopped.
     */
    fun read(): FloatArray? {
        val audioRecord = record ?: return null
        val shorts = ShortArray(READ_SAMPLES)
        val count = audioRecord.read(shorts, 0, shorts.size)
        if (count <= 0) return if (count == 0) FloatArray(0) else null
        return FloatArray(count) { shorts[it] / 32768.0f }
    }

    fun stop() {
        val audioRecord = record ?: return
        record = null
        runCatching { if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop() }
        audioRecord.release()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SLACK_FACTOR = 8
        private const val READ_SAMPLES = 4096

        /**
         * Mean-square energy of a block, used as a crude voice-activity signal: a run of blocks
         * below the ambient noise floor marks the end of an utterance. Deliberately simple — a real
         * VAD would be better, but energy is enough to decide "has the speaker paused", and it
         * costs nothing next to inference.
         */
        fun meanSquare(samples: FloatArray): Float {
            if (samples.isEmpty()) return 0f
            var sum = 0.0
            for (s in samples) sum += s.toDouble() * s
            return (sum / samples.size).toFloat()
        }

        /**
         * Whether [energy] counts as voice relative to [noiseFloor] (a running estimate of ambient
         * room noise, updated by the caller during quiet blocks). A *fixed* absolute energy
         * threshold was tried first and never actually worked: real speech level depends heavily on
         * mic gain, distance from the phone, and the room, so any single constant is either too
         * high (real speech never trips it — silently discarding all audio, which is exactly what
         * happened) or too low (constant false triggers in a noisy room). Comparing against a
         * *measured* noise floor adapts to whatever the actual device and environment are, instead
         * of guessing a number with no microphone to test it against.
         */
        internal fun isVoiced(energy: Float, noiseFloor: Float): Boolean {
            val floor = noiseFloor.coerceAtLeast(MIN_NOISE_FLOOR)
            return energy > floor * VOICE_ENERGY_MULTIPLIER
        }

        /** Smoothing for the running noise-floor estimate; only updated on blocks judged silent. */
        const val NOISE_FLOOR_EMA_ALPHA = 0.2f

        // How many times louder than ambient noise a block must be to count as speech. Kept low
        // enough to catch soft speech, high enough that normal mic self-noise/room hum doesn't
        // spuriously trigger it.
        private const val VOICE_ENERGY_MULTIPLIER = 4f

        // Floor under the floor: without this, a near-silent room (noiseFloor close to 0) would let
        // the tiniest fluctuation multiply up to "voiced", since anything times a very small number
        // is still very small.
        private const val MIN_NOISE_FLOOR = 1e-7f
    }
}

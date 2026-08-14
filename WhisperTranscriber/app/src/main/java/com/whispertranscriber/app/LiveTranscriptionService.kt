package com.whispertranscriber.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Live dictation: records the microphone and transcribes it as the user speaks.
 *
 * Runs as a foreground service (type `microphone`) rather than inside the Activity, for the same
 * reason batch transcription does — so a dictation isn't silently cut off when the screen locks or
 * the app is backgrounded mid-sentence.
 *
 * The loop has two threads because they run at completely different speeds. A reader coroutine
 * drains the mic continuously (it must never block, or audio is lost), appending to a shared
 * utterance buffer. A worker coroutine re-transcribes that buffer every [REFRESH_MS] to produce
 * updated partial text, and finalizes the utterance when the speaker pauses.
 *
 * Re-transcribing the whole current utterance on each refresh — rather than only the newest audio —
 * is what lets whisper revise earlier words once it has heard the rest of the sentence, which is
 * where most of live transcription's accuracy comes from. It is affordable because the buffer is
 * capped at [MAX_UTTERANCE_SECONDS], whisper's own analysis window.
 */
class LiveTranscriptionService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private val recorder = MicrophoneRecorder()
    private val bufferLock = Any()
    private var utterance = FloatArray(0)
    private var silentSamples = 0
    private var voicedSamples = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (LiveTranscriptionState.status.value.isRecording) return START_NOT_STICKY

        val model = WhisperModel.ALL.firstOrNull { it.id == intent?.getStringExtra(EXTRA_MODEL_ID) }
        val language = intent?.getStringExtra(EXTRA_LANGUAGE) ?: WhisperLanguage.AUTO_CODE
        if (model == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        LiveTranscriptionState.reset()
        LiveTranscriptionState.update { it.copy(isLoadingModel = true, isRecording = true) }

        serviceScope.launch {
            try {
                run(model, language)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LiveTranscriptionState.update {
                    it.copy(error = "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
                }
            } finally {
                LiveTranscriptionState.update { it.copy(isRecording = false, isLoadingModel = false) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        recorder.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun run(model: WhisperModel, language: String) {
        val modelFile = ModelManager(applicationContext).modelFile(model)

        Transcriber(modelFile.absolutePath).use { transcriber ->
            LiveTranscriptionState.update { it.copy(isLoadingModel = false) }
            recorder.start()

            val reader = serviceScope.launch(Dispatchers.IO) {
                while (isRunning()) {
                    val block = recorder.read() ?: break
                    if (block.isEmpty()) continue
                    appendBlock(block)
                }
            }

            try {
                var lastRefresh = 0L
                while (isRunning()) {
                    val now = System.currentTimeMillis()
                    val snapshot: FloatArray
                    val shouldFinalize: Boolean
                    synchronized(bufferLock) {
                        snapshot = utterance.copyOf()
                        shouldFinalize = utterance.size >= MAX_UTTERANCE_SECONDS * MicrophoneRecorder.SAMPLE_RATE ||
                            (voicedSamples > 0 && silentSamples >= SILENCE_SAMPLES_TO_FINALIZE)
                    }

                    val dueForRefresh = now - lastRefresh >= REFRESH_MS
                    if (snapshot.size >= MIN_SAMPLES_TO_TRANSCRIBE && (dueForRefresh || shouldFinalize)) {
                        lastRefresh = now
                        val text = transcribe(transcriber, snapshot, language)
                        if (shouldFinalize) {
                            LiveTranscriptionState.update { state ->
                                state.copy(
                                    finalizedText = listOf(state.finalizedText, text)
                                        .filter { it.isNotBlank() }.joinToString(" "),
                                    partialText = ""
                                )
                            }
                            resetUtterance()
                        } else {
                            LiveTranscriptionState.update { it.copy(partialText = text) }
                        }
                    } else if (shouldFinalize) {
                        // Nothing but silence accumulated — drop it rather than paying for inference.
                        resetUtterance()
                    } else {
                        kotlinx.coroutines.delay(POLL_MS)
                    }
                }

                // Whatever is still buffered when recording stops is a real (if unfinished)
                // utterance, so transcribe it once more rather than discarding it.
                val tail = synchronized(bufferLock) { utterance.copyOf() }
                if (tail.size >= MIN_SAMPLES_TO_TRANSCRIBE) {
                    val text = transcribe(transcriber, tail, language)
                    LiveTranscriptionState.update { state ->
                        state.copy(
                            finalizedText = listOf(state.finalizedText, text)
                                .filter { it.isNotBlank() }.joinToString(" "),
                            partialText = ""
                        )
                    }
                }
            } finally {
                reader.cancel()
                recorder.stop()
            }
        }
    }

    private fun transcribe(transcriber: Transcriber, samples: FloatArray, language: String): String =
        transcriber.transcribe(samples, language = language).trim()

    private fun isRunning(): Boolean =
        LiveTranscriptionState.status.value.isRecording && recorder.isRecording

    private fun appendBlock(block: FloatArray) {
        val energy = MicrophoneRecorder.meanSquare(block)
        synchronized(bufferLock) {
            // Leading silence is skipped entirely: buffering it would push real speech out of the
            // capped window and make whisper transcribe mostly-empty audio.
            if (voicedSamples == 0 && energy < SILENCE_ENERGY) return

            val grown = FloatArray(utterance.size + block.size)
            utterance.copyInto(grown)
            block.copyInto(grown, utterance.size)
            utterance = grown

            if (energy < SILENCE_ENERGY) {
                silentSamples += block.size
            } else {
                silentSamples = 0
                voicedSamples += block.size
            }
        }
    }

    private fun resetUtterance() {
        synchronized(bufferLock) {
            utterance = FloatArray(0)
            silentSamples = 0
            voicedSamples = 0
        }
    }

    private fun stopEverything() {
        LiveTranscriptionState.update { it.copy(isRecording = false) }
        recorder.stop()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.live_notification))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.live_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val EXTRA_MODEL_ID = "model_id"
        private const val EXTRA_LANGUAGE = "language"
        private const val ACTION_STOP = "stop"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "live_transcription"

        /** How often the in-progress utterance is re-transcribed to refresh the partial text. */
        private const val REFRESH_MS = 2_000L
        private const val POLL_MS = 100L

        /** Whisper's analysis window; past this an utterance is finalized whether or not it paused. */
        private const val MAX_UTTERANCE_SECONDS = 25

        /** Below ~0.4s whisper has too little context to produce anything useful. */
        private const val MIN_SAMPLES_TO_TRANSCRIBE = MicrophoneRecorder.SAMPLE_RATE * 2 / 5

        /** Roughly 0.8s of quiet ends an utterance — long enough not to cut mid-sentence pauses. */
        private const val SILENCE_SAMPLES_TO_FINALIZE = MicrophoneRecorder.SAMPLE_RATE * 4 / 5

        /**
         * Mean-square energy below which a block counts as silence. Empirical: normal speech sits
         * orders of magnitude above this, while room tone and mic self-noise sit below it.
         */
        private const val SILENCE_ENERGY = 0.0004f

        fun start(context: Context, model: WhisperModel, language: String) {
            val intent = Intent(context, LiveTranscriptionService::class.java)
                .putExtra(EXTRA_MODEL_ID, model.id)
                .putExtra(EXTRA_LANGUAGE, language)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LiveTranscriptionService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

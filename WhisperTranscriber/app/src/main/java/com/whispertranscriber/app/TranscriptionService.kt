package com.whispertranscriber.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs a whole transcription batch as a foreground service. A batch tied only to the Activity's
 * lifecycle was getting killed by the OS the moment the app was backgrounded for a while (Doze,
 * App Standby, plain low-memory process eviction) — fine for a quick single file, but fatal for a
 * multi-hundred-file overnight folder run, which would simply vanish with nothing to show for it.
 * Each finished transcript is also written to disk immediately as its own file, so even in the
 * worst case (the process is killed anyway) whatever finished before that point isn't lost.
 */
class TranscriptionService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private lateinit var modelManager: ModelManager

    override fun onCreate() {
        super.onCreate()
        modelManager = ModelManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (TranscriptionState.status.value is TranscriptionState.Status.Running) {
            // A batch is already in flight; ignore a stray second start instead of running two at once.
            return START_NOT_STICKY
        }

        val model = WhisperModel.ALL.firstOrNull { it.id == intent?.getStringExtra(EXTRA_MODEL_ID) }
        val remoteUrl = intent?.getStringExtra(EXTRA_REMOTE_URL)
        val uris = readUriListExtra(intent, EXTRA_URIS)
        val names = intent?.getStringArrayListExtra(EXTRA_NAMES).orEmpty()

        val sources: List<MediaSource> = when {
            model == null -> emptyList()
            remoteUrl != null -> listOf(MediaSource.RemoteUrl(remoteUrl))
            uris.isNotEmpty() -> uris.indices.map { i -> MediaSource.LocalFile(uris[i], names.getOrElse(i) { "file" }) }
            else -> emptyList()
        }

        if (model == null || sources.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(0, sources.size, sources.first().displayName))

        serviceScope.launch {
            try {
                runBatch(model, sources)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun runBatch(model: WhisperModel, sources: List<MediaSource>) = coroutineScope {
        val modelFile = modelManager.modelFile(model)
        val results = sources.map { TranscriptionResult(it) }
        val total = results.size
        val outputDir = File(
            getExternalFilesDir(null),
            "transcripts/" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        )
        outputDir.mkdirs()

        publish(results, 0, total)
        updateLoadingModelNotification()
        TranscriptionControl.reset()

        try {
            Transcriber(modelFile.absolutePath).use { transcriber ->
                TranscriptionControl.activeTranscriber = transcriber
                // One-file lookahead: decode (fast, MediaCodec) overlaps with transcribing
                // (slow, whisper.cpp inference) so decode time is hidden for every file but the first.
                var pendingDecode: Deferred<Result<FloatArray>>? = decodeAsync(this, results, 0)

                for ((index, result) in results.withIndex()) {
                    if (TranscriptionControl.stopRequested.get()) {
                        markRemainingCancelled(results, index)
                        publish(results, index, total)
                        break
                    }

                    updateNotification(index, total, result.source.displayName)

                    val decodeResult = pendingDecode?.await()
                    pendingDecode = decodeAsync(this, results, index + 1)

                    try {
                        val samples = checkNotNull(decodeResult).getOrThrow()

                        result.status = TranscriptionResult.Status.TRANSCRIBING
                        result.progressPercent = 0
                        result.text = ""
                        publish(results, index, total)

                        // The return value is ignored: it's rebuilt from the final segment list,
                        // which comes back empty when abort_callback interrupts the call, wiping
                        // out the partial transcript. result.text already has the same content
                        // (and survives an abort) because onSegment appends to it incrementally.
                        transcriber.transcribe(
                            samples,
                            onProgress = { percent ->
                                result.progressPercent = percent
                                publish(results, index, total)
                            },
                            onSegment = { segmentText ->
                                // whisper_full_parallel() can invoke this from more than one
                                // native worker thread at once for the same result (each handling
                                // a different chunk of the same file), so the append needs a lock
                                // — plain += here would be a lost-update race.
                                synchronized(result) { result.text += segmentText }
                                publish(results, index, total)
                            }
                        )
                        result.text = result.text.trim()
                        result.status = if (TranscriptionControl.stopRequested.get()) {
                            TranscriptionResult.Status.CANCELLED
                        } else {
                            TranscriptionResult.Status.DONE
                        }
                        result.progressPercent = 0
                        publish(results, index, total)
                        writeTranscriptFile(outputDir, index, result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        result.error = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                        result.status = TranscriptionResult.Status.ERROR
                        publish(results, index, total)
                    }

                    if (TranscriptionControl.stopRequested.get()) {
                        markRemainingCancelled(results, index + 1)
                        publish(results, index, total)
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The model itself failed to load (e.g. a corrupted download) rather than a per-file
            // decode/transcribe error, which is already handled above.
            modelManager.deleteModel(model)
        } finally {
            TranscriptionControl.reset()
        }

        writeCombinedTranscriptFile(outputDir, results)
        TranscriptionState.update(TranscriptionState.Status.Finished(results.map { it.copy() }, outputDir.absolutePath))
    }

    private fun markRemainingCancelled(results: List<TranscriptionResult>, fromIndex: Int) {
        for (i in fromIndex until results.size) {
            if (results[i].status == TranscriptionResult.Status.PENDING) {
                results[i].status = TranscriptionResult.Status.CANCELLED
            }
        }
    }

    private fun publish(results: List<TranscriptionResult>, currentIndex: Int, total: Int) {
        // A snapshot via .copy() matters here: TranscriptionResult's fields are mutated in place
        // on the same shared objects, so a plain toList() would just wrap references to objects
        // that keep changing underneath it — the "old" and "new" states StateFlow compares would
        // end up structurally identical (same, already-mutated objects on both sides), and it
        // would silently stop emitting live progress/text updates after the first one.
        TranscriptionState.update(TranscriptionState.Status.Running(results.map { it.copy() }, currentIndex, total))
    }

    private fun decodeAsync(scope: CoroutineScope, results: List<TranscriptionResult>, index: Int): Deferred<Result<FloatArray>>? {
        if (index !in results.indices) return null
        val result = results[index]
        return scope.async(Dispatchers.Default) { runCatching { decodeSource(result) } }
    }

    private fun decodeSource(result: TranscriptionResult): FloatArray {
        return when (val source = result.source) {
            is MediaSource.LocalFile -> {
                result.status = TranscriptionResult.Status.DECODING
                AudioDecoder.decodeToPcm16k(applicationContext, source.uri) { percent ->
                    result.progressPercent = percent
                }
            }

            is MediaSource.RemoteUrl -> {
                result.status = TranscriptionResult.Status.DOWNLOADING
                val file = UrlDownloader.download(applicationContext, source.url)
                try {
                    result.status = TranscriptionResult.Status.DECODING
                    AudioDecoder.decodeFromPath(file.absolutePath) { percent ->
                        result.progressPercent = percent
                    }
                } finally {
                    file.delete()
                }
            }
        }
    }

    private fun writeTranscriptFile(outputDir: File, index: Int, result: TranscriptionResult) {
        val safeName = result.source.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val file = File(outputDir, "%04d_%s.txt".format(index + 1, safeName))
        runCatching { FileOutputStream(file).use { it.write(result.text.toByteArray()) } }
    }

    private fun writeCombinedTranscriptFile(outputDir: File, results: List<TranscriptionResult>) {
        val combined = results.joinToString("\n\n") { r ->
            val body = when (r.status) {
                TranscriptionResult.Status.DONE, TranscriptionResult.Status.CANCELLED -> r.text
                else -> "[${r.status}] ${r.error ?: ""}"
            }
            "${r.source.displayName}:\n$body"
        }
        runCatching { FileOutputStream(File(outputDir, "all_transcripts.txt")).use { it.write(combined.toByteArray()) } }
    }

    private fun readUriListExtra(intent: Intent?, key: String): List<Uri> {
        if (intent == null) return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(key).orEmpty()
        }
    }

    private fun buildNotification(index: Int, total: Int, currentName: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.transcribing_notification, index + 1, total))
            .setContentText(currentName)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(index: Int, total: Int, currentName: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(index, total, currentName))
    }

    /** Loading a large model can itself take a while; make that visible instead of looking stuck. */
    private fun updateLoadingModelNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.loading_model_notification))
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.transcription_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val EXTRA_MODEL_ID = "model_id"
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_REMOTE_URL = "remote_url"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "transcription"

        fun startForFiles(context: Context, model: WhisperModel, sources: List<MediaSource.LocalFile>) {
            val intent = Intent(context, TranscriptionService::class.java)
                .putExtra(EXTRA_MODEL_ID, model.id)
                .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(sources.map { it.uri }))
                .putStringArrayListExtra(EXTRA_NAMES, ArrayList(sources.map { it.displayName }))
            ContextCompat.startForegroundService(context, intent)
        }

        fun startForUrl(context: Context, model: WhisperModel, url: String) {
            val intent = Intent(context, TranscriptionService::class.java)
                .putExtra(EXTRA_MODEL_ID, model.id)
                .putExtra(EXTRA_REMOTE_URL, url)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

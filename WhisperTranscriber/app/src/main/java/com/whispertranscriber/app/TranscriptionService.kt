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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

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
        val language = intent?.getStringExtra(EXTRA_LANGUAGE) ?: WhisperLanguage.AUTO_CODE
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
                runBatch(model, sources, language)
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

    private suspend fun runBatch(
        model: WhisperModel,
        sources: List<MediaSource>,
        language: String
    ) = coroutineScope {
        val modelFile = modelManager.modelFile(model)
        val results = sources.map { TranscriptionResult(it) }
        val total = results.size
        val outputDir = File(
            getExternalFilesDir(null),
            "transcripts/" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        )
        outputDir.mkdirs()

        publish(results, 0, total, loadingModel = true)
        updateLoadingModelNotification()
        TranscriptionControl.reset()

        try {
            Transcriber(modelFile.absolutePath).use { transcriber ->
                TranscriptionControl.activeTranscriber = transcriber
                publish(results, 0, total)

                for ((index, result) in results.withIndex()) {
                    if (TranscriptionControl.stopRequested.get()) {
                        markRemainingCancelled(results, index)
                        publish(results, index, total)
                        break
                    }

                    updateNotification(index, total, result.source.displayName)

                    try {
                        result.status = TranscriptionResult.Status.DECODING
                        result.progressPercent = 0
                        result.text = ""
                        publish(results, index, total)

                        streamTranscribe(this, transcriber, result, language) {
                            publish(results, index, total)
                        }

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

    private fun publish(results: List<TranscriptionResult>, currentIndex: Int, total: Int, loadingModel: Boolean = false) {
        // A snapshot via .copy() matters here: TranscriptionResult's fields are mutated in place
        // on the same shared objects, so a plain toList() would just wrap references to objects
        // that keep changing underneath it — the "old" and "new" states StateFlow compares would
        // end up structurally identical (same, already-mutated objects on both sides), and it
        // would silently stop emitting live progress/text updates after the first one.
        TranscriptionState.update(
            TranscriptionState.Status.Running(results.map { it.copy() }, currentIndex, total, loadingModel)
        )
    }

    /**
     * Decodes and transcribes one source as a two-stage pipeline: a producer coroutine decodes the
     * audio into ~30s PCM chunks while this coroutine transcribes the chunks already decoded. The
     * queue between them is deliberately tiny — it exists to keep the decoder one step ahead of
     * inference (which is far slower), not to buffer a whole file's audio in RAM, and its
     * bounded size is what applies backpressure so a fast decoder can't run away and OOM the batch.
     *
     * Transcript text lands in [result] incrementally as each chunk finishes, so text starts
     * appearing seconds in rather than only after the entire file has been decoded.
     */
    private suspend fun streamTranscribe(
        scope: CoroutineScope,
        transcriber: Transcriber,
        result: TranscriptionResult,
        language: String,
        onUpdate: () -> Unit
    ) {
        val queue = ArrayBlockingQueue<Any>(CHUNK_QUEUE_CAPACITY)
        val endOfStream = Any()

        val producer = scope.launch(Dispatchers.IO) {
            try {
                decodeChunks(result) { chunk ->
                    // offer-with-timeout rather than a blocking put: if the consumer stops early
                    // (Stop pressed, or an error), a blocking put would strand this thread forever
                    // holding the decoder open.
                    while (!queue.offer(chunk, 200, TimeUnit.MILLISECONDS)) {
                        if (TranscriptionControl.stopRequested.get()) throw CancellationException("stopped")
                    }
                    onUpdate()
                }
                queue.put(endOfStream)
            } catch (e: CancellationException) {
                queue.offer(endOfStream)
            } catch (e: Throwable) {
                queue.offer(e)
            }
        }

        try {
            while (true) {
                val item = queue.poll(200, TimeUnit.MILLISECONDS)
                    ?: if (TranscriptionControl.stopRequested.get()) break else continue

                when {
                    item === endOfStream -> break
                    item is Throwable -> throw item
                    item is FloatArray -> {
                        result.status = TranscriptionResult.Status.TRANSCRIBING
                        onUpdate()
                        // The return value is ignored: it is rebuilt from the final segment list,
                        // which comes back empty when abort_callback interrupts the call, wiping
                        // out the partial transcript. result.text already holds the same content
                        // (and survives an abort) because onSegment appends to it incrementally.
                        transcriber.transcribe(
                            item,
                            language = language,
                            onSegment = { segmentText ->
                                // whisper_full_parallel() can invoke this from more than one native
                                // worker thread at once for the same result, so the append needs a
                                // lock — a plain += here would be a lost-update race.
                                synchronized(result) { result.text += segmentText }
                                onUpdate()
                            }
                        )
                    }
                }

                if (TranscriptionControl.stopRequested.get()) break
            }
        } finally {
            producer.cancel()
        }
    }

    /**
     * Feeds decoded PCM chunks to [onChunk]. A remote URL is handed straight to MediaExtractor,
     * which fetches it progressively — so transcription starts while the download is still running,
     * instead of after it. Only if that fails outright (a server that won't serve range requests,
     * say) does it fall back to downloading the whole file first.
     */
    private fun decodeChunks(result: TranscriptionResult, onChunk: (FloatArray) -> Unit) {
        val onProgress: (Int) -> Unit = { percent -> result.progressPercent = percent }

        when (val source = result.source) {
            is MediaSource.LocalFile ->
                AudioDecoder.streamFromUri(applicationContext, source.uri, onProgress = onProgress, onChunk = onChunk)

            is MediaSource.RemoteUrl -> {
                // Checked before opening anything: a player-page link can't work as a media source
                // no matter which path is taken, and failing here yields a message that says why.
                UrlDownloader.rejectionReason(source.url)?.let { throw IOException(it) }

                var produced = false
                try {
                    AudioDecoder.streamFromPathOrUrl(source.url, onProgress = onProgress) {
                        produced = true
                        onChunk(it)
                    }
                } catch (e: IOException) {
                    // Retrying by downloading first is only safe while nothing has been emitted
                    // yet; past that point the transcript would gain duplicated audio.
                    if (produced) throw e
                    result.status = TranscriptionResult.Status.DOWNLOADING
                    val file = UrlDownloader.download(applicationContext, source.url)
                    try {
                        AudioDecoder.streamFromPathOrUrl(file.absolutePath, onProgress = onProgress, onChunk = onChunk)
                    } finally {
                        file.delete()
                    }
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
        private const val EXTRA_LANGUAGE = "language"
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_REMOTE_URL = "remote_url"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "transcription"

        /**
         * Two chunks in flight is enough to keep the decoder a step ahead of inference without
         * letting decoded audio pile up in memory — inference is far slower than decoding, so a
         * deeper queue would only ever be full.
         */
        private const val CHUNK_QUEUE_CAPACITY = 2

        fun startForFiles(
            context: Context,
            model: WhisperModel,
            sources: List<MediaSource.LocalFile>,
            language: String = WhisperLanguage.AUTO_CODE
        ) {
            val intent = Intent(context, TranscriptionService::class.java)
                .putExtra(EXTRA_MODEL_ID, model.id)
                .putExtra(EXTRA_LANGUAGE, language)
                .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(sources.map { it.uri }))
                .putStringArrayListExtra(EXTRA_NAMES, ArrayList(sources.map { it.displayName }))
            ContextCompat.startForegroundService(context, intent)
        }

        fun startForUrl(
            context: Context,
            model: WhisperModel,
            url: String,
            language: String = WhisperLanguage.AUTO_CODE
        ) {
            val intent = Intent(context, TranscriptionService::class.java)
                .putExtra(EXTRA_MODEL_ID, model.id)
                .putExtra(EXTRA_LANGUAGE, language)
                .putExtra(EXTRA_REMOTE_URL, url)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

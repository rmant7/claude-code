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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Downloads a Whisper model as a foreground service so the transfer keeps running — and keeps
 * network access despite Doze/App Standby — while the app is backgrounded or the screen is off.
 * Large models (500 MB-1.6 GB) routinely take many minutes on a mobile connection; a download
 * driven from an Activity-scoped coroutine was getting starved the moment the screen locked.
 */
class ModelDownloadService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var modelManager: ModelManager

    override fun onCreate() {
        super.onCreate()
        modelManager = ModelManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = WhisperModel.ALL.firstOrNull { it.id == intent?.getStringExtra(EXTRA_MODEL_ID) }
        if (model == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(model, 0))

        serviceScope.launch {
            try {
                var lastPercent = -1
                modelManager.downloadModel(model) { progress ->
                    val percent = if (progress.bytesTotal > 0) {
                        ((progress.bytesDownloaded * 100) / progress.bytesTotal).toInt()
                    } else {
                        0
                    }
                    if (percent != lastPercent) {
                        lastPercent = percent
                        ModelDownloadState.update(ModelDownloadState.Status.Downloading(model.id, percent))
                        updateNotification(model, percent)
                    }
                }
                ModelDownloadState.update(ModelDownloadState.Status.Completed(model.id))
            } catch (e: Exception) {
                ModelDownloadState.update(
                    ModelDownloadState.Status.Failed(model.id, e.message ?: e.javaClass.simpleName)
                )
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

    private fun buildNotification(model: WhisperModel, percent: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.downloading_model_notification, model.displayName))
            .setContentText("$percent%")
            .setProgress(100, percent, false)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(model: WhisperModel, percent: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(model, percent))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXTRA_MODEL_ID = "model_id"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "model_download"

        fun start(context: Context, model: WhisperModel) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .putExtra(EXTRA_MODEL_ID, model.id)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

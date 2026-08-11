package com.whispertranscriber.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

class ModelManager(private val context: Context) {

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun modelsDir(): File = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun modelFile(model: WhisperModel): File = File(modelsDir(), model.fileName)

    fun isDownloaded(model: WhisperModel): Boolean {
        val file = modelFile(model)
        return file.exists() && file.length() > 1_000_000L
    }

    fun deleteModel(model: WhisperModel) {
        modelFile(model).delete()
    }

    fun enqueueDownload(model: WhisperModel): Long {
        val destination = modelFile(model)
        destination.delete()
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle(model.displayName)
            .setDescription("Downloading Whisper model")
            .setDestinationUri(Uri.fromFile(destination))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        return downloadManager.enqueue(request)
    }

    data class Progress(val bytesDownloaded: Long, val bytesTotal: Long, val status: Int)

    fun queryProgress(downloadId: Long): Progress? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return Progress(bytesDownloaded, bytesTotal, status)
        }
    }
}

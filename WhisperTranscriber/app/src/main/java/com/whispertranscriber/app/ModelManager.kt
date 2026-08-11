package com.whispertranscriber.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ModelManager(private val context: Context) {

    fun modelsDir(): File = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun modelFile(model: WhisperModel): File = File(modelsDir(), model.fileName)

    fun isDownloaded(model: WhisperModel): Boolean {
        val file = modelFile(model)
        return file.exists() && file.length() > 1_000_000L
    }

    fun deleteModel(model: WhisperModel) {
        modelFile(model).delete()
    }

    data class Progress(val bytesDownloaded: Long, val bytesTotal: Long)

    /**
     * Streams the model into a ".part" temp file and only promotes it to the final file once
     * the whole download has been verified complete. Android's system DownloadManager was found
     * to sometimes report success on a truncated file (observed on MIUI/Xiaomi devices), which
     * left behind a corrupt model that whisper.cpp couldn't load — this avoids that failure mode
     * by keeping the whole transfer, and the completeness check, under our own control.
     */
    fun downloadModel(model: WhisperModel, onProgress: (Progress) -> Unit) {
        val destination = modelFile(model)
        val tempFile = File(modelsDir(), "${model.fileName}.part")
        val connection = URL(model.url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000

        try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Server returned HTTP $responseCode")
            }

            val totalBytes = connection.contentLengthLong
            var bytesDownloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytesDownloaded += read
                        onProgress(Progress(bytesDownloaded, totalBytes))
                    }
                }
            }

            if (totalBytes > 0 && bytesDownloaded != totalBytes) {
                throw IOException("Download incomplete: got $bytesDownloaded of $totalBytes bytes")
            }

            destination.delete()
            if (!tempFile.renameTo(destination)) {
                throw IOException("Could not save the downloaded model")
            }
        } finally {
            tempFile.delete()
            connection.disconnect()
        }
    }
}

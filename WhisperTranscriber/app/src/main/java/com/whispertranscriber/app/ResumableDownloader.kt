package com.whispertranscriber.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resumable, retrying file download — extracted out of what was `ModelManager`'s private
 * implementation so the same connection-drop/resume/backoff handling isn't maintained twice once
 * LLM model downloads need it too, rather than copy-pasting it and letting the two drift apart.
 *
 * Streams into a ".part" temp file and only promotes it to the destination once the whole transfer
 * is verified complete. Resumes via HTTP `Range` requests from wherever the ".part" file left off
 * and retries with backoff, as long as each attempt makes forward progress — it only gives up after
 * [MAX_CONSECUTIVE_FAILURES] attempts in a row that transferred zero new bytes.
 */
object ResumableDownloader {

    data class Progress(val bytesDownloaded: Long, val bytesTotal: Long)

    fun download(url: String, destination: File, tempFile: File, onProgress: (Progress) -> Unit) {
        var consecutiveFailures = 0
        var backoffMs = INITIAL_BACKOFF_MS
        var lastError: IOException = IOException("Download did not start")

        while (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) {
            val sizeBeforeAttempt = if (tempFile.exists()) tempFile.length() else 0L
            try {
                downloadOnce(url, tempFile, onProgress)
                destination.delete()
                if (!tempFile.renameTo(destination)) {
                    throw IOException("Could not save the downloaded file")
                }
                return
            } catch (e: IOException) {
                lastError = e
                val madeProgress = tempFile.exists() && tempFile.length() > sizeBeforeAttempt
                consecutiveFailures = if (madeProgress) 0 else consecutiveFailures + 1
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) break
                try {
                    Thread.sleep(backoffMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Download cancelled", interrupted)
                }
                backoffMs = if (madeProgress) INITIAL_BACKOFF_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }

        tempFile.delete()
        throw lastError
    }

    /** One resumable attempt: continues an existing ".part" file via a Range request when possible. */
    private fun downloadOnce(url: String, tempFile: File, onProgress: (Progress) -> Unit) {
        val resumeFrom = if (tempFile.exists()) tempFile.length() else 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        if (resumeFrom > 0) {
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        }

        try {
            connection.connect()
            val responseCode = connection.responseCode

            val append: Boolean
            var bytesDownloaded: Long
            val totalBytes: Long

            when (responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    append = true
                    bytesDownloaded = resumeFrom
                    totalBytes = parseContentRangeTotal(connection.getHeaderField("Content-Range"))
                        ?: (resumeFrom + connection.contentLengthLong)
                }

                HttpURLConnection.HTTP_OK -> {
                    // Server ignored the Range request (or this is the first attempt) — start over.
                    append = false
                    bytesDownloaded = 0L
                    totalBytes = connection.contentLengthLong
                }

                416 -> { // Range Not Satisfiable: the ".part" file is stale/complete; restart clean.
                    tempFile.delete()
                    throw IOException("Requested range not satisfiable, restarting")
                }

                else -> throw IOException("Server returned HTTP $responseCode")
            }

            connection.inputStream.use { input ->
                FileOutputStream(tempFile, append).use { output ->
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
        } finally {
            connection.disconnect()
        }
    }

    private const val MAX_CONSECUTIVE_FAILURES = 5
    private const val INITIAL_BACKOFF_MS = 2_000L
    private const val MAX_BACKOFF_MS = 30_000L
}

/** Top-level (not a Context-requiring member) so it's easy to unit test in isolation. */
internal fun parseContentRangeTotal(headerValue: String?): Long? {
    // Expected format: "bytes 1234-5678/9999"
    val slashIndex = headerValue?.lastIndexOf('/') ?: return null
    if (slashIndex < 0) return null
    return headerValue.substring(slashIndex + 1).toLongOrNull()
}

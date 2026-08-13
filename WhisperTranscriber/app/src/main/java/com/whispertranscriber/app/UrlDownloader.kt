package com.whispertranscriber.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Streams a remote audio/video URL into a temp file so it can be handed to AudioDecoder. */
object UrlDownloader {

    fun download(context: Context, url: String): File {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000

        try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Server returned HTTP $responseCode")
            }

            val tempFile = File.createTempFile("remote_media", guessExtension(url, connection.contentType), context.cacheDir)
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            return tempFile
        } finally {
            connection.disconnect()
        }
    }

    // internal + no android.net.Uri dependency (plain string parsing instead) so this pure logic
    // is directly unit-testable on the JVM without needing a real Android runtime.
    internal fun guessExtension(url: String, contentType: String?): String {
        val lastSegment = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val dot = lastSegment.lastIndexOf('.')
        if (dot in 0 until lastSegment.length - 1) {
            return "." + lastSegment.substring(dot + 1)
        }
        return when {
            contentType?.contains("mp4") == true -> ".mp4"
            contentType?.contains("mpeg") == true -> ".mp3"
            contentType?.contains("wav") == true -> ".wav"
            contentType?.contains("ogg") == true -> ".ogg"
            contentType?.contains("webm") == true -> ".webm"
            else -> ".media"
        }
    }
}

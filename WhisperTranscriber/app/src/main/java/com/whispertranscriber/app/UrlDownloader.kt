package com.whispertranscriber.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Streams a remote audio/video URL into a temp file so it can be handed to AudioDecoder. */
object UrlDownloader {

    /**
     * Hosts that serve a *player page* rather than a media file. Pasting one of these produces HTML,
     * which MediaExtractor then rejects with the famously unhelpful "Failed to instantiate
     * extractor", so they're named explicitly to give the user something actionable instead.
     *
     * Turning such a link into a media stream means extracting it the way yt-dlp does — parsing the
     * page's player JavaScript and solving its signature cipher, which those sites change
     * frequently and their terms of service prohibit. That is deliberately not implemented here.
     */
    private val PLAYER_PAGE_HOSTS = listOf(
        "youtube.com", "youtu.be", "vimeo.com", "dailymotion.com",
        "tiktok.com", "instagram.com", "facebook.com", "twitter.com", "x.com",
    )

    /**
     * Returns a human-readable reason this URL cannot work as a direct media source, or null if it
     * looks usable. Pure string logic, kept free of Android types so it is unit-testable.
     */
    internal fun rejectionReason(url: String): String? {
        val host = url.substringAfter("://", url)
            .substringBefore('/')
            .substringBefore(':')
            .removePrefix("www.")
            .lowercase()
        val match = PLAYER_PAGE_HOSTS.firstOrNull { host == it || host.endsWith(".$it") }
        return if (match != null) {
            "$match links point at a video page, not at a media file, so they can't be transcribed " +
                "directly. Download the audio with another tool first, then pick the file here."
        } else {
            null
        }
    }

    fun download(context: Context, url: String): File {
        rejectionReason(url)?.let { throw IOException(it) }
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
            // A web page where a media file was expected: worth catching here, because otherwise
            // several MB of HTML get written to disk only for the decoder to reject them later
            // with a message that gives no hint as to what actually went wrong.
            val contentType = connection.contentType.orEmpty()
            if (contentType.startsWith("text/html")) {
                throw IOException(
                    "That link returned a web page, not a media file. It needs to point directly " +
                        "at the audio or video (a URL ending in .mp3, .m4a, .mp4, …)."
                )
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

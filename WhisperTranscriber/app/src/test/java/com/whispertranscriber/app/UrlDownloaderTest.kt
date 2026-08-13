package com.whispertranscriber.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlDownloaderTest {

    @Test
    fun `takes the extension from the URL path`() {
        assertEquals(".mp3", UrlDownloader.guessExtension("https://example.com/audio.mp3", null))
    }

    @Test
    fun `strips a query string before looking for the extension`() {
        assertEquals(".wav", UrlDownloader.guessExtension("https://example.com/clip.wav?token=abc&x=1", null))
    }

    @Test
    fun `strips a fragment before looking for the extension`() {
        assertEquals(".mp4", UrlDownloader.guessExtension("https://example.com/video.mp4#t=10", "video/mp4"))
    }

    @Test
    fun `falls back to content type when the path has no extension`() {
        assertEquals(".mp4", UrlDownloader.guessExtension("https://example.com/download", "video/mp4"))
        assertEquals(".mp3", UrlDownloader.guessExtension("https://example.com/download", "audio/mpeg"))
        assertEquals(".wav", UrlDownloader.guessExtension("https://example.com/download", "audio/wav"))
        assertEquals(".ogg", UrlDownloader.guessExtension("https://example.com/download", "audio/ogg"))
        assertEquals(".webm", UrlDownloader.guessExtension("https://example.com/download", "video/webm"))
    }

    @Test
    fun `falls back to a generic extension when nothing is known`() {
        assertEquals(".media", UrlDownloader.guessExtension("https://example.com/download", null))
        assertEquals(".media", UrlDownloader.guessExtension("https://example.com/download", "application/octet-stream"))
    }

    @Test
    fun `a trailing dot with nothing after it is not treated as an extension`() {
        assertEquals(".media", UrlDownloader.guessExtension("https://example.com/file.", null))
    }
}

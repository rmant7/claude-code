package com.whispertranscriber.app

import android.net.Uri

/** A single item queued for transcription: either a local/SAF file or a remote URL. */
sealed class MediaSource(val displayName: String) {
    class LocalFile(val uri: Uri, name: String) : MediaSource(name)
    class RemoteUrl(val url: String) : MediaSource(url.substringAfterLast('/').ifBlank { url })
}

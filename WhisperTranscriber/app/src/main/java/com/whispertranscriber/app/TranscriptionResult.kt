package com.whispertranscriber.app

data class TranscriptionResult(
    val source: MediaSource,
    var status: Status = Status.PENDING,
    var text: String = "",
    var error: String? = null
) {
    enum class Status { PENDING, DOWNLOADING, DECODING, TRANSCRIBING, DONE, ERROR }
}

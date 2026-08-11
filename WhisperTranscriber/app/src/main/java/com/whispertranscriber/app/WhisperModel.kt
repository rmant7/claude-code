package com.whispertranscriber.app

data class WhisperModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val approxSizeMb: Int
) {
    val url: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    companion object {
        val ALL = listOf(
            WhisperModel("tiny", "Tiny", "ggml-tiny.bin", 75),
            WhisperModel("base", "Base", "ggml-base.bin", 142),
            WhisperModel("small", "Small", "ggml-small.bin", 466),
            WhisperModel("medium", "Medium", "ggml-medium.bin", 1500),
            WhisperModel("large-v3-turbo", "Large v3 Turbo", "ggml-large-v3-turbo.bin", 1620),
        )
    }
}

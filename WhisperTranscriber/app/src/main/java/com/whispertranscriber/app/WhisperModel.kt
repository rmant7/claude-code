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
            WhisperModel("base-q5", "Base (quantized, faster)", "ggml-base-q5_1.bin", 57),
            WhisperModel("small", "Small", "ggml-small.bin", 466),
            WhisperModel("small-q5", "Small (quantized, faster)", "ggml-small-q5_1.bin", 190),
            WhisperModel("medium", "Medium", "ggml-medium.bin", 1500),
            WhisperModel("medium-q5", "Medium (quantized, faster)", "ggml-medium-q5_0.bin", 539),
            WhisperModel("large-v3-turbo", "Large v3 Turbo", "ggml-large-v3-turbo.bin", 1620),
            WhisperModel("large-v3-turbo-q5", "Large v3 Turbo (quantized, faster)", "ggml-large-v3-turbo-q5_0.bin", 574),
        )
    }
}

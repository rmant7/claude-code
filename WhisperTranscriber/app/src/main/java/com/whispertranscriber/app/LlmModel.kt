package com.whispertranscriber.app

enum class LlmProvider(val displayName: String) {
    QWEN("Qwen"),
    GEMMA("Gemma"),
    DEEPSEEK("DeepSeek"),
    GLM("GLM"),
}

/**
 * A model this app knows to look for on Hugging Face. Deliberately does *not* carry a filename or
 * byte size — those are resolved live by [HfFileResolver] at browse time, since a repo's exact
 * files change on a timescale of weeks (confirmed directly: the same model family moved through
 * three numbered releases within a few months of each other). [repoId] pins which model *family*
 * this catalog entry means, not which specific file.
 */
data class LlmModelSeed(
    val id: String,
    val provider: LlmProvider,
    val paramsLabel: String,
    val repoId: String,
)

object LlmModelCatalog {

    /**
     * A small seed list, not a permanent catalog: one comfortable-size and one lightweight pick per
     * provider, chosen for being established, single-file-friendly GGUF repos as of when this was
     * written. This *will* go stale as these repos are superseded — the plan is a background check
     * against Hugging Face search that surfaces a "newer model available" prompt when a seed is no
     * longer the current release for its family, rather than silently drifting out of date. Not
     * implemented yet; this list is the interim source of truth until it is.
     */
    val SEEDS = listOf(
        LlmModelSeed("qwen-mid", LlmProvider.QWEN, "9B", "unsloth/Qwen3.5-9B-GGUF"),
        LlmModelSeed("qwen-small", LlmProvider.QWEN, "0.8B", "unsloth/Qwen3.5-0.8B-GGUF"),
        LlmModelSeed("gemma-mid", LlmProvider.GEMMA, "4B", "google/gemma-3-4b-it-qat-q4_0-gguf"),
        LlmModelSeed("gemma-small", LlmProvider.GEMMA, "1B", "google/gemma-3-1b-it-qat-q4_0-gguf"),
        LlmModelSeed("deepseek-mid", LlmProvider.DEEPSEEK, "7B", "unsloth/DeepSeek-R1-Distill-Qwen-7B-GGUF"),
        LlmModelSeed("deepseek-small", LlmProvider.DEEPSEEK, "1.5B", "unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF"),
        LlmModelSeed("glm-mid", LlmProvider.GLM, "9B", "bartowski/glm-4-9b-chat-GGUF"),
    )
}

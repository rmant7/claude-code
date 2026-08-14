package com.whispertranscriber.app

import android.content.Context
import java.io.File

/** Retained so existing call sites (`ModelManager.Progress`) don't need to change. */
typealias ModelManagerProgress = ResumableDownloader.Progress

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

    fun downloadModel(model: WhisperModel, onProgress: (ModelManagerProgress) -> Unit) {
        val destination = modelFile(model)
        val tempFile = File(modelsDir(), "${model.fileName}.part")
        ResumableDownloader.download(model.url, destination, tempFile, onProgress)
    }
}

package com.whispertranscriber.app

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.whispertranscriber.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var modelManager: ModelManager

    private var selectedFileUri: Uri? = null

    private val selectedModel: WhisperModel
        get() = WhisperModel.ALL[binding.modelSpinner.selectedItemPosition]

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedFileUri = uri
            binding.selectedFileText.text = uri.lastPathSegment ?: uri.toString()
            updateTranscribeButtonState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelManager = ModelManager(applicationContext)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            WhisperModel.ALL.map { "${it.displayName} (~${it.approxSizeMb} MB)" }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modelSpinner.adapter = adapter
        binding.modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshModelStatus()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.downloadButton.setOnClickListener { startModelDownload() }
        binding.deleteButton.setOnClickListener {
            modelManager.deleteModel(selectedModel)
            refreshModelStatus()
        }
        binding.pickFileButton.setOnClickListener { pickFileLauncher.launch(arrayOf("*/*")) }
        binding.transcribeButton.setOnClickListener { runTranscription() }
        binding.copyButton.setOnClickListener { copyResultToClipboard() }
        binding.shareButton.setOnClickListener { shareResult() }

        refreshModelStatus()
    }

    private fun refreshModelStatus() {
        val downloaded = modelManager.isDownloaded(selectedModel)
        binding.modelStatusText.text = getString(
            if (downloaded) R.string.status_downloaded else R.string.status_not_downloaded
        )
        binding.downloadButton.isEnabled = !downloaded
        binding.deleteButton.isEnabled = downloaded
        updateTranscribeButtonState()
    }

    private fun updateTranscribeButtonState() {
        binding.transcribeButton.isEnabled =
            selectedFileUri != null && modelManager.isDownloaded(selectedModel)
    }

    private fun startModelDownload() {
        val model = selectedModel
        val downloadId = modelManager.enqueueDownload(model)
        binding.downloadButton.isEnabled = false
        binding.downloadProgressBar.visibility = View.VISIBLE
        binding.downloadProgressBar.progress = 0

        lifecycleScope.launch(Dispatchers.IO) {
            var finished = false
            while (!finished) {
                delay(400)
                val progress = modelManager.queryProgress(downloadId) ?: break
                val percent = if (progress.bytesTotal > 0) {
                    ((progress.bytesDownloaded * 100) / progress.bytesTotal).toInt()
                } else {
                    0
                }

                withContext(Dispatchers.Main) {
                    binding.downloadProgressBar.progress = percent
                }

                when (progress.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        finished = true
                        withContext(Dispatchers.Main) {
                            binding.downloadProgressBar.visibility = View.GONE
                            refreshModelStatus()
                            Toast.makeText(this@MainActivity, R.string.download_complete, Toast.LENGTH_SHORT).show()
                        }
                    }

                    DownloadManager.STATUS_FAILED -> {
                        finished = true
                        withContext(Dispatchers.Main) {
                            binding.downloadProgressBar.visibility = View.GONE
                            binding.downloadButton.isEnabled = true
                            Toast.makeText(this@MainActivity, R.string.download_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun runTranscription() {
        val uri = selectedFileUri ?: return
        val modelFile = modelManager.modelFile(selectedModel)

        binding.transcribeButton.isEnabled = false
        binding.copyButton.isEnabled = false
        binding.shareButton.isEnabled = false
        binding.transcribeProgressBar.visibility = View.VISIBLE
        binding.resultText.text = getString(R.string.transcribing)

        lifecycleScope.launch(Dispatchers.Default) {
            var resultText: String? = null
            var errorMessage: String? = null
            try {
                val samples = AudioDecoder.decodeToPcm16k(applicationContext, uri)
                Transcriber(modelFile.absolutePath).use { transcriber ->
                    resultText = transcriber.transcribe(samples).trim()
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: e.javaClass.simpleName
            }

            withContext(Dispatchers.Main) {
                binding.transcribeProgressBar.visibility = View.GONE
                binding.transcribeButton.isEnabled = true

                if (errorMessage != null) {
                    binding.resultText.text = getString(R.string.transcribe_error, errorMessage)
                } else {
                    val text = resultText.orEmpty()
                    binding.resultText.text = text.ifBlank { getString(R.string.result_empty) }
                    val hasText = text.isNotBlank()
                    binding.copyButton.isEnabled = hasText
                    binding.shareButton.isEnabled = hasText
                }
            }
        }
    }

    private fun copyResultToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", binding.resultText.text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareResult() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, binding.resultText.text.toString())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_text)))
    }
}

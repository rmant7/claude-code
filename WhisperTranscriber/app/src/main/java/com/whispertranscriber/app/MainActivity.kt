package com.whispertranscriber.app

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.whispertranscriber.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private enum class Mode { FILE, URL, FOLDER }

    private lateinit var binding: ActivityMainBinding
    private lateinit var modelManager: ModelManager
    private lateinit var resultsAdapter: ResultsAdapter

    private val results = mutableListOf<TranscriptionResult>()

    private var currentMode = Mode.FILE
    private var selectedFileUri: Uri? = null
    private var selectedFileName: String = ""
    private var selectedFolderFiles: List<DocumentFile> = emptyList()

    private val selectedModel: WhisperModel
        get() = WhisperModel.ALL[binding.modelSpinner.selectedItemPosition]

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedFileUri = uri
            selectedFileName = uri.lastPathSegment ?: uri.toString()
            binding.selectedFileText.text = selectedFileName
            updateTranscribeButtonState()
        }
    }

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val tree = DocumentFile.fromTreeUri(this, uri)
            val files = tree?.let { MediaFileUtils.listMediaFilesRecursively(it) }.orEmpty()
            selectedFolderFiles = files
            binding.selectedFolderText.text = getString(R.string.folder_files_found, files.size)
            updateTranscribeButtonState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelManager = ModelManager(applicationContext)

        setUpModelSpinner()
        setUpSourceModeSwitcher()
        setUpResultsList()

        binding.downloadButton.setOnClickListener { startModelDownload() }
        binding.deleteButton.setOnClickListener {
            modelManager.deleteModel(selectedModel)
            refreshModelStatus()
        }
        binding.pickFileButton.setOnClickListener { pickFileLauncher.launch(arrayOf("*/*")) }
        binding.pickFolderButton.setOnClickListener { pickFolderLauncher.launch(null) }
        binding.transcribeButton.setOnClickListener { onTranscribeClicked() }
        binding.copyAllButton.setOnClickListener { copyAllResults() }
        binding.shareAllButton.setOnClickListener { shareAllResults() }

        binding.urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateTranscribeButtonState()
        })

        refreshModelStatus()
    }

    private fun setUpModelSpinner() {
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
    }

    private fun setUpSourceModeSwitcher() {
        binding.sourceModeGroup.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            currentMode = when (checkedId) {
                R.id.modeUrlRadio -> Mode.URL
                R.id.modeFolderRadio -> Mode.FOLDER
                else -> Mode.FILE
            }
            binding.fileModeLayout.visibility = if (currentMode == Mode.FILE) View.VISIBLE else View.GONE
            binding.urlModeLayout.visibility = if (currentMode == Mode.URL) View.VISIBLE else View.GONE
            binding.folderModeLayout.visibility = if (currentMode == Mode.FOLDER) View.VISIBLE else View.GONE
            updateTranscribeButtonState()
        }
    }

    private fun setUpResultsList() {
        resultsAdapter = ResultsAdapter(results)
        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.resultsRecyclerView.adapter = resultsAdapter
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
        val modelReady = modelManager.isDownloaded(selectedModel)
        val hasSource = when (currentMode) {
            Mode.FILE -> selectedFileUri != null
            Mode.URL -> binding.urlInput.text?.toString()?.isNotBlank() == true
            Mode.FOLDER -> selectedFolderFiles.isNotEmpty()
        }
        binding.transcribeButton.isEnabled = modelReady && hasSource
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

    private fun onTranscribeClicked() {
        val sources: List<MediaSource> = when (currentMode) {
            Mode.FILE -> selectedFileUri?.let { listOf(MediaSource.LocalFile(it, selectedFileName)) }.orEmpty()

            Mode.URL -> {
                val url = binding.urlInput.text?.toString()?.trim().orEmpty()
                if (url.isNotBlank()) listOf(MediaSource.RemoteUrl(url)) else emptyList()
            }

            Mode.FOLDER -> selectedFolderFiles.map { doc ->
                MediaSource.LocalFile(doc.uri, doc.name ?: doc.uri.lastPathSegment ?: "file")
            }
        }

        if (sources.isNotEmpty()) runBatch(sources)
    }

    private fun runBatch(sources: List<MediaSource>) {
        val modelFile = modelManager.modelFile(selectedModel)

        results.clear()
        results.addAll(sources.map { TranscriptionResult(it) })
        resultsAdapter.notifyDataSetChanged()

        binding.resultsSection.visibility = View.VISIBLE
        binding.copyAllButton.isEnabled = false
        binding.shareAllButton.isEnabled = false
        binding.transcribeButton.isEnabled = false
        binding.transcribeProgressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                Transcriber(modelFile.absolutePath).use { transcriber ->
                    for (result in results) {
                        try {
                            val samples = when (val source = result.source) {
                                is MediaSource.LocalFile -> {
                                    setStatus(result, TranscriptionResult.Status.DECODING)
                                    AudioDecoder.decodeToPcm16k(applicationContext, source.uri)
                                }

                                is MediaSource.RemoteUrl -> {
                                    setStatus(result, TranscriptionResult.Status.DOWNLOADING)
                                    val file = UrlDownloader.download(applicationContext, source.url)
                                    try {
                                        setStatus(result, TranscriptionResult.Status.DECODING)
                                        AudioDecoder.decodeFromPath(file.absolutePath)
                                    } finally {
                                        file.delete()
                                    }
                                }
                            }

                            setStatus(result, TranscriptionResult.Status.TRANSCRIBING)
                            result.text = transcriber.transcribe(samples).trim()
                            setStatus(result, TranscriptionResult.Status.DONE)
                        } catch (e: Exception) {
                            result.error = e.message ?: e.javaClass.simpleName
                            setStatus(result, TranscriptionResult.Status.ERROR)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val message = getString(R.string.transcribe_error, e.message ?: e.javaClass.simpleName)
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }

            withContext(Dispatchers.Main) {
                binding.transcribeProgressBar.visibility = View.GONE
                binding.transcribeButton.isEnabled = true
                val anyDone = results.any { it.status == TranscriptionResult.Status.DONE }
                binding.copyAllButton.isEnabled = anyDone
                binding.shareAllButton.isEnabled = anyDone
            }
        }
    }

    private suspend fun setStatus(result: TranscriptionResult, status: TranscriptionResult.Status) {
        result.status = status
        withContext(Dispatchers.Main) { resultsAdapter.notifyDataSetChanged() }
    }

    private fun buildCombinedText(): String =
        results.filter { it.status == TranscriptionResult.Status.DONE }
            .joinToString("\n\n") { r ->
                "${r.source.displayName}:\n${r.text.ifBlank { getString(R.string.no_speech_detected) }}"
            }

    private fun copyAllResults() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcripts", buildCombinedText()))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareAllResults() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, buildCombinedText())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_all)))
    }
}

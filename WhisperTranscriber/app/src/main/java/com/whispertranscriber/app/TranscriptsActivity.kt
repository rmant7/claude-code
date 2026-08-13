package com.whispertranscriber.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.whispertranscriber.app.databinding.ActivityTranscriptsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Lets the user browse and open transcript .txt files already written to disk by past batches. */
class TranscriptsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTranscriptsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranscriptsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.transcripts_title)

        binding.transcriptFilesRecyclerView.layoutManager = LinearLayoutManager(this)
        loadTranscriptFiles()
    }

    private fun loadTranscriptFiles() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { findTranscriptFiles() }
            val adapter = TranscriptFilesAdapter(entries) { entry -> showTranscriptViewer(entry) }
            binding.transcriptFilesRecyclerView.adapter = adapter
            binding.emptyStateText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun findTranscriptFiles(): List<TranscriptFileEntry> {
        val root = File(getExternalFilesDir(null), "transcripts")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "txt" }.toList()
        return files.sortedByDescending { it.lastModified() }.map { file ->
            TranscriptFileEntry(file, file.relativeTo(root).path)
        }
    }

    private fun showTranscriptViewer(entry: TranscriptFileEntry) {
        val content = runCatching { entry.file.readText() }.getOrDefault("")
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_transcript_viewer, null)
        view.findViewById<TextView>(R.id.transcriptContentText).text = content

        AlertDialog.Builder(this)
            .setTitle(entry.relativePath)
            .setView(view)
            .setPositiveButton(R.string.share) { _, _ -> shareTranscript(entry.relativePath, content) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun shareTranscript(name: String, content: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }
}

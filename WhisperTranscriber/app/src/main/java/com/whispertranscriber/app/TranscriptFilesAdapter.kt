package com.whispertranscriber.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.DateFormat
import java.util.Date

data class TranscriptFileEntry(val file: File, val relativePath: String)

class TranscriptFilesAdapter(
    private val items: List<TranscriptFileEntry>,
    private val onClick: (TranscriptFileEntry) -> Unit
) : RecyclerView.Adapter<TranscriptFilesAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.fileNameText)
        val meta: TextView = view.findViewById(R.id.fileMetaText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_transcript_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.relativePath
        val sizeKb = (item.file.length() / 1024).coerceAtLeast(1)
        val modified = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(item.file.lastModified()))
        holder.meta.text = "$modified · $sizeKb KB"
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}

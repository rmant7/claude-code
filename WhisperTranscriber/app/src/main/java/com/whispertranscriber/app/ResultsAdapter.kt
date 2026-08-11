package com.whispertranscriber.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ResultsAdapter(private val items: List<TranscriptionResult>) :
    RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.itemName)
        val status: TextView = view.findViewById(R.id.itemStatus)
        val text: TextView = view.findViewById(R.id.itemText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        holder.name.text = item.source.displayName

        val baseLabel = statusLabel(context, item.status)
        holder.status.text = when (item.status) {
            TranscriptionResult.Status.DECODING, TranscriptionResult.Status.TRANSCRIBING ->
                if (item.progressPercent > 0) "$baseLabel ${item.progressPercent}%" else baseLabel
            else -> baseLabel
        }

        holder.text.text = when (item.status) {
            TranscriptionResult.Status.ERROR ->
                context.getString(R.string.transcribe_error, item.error ?: "")
            TranscriptionResult.Status.DONE ->
                item.text.ifBlank { context.getString(R.string.no_speech_detected) }
            else -> ""
        }
    }

    override fun getItemCount(): Int = items.size

    private fun statusLabel(context: android.content.Context, status: TranscriptionResult.Status): String {
        val resId = when (status) {
            TranscriptionResult.Status.PENDING -> R.string.status_pending
            TranscriptionResult.Status.DOWNLOADING -> R.string.status_downloading_file
            TranscriptionResult.Status.DECODING -> R.string.status_decoding
            TranscriptionResult.Status.TRANSCRIBING -> R.string.transcribing
            TranscriptionResult.Status.DONE -> R.string.status_done
            TranscriptionResult.Status.ERROR -> R.string.status_failed
        }
        return context.getString(resId)
    }
}

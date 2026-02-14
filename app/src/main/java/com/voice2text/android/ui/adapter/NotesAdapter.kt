package com.voice2text.android.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.voice2text.android.databinding.ItemNoteBinding
import com.voice2text.android.notes.NoteEntity
import java.time.format.DateTimeFormatter

/**
 * RecyclerView adapter for the notes list on the main screen.
 *
 * Uses [ListAdapter] with [DiffUtil] so only changed items re-render
 * when the list updates (e.g. after a new note is saved).
 */
class NotesAdapter(
    private val onClick: (NoteEntity) -> Unit,
    private val onLongClick: (NoteEntity) -> Unit
) : ListAdapter<NoteEntity, NotesAdapter.NoteViewHolder>(NoteDiffCallback) {

    companion object {
        private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy  h:mm a")
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val binding = ItemNoteBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return NoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NoteViewHolder(
        private val binding: ItemNoteBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(note: NoteEntity) {
            binding.noteTitle.text = note.title
            binding.noteTimestamp.text = DISPLAY_FORMAT.format(note.timestamp)

            binding.root.setOnClickListener { onClick(note) }
            binding.root.setOnLongClickListener {
                onLongClick(note)
                true
            }
        }
    }

    private object NoteDiffCallback : DiffUtil.ItemCallback<NoteEntity>() {
        override fun areItemsTheSame(oldItem: NoteEntity, newItem: NoteEntity): Boolean =
            oldItem.filePath == newItem.filePath

        override fun areContentsTheSame(oldItem: NoteEntity, newItem: NoteEntity): Boolean =
            oldItem == newItem
    }
}

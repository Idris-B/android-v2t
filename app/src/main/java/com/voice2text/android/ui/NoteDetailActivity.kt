package com.voice2text.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.voice2text.android.R
import com.voice2text.android.databinding.ActivityNoteDetailBinding
import com.voice2text.android.notes.NoteRepository
import com.voice2text.android.settings.PreferencesRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/**
 * Displays a single note's full text with share, copy, and delete actions.
 *
 * Receives the note's file path via [EXTRA_NOTE_PATH]. On open it reads the
 * note from disk (via [NoteRepository]) and displays the title, timestamp,
 * and full body text in a scrollable view.
 *
 * Why read from disk instead of passing the text as an Intent extra?
 * Intent extras have a ~500 KB limit (Binder transaction limit). Voice notes
 * can theoretically be very long, so reading from the file is safer.
 */
class NoteDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_PATH = "extra_note_path"
    }

    private lateinit var binding: ActivityNoteDetailBinding
    private lateinit var noteRepo: NoteRepository
    private lateinit var prefs: PreferencesRepository

    private var noteText: String = ""
    private var noteTitle: String = ""
    private var notePath: String = ""
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        noteRepo = NoteRepository(applicationContext)
        prefs = PreferencesRepository(applicationContext)

        notePath = intent.getStringExtra(EXTRA_NOTE_PATH) ?: run {
            finish()
            return
        }

        loadNote()
        setupButtons()
        applyTheme()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    // ── Theme ────────────────────────────────────────────────────────────

    private fun applyTheme() {
        lifecycleScope.launch {
            val theme = ThemeColors.forKey(prefs.appTheme.first())
            theme.applyTo(this@NoteDetailActivity)

            binding.noteTimestamp.setTextColor(theme.secondaryText)
            binding.noteText.setTextColor(theme.text)
        }
    }

    // ── Load ──────────────────────────────────────────────────────────────

    private fun loadNote() {
        lifecycleScope.launch {
            val folderUri = prefs.notesFolderUri.first()
            // Find the note in the list that matches the path we were given
            val note = noteRepo.listNotes(folderUri).find { it.filePath == notePath }

            if (note == null) {
                Toast.makeText(
                    this@NoteDetailActivity,
                    R.string.note_not_found,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }

            noteText = note.text
            noteTitle = note.title

            supportActionBar?.title = noteTitle

            val formatter = DateTimeFormatter.ofPattern("MMMM d, yyyy  h:mm a")
            binding.noteTimestamp.text = note.timestamp.format(formatter)
            binding.noteText.text = noteText

            // Show play button if audio file exists
            val audioPath = note.audioFilePath
            if (audioPath != null && File(audioPath).exists()) {
                binding.playAudioButton.visibility = View.VISIBLE
                setupAudioPlayback(audioPath)
            }
        }
    }

    // ── Audio playback ─────────────────────────────────────────────────────

    private fun setupAudioPlayback(audioPath: String) {
        binding.playAudioButton.setOnClickListener {
            if (isPlaying) {
                pauseAudio()
            } else {
                playAudio(audioPath)
            }
        }
    }

    private fun playAudio(audioPath: String) {
        if (mediaPlayer == null) {
            val player = MediaPlayer()
            player.setDataSource(audioPath)
            player.prepare()
            player.setOnCompletionListener {
                isPlaying = false
                binding.playAudioButton.text = getString(R.string.play_audio)
                binding.playAudioButton.setIconResource(android.R.drawable.ic_media_play)
            }
            mediaPlayer = player
        }
        mediaPlayer?.start()
        isPlaying = true
        binding.playAudioButton.text = getString(R.string.pause_audio)
        binding.playAudioButton.setIconResource(android.R.drawable.ic_media_pause)
    }

    private fun pauseAudio() {
        mediaPlayer?.pause()
        isPlaying = false
        binding.playAudioButton.text = getString(R.string.play_audio)
        binding.playAudioButton.setIconResource(android.R.drawable.ic_media_play)
    }

    // ── Buttons ───────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.shareButton.setOnClickListener { shareNote() }
        binding.copyButton.setOnClickListener { copyNote() }
        binding.deleteButton.setOnClickListener { confirmDelete() }
    }

    private fun shareNote() {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, noteTitle)
            putExtra(Intent.EXTRA_TEXT, noteText)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share_via)))
    }

    private fun copyNote() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(noteTitle, noteText))
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_note_title)
            .setMessage(R.string.delete_note_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    noteRepo.deleteNote(notePath)
                    Toast.makeText(
                        this@NoteDetailActivity,
                        R.string.note_deleted,
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

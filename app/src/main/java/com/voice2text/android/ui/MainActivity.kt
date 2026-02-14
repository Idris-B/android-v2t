package com.voice2text.android.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.voice2text.android.R
import com.voice2text.android.databinding.ActivityMainBinding
import com.voice2text.android.notes.NoteEntity
import com.voice2text.android.notes.NoteRepository
import com.voice2text.android.service.TranscriptionService
import com.voice2text.android.settings.PreferencesRepository
import com.voice2text.android.ui.adapter.NotesAdapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Main screen: shows a big record button, live transcript while recording,
 * and a list of saved notes when idle.
 *
 * Binds to [TranscriptionService] to observe state and show live text.
 * The service handles all audio and engine logic — the Activity is purely UI.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var noteRepo: NoteRepository
    private lateinit var prefs: PreferencesRepository
    private lateinit var notesAdapter: NotesAdapter

    private var transcriptionService: TranscriptionService? = null
    private var serviceBound = false

    // ── Service connection ───────────────────────────────────────────────

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder !is TranscriptionService.LocalBinder) return
            transcriptionService = binder.getService()
            serviceBound = true
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            transcriptionService = null
            serviceBound = false
        }
    }

    // ── Permission launcher ─────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            startRecording()
        }
        // If denied, the button just won't start recording.
        // A production app would show a rationale dialog here.
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        noteRepo = NoteRepository(applicationContext)
        prefs = PreferencesRepository(applicationContext)

        setupNotesRecyclerView()
        setupButtons()
        applyTheme()
        loadNotes()
    }

    override fun onStart() {
        super.onStart()
        // Bind to the service if it's already running (e.g. started by notification)
        Intent(this, TranscriptionService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        loadNotes()
    }

    // ── Theme ────────────────────────────────────────────────────────────

    private fun applyTheme() {
        lifecycleScope.launch {
            val theme = ThemeColors.forKey(prefs.appTheme.first())
            theme.applyTo(this@MainActivity)

            binding.titleText.setTextColor(theme.text)
            binding.statusText.setTextColor(theme.secondaryText)
            binding.transcriptText.setTextColor(theme.text)
            binding.emptyStateText.setTextColor(theme.secondaryText)

            // Tint the transcript background drawable
            val bg = binding.transcriptScroll.background
            if (bg is GradientDrawable) {
                // Use a slightly lighter/darker shade for the transcript area
                val tintColor = if (theme == ThemeColors.forKey("light")) 0xFFF5F5F5.toInt()
                else theme.background
                bg.setColor(tintColor)
                bg.setStroke(1, theme.secondaryText and 0x40FFFFFF)
            }
        }
    }

    // ── Setup ────────────────────────────────────────────────────────────

    private fun setupNotesRecyclerView() {
        notesAdapter = NotesAdapter(
            onClick = { note -> openNoteDetail(note) },
            onLongClick = { note -> confirmDeleteNote(note) }
        )
        binding.notesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = notesAdapter
        }
    }

    private fun setupButtons() {
        binding.recordButton.setOnClickListener {
            val service = transcriptionService
            if (service != null && service.transcriptionState.value is TranscriptionService.State.Listening) {
                stopRecording()
            } else {
                requestPermissionsAndRecord()
            }
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ── Recording ────────────────────────────────────────────────────────

    private fun requestPermissionsAndRecord() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)

        // Android 13+ needs POST_NOTIFICATIONS for the foreground service notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            startRecording()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startRecording() {
        // Start the service (promotes to foreground internally)
        val intent = TranscriptionService.startIntent(this)
        ContextCompat.startForegroundService(this, intent)

        // Bind so we can observe state
        if (!serviceBound) {
            Intent(this, TranscriptionService::class.java).also {
                bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        } else {
            transcriptionService?.startTranscription()
        }
    }

    private fun stopRecording() {
        transcriptionService?.stopTranscription()
    }

    // ── Observe service state ────────────────────────────────────────────

    private fun observeServiceState() {
        val service = transcriptionService ?: return

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                service.transcriptionState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: TranscriptionService.State) {
        when (state) {
            is TranscriptionService.State.Idle -> {
                binding.statusText.text = getString(R.string.status_idle)
                binding.transcriptScroll.visibility = View.GONE
                binding.notesRecyclerView.visibility = View.VISIBLE
                binding.recordButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                binding.recordButton.contentDescription = getString(R.string.start_recording)
                loadNotes()
            }
            is TranscriptionService.State.Initializing -> {
                binding.statusText.text = getString(R.string.status_initializing)
                binding.transcriptScroll.visibility = View.VISIBLE
                binding.notesRecyclerView.visibility = View.GONE
                binding.emptyStateText.visibility = View.GONE
                binding.transcriptText.text = ""
            }
            is TranscriptionService.State.Listening -> {
                binding.statusText.text = getString(R.string.status_listening)
                binding.transcriptScroll.visibility = View.VISIBLE
                binding.notesRecyclerView.visibility = View.GONE
                binding.emptyStateText.visibility = View.GONE
                binding.transcriptText.text = state.partialText
                binding.recordButton.setImageResource(android.R.drawable.ic_media_pause)
                binding.recordButton.contentDescription = getString(R.string.stop_recording)

                // Auto-scroll to bottom as text comes in
                binding.transcriptScroll.post {
                    binding.transcriptScroll.fullScroll(View.FOCUS_DOWN)
                }
            }
            is TranscriptionService.State.Saving -> {
                binding.statusText.text = getString(R.string.status_saving)
            }
            is TranscriptionService.State.Error -> {
                binding.statusText.text = state.message
                binding.recordButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                binding.recordButton.contentDescription = getString(R.string.start_recording)
            }
        }
    }

    // ── Notes ────────────────────────────────────────────────────────────

    private fun loadNotes() {
        lifecycleScope.launch {
            val folderUri = prefs.notesFolderUri.first()
            val notes = noteRepo.listNotes(folderUri)
            notesAdapter.submitList(notes)

            val isIdle = transcriptionService?.transcriptionState?.value
                ?.let { it is TranscriptionService.State.Idle } ?: true

            if (isIdle) {
                binding.emptyStateText.visibility =
                    if (notes.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openNoteDetail(note: NoteEntity) {
        val intent = Intent(this, NoteDetailActivity::class.java).apply {
            putExtra(NoteDetailActivity.EXTRA_NOTE_PATH, note.filePath)
        }
        startActivity(intent)
    }

    private fun confirmDeleteNote(note: NoteEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_note_title)
            .setMessage(R.string.delete_note_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    noteRepo.deleteNote(note.filePath)
                    loadNotes()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

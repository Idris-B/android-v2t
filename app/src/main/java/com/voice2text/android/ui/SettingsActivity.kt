package com.voice2text.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.voice2text.android.R
import com.voice2text.android.Voice2TextApplication
import com.voice2text.android.databinding.ActivitySettingsBinding
import com.voice2text.android.settings.PreferencesRepository
import com.voice2text.android.speech.EngineFactory
import com.voice2text.android.speech.VoskModelManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings screen for configuring the speech engine, notes storage
 * location, and trigger preferences.
 *
 * All settings are persisted via [PreferencesRepository] (DataStore).
 * Changes take effect immediately — no "save" button needed.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PreferencesRepository
    private lateinit var modelManager: VoskModelManager

    // ── SAF folder picker ────────────────────────────────────────────────

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        handleFolderSelected(uri)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Show back arrow in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        prefs = PreferencesRepository(applicationContext)
        modelManager = VoskModelManager(applicationContext)

        setupEngineSection()
        observeModelState()
        setupStorageSection()
        setupAudioSection()
        setupTriggersSection()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ── Engine section ───────────────────────────────────────────────────

    private fun setupEngineSection() {
        // Load current preference and set the radio button
        lifecycleScope.launch {
            val currentEngine = prefs.speechEngine.first()
            when (currentEngine) {
                EngineFactory.ENGINE_VOSK -> binding.radioVosk.isChecked = true
                else -> binding.radioAndroid.isChecked = true
            }
            updateVoskModelStatus(currentEngine == EngineFactory.ENGINE_VOSK)
        }

        // Listen for changes
        binding.engineRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val engineId = when (checkedId) {
                R.id.radioVosk -> EngineFactory.ENGINE_VOSK
                else -> EngineFactory.ENGINE_ANDROID
            }
            lifecycleScope.launch {
                prefs.setSpeechEngine(engineId)
            }
            updateVoskModelStatus(engineId == EngineFactory.ENGINE_VOSK)
        }
    }

    /**
     * Shows/hides the Vosk model section (status + download/delete button)
     * depending on whether Vosk is the selected engine.
     */
    private fun updateVoskModelStatus(showStatus: Boolean) {
        val visibility = if (showStatus) View.VISIBLE else View.GONE
        binding.voskModelStatus.visibility = visibility
        binding.voskModelButton.visibility = visibility
        // Progress bar visibility is managed by observeModelState
        if (!showStatus) {
            binding.voskModelProgress.visibility = View.GONE
        }

        if (showStatus) {
            // Refresh the UI based on the current model manager state
            updateModelUI(modelManager.downloadState.value)
        }
    }

    /**
     * Collects model download state changes and updates the UI accordingly.
     * This runs for the lifetime of the activity, so progress updates
     * from a background download are reflected in real-time.
     */
    private fun observeModelState() {
        lifecycleScope.launch {
            modelManager.downloadState.collect { state ->
                updateModelUI(state)
            }
        }
    }

    /**
     * Updates status text, progress bar, and button label/action
     * based on the current [VoskModelManager.State].
     */
    private fun updateModelUI(state: VoskModelManager.State) {
        when (state) {
            is VoskModelManager.State.Idle -> {
                if (modelManager.isModelReady) {
                    binding.voskModelStatus.text = getString(R.string.settings_vosk_model_ready)
                    binding.voskModelButton.text = getString(R.string.settings_vosk_delete_model)
                    binding.voskModelButton.setOnClickListener { deleteModel() }
                } else {
                    binding.voskModelStatus.text = getString(R.string.settings_vosk_model_missing)
                    binding.voskModelButton.text = getString(R.string.settings_vosk_download_model)
                    binding.voskModelButton.setOnClickListener { downloadModel() }
                }
                binding.voskModelButton.isEnabled = true
                binding.voskModelProgress.visibility = View.GONE
            }
            is VoskModelManager.State.Downloading -> {
                binding.voskModelStatus.text =
                    getString(R.string.settings_vosk_downloading, state.progressPercent)
                binding.voskModelProgress.visibility = View.VISIBLE
                binding.voskModelProgress.progress = state.progressPercent
                binding.voskModelButton.isEnabled = false
            }
            is VoskModelManager.State.Extracting -> {
                binding.voskModelStatus.text = getString(R.string.settings_vosk_extracting)
                binding.voskModelProgress.visibility = View.VISIBLE
                binding.voskModelProgress.isIndeterminate = true
                binding.voskModelButton.isEnabled = false
            }
            is VoskModelManager.State.Ready -> {
                binding.voskModelStatus.text = getString(R.string.settings_vosk_model_ready)
                binding.voskModelButton.text = getString(R.string.settings_vosk_delete_model)
                binding.voskModelButton.setOnClickListener { deleteModel() }
                binding.voskModelButton.isEnabled = true
                binding.voskModelProgress.visibility = View.GONE
            }
            is VoskModelManager.State.Error -> {
                binding.voskModelStatus.text =
                    getString(R.string.settings_vosk_download_failed, state.message)
                binding.voskModelButton.text = getString(R.string.settings_vosk_download_model)
                binding.voskModelButton.setOnClickListener { downloadModel() }
                binding.voskModelButton.isEnabled = true
                binding.voskModelProgress.visibility = View.GONE
            }
        }
    }

    private fun downloadModel() {
        lifecycleScope.launch {
            try {
                modelManager.ensureModelDownloaded()
            } catch (_: Exception) {
                // Error state is already emitted via downloadState flow
            }
        }
    }

    private fun deleteModel() {
        modelManager.deleteModel()
    }

    // ── Storage section ──────────────────────────────────────────────────

    private fun setupStorageSection() {
        // Load current folder preference
        lifecycleScope.launch {
            val folderUri = prefs.notesFolderUri.first()
            updateFolderDisplay(folderUri)
        }

        binding.chooseFolderButton.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        binding.resetFolderButton.setOnClickListener {
            lifecycleScope.launch {
                prefs.setNotesFolderUri(null)
                updateFolderDisplay(null)
            }
        }
    }

    /**
     * Handles a folder selected from the SAF picker.
     *
     * We take a persistable URI permission so the app can still write to
     * this folder after a reboot. Without this, the URI would expire when
     * the app is killed.
     */
    private fun handleFolderSelected(uri: Uri) {
        // Take persistent read+write permission
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, flags)

        val uriString = uri.toString()
        lifecycleScope.launch {
            prefs.setNotesFolderUri(uriString)
            updateFolderDisplay(uriString)
        }
    }

    private fun updateFolderDisplay(folderUriString: String?) {
        if (folderUriString == null) {
            binding.currentFolderText.text = getString(R.string.settings_folder_default)
            binding.resetFolderButton.visibility = View.GONE
        } else {
            // Try to show a human-readable path from the URI.
            // SAF URIs look like "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FNotes"
            // We decode it to show something like "primary:Documents/Notes"
            val readable = Uri.decode(
                Uri.parse(folderUriString).lastPathSegment ?: folderUriString
            )
            binding.currentFolderText.text = readable
            binding.resetFolderButton.visibility = View.VISIBLE
        }
    }

    // ── Audio recording section ─────────────────────────────────────────

    private fun setupAudioSection() {
        lifecycleScope.launch {
            val enabled = prefs.saveAudioRecording.first()
            binding.saveAudioSwitch.isChecked = enabled
        }

        binding.saveAudioSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                prefs.setSaveAudioRecording(isChecked)
            }
        }
    }

    // ── Triggers section ─────────────────────────────────────────────────

    private fun setupTriggersSection() {
        // Load current BT trigger preference to set the switch state.
        // The Application class reactively mirrors the preference into
        // TriggerManager, so we only need to update the preference here.
        lifecycleScope.launch {
            val btEnabled = prefs.bluetoothTriggerEnabled.first()
            binding.bluetoothTriggerSwitch.isChecked = btEnabled
            updateBtModeVisibility(btEnabled)

            val btMode = prefs.btTriggerMode.first()
            when (btMode) {
                "hold" -> binding.radioBtHold.isChecked = true
                else -> binding.radioBtToggle.isChecked = true
            }
        }

        binding.bluetoothTriggerSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                prefs.setBluetoothTriggerEnabled(isChecked)
            }
            updateBtModeVisibility(isChecked)
        }

        binding.btModeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioBtHold -> "hold"
                else -> "toggle"
            }
            lifecycleScope.launch {
                prefs.setBtTriggerMode(mode)
            }
        }
    }

    private fun updateBtModeVisibility(btEnabled: Boolean) {
        binding.btModeRadioGroup.visibility = if (btEnabled) View.VISIBLE else View.GONE
    }
}

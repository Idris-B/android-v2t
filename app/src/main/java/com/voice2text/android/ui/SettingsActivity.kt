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
import com.voice2text.android.databinding.ActivitySettingsBinding
import com.voice2text.android.settings.PreferencesRepository
import com.voice2text.android.speech.EngineFactory
import com.voice2text.android.speech.VoskEngine
import com.voice2text.android.trigger.TriggerManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

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
    private lateinit var triggerManager: TriggerManager

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
        triggerManager = TriggerManager(applicationContext)

        setupEngineSection()
        setupStorageSection()
        setupTriggersSection()
    }

    override fun onDestroy() {
        // Don't release triggerManager here — it should stay active
        // if the BT trigger is enabled. The Application class or
        // MainActivity should own its long-term lifecycle.
        super.onDestroy()
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
     * Shows model download status when Vosk is selected.
     * Checks whether the model directory exists on disk.
     */
    private fun updateVoskModelStatus(showStatus: Boolean) {
        if (!showStatus) {
            binding.voskModelStatus.visibility = View.GONE
            return
        }

        binding.voskModelStatus.visibility = View.VISIBLE
        val modelDir = File(filesDir, VoskEngine.MODEL_DIR_NAME)
        if (modelDir.exists() && modelDir.isDirectory) {
            binding.voskModelStatus.text = getString(R.string.settings_vosk_model_ready)
        } else {
            binding.voskModelStatus.text = getString(R.string.settings_vosk_model_missing)
        }
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

    // ── Triggers section ─────────────────────────────────────────────────

    private fun setupTriggersSection() {
        // Load current BT trigger preference
        lifecycleScope.launch {
            val btEnabled = prefs.bluetoothTriggerEnabled.first()
            binding.bluetoothTriggerSwitch.isChecked = btEnabled
            triggerManager.setBluetoothTriggerEnabled(btEnabled)
        }

        binding.bluetoothTriggerSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                prefs.setBluetoothTriggerEnabled(isChecked)
            }
            triggerManager.setBluetoothTriggerEnabled(isChecked)
        }
    }
}

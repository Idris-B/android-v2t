package com.voice2text.android.speech

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads and extracts the Vosk speech recognition model.
 *
 * The model (~50 MB compressed) is downloaded from alphacephei.com,
 * unzipped into [Context.getFilesDir]/vosk-model/, and ready for
 * [VoskEngine] to load.
 *
 * Progress is reported via [downloadState] (a StateFlow) and a system
 * notification so the user sees progress even if they leave the app.
 *
 * Why not use WorkManager?
 * WorkManager would be better for guaranteed background delivery, but
 * it adds complexity for what's a one-time ~50 MB download. We use a
 * simple coroutine instead — the caller (SettingsActivity or the service)
 * can launch this in a lifecycleScope. If the download is interrupted,
 * the partial directory is cleaned up and the user can retry.
 */
class VoskModelManager(private val context: Context) {

    companion object {
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

        /** Notification channel for download progress. */
        private const val CHANNEL_ID = "model_download_channel"
        private const val NOTIFICATION_ID = 2

        /** Name of the zip file's root directory (becomes vosk-model on disk). */
        private const val ZIP_ROOT_DIR = "vosk-model-small-en-us-0.15"
    }

    sealed class State {
        object Idle : State()
        data class Downloading(val progressPercent: Int) : State()
        object Extracting : State()
        object Ready : State()
        data class Error(val message: String) : State()
    }

    private val _downloadState = MutableStateFlow<State>(State.Idle)
    val downloadState: StateFlow<State> = _downloadState.asStateFlow()

    private val modelDir = File(context.filesDir, VoskEngine.MODEL_DIR_NAME)

    /** True if the model is already downloaded and extracted. */
    val isModelReady: Boolean
        get() = modelDir.exists() && modelDir.isDirectory &&
                (modelDir.listFiles()?.isNotEmpty() == true)

    /**
     * Downloads and extracts the Vosk model. No-op if already downloaded.
     *
     * Must be called from a coroutine — runs I/O on [Dispatchers.IO].
     * Progress is emitted to [downloadState] and shown in a notification.
     */
    suspend fun ensureModelDownloaded() {
        if (isModelReady) {
            _downloadState.value = State.Ready
            return
        }

        createNotificationChannel()

        try {
            val zipFile = downloadModel()
            extractModel(zipFile)
            zipFile.delete()
            _downloadState.value = State.Ready
            showNotification("Model ready", -1)
        } catch (e: Exception) {
            // Clean up partial download/extraction
            modelDir.deleteRecursively()
            _downloadState.value = State.Error(e.message ?: "Download failed")
            showNotification("Download failed: ${e.message}", -1)
            throw e
        }
    }

    /**
     * Deletes the downloaded model to free up space.
     */
    fun deleteModel() {
        modelDir.deleteRecursively()
        _downloadState.value = State.Idle
    }

    // ── Download ─────────────────────────────────────────────────────────

    private suspend fun downloadModel(): File = withContext(Dispatchers.IO) {
        _downloadState.value = State.Downloading(0)
        showNotification("Downloading model…", 0)

        val zipFile = File(context.cacheDir, "vosk-model.zip")

        val url = URL(MODEL_URL)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000

        try {
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw SpeechEngineException(
                    "Download failed: HTTP ${connection.responseCode}"
                )
            }

            val totalBytes = connection.contentLength.toLong()
            var downloadedBytes = 0L

            connection.inputStream.buffered().use { input ->
                FileOutputStream(zipFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val percent = if (totalBytes > 0) {
                            ((downloadedBytes * 100) / totalBytes).toInt()
                        } else {
                            -1
                        }
                        _downloadState.value = State.Downloading(percent)
                        showNotification("Downloading model… $percent%", percent)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        zipFile
    }

    // ── Extraction ───────────────────────────────────────────────────────

    private suspend fun extractModel(zipFile: File) = withContext(Dispatchers.IO) {
        _downloadState.value = State.Extracting
        showNotification("Extracting model…", -1)

        modelDir.mkdirs()

        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                // The zip contains files like "vosk-model-small-en-us-0.15/am/final.mdl"
                // We strip the root directory to get "am/final.mdl" and write into modelDir.
                val relativePath = entry.name.removePrefix("$ZIP_ROOT_DIR/")

                if (relativePath.isNotEmpty()) {
                    val outFile = File(modelDir, relativePath)

                    // Security: prevent zip-slip by ensuring output is inside modelDir
                    if (!outFile.canonicalPath.startsWith(modelDir.canonicalPath)) {
                        throw SpeechEngineException("Zip entry outside target dir: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output ->
                            zip.copyTo(output)
                        }
                    }
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    // ── Notifications ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Download",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress when downloading the offline speech model"
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * Shows or updates the download progress notification.
     * @param text Status text to display.
     * @param progress 0-100 for determinate progress, -1 for indeterminate.
     */
    private fun showNotification(text: String, progress: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Voice2Text")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(progress >= 0)

        if (progress >= 0) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, false)
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, builder.build())
    }
}

package com.voice2text.android.notes

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Handles saving and loading voice notes as plain-text markdown files.
 *
 * Storage strategy:
 * - If the user has chosen an external folder via SAF (Storage Access Framework),
 *   notes are written there using [DocumentFile]. The URI is persisted with
 *   takePersistableUriPermission so it survives reboots.
 * - Otherwise, notes go to internal app storage (filesDir/notes/).
 *
 * File format: `VoiceNote_2026-02-13_14-30-00.md` containing a markdown
 * header with timestamp followed by the transcribed text.
 */
class NoteRepository(private val context: Context) {

    companion object {
        private const val NOTES_DIR = "notes"
        private const val FILE_PREFIX = "VoiceNote_"
        private const val FILE_EXTENSION = ".md"
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
        private val AUDIO_COMMENT_REGEX = Regex("<!-- audio: (.+?) -->")
    }

    /**
     * Saves transcribed text as a new note file.
     *
     * @param text The transcribed text to save.
     * @param folderUriString SAF URI of the user-chosen folder, or null for internal storage.
     * @param audioFilePath Absolute path to an associated audio recording, or null.
     * @return The [NoteEntity] representing the saved note.
     */
    suspend fun saveNote(
        text: String,
        folderUriString: String?,
        audioFilePath: String? = null
    ): NoteEntity {
        val now = LocalDateTime.now()
        val fileName = "$FILE_PREFIX${TIMESTAMP_FORMAT.format(now)}$FILE_EXTENSION"
        val content = buildNoteContent(text, now, audioFilePath)

        return if (folderUriString != null) {
            saveToExternalFolder(content, fileName, folderUriString, now, text, audioFilePath)
        } else {
            saveToInternalStorage(content, fileName, now, text, audioFilePath)
        }
    }

    /**
     * Lists all saved notes from the active storage location.
     *
     * @param folderUriString SAF URI of the user-chosen folder, or null for internal storage.
     */
    suspend fun listNotes(folderUriString: String?): List<NoteEntity> = withContext(Dispatchers.IO) {
        if (folderUriString != null) {
            listFromExternalFolder(folderUriString)
        } else {
            listFromInternalStorage()
        }
    }

    /**
     * Reads the full text of a note from its file path.
     */
    suspend fun readNote(filePath: String): String = withContext(Dispatchers.IO) {
        if (filePath.startsWith("content://")) {
            val uri = Uri.parse(filePath)
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                it.readText()
            } ?: ""
        } else {
            File(filePath).readText()
        }
    }

    /**
     * Deletes a note by its file path, including any associated audio file.
     */
    suspend fun deleteNote(filePath: String): Boolean = withContext(Dispatchers.IO) {
        // Try to find and delete associated audio file
        deleteAssociatedAudio(filePath)

        if (filePath.startsWith("content://")) {
            val uri = Uri.parse(filePath)
            val doc = DocumentFile.fromSingleUri(context, uri)
            doc?.delete() ?: false
        } else {
            File(filePath).delete()
        }
    }

    /**
     * Reads the note file to find an audio comment, then deletes the audio file.
     */
    private fun deleteAssociatedAudio(filePath: String) {
        try {
            val content = if (filePath.startsWith("content://")) {
                val uri = Uri.parse(filePath)
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } else {
                File(filePath).takeIf { it.exists() }?.readText()
            }
            val audioFileName = content?.let { AUDIO_COMMENT_REGEX.find(it)?.groupValues?.get(1) }
            if (audioFileName != null) {
                val audioFile = File(File(context.filesDir, NOTES_DIR), audioFileName)
                audioFile.delete()
            }
        } catch (_: Exception) {
            // Best effort — don't fail the note deletion if audio cleanup fails
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private fun buildNoteContent(
        text: String,
        timestamp: LocalDateTime,
        audioFilePath: String? = null
    ): String {
        val sb = StringBuilder()
        sb.append("# Voice Note — ${DISPLAY_FORMAT.format(timestamp)}\n\n")
        sb.append("$text\n")
        if (audioFilePath != null) {
            val audioFileName = File(audioFilePath).name
            sb.append("\n<!-- audio: $audioFileName -->\n")
        }
        return sb.toString()
    }

    private suspend fun saveToInternalStorage(
        content: String,
        fileName: String,
        timestamp: LocalDateTime,
        rawText: String,
        audioFilePath: String? = null
    ): NoteEntity = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, NOTES_DIR).also { it.mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content)

        NoteEntity(
            title = deriveTitle(rawText, timestamp),
            text = rawText,
            timestamp = timestamp,
            filePath = file.absolutePath,
            audioFilePath = audioFilePath
        )
    }

    private suspend fun saveToExternalFolder(
        content: String,
        fileName: String,
        folderUriString: String,
        timestamp: LocalDateTime,
        rawText: String,
        audioFilePath: String? = null
    ): NoteEntity = withContext(Dispatchers.IO) {
        val folderUri = Uri.parse(folderUriString)
        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: throw IllegalStateException("Cannot access folder: $folderUriString")

        val docFile = folder.createFile("text/markdown", fileName)
            ?: throw IllegalStateException("Failed to create file in folder")

        context.contentResolver.openOutputStream(docFile.uri)?.use { stream ->
            stream.write(content.toByteArray())
        } ?: throw IllegalStateException("Cannot open output stream for ${docFile.uri}")

        NoteEntity(
            title = deriveTitle(rawText, timestamp),
            text = rawText,
            timestamp = timestamp,
            filePath = docFile.uri.toString(),
            audioFilePath = audioFilePath
        )
    }

    private fun listFromInternalStorage(): List<NoteEntity> {
        val dir = File(context.filesDir, NOTES_DIR)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_EXTENSION) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val text = file.readText()
                val rawText = extractRawText(text)
                val timestamp = parseTimestampFromFileName(file.name)
                val audioFileName = AUDIO_COMMENT_REGEX.find(text)?.groupValues?.get(1)
                val audioPath = audioFileName?.let { File(dir, it).absolutePath }
                NoteEntity(
                    title = deriveTitle(rawText, timestamp),
                    text = rawText,
                    timestamp = timestamp,
                    filePath = file.absolutePath,
                    audioFilePath = audioPath
                )
            }
            ?: emptyList()
    }

    private fun listFromExternalFolder(folderUriString: String): List<NoteEntity> {
        val folderUri = Uri.parse(folderUriString)
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()
        val internalDir = File(context.filesDir, NOTES_DIR)

        return folder.listFiles()
            .filter { it.name?.startsWith(FILE_PREFIX) == true && it.name?.endsWith(FILE_EXTENSION) == true }
            .sortedByDescending { it.lastModified() }
            .mapNotNull { docFile ->
                try {
                    val text = context.contentResolver.openInputStream(docFile.uri)
                        ?.bufferedReader()?.use { it.readText() } ?: return@mapNotNull null
                    val rawText = extractRawText(text)
                    val timestamp = parseTimestampFromFileName(docFile.name ?: "")
                    val audioFileName = AUDIO_COMMENT_REGEX.find(text)?.groupValues?.get(1)
                    val audioPath = audioFileName?.let { File(internalDir, it).absolutePath }
                    NoteEntity(
                        title = deriveTitle(rawText, timestamp),
                        text = rawText,
                        timestamp = timestamp,
                        filePath = docFile.uri.toString(),
                        audioFilePath = audioPath
                    )
                } catch (e: Exception) {
                    null // Skip files we can't read
                }
            }
    }

    /**
     * Strips the markdown header from stored content to get the raw transcription.
     */
    private fun extractRawText(fileContent: String): String {
        // Content format: "# Voice Note — ...\n\n<raw text>\n\n<!-- audio: ... -->\n"
        val headerEnd = fileContent.indexOf("\n\n")
        val body = if (headerEnd >= 0) {
            fileContent.substring(headerEnd + 2).trimEnd()
        } else {
            fileContent.trimEnd()
        }
        // Strip the audio comment if present
        return AUDIO_COMMENT_REGEX.replace(body, "").trimEnd()
    }

    /**
     * First ~60 chars of text, or the formatted timestamp if text is too short.
     */
    private fun deriveTitle(text: String, timestamp: LocalDateTime): String {
        val trimmed = text.trim()
        return if (trimmed.length > 60) {
            trimmed.take(57) + "..."
        } else if (trimmed.isNotBlank()) {
            trimmed
        } else {
            "Voice Note — ${DISPLAY_FORMAT.format(timestamp)}"
        }
    }

    private fun parseTimestampFromFileName(fileName: String): LocalDateTime {
        return try {
            // "VoiceNote_2026-02-13_14-30-00.md" → "2026-02-13_14-30-00"
            val tsString = fileName
                .removePrefix(FILE_PREFIX)
                .removeSuffix(FILE_EXTENSION)
            LocalDateTime.parse(tsString, TIMESTAMP_FORMAT)
        } catch (e: Exception) {
            LocalDateTime.now()
        }
    }
}

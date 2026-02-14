package com.voice2text.android.notes

import java.time.LocalDateTime

/**
 * In-memory representation of a saved voice note.
 *
 * Notes are stored as plain text files on disk — this data class is just
 * a convenience for the UI layer. We derive it from the file system rather
 * than maintaining a separate database, keeping things simple for v1.
 */
data class NoteEntity(
    /** Display title — derived from the first line or timestamp. */
    val title: String,

    /** Full transcribed text. */
    val text: String,

    /** When the note was created. */
    val timestamp: LocalDateTime,

    /**
     * Where the file lives. For internal storage this is an absolute path;
     * for SAF-managed external folders this is a content:// URI string.
     */
    val filePath: String
)

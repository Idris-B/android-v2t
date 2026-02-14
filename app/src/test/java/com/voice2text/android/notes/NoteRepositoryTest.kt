package com.voice2text.android.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for [NoteRepository] using internal storage only.
 *
 * SAF (external folder) paths are excluded because Robolectric doesn't
 * provide a real ContentResolver for DocumentFile operations. Those
 * would need instrumented tests on a real device.
 *
 * These tests verify the internal storage path which covers the core
 * logic: file naming, markdown formatting, listing, reading, deleting,
 * and title derivation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NoteRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: NoteRepository
    private lateinit var notesDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = NoteRepository(context)
        notesDir = File(context.filesDir, "notes")
    }

    @After
    fun tearDown() {
        // Clean up notes directory between tests
        notesDir.deleteRecursively()
    }

    // ── saveNote ────────────────────────────────────────────────────────

    @Test
    fun `saveNote creates file in internal storage`() = runTest {
        val note = repo.saveNote("Hello world", null)

        assertTrue("Notes dir should exist", notesDir.exists())
        assertTrue("Note file should exist", File(note.filePath).exists())
    }

    @Test
    fun `saveNote returns NoteEntity with correct fields`() = runTest {
        val text = "Meeting notes from today"
        val note = repo.saveNote(text, null)

        assertEquals(text, note.text)
        assertEquals(text, note.title) // text is < 60 chars so title == text
        assertTrue(note.filePath.contains("VoiceNote_"))
        assertTrue(note.filePath.endsWith(".md"))
        assertNotNull(note.timestamp)
    }

    @Test
    fun `saveNote writes markdown with header`() = runTest {
        val text = "Test transcription"
        val note = repo.saveNote(text, null)

        val fileContent = File(note.filePath).readText()
        assertTrue(
            "File should start with markdown header",
            fileContent.startsWith("# Voice Note — ")
        )
        assertTrue(
            "File should contain the raw text",
            fileContent.contains(text)
        )
    }

    @Test
    fun `saveNote truncates long text for title`() = runTest {
        val longText = "A".repeat(100)
        val note = repo.saveNote(longText, null)

        assertEquals(60, note.title.length)
        assertTrue(note.title.endsWith("..."))
    }

    @Test
    fun `saveNote with blank text uses timestamp as title`() = runTest {
        val note = repo.saveNote("   ", null)

        assertTrue(
            "Blank text should produce timestamp-based title",
            note.title.startsWith("Voice Note — ")
        )
    }

    // ── listNotes ───────────────────────────────────────────────────────

    @Test
    fun `listNotes returns empty list when no notes exist`() = runTest {
        val notes = repo.listNotes(null)
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `listNotes returns saved notes`() = runTest {
        repo.saveNote("First note", null)
        repo.saveNote("Second note", null)

        val notes = repo.listNotes(null)
        assertEquals(2, notes.size)
    }

    @Test
    fun `listNotes sorted by most recent first`() = runTest {
        repo.saveNote("First note", null)
        // Ensure different file timestamps by touching the file
        Thread.sleep(50)
        repo.saveNote("Second note", null)

        val notes = repo.listNotes(null)
        assertEquals(2, notes.size)
        // Most recent first — "Second note" should be at index 0
        assertEquals("Second note", notes[0].text)
        assertEquals("First note", notes[1].text)
    }

    @Test
    fun `listNotes ignores non-VoiceNote files`() = runTest {
        // Create a note via the repo
        repo.saveNote("Real note", null)

        // Create a random file in the notes dir that doesn't match the pattern
        File(notesDir, "random.txt").writeText("not a voice note")

        val notes = repo.listNotes(null)
        assertEquals(1, notes.size)
        assertEquals("Real note", notes[0].text)
    }

    @Test
    fun `listNotes extracts raw text from markdown content`() = runTest {
        repo.saveNote("Hello from the mic", null)

        val notes = repo.listNotes(null)
        assertEquals(1, notes.size)
        // The raw text should NOT contain the markdown header
        assertFalse(notes[0].text.startsWith("#"))
        assertEquals("Hello from the mic", notes[0].text)
    }

    // ── readNote ────────────────────────────────────────────────────────

    @Test
    fun `readNote returns file content for internal storage path`() = runTest {
        val note = repo.saveNote("Some text", null)
        val content = repo.readNote(note.filePath)

        assertTrue(content.startsWith("# Voice Note — "))
        assertTrue(content.contains("Some text"))
    }

    // ── deleteNote ──────────────────────────────────────────────────────

    @Test
    fun `deleteNote removes file from internal storage`() = runTest {
        val note = repo.saveNote("Delete me", null)
        assertTrue(File(note.filePath).exists())

        val deleted = repo.deleteNote(note.filePath)

        assertTrue(deleted)
        assertFalse(File(note.filePath).exists())
    }

    @Test
    fun `deleteNote returns false for nonexistent file`() = runTest {
        val result = repo.deleteNote("/nonexistent/path.md")
        assertFalse(result)
    }

    // ── round-trip ──────────────────────────────────────────────────────

    @Test
    fun `save then list then delete full lifecycle`() = runTest {
        // Save
        val note = repo.saveNote("Lifecycle test", null)
        assertEquals("Lifecycle test", note.text)

        // List
        val notesBefore = repo.listNotes(null)
        assertEquals(1, notesBefore.size)
        assertEquals("Lifecycle test", notesBefore[0].text)

        // Delete
        repo.deleteNote(note.filePath)

        // List after delete
        val notesAfter = repo.listNotes(null)
        assertTrue(notesAfter.isEmpty())
    }
}

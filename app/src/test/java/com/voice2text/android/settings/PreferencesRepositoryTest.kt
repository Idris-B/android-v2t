package com.voice2text.android.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [PreferencesRepository] using Robolectric.
 *
 * DataStore persists to disk so each test gets a fresh context.
 * We verify default values and that setters correctly update the
 * corresponding Flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PreferencesRepositoryTest {

    private lateinit var context: Context
    private lateinit var prefs: PreferencesRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferencesRepository(context)
    }

    // ── Speech engine ───────────────────────────────────────────────────

    @Test
    fun `speechEngine defaults to android`() = runTest {
        val engine = prefs.speechEngine.first()
        assertEquals("android", engine)
    }

    @Test
    fun `setSpeechEngine updates the flow`() = runTest {
        prefs.setSpeechEngine("vosk")
        val engine = prefs.speechEngine.first()
        assertEquals("vosk", engine)
    }

    @Test
    fun `setSpeechEngine can switch back to android`() = runTest {
        prefs.setSpeechEngine("vosk")
        prefs.setSpeechEngine("android")
        val engine = prefs.speechEngine.first()
        assertEquals("android", engine)
    }

    // ── Notes folder URI ────────────────────────────────────────────────

    @Test
    fun `notesFolderUri defaults to null`() = runTest {
        val uri = prefs.notesFolderUri.first()
        assertNull(uri)
    }

    @Test
    fun `setNotesFolderUri stores the URI string`() = runTest {
        val testUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        prefs.setNotesFolderUri(testUri)
        val uri = prefs.notesFolderUri.first()
        assertEquals(testUri, uri)
    }

    @Test
    fun `setNotesFolderUri with null clears the value`() = runTest {
        prefs.setNotesFolderUri("content://some/uri")
        prefs.setNotesFolderUri(null)
        val uri = prefs.notesFolderUri.first()
        assertNull(uri)
    }

    // ── Bluetooth trigger ───────────────────────────────────────────────

    @Test
    fun `bluetoothTriggerEnabled defaults to false`() = runTest {
        val enabled = prefs.bluetoothTriggerEnabled.first()
        assertFalse(enabled)
    }

    @Test
    fun `setBluetoothTriggerEnabled updates the flow`() = runTest {
        prefs.setBluetoothTriggerEnabled(true)
        val enabled = prefs.bluetoothTriggerEnabled.first()
        assertTrue(enabled)
    }

    @Test
    fun `setBluetoothTriggerEnabled can toggle off`() = runTest {
        prefs.setBluetoothTriggerEnabled(true)
        prefs.setBluetoothTriggerEnabled(false)
        val enabled = prefs.bluetoothTriggerEnabled.first()
        assertFalse(enabled)
    }
}

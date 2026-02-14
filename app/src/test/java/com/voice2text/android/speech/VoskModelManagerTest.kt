package com.voice2text.android.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for [VoskModelManager].
 *
 * These tests cover the local state management and file operations.
 * We don't test the actual HTTP download (that would need a mock server
 * or instrumented test) — instead we focus on:
 *
 * - [isModelReady] correctly reflecting disk state
 * - [deleteModel] cleaning up the directory
 * - [downloadState] initial value
 * - [ensureModelDownloaded] short-circuits when model exists
 *
 * The download + extraction path is tested indirectly via integration
 * tests or manual testing on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoskModelManagerTest {

    private lateinit var context: Context
    private lateinit var manager: VoskModelManager
    private lateinit var modelDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = VoskModelManager(context)
        modelDir = File(context.filesDir, VoskEngine.MODEL_DIR_NAME)
    }

    @After
    fun tearDown() {
        modelDir.deleteRecursively()
    }

    // ── isModelReady ────────────────────────────────────────────────────

    @Test
    fun `isModelReady returns false when model directory does not exist`() {
        assertFalse(manager.isModelReady)
    }

    @Test
    fun `isModelReady returns false when model directory is empty`() {
        modelDir.mkdirs()
        assertFalse(manager.isModelReady)
    }

    @Test
    fun `isModelReady returns true when model directory has files`() {
        modelDir.mkdirs()
        File(modelDir, "conf/model.conf").also {
            it.parentFile?.mkdirs()
            it.writeText("dummy model config")
        }
        assertTrue(manager.isModelReady)
    }

    // ── downloadState ───────────────────────────────────────────────────

    @Test
    fun `initial downloadState is Idle`() {
        assertEquals(VoskModelManager.State.Idle, manager.downloadState.value)
    }

    // ── ensureModelDownloaded ────────────────────────────────────────────

    @Test
    fun `ensureModelDownloaded short-circuits to Ready when model exists`() = runTest {
        // Simulate a previously downloaded model
        modelDir.mkdirs()
        File(modelDir, "am/final.mdl").also {
            it.parentFile?.mkdirs()
            it.writeText("fake model data")
        }

        manager.ensureModelDownloaded()

        assertEquals(VoskModelManager.State.Ready, manager.downloadState.value)
    }

    // ── deleteModel ─────────────────────────────────────────────────────

    @Test
    fun `deleteModel removes the model directory`() {
        // Set up a fake model
        modelDir.mkdirs()
        File(modelDir, "file.bin").writeText("data")
        assertTrue(modelDir.exists())

        manager.deleteModel()

        assertFalse(modelDir.exists())
    }

    @Test
    fun `deleteModel transitions state to Idle`() {
        modelDir.mkdirs()
        File(modelDir, "file.bin").writeText("data")

        manager.deleteModel()

        assertEquals(VoskModelManager.State.Idle, manager.downloadState.value)
    }

    @Test
    fun `deleteModel is safe to call when model does not exist`() {
        assertFalse(modelDir.exists())

        // Should not throw
        manager.deleteModel()

        assertFalse(modelDir.exists())
        assertEquals(VoskModelManager.State.Idle, manager.downloadState.value)
    }

    // ── state transitions ───────────────────────────────────────────────

    @Test
    fun `deleteModel after Ready resets to Idle`() = runTest {
        // Simulate model present
        modelDir.mkdirs()
        File(modelDir, "am/final.mdl").also {
            it.parentFile?.mkdirs()
            it.writeText("fake")
        }
        manager.ensureModelDownloaded()
        assertEquals(VoskModelManager.State.Ready, manager.downloadState.value)

        manager.deleteModel()
        assertEquals(VoskModelManager.State.Idle, manager.downloadState.value)
        assertFalse(manager.isModelReady)
    }
}

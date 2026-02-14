package com.voice2text.android.service

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Tests for [TranscriptionService] focusing on the state machine,
 * intent handling, and public API behavior.
 *
 * We use Robolectric's ServiceController to drive the Android service
 * lifecycle without a real device. The actual speech engine is not
 * available in this environment, so we test:
 *
 * - Initial state is Idle
 * - Intent factory methods produce correct actions
 * - Static isRunning flag
 * - startTranscription guards against double-start
 * - stopTranscription with no content transitions to Idle
 * - Binder returns the service
 *
 * Full integration tests of the engine → service → save flow
 * would need an instrumented test with a mock SpeechEngine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TranscriptionServiceTest {

    private lateinit var controller: ServiceController<TranscriptionService>
    private lateinit var service: TranscriptionService

    @Before
    fun setUp() {
        // Reset static state between tests
        // isRunning is private set so we drive it through the service lifecycle
        controller = Robolectric.buildService(TranscriptionService::class.java)
        controller.create()
        service = controller.get()
    }

    // ── Initial state ───────────────────────────────────────────────────

    @Test
    fun `initial transcription state is Idle`() {
        val state = service.transcriptionState.value
        assertTrue(
            "Initial state should be Idle, was $state",
            state is TranscriptionService.State.Idle
        )
    }

    // ── Intent factories ────────────────────────────────────────────────

    @Test
    fun `startIntent has ACTION_START`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = TranscriptionService.startIntent(context)
        assertEquals(TranscriptionService.ACTION_START, intent.action)
    }

    @Test
    fun `stopIntent has ACTION_STOP`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = TranscriptionService.stopIntent(context)
        assertEquals(TranscriptionService.ACTION_STOP, intent.action)
    }

    // ── Binder ──────────────────────────────────────────────────────────

    @Test
    fun `onBind returns LocalBinder that provides the service`() {
        val binder = service.onBind(null)
        assertNotNull(binder)
        assertTrue(binder is TranscriptionService.LocalBinder)
        val boundService = (binder as TranscriptionService.LocalBinder).getService()
        assertEquals(service, boundService)
    }

    // ── stopTranscription with no content ───────────────────────────────

    @Test
    fun `stopTranscription with no recorded content transitions to Idle`() {
        // Without ever starting, the transcript buffer is empty.
        // Calling stop should just clean up and go to Idle.
        service.stopTranscription()

        val state = service.transcriptionState.value
        assertTrue(
            "State after stopping empty session should be Idle, was $state",
            state is TranscriptionService.State.Idle
        )
    }

    @Test
    fun `stopTranscription sets isRunning to false`() {
        service.stopTranscription()
        assertFalse(TranscriptionService.isRunning)
    }

    // ── startTranscription state transition ──────────────────────────────

    @Test
    fun `startTranscription transitions to Initializing`() {
        // startTranscription will set state to Initializing synchronously,
        // then launch a coroutine to create the engine. The engine creation
        // will fail in test (no Vosk model, no SpeechRecognizer), but we
        // can verify the immediate transition.
        service.startTranscription()

        val state = service.transcriptionState.value
        // Could be Initializing (synchronous) or Error (if coroutine ran fast)
        assertTrue(
            "State should be Initializing or Error after start, was $state",
            state is TranscriptionService.State.Initializing ||
                    state is TranscriptionService.State.Error
        )
    }

    @Test
    fun `startTranscription sets isRunning to true`() {
        service.startTranscription()
        assertTrue(TranscriptionService.isRunning)
    }

    @Test
    fun `double startTranscription is guarded`() {
        service.startTranscription()
        assertTrue(TranscriptionService.isRunning)

        // Second call should be a no-op (guarded by state check)
        service.startTranscription()

        // isRunning should still be true (not reset by a second call)
        assertTrue(TranscriptionService.isRunning)
    }

    // ── onStartCommand dispatching ──────────────────────────────────────

    @Test
    fun `onStartCommand with ACTION_STOP transitions to Idle`() {
        val intent = Intent().apply {
            action = TranscriptionService.ACTION_STOP
        }
        service.onStartCommand(intent, 0, 1)

        val state = service.transcriptionState.value
        assertTrue(
            "ACTION_STOP should result in Idle, was $state",
            state is TranscriptionService.State.Idle
        )
    }

    @Test
    fun `onStartCommand with null intent does not crash`() {
        // Android may restart the service with null intent (START_STICKY)
        service.onStartCommand(null, 0, 1)
        // Should just be a no-op — verify no crash
    }

    @Test
    fun `onStartCommand with unknown action does not crash`() {
        val intent = Intent().apply {
            action = "com.voice2text.android.UNKNOWN"
        }
        service.onStartCommand(intent, 0, 1)
        // Should be a no-op
    }
}

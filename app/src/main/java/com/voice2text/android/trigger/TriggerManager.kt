package com.voice2text.android.trigger

import android.content.Context

/**
 * Coordinates activation and deactivation of all trigger types.
 *
 * Currently manages:
 * - **Bluetooth media button** via [MediaSessionTrigger] — holds an active
 *   MediaSession that captures hardware button presses.
 * - **Quick Settings tile** — always available once declared in the manifest;
 *   no activation needed here. [TranscriptionTileService] handles it.
 * - **Notification actions** — built into [TranscriptionService]'s persistent
 *   notification; no activation needed here.
 *
 * The only trigger that requires explicit lifecycle management is the
 * MediaSession (Bluetooth button). The others are passive (system-managed).
 *
 * Usage:
 *   val triggerManager = TriggerManager(applicationContext)
 *   triggerManager.setBluetoothTriggerEnabled(true)  // from settings
 *   // ... later, on app shutdown:
 *   triggerManager.releaseAll()
 */
class TriggerManager(private val context: Context) {

    private val mediaSessionTrigger = MediaSessionTrigger(context)

    /** Whether the Bluetooth media button trigger is currently active. */
    val isBluetoothTriggerActive: Boolean
        get() = mediaSessionTrigger.isActive

    /**
     * Enable or disable the Bluetooth media button trigger.
     *
     * When enabled, pressing the play/pause button on a Bluetooth headset
     * (or wired earbuds) will toggle transcription. When disabled, those
     * buttons behave normally (go to the active music app).
     */
    fun setBluetoothTriggerEnabled(enabled: Boolean) {
        if (enabled) {
            mediaSessionTrigger.activate()
        } else {
            mediaSessionTrigger.deactivate()
        }
    }

    /** Set the BT button behavior mode ("toggle" or "hold"). */
    fun setBtTriggerMode(mode: String) {
        mediaSessionTrigger.mode = mode
    }

    /**
     * Release all trigger resources. Call this when the app is being
     * destroyed or when all triggers should be disabled.
     */
    fun releaseAll() {
        mediaSessionTrigger.deactivate()
    }
}

package com.voice2text.android.trigger

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.voice2text.android.service.TranscriptionService

/**
 * Quick Settings tile that toggles transcription on/off.
 *
 * Users add this tile to their notification shade via "Edit tiles".
 * Once added, a single tap starts or stops recording — no need to
 * open the app at all.
 *
 * How it works:
 * - The tile is stateless between clicks. On each tap we check
 *   [TranscriptionService.isRunning] (a simple static flag) to decide
 *   whether to start or stop.
 * - We send the same ACTION_START / ACTION_STOP intents that the
 *   notification actions and Bluetooth trigger use. The service is
 *   the single source of truth for state.
 *
 * Why Quick Settings?
 * - Available on API 24+ (our min is 26) with no special permissions.
 * - Two taps to reach (pull shade + tap), but very reliable.
 * - No accessibility service needed, no Google Play review friction.
 */
class TranscriptionTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        if (TranscriptionService.isRunning) {
            // Stop transcription
            val stopIntent = TranscriptionService.stopIntent(this)
            startService(stopIntent)
        } else {
            // Start transcription
            val startIntent = TranscriptionService.startIntent(this)
            ContextCompat.startForegroundService(this, startIntent)
        }

        // Give the service a moment to update, then refresh tile
        qsTile?.let { tile ->
            tile.state = if (TranscriptionService.isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.updateTile()
        }
    }

    private fun updateTileState() {
        qsTile?.let { tile ->
            if (TranscriptionService.isRunning) {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Stop Recording"
                tile.contentDescription = "Voice2Text is recording. Tap to stop."
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Voice2Text"
                tile.contentDescription = "Tap to start voice recording."
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                tile.stateDescription = if (TranscriptionService.isRunning) "Recording" else "Idle"
            }
            tile.updateTile()
        }
    }
}

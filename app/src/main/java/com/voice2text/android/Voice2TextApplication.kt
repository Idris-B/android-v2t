package com.voice2text.android

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.voice2text.android.settings.PreferencesRepository
import com.voice2text.android.trigger.TriggerManager

/**
 * Application-level singleton that owns long-lived components whose
 * lifecycle must outlast any single Activity.
 *
 * Currently manages:
 * - [TriggerManager] — so the Bluetooth media-button trigger stays
 *   active even after SettingsActivity is destroyed.
 */
class Voice2TextApplication : Application() {

    lateinit var triggerManager: TriggerManager
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()

        triggerManager = TriggerManager(applicationContext)
        val prefs = PreferencesRepository(applicationContext)

        // Reactively mirror the preference into the trigger manager
        appScope.launch {
            prefs.bluetoothTriggerEnabled.collect { enabled ->
                triggerManager.setBluetoothTriggerEnabled(enabled)
            }
        }
    }

    override fun onTerminate() {
        triggerManager.releaseAll()
        super.onTerminate()
    }
}

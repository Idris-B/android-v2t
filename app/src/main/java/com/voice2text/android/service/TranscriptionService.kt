package com.voice2text.android.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

class TranscriptionService : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): TranscriptionService = this@TranscriptionService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder? = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}

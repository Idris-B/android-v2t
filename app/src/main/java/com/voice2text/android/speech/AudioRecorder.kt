package com.voice2text.android.speech

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Simple wrapper around [MediaRecorder] that captures microphone audio to an M4A file.
 *
 * Usage: call [start] with an output file, then [stop] when done. The recorder
 * uses AAC encoding in an MPEG-4 container, producing compact files suitable for
 * voice playback.
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 44100
        private const val BIT_RATE = 128_000
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    /**
     * Configures and starts recording audio to [file].
     *
     * @param context Android context, needed for MediaRecorder on API 31+.
     * @param file Destination file (should have `.m4a` extension).
     */
    fun start(context: Context, file: File) {
        outputFile = file

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mr.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(SAMPLE_RATE)
            setAudioEncodingBitRate(BIT_RATE)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        recorder = mr
        Log.d(TAG, "Recording started: ${file.absolutePath}")
    }

    /**
     * Stops the recording and releases resources.
     *
     * @return The output file, or null if recording was never started.
     */
    fun stop(): File? {
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            Log.d(TAG, "Recording stopped: ${outputFile?.absolutePath}")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder", e)
            release()
            // Delete incomplete file
            outputFile?.delete()
            null
        }
    }

    /** Releases resources without saving. Deletes any incomplete output file. */
    fun release() {
        try {
            recorder?.reset()
            recorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing recorder", e)
        }
        recorder = null
        outputFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
        outputFile = null
    }
}

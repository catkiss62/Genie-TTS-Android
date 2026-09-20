package com.catkiss62.geniettsbenchmark

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager

/** Shared playback policy for the benchmark and the future AI Companion provider. */
object SystemAudioPolicy {
    fun isSilentOrVibrate(context: Context): Boolean {
        val audioManager = context.getSystemService(AudioManager::class.java)
        return audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL
    }

    fun speechAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}

package moe.rukamori.archivetune.playback

import android.content.SharedPreferences
import androidx.core.content.edit

internal class UsbVolumeStore(private val preferences: SharedPreferences) {
    fun read(deviceKey: String): Float? = synchronized(preferences) {
        readValue(pendingKey(deviceKey)) ?: readValue(deviceKey)
    }

    fun select(deviceKey: String, volumeDb: Float) = synchronized(preferences) {
        preferences.edit { putFloat(pendingKey(deviceKey), volumeDb) }
    }

    fun confirm(deviceKey: String, requestedDb: Float, confirmedDb: Float) = synchronized(preferences) {
        preferences.edit {
            putFloat(deviceKey, confirmedDb)

            if (readValue(pendingKey(deviceKey)) == requestedDb) remove(pendingKey(deviceKey))
        }
    }

    fun reject(deviceKey: String, requestedDb: Float) = synchronized(preferences) {
        if (readValue(pendingKey(deviceKey)) == requestedDb) {
            preferences.edit { remove(pendingKey(deviceKey)) }
        }
    }

    private fun readValue(key: String): Float? = runCatching {
        if (preferences.contains(key)) preferences.getFloat(key, Float.NaN).takeIf(Float::isFinite) else null
    }.getOrNull()

    private fun pendingKey(deviceKey: String): String = "$deviceKey:pending"
}

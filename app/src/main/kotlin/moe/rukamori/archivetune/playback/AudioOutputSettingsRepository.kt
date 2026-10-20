package moe.rukamori.archivetune.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.BitPerfectEnabledKey
import moe.rukamori.archivetune.constants.DirectUsbEnabledKey
import moe.rukamori.archivetune.domain.player.AudioBypassMode
import moe.rukamori.archivetune.utils.dataStore

@Singleton
class AudioOutputSettingsRepository @Inject constructor(@param:ApplicationContext private val context: Context) {
    val mode = context.dataStore.data.map { preferences ->
        when {
            preferences[DirectUsbEnabledKey] == true -> AudioBypassMode.Usb
            preferences[BitPerfectEnabledKey] == true -> AudioBypassMode.Native
            else -> AudioBypassMode.Off
        }
    }

    suspend fun setMode(mode: AudioBypassMode) = withContext(Dispatchers.IO) {
        context.dataStore.edit {
            it[BitPerfectEnabledKey] = mode != AudioBypassMode.Off
            it[DirectUsbEnabledKey] = mode == AudioBypassMode.Usb
        }
        Unit
    }
}

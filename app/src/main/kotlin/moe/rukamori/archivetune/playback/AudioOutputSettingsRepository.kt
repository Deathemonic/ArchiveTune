package moe.rukamori.archivetune.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import com.truesample.usbaudio.UsbAudioOutput
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.BitPerfectEnabledKey
import moe.rukamori.archivetune.constants.DirectUsbEnabledKey
import moe.rukamori.archivetune.domain.player.AudioBypassMode
import moe.rukamori.archivetune.domain.player.UsbAvailability
import moe.rukamori.archivetune.utils.dataStore

@Singleton
class AudioOutputSettingsRepository @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private fun hasUsbOutput() = usbManager.deviceList.values.any { UsbAudioOutput.isUsbAudioOutput(it) }

    val usbAvailability = observeUsbAvailability(
        register = { changed ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = changed()
            }
            ContextCompat.registerReceiver(
                context, receiver,
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            AutoCloseable { context.unregisterReceiver(receiver) }
        },
        hasOutput = ::hasUsbOutput,
    )

    val mode = context.dataStore.data.map { preferences ->
        when {
            preferences[DirectUsbEnabledKey] == true -> AudioBypassMode.Usb
            preferences[BitPerfectEnabledKey] == true -> AudioBypassMode.Native
            else -> AudioBypassMode.Off
        }
    }

    suspend fun setMode(mode: AudioBypassMode) = withContext(Dispatchers.IO) {
        context.dataStore.edit {
            val selected = if (mode == AudioBypassMode.Usb && !hasUsbOutput()) AudioBypassMode.Off else mode
            it[BitPerfectEnabledKey] = selected != AudioBypassMode.Off
            it[DirectUsbEnabledKey] = selected == AudioBypassMode.Usb
        }
        Unit
    }
}

internal fun observeUsbAvailability(
    register: (changed: () -> Unit) -> AutoCloseable,
    hasOutput: () -> Boolean,
): Flow<UsbAvailability> = callbackFlow {
    fun publish() {
        val availability = try {
            if (hasOutput()) UsbAvailability.Available else UsbAvailability.Unavailable
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            UsbAvailability.Error
        }
        trySend(availability)
    }
    val registration = register { launch { publish() } }
    try {
        publish()
        awaitClose()
    } finally {
        registration.close()
    }
}.catch { failure ->
    if (failure is CancellationException) throw failure
    emit(UsbAvailability.Error)
}.distinctUntilChanged().flowOn(Dispatchers.IO)

package moe.rukamori.archivetune.domain.player

import javax.inject.Inject
import moe.rukamori.archivetune.playback.AudioOutputSettingsRepository

enum class AudioBypassMode { Off, Native, Usb }

class ConfigureAudioOutputUseCase @Inject constructor(private val repository: AudioOutputSettingsRepository) {
    val mode = repository.mode
    suspend fun setMode(mode: AudioBypassMode) = repository.setMode(mode)
}

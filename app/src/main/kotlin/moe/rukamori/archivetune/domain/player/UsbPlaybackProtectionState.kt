package moe.rukamori.archivetune.domain.player

sealed interface UsbPlaybackProtectionState {
    data object Disabled : UsbPlaybackProtectionState
    data object Preparing : UsbPlaybackProtectionState
    data object Ready : UsbPlaybackProtectionState
    data object Playing : UsbPlaybackProtectionState
    data class Error(val failure: UsbPlaybackProtectionFailure) : UsbPlaybackProtectionState
}

enum class UsbPlaybackProtectionFailure { Setup, Playback, Release }

enum class UsbAvailability { Available, Unavailable, Error }

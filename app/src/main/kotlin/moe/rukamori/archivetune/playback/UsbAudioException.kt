package moe.rukamori.archivetune.playback

import androidx.annotation.StringRes

internal class UsbAudioException(@param:StringRes val messageRes: Int) : IllegalStateException()

internal fun checkUsb(condition: Boolean, @StringRes messageRes: Int) {
    if (!condition) throw UsbAudioException(messageRes)
}

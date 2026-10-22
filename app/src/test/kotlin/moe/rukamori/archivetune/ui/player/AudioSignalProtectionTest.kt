package moe.rukamori.archivetune.ui.player

import android.app.Application
import moe.rukamori.archivetune.domain.player.UsbPlaybackProtectionFailure
import moe.rukamori.archivetune.domain.player.UsbPlaybackProtectionState
import moe.rukamori.archivetune.models.ActiveOutputDevice
import moe.rukamori.archivetune.models.PlayerOutputDevice
import moe.rukamori.archivetune.playback.AudioSignalData
import moe.rukamori.archivetune.playback.DirectUsbAudioState
import moe.rukamori.archivetune.playback.StreamingSourceData
import moe.rukamori.archivetune.playback.NativeUsbAudioState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class AudioSignalProtectionTest {
    @Test
    fun protectionFailuresAreVisibleDuringUsbPlayback() {
        for (failure in UsbPlaybackProtectionFailure.entries) {
            val model = signal(true, UsbPlaybackProtectionState.Error(failure)).toAudioSignalUiModel()
            assertTrue(model.usbBackgroundProtectionFailed)
        }
    }

    @Test
    fun healthyProtectionDoesNotShowAnError() {
        val model = signal(true, UsbPlaybackProtectionState.Playing).toAudioSignalUiModel()
        assertFalse(model.usbBackgroundProtectionFailed)
    }

    @Test
    fun staleUsbFailuresDoNotWarnDuringNormalPlayback() {
        val model = signal(false, UsbPlaybackProtectionState.Error(UsbPlaybackProtectionFailure.Release)).toAudioSignalUiModel()
        assertFalse(model.usbBackgroundProtectionFailed)
    }

    private fun signal(usbEnabled: Boolean, protection: UsbPlaybackProtectionState) = AudioSignalData(
        source = StreamingSourceData(null, null, false, null, null),
        native = NativeUsbAudioState(),
        usb = DirectUsbAudioState(enabled = usbEnabled),
        outputDevice = ActiveOutputDevice(PlayerOutputDevice.Usb, "USB DAC"),
        backgroundProtection = protection,
    )
}

package moe.rukamori.archivetune.ui.player

import android.view.KeyEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

internal val LocalUsbVolumeKeyAction = staticCompositionLocalOf<(Int) -> Boolean> { { false } }

internal class UsbVolumeKeyHandler(private val adjustVolume: (Int) -> Boolean) {
    private val handledKeys = mutableSetOf<Int>()

    fun onKeyEvent(event: KeyEvent): Boolean = when (event.action) {
        KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode)
        KeyEvent.ACTION_UP -> onKeyUp(event.keyCode)
        else -> false
    }

    fun onKeyDown(keyCode: Int): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return false
        }

        if (!adjustVolume(direction)) return false

        handledKeys += keyCode

        return true
    }

    fun onKeyUp(keyCode: Int): Boolean = handledKeys.remove(keyCode)
}

@Composable
internal fun HandleUsbVolumeKeys() {
    val view = LocalView.current
    val adjustVolume = LocalUsbVolumeKeyAction.current
    val handler = remember(adjustVolume) { UsbVolumeKeyHandler(adjustVolume) }

    DisposableEffect(view, handler) {
        val window = generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        val original = window?.callback
        val callback = original?.let { UsbVolumeWindowCallback(it, handler) }

        if (window != null && callback != null) window.callback = callback

        onDispose {
            if (window != null && callback != null && window.callback === callback) window.callback = original
        }
    }
}

internal class UsbVolumeWindowCallback(
    private val original: Window.Callback,
    private val handler: UsbVolumeKeyHandler,
) : Window.Callback by original {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = handler.onKeyEvent(event) || original.dispatchKeyEvent(event)
}

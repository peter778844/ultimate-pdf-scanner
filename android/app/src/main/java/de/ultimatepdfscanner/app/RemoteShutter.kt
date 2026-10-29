package de.ultimatepdfscanner.app

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent

/**
 * Bluetooth camera remotes (e.g. "AB Shutter 3", selfie-stick buttons) pair as keyboards and
 * send one of a few key codes: volume up/down (iOS mode), enter (Android mode), camera,
 * space or a media/headset key. While the camera screen is open, any of them takes a photo;
 * the phone's own volume buttons work the same way, as in the system camera.
 */
object RemoteShutter {
    private val SHUTTER_KEYS = setOf(
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_FOCUS, KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_HEADSETHOOK,
    )

    fun isShutterKey(keyCode: Int) = keyCode in SHUTTER_KEYS

    /** True if an external keyboard-class input device (a paired remote) is connected. */
    fun remoteConnected(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false // isExternal needs Android 10
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        for (id in im.inputDeviceIds) {
            val d: InputDevice = InputDevice.getDevice(id) ?: continue
            if (d.isExternal && !d.isVirtual && (d.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD) return true
        }
        return false
    }
}

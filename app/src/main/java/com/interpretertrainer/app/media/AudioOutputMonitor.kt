package com.interpretertrainer.app.media

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

enum class AudioRouteKind {
    BLUETOOTH,
    WIRED,
    EXTERNAL,
    SPEAKER
}

data class AudioOutputRoute(
    val kind: AudioRouteKind,
    val label: String,
    val deviceName: String? = null
) {
    val isExternal: Boolean get() = kind != AudioRouteKind.SPEAKER
}

@Composable
fun rememberAudioOutputRoute(): AudioOutputRoute {
    val context = LocalContext.current.applicationContext
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var route by remember { mutableStateOf(resolveAudioRoute(audioManager)) }

    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                route = resolveAudioRoute(audioManager)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                route = resolveAudioRoute(audioManager)
            }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        route = resolveAudioRoute(audioManager)

        onDispose {
            audioManager.unregisterAudioDeviceCallback(callback)
        }
    }

    return route
}

private fun resolveAudioRoute(audioManager: AudioManager): AudioOutputRoute {
    val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    outputs.firstOrNull { it.type in bluetoothTypes }?.let { device ->
        return AudioOutputRoute(
            kind = AudioRouteKind.BLUETOOTH,
            label = "Bluetooth audio connected",
            deviceName = device.productName?.toString()?.takeIf { it.isNotBlank() }
        )
    }

    outputs.firstOrNull { it.type in wiredTypes }?.let { device ->
        return AudioOutputRoute(
            kind = AudioRouteKind.WIRED,
            label = "Wired headphones connected",
            deviceName = device.productName?.toString()?.takeIf { it.isNotBlank() }
        )
    }

    outputs.firstOrNull { it.type in otherExternalTypes }?.let { device ->
        return AudioOutputRoute(
            kind = AudioRouteKind.EXTERNAL,
            label = "External audio connected",
            deviceName = device.productName?.toString()?.takeIf { it.isNotBlank() }
        )
    }

    return AudioOutputRoute(
        kind = AudioRouteKind.SPEAKER,
        label = "Phone speaker"
    )
}

private val bluetoothTypes = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_HEARING_AID
)

private val wiredTypes = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET
)

private val otherExternalTypes = setOf(
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC,
    AudioDeviceInfo.TYPE_HDMI_EARC,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL
)

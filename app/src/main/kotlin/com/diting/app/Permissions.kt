package com.diting.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permission state, checked at the point of use.
 *
 * Requesting a permission at launch is not the same as having it. The user can
 * deny the dialog, dismiss it, or revoke the grant later from Settings, and on
 * Android 12+ the system itself revokes permissions for apps left unused for a
 * few months. Every one of those leaves the app running with the permission
 * gone.
 *
 * That matters more here than in most apps because of what the platform does
 * when a permission is missing:
 *
 * - `startForeground(…, FOREGROUND_SERVICE_TYPE_MICROPHONE)` throws
 *   `SecurityException` when RECORD_AUDIO is not granted. It does not fail
 *   quietly — it kills the process.
 * - `BluetoothLeScanner.startScan` throws the same way without BLUETOOTH_SCAN,
 *   as does every `BluetoothGatt` call without BLUETOOTH_CONNECT.
 *
 * So the choice is not between checking and not checking. It is between
 * checking and crashing.
 */
object DitingPermissions {

    /** Needed before the phone microphone can be used at all. */
    const val AUDIO = Manifest.permission.RECORD_AUDIO

    /**
     * Scanning for and talking to the recorder.
     *
     * API 31 split Bluetooth into scan/connect permissions that no longer imply
     * location. Below that the platform refuses to scan without a location
     * grant, however unrelated that is to what the app does.
     */
    val bluetooth: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Everything the app asks for up front. */
    fun all(): Array<String> = buildList {
        add(AUDIO)
        addAll(bluetooth)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasAudio(context: Context): Boolean = has(context, AUDIO)

    fun hasBluetooth(context: Context): Boolean = bluetooth.all { has(context, it) }

    /**
     * @throws MissingPermission naming what is missing, in words the user can
     *   act on. Callers surface [MissingPermission.message] directly.
     */
    fun requireAudio(context: Context) {
        if (!hasAudio(context)) {
            throw MissingPermission(AUDIO, "需要麦克风权限才能录音。请在系统设置里开启后重试。")
        }
    }

    fun requireBluetooth(context: Context) {
        val missing = bluetooth.firstOrNull { !has(context, it) } ?: return
        throw MissingPermission(
            missing,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "需要「附近的设备」权限才能找到并连接录音卡。请在系统设置里开启后重试。"
            } else {
                "Android 12 以下的系统要求定位权限才允许蓝牙扫描。请在系统设置里开启后重试。"
            },
        )
    }
}

/**
 * A permission the app needs is not granted.
 *
 * Extends `SecurityException` so that a call site which already catches the
 * platform's own failure keeps working, but carries a message written for the
 * user rather than for logcat — the device screens show `message` in a dialog.
 */
class MissingPermission(val permission: String, message: String) : SecurityException(message)

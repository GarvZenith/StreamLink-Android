package com.streamlink.android.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlin.math.abs

object DeviceIdentity {

    /**
     * Generates a permanent, hardware-bound 6-digit numeric device ID.
     * Derived deterministically from Settings.Secure.ANDROID_ID + Build hardware signature.
     *
     * Even if the app is completely uninstalled and reinstalled, this method returns
     * the EXACT SAME 6-digit code for the device.
     */
    fun getPermanentCode(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) { null } ?: "streamlink_fallback"

        val hardwareSig = "${androidId}_${Build.BOARD}_${Build.BRAND}_streamlink"
        val hash = abs(hardwareSig.hashCode())
        val code = 100000 + (hash % 900000)
        return code.toString()
    }
}

package com.streamlink.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.streamlink.android.databinding.ActivityMainBinding
import com.streamlink.android.input.RemoteInputAccessibilityService
import com.streamlink.android.input.ShizukuInjector
import com.streamlink.android.updater.AutoUpdater
import com.streamlink.android.util.DeviceIdentity
import com.streamlink.android.webrtc.WebRtcCore

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WebRtcCore.init(applicationContext)
        AutoUpdater.checkForUpdate(this)

        val code = DeviceIdentity.getPermanentCode(this)
        binding.tvPermanentCode.text = code

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        binding.btnStartHost.setOnClickListener {
            startActivity(Intent(this, HostActivity::class.java))
        }

        binding.btnConnectViewer.setOnClickListener {
            val roomCodeInput = binding.etRoomCode.text.toString().trim()
            if (roomCodeInput.length != 6) {
                Toast.makeText(this, "Enter valid 6-digit code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, ViewerActivity::class.java).apply {
                putExtra(ViewerActivity.EXTRA_ROOM_CODE, roomCodeInput)
            }
            startActivity(intent)
        }

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnShizuku.setOnClickListener {
            if (ShizukuInjector.canRequest()) {
                ShizukuInjector.requestPermission()
            } else {
                Toast.makeText(this, "Shizuku app is not running or not supported", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
    }

    private fun updatePermissionStatuses() {
        val accRunning = RemoteInputAccessibilityService.isRunning()
        binding.tvAccessibilityStatus.text = if (accRunning) {
            "Accessibility Service: Enabled (Active)"
        } else {
            "Accessibility Service: Disabled"
        }

        val shizukuAvailable = ShizukuInjector.isAvailable()
        binding.tvShizukuStatus.text = if (shizukuAvailable) {
            "Shizuku (Full Control): Granted & Active"
        } else {
            "Shizuku (Full Control): Not Granted"
        }
    }
}

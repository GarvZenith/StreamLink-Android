package com.streamlink.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.streamlink.android.databinding.ActivityHostBinding
import com.streamlink.android.host.ScreenCaptureService
import com.streamlink.android.input.InputController
import com.streamlink.android.input.RemoteInputAccessibilityService
import com.streamlink.android.input.ShizukuInjector
import com.streamlink.android.signaling.SignalingClient
import com.streamlink.android.util.DeviceIdentity
import com.streamlink.android.webrtc.FileTransfer
import com.streamlink.android.webrtc.WebRtcClient
import com.streamlink.android.webrtc.WebRtcCore
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

class HostActivity : AppCompatActivity(), SignalingClient.Listener {

    private lateinit var binding: ActivityHostBinding
    private var signaling: SignalingClient? = null
    private var webRtcClient: WebRtcClient? = null
    private var inputController: InputController? = null
    private var localTrack: VideoTrack? = null
    private var activeViewerId: String? = null

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.onTrackReady = { track ->
                runOnUiThread {
                    localTrack = track
                    startSignaling()
                }
            }
            ScreenCaptureService.start(this, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val displayMetrics = resources.displayMetrics
        inputController = InputController(displayMetrics.widthPixels, displayMetrics.heightPixels)

        checkControlPermissions()

        binding.btnStopSharing.setOnClickListener {
            stopSharing()
            finish()
        }

        // If screen capture is already running (re-opened activity), reuse existing track
        if (ScreenCaptureService.currentVideoTrack != null) {
            localTrack = ScreenCaptureService.currentVideoTrack
            startSignaling()
        } else {
            requestScreenCapturePermission()
        }
    }

    private fun checkControlPermissions() {
        val accActive = RemoteInputAccessibilityService.isRunning()
        val shizukuActive = ShizukuInjector.isAvailable()

        if (!accActive && !shizukuActive) {
            Toast.makeText(
                this,
                "Notice: Enable Accessibility Service in Settings so remote mouse clicks can control this phone!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestScreenCapturePermission() {
        val projMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(projMgr.createScreenCaptureIntent())
    }

    private fun startSignaling() {
        binding.tvStatus.text = "Connecting to signaling server..."
        signaling = SignalingClient(this).apply { connect() }
    }

    override fun onConnected() {
        val permanentCode = DeviceIdentity.getPermanentCode(this)
        runOnUiThread {
            binding.tvStatus.text = "Registering device room..."
            signaling?.createRoom("Android Host", Build.MODEL, permanentCode) { success, roomId ->
                runOnUiThread {
                    if (success && roomId != null) {
                        binding.tvCode.text = roomId
                        binding.tvStatus.text = "Waiting for remote viewer..."
                        binding.progressBar.visibility = View.GONE
                    } else {
                        binding.tvStatus.text = "Failed to register room"
                    }
                }
            }
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            binding.tvStatus.text = "Disconnected from server"
        }
    }

    override fun onViewerConnected(viewerId: String, deviceType: String) {
        activeViewerId = viewerId
        runOnUiThread {
            binding.tvStatus.text = "Viewer connected ($deviceType). Negotiating WebRTC..."
        }
        Thread {
            val iceServers = WebRtcCore.loadIceServers()
            runOnUiThread {
                val client = WebRtcClient(
                    isHost = true,
                    remoteId = viewerId,
                    iceServers = iceServers,
                    signaling = signaling!!,
                    localVideoTrack = localTrack,
                    onRemoteVideoTrack = {},
                    onInputEvent = { json -> inputController?.handle(json) },
                    onFileChannel = { dc ->
                        FileTransfer(this, dc) { msg ->
                            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                        }
                    },
                    onConnectionState = { state ->
                        runOnUiThread {
                            binding.tvStatus.text = "WebRTC Status: $state"
                        }
                    }
                )
                webRtcClient = client
                client.startAsHost()
            }
        }.start()
    }

    override fun onRemoteOffer(senderId: String, sdp: SessionDescription) {}

    override fun onRemoteAnswer(senderId: String, sdp: SessionDescription) {
        webRtcClient?.onRemoteAnswer(sdp)
    }

    override fun onRemoteIce(senderId: String, candidate: IceCandidate) {
        webRtcClient?.onRemoteIce(candidate)
    }

    override fun onHostDisconnected() {}

    override fun onViewerDisconnected(viewerId: String) {
        if (viewerId == activeViewerId) {
            runOnUiThread {
                binding.tvStatus.text = "Remote viewer disconnected. Waiting for new connection..."
                webRtcClient?.close()
                webRtcClient = null
            }
        }
    }

    override fun onRelayInput(senderId: String, eventData: JSONObject) {
        inputController?.handle(eventData)
    }

    /** Pressing Back minimizes the app to background so screen sharing STAYS ALIVE. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    /** Explicitly stop screen capture and close connection when 'Stop Sharing' is clicked. */
    private fun stopSharing() {
        webRtcClient?.close()
        signaling?.disconnect()
        ScreenCaptureService.stop(this)
        binding.tvStatus.text = "Sharing Stopped"
    }

    override fun onDestroy() {
        // Do NOT stop screen capture service on activity destroy (allows background sharing)
        super.onDestroy()
    }
}

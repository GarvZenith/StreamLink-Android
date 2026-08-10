package com.streamlink.android

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.streamlink.android.databinding.ActivityViewerBinding
import com.streamlink.android.signaling.SignalingClient
import com.streamlink.android.webrtc.FileTransfer
import com.streamlink.android.webrtc.WebRtcClient
import com.streamlink.android.webrtc.WebRtcCore
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.RendererCommon
import org.webrtc.SessionDescription

class ViewerActivity : AppCompatActivity(), SignalingClient.Listener {

    private lateinit var binding: ActivityViewerBinding
    private var signaling: SignalingClient? = null
    private var webRtcClient: WebRtcClient? = null
    private var roomCode: String = ""
    private var hostId: String? = null

    companion object {
        const val EXTRA_ROOM_CODE = "extra_room_code"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: ""
        if (roomCode.isEmpty()) {
            Toast.makeText(this, "Room code missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initRenderer()
        setupTouchAndNavListeners()

        binding.tvViewerStatus.text = "Connecting to server..."
        signaling = SignalingClient(this).apply { connect() }
    }

    private fun initRenderer() {
        binding.surfaceView.init(WebRtcCore.eglBase.eglBaseContext, null)
        binding.surfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        binding.surfaceView.setEnableHardwareScaler(true)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndNavListeners() {
        binding.surfaceView.setOnTouchListener { v, event ->
            val w = v.width.toFloat()
            val h = v.height.toFloat()
            if (w <= 0f || h <= 0f) return@setOnTouchListener false

            val xPct = (event.x / w).coerceIn(0f, 1f)
            val yPct = (event.y / h).coerceIn(0f, 1f)

            val typeStr = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> "touchstart"
                MotionEvent.ACTION_MOVE -> "touchmove"
                MotionEvent.ACTION_UP -> "touchend"
                else -> return@setOnTouchListener false
            }

            val json = JSONObject().apply {
                put("type", typeStr)
                put("xPercent", xPct.toDouble())
                put("yPercent", yPct.toDouble())
            }
            webRtcClient?.sendInput(json)
            true
        }

        binding.btnNavBack.setOnClickListener { sendNav("back") }
        binding.btnNavHome.setOnClickListener { sendNav("home") }
        binding.btnNavRecents.setOnClickListener { sendNav("recents") }
    }

    private fun sendNav(action: String) {
        val json = JSONObject().apply {
            put("type", "nav")
            put("action", action)
        }
        webRtcClient?.sendInput(json)
    }

    override fun onConnected() {
        runOnUiThread {
            binding.tvViewerStatus.text = "Joining room $roomCode..."
            signaling?.joinRoom(roomCode, "Android Viewer") { success, hostDevice ->
                runOnUiThread {
                    if (success) {
                        binding.tvViewerStatus.text = "Joined! Connecting to $hostDevice..."
                    } else {
                        binding.tvViewerStatus.text = "Failed to join room"
                        Toast.makeText(this, "Room not found or invalid code", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onDisconnected() {
        runOnUiThread { binding.tvViewerStatus.text = "Disconnected from server" }
    }

    override fun onViewerConnected(viewerId: String, deviceType: String) {}

    override fun onRemoteOffer(senderId: String, sdp: SessionDescription) {
        hostId = senderId
        Thread {
            val iceServers = WebRtcCore.loadIceServers()
            runOnUiThread {
                val client = WebRtcClient(
                    isHost = false,
                    remoteId = senderId,
                    iceServers = iceServers,
                    signaling = signaling!!,
                    localVideoTrack = null,
                    onRemoteVideoTrack = { track ->
                        runOnUiThread {
                            track.addSink(binding.surfaceView)
                            binding.tvViewerStatus.visibility = View.GONE
                            binding.viewerProgressBar.visibility = View.GONE
                        }
                    },
                    onInputEvent = {},
                    onFileChannel = { dc ->
                        FileTransfer(this, dc) { msg ->
                            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                        }
                    },
                    onConnectionState = { state ->
                        runOnUiThread {
                            if (state != "CONNECTED") {
                                binding.tvViewerStatus.text = "Status: $state"
                            }
                        }
                    }
                )
                webRtcClient = client
                client.onRemoteOffer(sdp)
            }
        }.start()
    }

    override fun onRemoteAnswer(senderId: String, sdp: SessionDescription) {}

    override fun onRemoteIce(senderId: String, candidate: IceCandidate) {
        webRtcClient?.onRemoteIce(candidate)
    }

    override fun onHostDisconnected() {
        runOnUiThread {
            binding.tvViewerStatus.visibility = View.VISIBLE
            binding.tvViewerStatus.text = "Host disconnected"
            Toast.makeText(this, "Host left session", Toast.LENGTH_LONG).show()
        }
    }

    override fun onViewerDisconnected(viewerId: String) {}
    override fun onRelayInput(senderId: String, eventData: JSONObject) {}

    override fun onDestroy() {
        webRtcClient?.close()
        signaling?.disconnect()
        binding.surfaceView.release()
        super.onDestroy()
    }
}

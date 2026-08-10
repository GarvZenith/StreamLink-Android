package com.streamlink.android.webrtc

import android.util.Log
import com.streamlink.android.Config
import com.streamlink.android.signaling.SignalingClient
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Ek viewer<->host peer connection. Dono role isi class se chalte hain:
 *  - HOST  (isHost=true): screen video track add karta hai, 'remote-input' + 'file'
 *    data channels banata hai, aur OFFER bhejta hai. Received input -> onInputEvent.
 *  - VIEWER(isHost=false): OFFER ka ANSWER deta hai, remote video track render ke
 *    liye deta hai, aur input events 'remote-input' channel par bhejta hai.
 *
 * Protocol bilkul wahi hai jo web/desktop client use karta hai -> full interop.
 */
class WebRtcClient(
    private val isHost: Boolean,
    private val remoteId: String,
    private val iceServers: List<PeerConnection.IceServer>,
    private val signaling: SignalingClient,
    private val localVideoTrack: VideoTrack?,           // host only
    private val onRemoteVideoTrack: (VideoTrack) -> Unit, // viewer only
    private val onInputEvent: (JSONObject) -> Unit,       // host only (inject)
    private val onFileChannel: (DataChannel) -> Unit,     // file transfer wiring
    private val onConnectionState: (String) -> Unit
) {
    private var pc: PeerConnection? = null
    private var inputChannel: DataChannel? = null

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) { signaling.sendIce(remoteId, c) }
        override fun onIceCandidatesRemoved(cs: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onRenegotiationNeeded() {}
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            onConnectionState(s.name)
        }
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>?) {
            (receiver.track() as? VideoTrack)?.let { onRemoteVideoTrack(it) }
        }
        override fun onDataChannel(dc: DataChannel) {
            when (dc.label()) {
                "file" -> onFileChannel(dc)
                else -> { inputChannel = dc; wireInputChannel(dc) }
            }
        }
    }

    private fun buildPc() {
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }
        pc = WebRtcCore.factory.createPeerConnection(cfg, pcObserver)
    }

    /** HOST: viewer aane par offerer ban ke connection start karo. */
    fun startAsHost() {
        buildPc()
        val p = pc ?: return
        localVideoTrack?.let { p.addTrack(it, listOf("stream0")) }

        // Input + file channels host banata hai (web/desktop jaisa).
        val init = DataChannel.Init().apply { ordered = true }
        inputChannel = p.createDataChannel("remote-input", init)
        wireInputChannel(inputChannel!!)
        onFileChannel(p.createDataChannel("file", DataChannel.Init().apply { ordered = true }))

        applyVideoBitrate(p)

        p.createOffer(SimpleSdpObserver(onCreate = { sdp ->
            p.setLocalDescription(SimpleSdpObserver(), sdp)
            signaling.sendOffer(remoteId, sdp)
        }), MediaConstraints())
    }

    /** VIEWER: remote offer aaya -> answer banao. */
    fun onRemoteOffer(sdp: SessionDescription) {
        buildPc()
        val p = pc ?: return
        p.setRemoteDescription(SimpleSdpObserver(onSet = {
            p.createAnswer(SimpleSdpObserver(onCreate = { ans ->
                p.setLocalDescription(SimpleSdpObserver(), ans)
                signaling.sendAnswer(remoteId, ans)
            }), MediaConstraints())
        }), sdp)
    }

    /** HOST: viewer ka answer. */
    fun onRemoteAnswer(sdp: SessionDescription) {
        pc?.setRemoteDescription(SimpleSdpObserver(), sdp)
    }

    fun onRemoteIce(c: IceCandidate) { pc?.addIceCandidate(c) }

    /** VIEWER: input event host ko bhejo (P2P channel; warna socket relay). */
    fun sendInput(event: JSONObject) {
        val ch = inputChannel
        if (ch != null && ch.state() == DataChannel.State.OPEN) {
            val bytes = event.toString().toByteArray(StandardCharsets.UTF_8)
            ch.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        } else {
            signaling.sendRelayInput(remoteId, event)
        }
    }

    private fun wireInputChannel(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(prev: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                try {
                    onInputEvent(JSONObject(String(bytes, StandardCharsets.UTF_8)))
                } catch (e: Exception) { Log.w(TAG, "bad input json: $e") }
            }
        })
    }

    private fun applyVideoBitrate(p: PeerConnection) {
        try {
            val sender = p.senders.firstOrNull { it.track()?.kind() == "video" } ?: return
            val params = sender.parameters
            if (params.encodings.isEmpty())
                params.encodings.add(org.webrtc.RtpParameters.Encoding(null, true, null))
            params.encodings[0].maxBitrateBps = Config.VIDEO_MAX_BITRATE
            params.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            sender.parameters = params
        } catch (e: Exception) { Log.w(TAG, "bitrate: $e") }
    }

    fun close() {
        try { inputChannel?.close() } catch (_: Exception) {}
        try { pc?.close(); pc?.dispose() } catch (_: Exception) {}
        pc = null
    }

    companion object { private const val TAG = "WebRtcClient" }
}

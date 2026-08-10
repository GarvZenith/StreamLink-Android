package com.streamlink.android.signaling

import android.util.Log
import com.streamlink.android.Config
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Signaling wrapper — SAME events/schema as desktop server (server/server.js) &
 * web client (client/app.js). Isse Android, existing PC/web clients ke saath
 * directly connect hota hai.
 *
 * Host  : create-room -> (viewer-connected) -> signal-offer/answer/ice
 * Viewer: join-room   -> (signal-offer)     -> signal-answer/ice
 */
class SignalingClient(private val listener: Listener) {

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        /** Host: ek viewer aaya (offerer banna hai). */
        fun onViewerConnected(viewerId: String, deviceType: String)
        fun onRemoteOffer(senderId: String, sdp: SessionDescription)
        fun onRemoteAnswer(senderId: String, sdp: SessionDescription)
        fun onRemoteIce(senderId: String, candidate: IceCandidate)
        fun onHostDisconnected()
        fun onViewerDisconnected(viewerId: String)
        /** Fallback relay input (jab data channel abhi open nahi). */
        fun onRelayInput(senderId: String, eventData: JSONObject)
    }

    private var socket: Socket? = null
    var roomId: String? = null
        private set
    private var keepAlive: Thread? = null

    fun connect() {
        val opts = IO.Options().apply {
            transports = arrayOf("websocket", "polling")
            reconnection = true
            reconnectionAttempts = Int.MAX_VALUE
            reconnectionDelay = 1500
        }
        val s = IO.socket(Config.SERVER_URL, opts)
        socket = s

        s.on(Socket.EVENT_CONNECT) { listener.onConnected(); startKeepAlive() }
        s.on(Socket.EVENT_DISCONNECT) { listener.onDisconnected(); stopKeepAlive() }

        s.on("signal-offer") { a ->
            val o = a[0] as JSONObject
            val sender = o.getString("senderId")
            val offer = o.getJSONObject("offer")
            listener.onRemoteOffer(sender, SessionDescription(
                SessionDescription.Type.OFFER, offer.getString("sdp")))
        }
        s.on("signal-answer") { a ->
            val o = a[0] as JSONObject
            val sender = o.getString("senderId")
            val ans = o.getJSONObject("answer")
            listener.onRemoteAnswer(sender, SessionDescription(
                SessionDescription.Type.ANSWER, ans.getString("sdp")))
        }
        s.on("signal-ice") { a ->
            val o = a[0] as JSONObject
            val sender = o.getString("senderId")
            val c = o.getJSONObject("candidate")
            if (c.has("candidate") && !c.isNull("candidate")) {
                listener.onRemoteIce(sender, IceCandidate(
                    c.optString("sdpMid", null),
                    c.optInt("sdpMLineIndex", 0),
                    c.getString("candidate")))
            }
        }
        s.on("viewer-connected") { a ->
            val o = a[0] as JSONObject
            listener.onViewerConnected(o.getString("viewerId"),
                o.optString("deviceType", "Remote"))
        }
        s.on("host-disconnected") { listener.onHostDisconnected() }
        s.on("viewer-disconnected") { a ->
            listener.onViewerDisconnected((a[0] as JSONObject).optString("viewerId"))
        }
        s.on("remote-input-event") { a ->
            val o = a[0] as JSONObject
            listener.onRelayInput(o.optString("senderId"), o.getJSONObject("eventData"))
        }
        s.connect()
    }

    /** HOST: room banao. requestedId = permanent 6-digit code (unattended access). */
    fun createRoom(deviceType: String, deviceName: String, requestedId: String?,
                   cb: (Boolean, String?) -> Unit) {
        val payload = JSONObject()
            .put("deviceType", deviceType).put("deviceName", deviceName)
        if (requestedId != null) payload.put("roomId", requestedId)
        socket?.emit("create-room", arrayOf(payload)) { res ->
            val r = res.getOrNull(0) as? JSONObject
            if (r?.optBoolean("success") == true) {
                roomId = r.getString("roomId"); cb(true, roomId)
            } else cb(false, null)
        }
    }

    /** VIEWER: room join karo. */
    fun joinRoom(code: String, deviceType: String,
                 cb: (Boolean, String?) -> Unit) {
        val payload = JSONObject().put("roomId", code).put("deviceType", deviceType)
        socket?.emit("join-room", arrayOf(payload)) { res ->
            val r = res.getOrNull(0) as? JSONObject
            if (r?.optBoolean("success") == true) {
                roomId = r.getString("roomId")
                cb(true, r.optString("hostDeviceName", "Device"))
            } else cb(false, r?.optString("error"))
        }
    }

    fun sendOffer(targetId: String, sdp: SessionDescription) =
        socket?.emit("signal-offer", JSONObject()
            .put("targetId", targetId).put("offer", sdpJson(sdp)))

    fun sendAnswer(targetId: String, sdp: SessionDescription) =
        socket?.emit("signal-answer", JSONObject()
            .put("targetId", targetId).put("answer", sdpJson(sdp)))

    fun sendIce(targetId: String, c: IceCandidate) =
        socket?.emit("signal-ice", JSONObject()
            .put("targetId", targetId).put("candidate", JSONObject()
                .put("candidate", c.sdp)
                .put("sdpMid", c.sdpMid)
                .put("sdpMLineIndex", c.sdpMLineIndex)))

    /** Fallback: input event socket relay se bhejo (data channel na khula ho to). */
    fun sendRelayInput(targetId: String?, eventData: JSONObject) =
        socket?.emit("input-event", JSONObject()
            .put("targetId", targetId).put("eventData", eventData))

    private fun sdpJson(sdp: SessionDescription) = JSONObject()
        .put("type", sdp.type.canonicalForm()).put("sdp", sdp.description)

    private fun startKeepAlive() {
        stopKeepAlive()
        keepAlive = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(15000)
                    socket?.emit("keep-alive-ping")
                }
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopKeepAlive() { keepAlive?.interrupt(); keepAlive = null }

    fun disconnect() {
        stopKeepAlive()
        try { socket?.disconnect(); socket?.off() } catch (e: Exception) { Log.w(TAG, "$e") }
        socket = null
    }

    companion object { private const val TAG = "SignalingClient" }
}

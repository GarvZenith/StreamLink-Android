package com.streamlink.android.webrtc

import android.content.Context
import com.streamlink.android.Config
import org.json.JSONArray
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import java.net.HttpURLConnection
import java.net.URL

/**
 * Process-wide WebRTC singletons: EglBase + PeerConnectionFactory.
 * ICE servers server ke /api/turn se load hote hain (desktop jaisa) — reliable
 * TURN relay milta hai; fallback me STUN + public TURN.
 */
object WebRtcCore {

    lateinit var eglBase: EglBase
        private set
    lateinit var factory: PeerConnectionFactory
        private set
    private var initialised = false

    fun init(context: Context) {
        if (initialised) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .createInitializationOptions()
        )
        eglBase = EglBase.create()
        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
        initialised = true
    }

    private val fallbackIce = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
    )

    /** Blocking network call — background thread se call karo. */
    fun loadIceServers(): List<PeerConnection.IceServer> {
        return try {
            val conn = (URL(Config.SERVER_URL + "/api/turn").openConnection() as HttpURLConnection)
            conn.connectTimeout = 6000; conn.readTimeout = 6000
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            val list = ArrayList<PeerConnection.IceServer>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val urls = o.get("urls")
                val b = if (urls is String) PeerConnection.IceServer.builder(urls)
                        else PeerConnection.IceServer.builder(o.getJSONArray("urls").let { u ->
                            (0 until u.length()).map { u.getString(it) } })
                if (o.has("username")) b.setUsername(o.getString("username"))
                if (o.has("credential")) b.setPassword(o.getString("credential"))
                list.add(b.createIceServer())
            }
            if (list.isEmpty()) fallbackIce else list
        } catch (e: Exception) {
            fallbackIce
        }
    }
}

package com.streamlink.android.webrtc

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** Boilerplate SdpObserver ko chhota karta hai. */
open class SimpleSdpObserver(
    private val onCreate: (SessionDescription) -> Unit = {},
    private val onSet: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = onCreate(sdp)
    override fun onSetSuccess() = onSet()
    override fun onCreateFailure(error: String) = onError(error)
    override fun onSetFailure(error: String) = onError(error)
}

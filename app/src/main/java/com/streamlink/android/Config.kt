package com.streamlink.android

/**
 * App-wide config. SERVER_URL wahi cloud signaling server hai jo desktop app use
 * karta hai (Render). Isse Android app, existing Windows/web clients ke saath
 * seedhe interoperate karta hai — koi server change nahi chahiye.
 *
 * NOTE: https:// laga rehna chahiye, aage slash mat lagana.
 */
object Config {
    const val SERVER_URL = "https://streamlink-server.onrender.com"

    // Screen capture target (host side). Device resolution ke hisaab se auto-scale
    // hota hai; yeh sirf upper cap hai taaki bandwidth theek rahe.
    const val CAPTURE_MAX_WIDTH = 1280
    const val CAPTURE_MAX_HEIGHT = 2560
    const val CAPTURE_FPS = 30
    const val VIDEO_MAX_BITRATE = 6_000_000 // 6 Mbps
}

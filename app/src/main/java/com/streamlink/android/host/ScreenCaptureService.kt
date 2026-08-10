package com.streamlink.android.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.streamlink.android.Config
import com.streamlink.android.R
import com.streamlink.android.webrtc.WebRtcCore
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Foreground service jo phone ki screen capture karta hai (MediaProjection) aur ek
 * WebRTC VideoTrack banata hai. HostSession is track ko peer connection me add karta
 * hai. Service background me chalta rehta hai taaki app minimise karne par bhi share
 * na ruke.
 */
class ScreenCaptureService : Service() {

    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        startForegroundInternal()

        val resultData: Intent = intent.getParcelableExtra(EXTRA_RESULT_DATA)
            ?: run { stopSelf(); return START_NOT_STICKY }

        startCapture(resultData)
        return START_STICKY
    }

    private fun startCapture(resultData: Intent) {
        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        // Aspect ratio maintain karte hue cap lagao (bandwidth control).
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        val scale = minOf(
            Config.CAPTURE_MAX_WIDTH.toFloat() / w,
            Config.CAPTURE_MAX_HEIGHT.toFloat() / h, 1f)
        w = (w * scale).toInt() and 1.inv()   // even numbers (encoder requirement)
        h = (h * scale).toInt() and 1.inv()

        val cap = ScreenCapturerAndroid(resultData, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        })
        capturer = cap

        val src = WebRtcCore.factory.createVideoSource(true) // isScreencast = true
        videoSource = src
        helper = SurfaceTextureHelper.create("ScreenCapture", WebRtcCore.eglBase.eglBaseContext)
        cap.initialize(helper, applicationContext, src.capturerObserver)
        cap.startCapture(w, h, Config.CAPTURE_FPS)

        val track = WebRtcCore.factory.createVideoTrack("video0", src)
        track.setEnabled(true)
        currentVideoTrack = track
        onTrackReady?.invoke(track)
    }

    private fun startForegroundInternal() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Screen sharing",
                    NotificationManager.IMPORTANCE_LOW))
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("StreamLink — screen sharing live")
            .setContentText("Aapki screen share ho rahi hai")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose()
        videoSource?.dispose()
        helper?.dispose()
        currentVideoTrack = null
        capturer = null; videoSource = null; helper = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "screen_capture"
        private const val NOTIF_ID = 1001

        /** Capture start hone par bana track (HostSession isse read karta hai). */
        @JvmStatic var currentVideoTrack: VideoTrack? = null
        @JvmStatic var onTrackReady: ((VideoTrack) -> Unit)? = null

        fun start(context: Context, resultData: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}

package com.streamlink.android.webrtc

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import org.webrtc.DataChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * 'file' data channel handler — meta/chunk/end protocol bilkul web/desktop jaisa,
 * isliye PC <-> phone file transfer dono taraf chalta hai. Aayi hui files phone ke
 * Downloads me save hoti hain.
 */
class FileTransfer(
    private val context: Context,
    private val channel: DataChannel,
    private val notify: (String) -> Unit
) {
    private var incomingName: String? = null
    private var buffer: ByteArrayOutputStream? = null

    init {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(prev: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buf: DataChannel.Buffer) {
                val bytes = ByteArray(buf.data.remaining()); buf.data.get(bytes)
                if (buf.binary) { buffer?.write(bytes); return }
                val msg = try { JSONObject(String(bytes, StandardCharsets.UTF_8)) } catch (e: Exception) { return }
                when (msg.optString("t")) {
                    "meta" -> {
                        incomingName = msg.optString("name", "file")
                        buffer = ByteArrayOutputStream()
                        notify("Receiving ${incomingName}…")
                    }
                    "end" -> finishIncoming()
                }
            }
        })
    }

    private fun finishIncoming() {
        val name = incomingName ?: return
        val data = buffer?.toByteArray() ?: return
        incomingName = null; buffer = null
        try {
            saveToDownloads(name, data)
            notify("Saved to Downloads: $name")
        } catch (e: Exception) { notify("Save failed: $e"); Log.w(TAG, "$e") }
    }

    private fun saveToDownloads(name: String, data: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(dir, name).writeBytes(data)
        }
    }

    /** Local file (bytes) remote ko bhejo. */
    fun sendFile(name: String, data: ByteArray) {
        if (channel.state() != DataChannel.State.OPEN) { notify("Connect nahi hai"); return }
        notify("Sending $name…")
        sendText(JSONObject().put("t", "meta").put("name", name).put("size", data.size))
        val chunk = 16 * 1024
        var off = 0
        while (off < data.size) {
            val end = minOf(off + chunk, data.size)
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(data.copyOfRange(off, end)), true))
            off = end
        }
        sendText(JSONObject().put("t", "end").put("name", name))
        notify("Sent: $name")
    }

    private fun sendText(o: JSONObject) {
        val b = o.toString().toByteArray(StandardCharsets.UTF_8)
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(b), false))
    }

    companion object { private const val TAG = "FileTransfer" }
}

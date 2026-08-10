package com.streamlink.android.updater

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.streamlink.android.BuildConfig
import com.streamlink.android.Config
import com.streamlink.android.R
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object AutoUpdater {

    private const val TAG = "AutoUpdater"

    fun checkForUpdate(context: Context, silent: Boolean = true) {
        thread {
            try {
                val conn = (URL(Config.UPDATE_SERVER_URL + "/api/version").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                if (conn.responseCode != 200) return@thread
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body).optJSONObject("android") ?: return@thread

                val latestCode = json.optInt("versionCode", 0)
                val latestName = json.optString("versionName", "1.0.0")
                val downloadUrl = json.optString("downloadUrl", "")
                val changelog = json.optString("changelog", "New performance and feature updates available.")

                val currentCode = BuildConfig.VERSION_CODE

                if (latestCode > currentCode && downloadUrl.isNotEmpty()) {
                    Handler(Looper.getMainLooper()).post {
                        showModernUpdateDialog(context, latestName, changelog, downloadUrl)
                    }
                } else if (!silent) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "StreamLink is up to date!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: $e")
                if (!silent) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Could not check for updates", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showModernUpdateDialog(
        context: Context,
        versionName: String,
        changelog: String,
        downloadUrl: String
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_update, null)
        dialog.setContentView(view)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(false)

        val tvVersionBadge = view.findViewById<TextView>(R.id.tvVersionBadge)
        val tvChangelog = view.findViewById<TextView>(R.id.tvChangelog)
        val containerProgress = view.findViewById<View>(R.id.containerProgress)
        val progressBarDownload = view.findViewById<ProgressBar>(R.id.progressBarDownload)
        val tvPercentText = view.findViewById<TextView>(R.id.tvPercentText)
        val tvSizeDetails = view.findViewById<TextView>(R.id.tvSizeDetails)
        val btnCancelUpdate = view.findViewById<MaterialButton>(R.id.btnCancelUpdate)
        val btnStartDownload = view.findViewById<MaterialButton>(R.id.btnStartDownload)

        tvVersionBadge.text = "v$versionName"
        tvChangelog.text = changelog

        btnCancelUpdate.setOnClickListener {
            dialog.dismiss()
        }

        btnStartDownload.setOnClickListener {
            btnStartDownload.isEnabled = false
            btnCancelUpdate.visibility = View.GONE
            containerProgress.visibility = View.VISIBLE

            downloadApkWithProgress(
                context,
                downloadUrl,
                onProgress = { downloaded, total ->
                    val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                    val dlMb = downloaded / (1024f * 1024f)
                    val totalMb = total / (1024f * 1024f)

                    progressBarDownload.progress = percent
                    tvPercentText.text = "$percent%"
                    tvSizeDetails.text = String.format("%.1f MB / %.1f MB", dlMb, totalMb)
                },
                onComplete = { apkFile ->
                    dialog.dismiss()
                    installApk(context, apkFile)
                },
                onError = { err ->
                    btnStartDownload.isEnabled = true
                    btnCancelUpdate.visibility = View.VISIBLE
                    containerProgress.visibility = View.GONE
                    Toast.makeText(context, "Download failed: $err", Toast.LENGTH_LONG).show()
                }
            )
        }

        dialog.show()
    }

    private fun downloadApkWithProgress(
        context: Context,
        urlStr: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onComplete: (apkFile: File) -> Unit,
        onError: (message: String) -> Unit
    ) {
        thread {
            try {
                val apkFile = File(context.cacheDir, "streamlink-update.apk")
                if (apkFile.exists()) apkFile.delete()

                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 30000
                }

                val totalLength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    conn.contentLengthLong
                } else {
                    conn.contentLength.toLong()
                }

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var downloaded = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            Handler(Looper.getMainLooper()).post {
                                onProgress(downloaded, totalLength)
                            }
                        }
                    }
                }

                Handler(Looper.getMainLooper()).post {
                    onComplete(apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error: $e")
                Handler(Looper.getMainLooper()).post {
                    onError(e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                } else {
                    Uri.fromFile(apkFile)
                }
                setDataAndType(apkUri, "application/vnd.android.package-archive")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "APK install trigger failed: $e")
            Toast.makeText(context, "Failed to start installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

package com.streamlink.android.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.streamlink.android.BuildConfig
import com.streamlink.android.Config
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
                val conn = (URL(Config.SERVER_URL + "/api/version").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode != 200) return@thread
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body).optJSONObject("android") ?: return@thread

                val latestCode = json.optInt("versionCode", 0)
                val latestName = json.optString("versionName", "")
                val downloadUrl = json.optString("downloadUrl", "")
                val changelog = json.optString("changelog", "New performance and feature updates available.")

                val currentCode = BuildConfig.VERSION_CODE

                if (latestCode > currentCode && downloadUrl.isNotEmpty()) {
                    Handler(Looper.getMainLooper()).post {
                        showUpdateDialog(context, latestName, changelog, downloadUrl)
                    }
                } else if (!silent) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "StreamLink is already up to date!", Toast.LENGTH_SHORT).show()
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

    private fun showUpdateDialog(context: Context, versionName: String, changelog: String, downloadUrl: String) {
        AlertDialog.Builder(context)
            .setTitle("Update Available ($versionName)")
            .setMessage("$changelog\n\nWould you like to download and install this update automatically?")
            .setPositiveButton("Update Now") { _, _ ->
                downloadAndInstallApk(context, downloadUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstallApk(context: Context, urlStr: String) {
        Toast.makeText(context, "Downloading update in background...", Toast.LENGTH_LONG).show()

        thread {
            try {
                val apkFile = File(context.cacheDir, "streamlink-update.apk")
                if (apkFile.exists()) apkFile.delete()

                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 30000
                }
                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }

                Handler(Looper.getMainLooper()).post {
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "APK download failed: $e")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Update download failed: ${e.message}", Toast.LENGTH_LONG).show()
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
            Toast.makeText(context, "Failed to start installer", Toast.LENGTH_SHORT).show()
        }
    }
}

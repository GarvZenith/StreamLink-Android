package com.streamlink.android.input

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.OutputStreamWriter

/**
 * FULL / precise control (bina root) — Shizuku ke through. Shizuku ADB-granted shell
 * process deta hai jisme hum `input` commands chalate hain (tap, swipe, keyevent,
 * text). Ek hi persistent `sh` process reuse hota hai taaki latency kam rahe.
 *
 * Agar Shizuku available/granted nahi -> InputController Accessibility par fallback.
 */
object ShizukuInjector {

    private const val TAG = "ShizukuInjector"
    private const val PERM_CODE = 4711

    private var shellWriter: OutputStreamWriter? = null
    private var shellProcess: Process? = null

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    fun canRequest(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.shouldShowRequestPermissionRationale()
    } catch (e: Exception) { false }

    fun requestPermission() {
        try { if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            Shizuku.requestPermission(PERM_CODE) } catch (e: Exception) { Log.w(TAG, "$e") }
    }

    private fun ensureShell(): OutputStreamWriter? {
        shellWriter?.let { return it }
        return try {
            // Shizuku.newProcess hidden hai -> reflection se access.
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val proc = m.invoke(null, arrayOf("sh"), null, null) as Process
            shellProcess = proc
            OutputStreamWriter(proc.outputStream).also { shellWriter = it }
        } catch (e: Exception) { Log.w(TAG, "shell start failed: $e"); null }
    }

    @Synchronized
    private fun exec(cmd: String) {
        val w = ensureShell() ?: return
        try { w.write(cmd); w.write("\n"); w.flush() }
        catch (e: Exception) { Log.w(TAG, "$e"); reset() }
    }

    fun tap(x: Int, y: Int) = exec("input tap $x $y")
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) =
        exec("input swipe $x1 $y1 $x2 $y2 $ms")
    fun keyEvent(code: Int) = exec("input keyevent $code")
    fun text(s: String) {
        val safe = s.replace("'", "'\\''")
        exec("input text '$safe'")
    }

    private fun reset() {
        try { shellWriter?.close() } catch (_: Exception) {}
        try { shellProcess?.destroy() } catch (_: Exception) {}
        shellWriter = null; shellProcess = null
    }
}

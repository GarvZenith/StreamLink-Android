package com.streamlink.android.input

import android.view.KeyEvent
import org.json.JSONObject
import java.io.OutputStreamWriter
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Received remote input events (wahi JSON schema jo web/desktop bhejte hain) ko is
 * Android device par apply karta hai. Preference: Shizuku (precise/full control) —
 * available na ho to AccessibilityService gestures — missing to Root shell.
 *
 * Gesture model: down..(moves)..up ko buffer karke ek tap ya swipe/drag banate hain.
 * De-duplicates mousedown+mouseup+click redundant double-taps.
 */
class InputController(
    @Volatile var screenW: Int,
    @Volatile var screenH: Int
) {
    private var pressed = false
    private var startX = 0f; private var startY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var lastTapTime = 0L

    private val tapSlop = 16f
    private val tapMaxMs = 450L

    private fun useShizuku() = ShizukuInjector.isAvailable()
    private fun acc() = RemoteInputAccessibilityService.instance

    fun handle(e: JSONObject) {
        when (e.optString("type")) {
            "mousedown", "touchstart" -> onDown(px(e), py(e))
            "mousemove", "touchmove" -> onMove(px(e), py(e))
            "mouseup", "touchend" -> onUp(px(e), py(e))
            "click" -> onClick(px(e), py(e))
            "scroll" -> onScroll(e.optDouble("deltaY", 0.0))
            "keydown" -> onKey(e)
            "nav" -> onNav(e.optString("action"))   // Android viewer extension
        }
    }

    private fun px(e: JSONObject) = (e.optDouble("xPercent", 0.0) * screenW).toFloat()
    private fun py(e: JSONObject) = (e.optDouble("yPercent", 0.0) * screenH).toFloat()

    private fun onDown(x: Float, y: Float) {
        pressed = true; moved = false
        startX = x; startY = y; lastX = x; lastY = y
        downTime = System.currentTimeMillis()
    }

    private fun onMove(x: Float, y: Float) {
        if (!pressed) return
        lastX = x; lastY = y
        if (hypot((x - startX).toDouble(), (y - startY).toDouble()) > tapSlop) moved = true
    }

    private fun onUp(x: Float, y: Float) {
        if (!pressed) { tap(x, y); return }
        pressed = false
        val ex = if (x > 0f || y > 0f) x else lastX
        val ey = if (x > 0f || y > 0f) y else lastY
        val dt = System.currentTimeMillis() - downTime
        if (!moved && dt < tapMaxMs) {
            tap(startX, startY)
        } else if (moved) {
            swipe(startX, startY, ex, ey, dt.coerceIn(80, 1200))
        }
    }

    private fun onClick(x: Float, y: Float) {
        // Prevent double-tap if mousedown+mouseup already fired a tap recently
        if (System.currentTimeMillis() - lastTapTime > 350) {
            tap(x, y)
        }
    }

    private fun tap(x: Float, y: Float) {
        lastTapTime = System.currentTimeMillis()
        if (useShizuku()) {
            ShizukuInjector.tap(x.toInt(), y.toInt())
        } else if (acc() != null) {
            acc()?.tap(x, y)
        } else {
            execRootCommand("input tap ${x.toInt()} ${y.toInt()}")
        }
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        if (useShizuku()) {
            ShizukuInjector.swipe(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), ms.toInt())
        } else if (acc() != null) {
            acc()?.swipe(x1, y1, x2, y2, ms)
        } else {
            execRootCommand("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $ms")
        }
    }

    private fun onScroll(deltaY: Double) {
        if (abs(deltaY) < 1) return
        val cx = screenW / 2f
        val cy = screenH / 2f
        val off = screenH * 0.25f
        // wheel down (deltaY>0) => content niche => finger upar swipe
        val (y1, y2) = if (deltaY > 0) Pair(cy + off, cy - off) else Pair(cy - off, cy + off)
        swipe(cx, y1, cx, y2, 150)
    }

    private fun onKey(e: JSONObject) {
        val code = e.optString("code")
        val key = e.optString("key")
        val kc = keyCode(code, key)
        if (kc != null) {
            if (useShizuku()) ShizukuInjector.keyEvent(kc)
            else when (kc) {
                KeyEvent.KEYCODE_BACK -> acc()?.globalBack()
                KeyEvent.KEYCODE_HOME -> acc()?.globalHome()
                KeyEvent.KEYCODE_DEL -> acc()?.backspace()
                else -> execRootCommand("input keyevent $kc")
            }
            return
        }
        // Printable character -> text input
        if (key.length == 1 && !e.optBoolean("ctrlKey") && !e.optBoolean("altKey")) {
            if (useShizuku()) ShizukuInjector.text(key)
            else if (acc() != null) acc()?.typeText(key)
            else execRootCommand("input text '$key'")
        }
    }

    private fun onNav(action: String) {
        when (action) {
            "back" -> if (useShizuku()) ShizukuInjector.keyEvent(KeyEvent.KEYCODE_BACK) else acc()?.globalBack() ?: execRootCommand("input keyevent 4")
            "home" -> if (useShizuku()) ShizukuInjector.keyEvent(KeyEvent.KEYCODE_HOME) else acc()?.globalHome() ?: execRootCommand("input keyevent 3")
            "recents" -> if (useShizuku()) ShizukuInjector.keyEvent(KeyEvent.KEYCODE_APP_SWITCH) else acc()?.globalRecents() ?: execRootCommand("input keyevent 187")
        }
    }

    private fun keyCode(code: String, key: String): Int? = when (code) {
        "Enter", "NumpadEnter" -> KeyEvent.KEYCODE_ENTER
        "Backspace" -> KeyEvent.KEYCODE_DEL
        "Tab" -> KeyEvent.KEYCODE_TAB
        "Escape" -> KeyEvent.KEYCODE_BACK
        "ArrowUp" -> KeyEvent.KEYCODE_DPAD_UP
        "ArrowDown" -> KeyEvent.KEYCODE_DPAD_DOWN
        "ArrowLeft" -> KeyEvent.KEYCODE_DPAD_LEFT
        "ArrowRight" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "Delete" -> KeyEvent.KEYCODE_FORWARD_DEL
        "Space" -> KeyEvent.KEYCODE_SPACE
        else -> if (key == "Backspace") KeyEvent.KEYCODE_DEL else null
    }

    private fun execRootCommand(cmd: String) {
        try {
            val process = Runtime.getRuntime().exec("su")
            OutputStreamWriter(process.outputStream).use {
                it.write(cmd + "\n")
                it.write("exit\n")
                it.flush()
            }
        } catch (_: Exception) {}
    }
}

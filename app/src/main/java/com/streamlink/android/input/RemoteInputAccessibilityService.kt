package com.streamlink.android.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Bina-root control ka base path. dispatchGesture se tap/swipe/scroll, aur
 * performGlobalAction se Back/Home/Recents. Text focused editable node me jaata hai.
 * (Precise/keyboard-heavy control ke liye Shizuku mode use hota hai — dekho InputController.)
 */
class RemoteInputAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, 0, 50)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatch(path, 0, durationMs.coerceIn(20, 3000))
    }

    private fun dispatch(path: Path, start: Long, duration: Long) {
        try {
            val stroke = GestureDescription.StrokeDescription(path, start, duration)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (_: Exception) {}
    }

    fun globalBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun globalRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /** Focused editable field me text daalo (append). */
    fun typeText(text: String) {
        val node = findFocusedEditable(rootInActiveWindow) ?: return
        val existing = node.text?.toString() ?: ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                existing + text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun backspace() {
        val node = findFocusedEditable(rootInActiveWindow) ?: return
        val t = node.text?.toString() ?: return
        if (t.isEmpty()) return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                t.dropLast(1))
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            findFocusedEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    companion object {
        @JvmStatic @Volatile var instance: RemoteInputAccessibilityService? = null
        fun isRunning() = instance != null
    }
}

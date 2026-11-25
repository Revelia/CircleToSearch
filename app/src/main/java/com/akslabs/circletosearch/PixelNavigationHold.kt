package com.akslabs.circletosearch.pixelbar

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityManager
import com.akslabs.circletosearch.pixelbar.data.ActionType
import com.akslabs.circletosearch.pixelbar.data.GestureType
import com.akslabs.circletosearch.pixelbar.data.OverlaySegment

internal class PixelNavigationHold(private val service: AccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val lock = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val accessibility = service.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val main = Handler(Looper.getMainLooper())
    private var cleanup: (() -> Unit)? = null

    fun assigned(segment: OverlaySegment) =
        Build.VERSION.SDK_INT >= 30 &&
            segment.gestures[GestureType.LONG_PRESS] == ActionType.OPEN_APP &&
            segment.gestureData[GestureType.LONG_PRESS] == "com.parallelc.micts"

    fun available() = service.packageManager.getLaunchIntentForPackage("com.parallelc.micts") != null

    // Called for both adding and updating a segment. The coordinate origin of
    // the normal AKS editor stays intact for all the other actions.
    fun position(p: WindowManager.LayoutParams, segment: OverlaySegment): Boolean {
        val desired = WindowManager.LayoutParams().apply { copyFrom(p) }
        desired.gravity = Gravity.TOP or Gravity.START
        desired.width = segment.width
        desired.height = segment.height
        desired.x = segment.xOffset
        desired.y = segment.yOffset
        if (Build.VERSION.SDK_INT >= 30) {
            desired.fitInsetsTypes = WindowManager.LayoutParams().fitInsetsTypes
            if (assigned(segment)) {
                val metrics = wm.maximumWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars() or WindowInsets.Type.mandatorySystemGestures()
                )
                val width = metrics.bounds.width()
                desired.fitInsetsTypes = 0
                desired.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                desired.x = 0
                desired.y = 0
                // Use the user's width. A full-screen default becomes the width
                // of the tested 332 px hit area on a 1080 px portrait Pixel.
                desired.width = if (segment.width in 1 until width) segment.width else width * 332 / 1080
                desired.height = insets.bottom.coerceAtLeast(1)
            }
        }
        return p.copyFrom(desired) != 0
    }

    fun focusedWindow(): Int? {
        if (lock.isDeviceLocked) return null
        return service.windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
        }?.id
    }

    fun cancel() { cleanup?.invoke() }

    private fun trace(message: String) {
        if (service.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d("PixelNavHold", message)
    }

    private fun ownsPoint(target: View, x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val node = target.createAccessibilityNodeInfo()
        val id = node.windowId
        @Suppress("DEPRECATION")
        node.recycle()
        val region = Region()
        val window = service.windows.firstOrNull {
            it.getRegionInScreen(region)
            region.contains(x, y)
        }
        return window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && window.id == id
    }

    // A dispatched accessibility gesture cancels the active pointer stream.
    // Send its short replacement stroke to our own non-focusable input window.
    fun launch(source: View, origin: Int?, action: () -> Unit) {
        if (origin == null) { trace("No focused application at touch-down"); return }
        if (cleanup != null || !available() || lock.isDeviceLocked ||
            !source.isAttachedToWindow || source.display?.displayId != Display.DEFAULT_DISPLAY ||
            accessibility.isTouchExplorationEnabled || service.magnificationController.scale != 1f) {
            trace("Hold unavailable in the current state")
            return
        }

        val target = View(service)
        target.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        val size = (32 * service.resources.displayMetrics.density).toInt().coerceAtLeast(8)
        val params = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.CENTER
        params.title = "Pixel navigation hold"

        val token = Any()
        var active = true
        var ownDown = false
        var ownUp = false
        var completed = false
        var checking = false
        fun later(delay: Long, run: () -> Unit) {
            main.postAtTime({ if (active) run() }, token, SystemClock.uptimeMillis() + delay)
        }
        fun close() {
            if (!active) return
            active = false
            main.removeCallbacksAndMessages(token)
            cleanup = null
            if (target.isAttachedToWindow || target.parent != null) {
                runCatching { wm.removeViewImmediate(target) }
            }
        }
        var previousMatch = false
        fun awaitOrigin(deadline: Long) {
            if (!source.isAttachedToWindow || lock.isDeviceLocked) { close(); return }
            val matches = focusedWindow() == origin
            if (matches && previousMatch) {
                close()
                if (available()) { trace("Launching after focus check"); action() }
            } else if (SystemClock.uptimeMillis() < deadline) {
                previousMatch = matches
                later(16) { awaitOrigin(deadline) }
            } else {
                trace("Original window did not regain focus")
                close()
            }
        }
        fun checkDelivery() {
            if (completed && ownUp && !checking) {
                checking = true
                val deadline = SystemClock.uptimeMillis() + 450
                later(16) { awaitOrigin(deadline) }
            }
        }
        cleanup = ::close
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> ownDown = true
                MotionEvent.ACTION_UP -> { ownUp = ownDown; checkDelivery() }
                MotionEvent.ACTION_CANCEL -> ownDown = false
            }
            true
        }
        later(900) { trace("Timed out waiting for gesture delivery"); close() }
        if (runCatching { wm.addView(target, params) }.isFailure) {
            trace("Could not attach gesture target"); close(); return
        }
        target.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            private var sent = false
            override fun onDraw() {
                if (sent || !active) return
                sent = true
                val deadline = SystemClock.uptimeMillis() + 240
                fun dispatchWhenReady() {
                    if (!active) return
                    if (!target.isShown || !source.isAttachedToWindow || lock.isDeviceLocked) {
                        close(); return
                    }
                    val at = IntArray(2)
                    target.getLocationOnScreen(at)
                    val x = at[0] + target.width / 2
                    val y = at[1] + target.height / 2
                    if (!ownsPoint(target, x, y)) {
                        if (SystemClock.uptimeMillis() < deadline) later(16) { dispatchWhenReady() }
                        else { trace("Gesture target is not the top window at its center"); close() }
                        return
                    }
                    val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                    val stroke = GestureDescription.StrokeDescription(path, 0, 32)
                    val gesture = GestureDescription.Builder().addStroke(stroke).build()
                    val result = object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: GestureDescription) {
                            if (active) { completed = true; checkDelivery() }
                        }
                        override fun onCancelled(g: GestureDescription) {
                            if (active) trace("Replacement gesture cancelled")
                            close()
                        }
                    }
                    if (!service.dispatchGesture(gesture, result, main)) {
                        trace("Replacement gesture rejected")
                        close()
                    }
                }
                target.postOnAnimation { dispatchWhenReady() }
            }
        })
    }
}

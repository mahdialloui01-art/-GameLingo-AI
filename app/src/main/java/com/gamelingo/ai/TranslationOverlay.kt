package com.gamelingo.ai

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.max

/** Immediate visual cover followed by Arabic replacement. Textured backgrounds are not reconstructed. */
class TranslationOverlay(context: Context) {
    private val appContext = context
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val views = LinkedHashMap<String, TextView>()
    private val params = LinkedHashMap<String, WindowManager.LayoutParams>()
    private val lastSeenFrame = HashMap<String, Long>()
    private var frameNumber = 0L

    @Suppress("DEPRECATION")
    private val overlayType = if (Build.VERSION.SDK_INT >= 26)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

    @Synchronized fun beginFrame() { frameNumber++ }

    @Synchronized fun coverAt(id: String, bounds: Rect, background: Int, screenWidth: Int, screenHeight: Int) {
        drawAt(id, "", bounds, background, screenWidth, screenHeight)
    }

    @Synchronized fun showAt(id: String, text: String, bounds: Rect, background: Int, screenWidth: Int, screenHeight: Int) {
        if (text.isNotBlank()) drawAt(id, text, bounds, background, screenWidth, screenHeight)
    }

    private fun drawAt(id: String, text: String, bounds: Rect, sampledBackground: Int, screenWidth: Int, screenHeight: Int) {
        if (bounds.isEmpty || screenWidth < 1 || screenHeight < 1) return
        lastSeenFrame[id] = frameNumber
        val sourceLeft = bounds.left.coerceIn(0, screenWidth - 1)
        val sourceRight = bounds.right.coerceIn(sourceLeft + 1, screenWidth)
        val sourceTop = bounds.top.coerceIn(0, screenHeight - 1)
        val sourceBottom = bounds.bottom.coerceIn(sourceTop + 1, screenHeight)
        val sourceWidth = (sourceRight - sourceLeft).coerceAtLeast(24)
        val sourceHeight = (sourceBottom - sourceTop).coerceAtLeast(18)
        val textSizePx = (sourceHeight * 0.78f).coerceIn(13f, 42f)
        val translatedWidth = if (text.isBlank()) 0 else (text.length * textSizePx * 0.62f).toInt() + 18
        val width = max(sourceWidth + 8, translatedWidth).coerceAtMost(screenWidth).coerceAtLeast(24)
        val height = max(sourceHeight + 6, (textSizePx * 1.42f).toInt()).coerceAtMost(screenHeight).coerceAtLeast(22)
        val left = (sourceRight - width).coerceIn(0, screenWidth - width)
        val top = ((sourceTop + sourceBottom - height) / 2).coerceIn(0, screenHeight - height)

        val luminance = (Color.red(sampledBackground) * 299 + Color.green(sampledBackground) * 587 + Color.blue(sampledBackground) * 114) / 1000
        val foreground = if (luminance < 145) Color.WHITE else Color.BLACK
        val opaqueBackground = Color.rgb(Color.red(sampledBackground), Color.green(sampledBackground), Color.blue(sampledBackground))
        val view = views[id] ?: TextView(appContext).also {
            it.gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            it.includeFontPadding = false
            it.setPadding(4, 0, 4, 0)
            it.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            it.textDirection = android.view.View.TEXT_DIRECTION_RTL
            it.maxLines = 1
            views[id] = it
        }
        view.text = text
        view.textSize = (textSizePx / appContext.resources.displayMetrics.scaledDensity).coerceIn(10f, 28f)
        view.setTextColor(foreground)
        view.background = GradientDrawable().apply { setColor(opaqueBackground); cornerRadius = 1f }

        val layout = params[id] ?: WindowManager.LayoutParams(
            width, height, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).also { params[id] = it }
        layout.width = width
        layout.height = height
        layout.gravity = Gravity.TOP or Gravity.LEFT
        layout.x = left
        layout.y = top
        try {
            if (view.parent == null) manager.addView(view, layout) else manager.updateViewLayout(view, layout)
        } catch (_: Exception) { }
    }

    @Synchronized fun markActive(id: String) { lastSeenFrame[id] = frameNumber }

    @Synchronized fun isActive(id: String): Boolean {
        val seen = lastSeenFrame[id] ?: return false
        return frameNumber - seen <= 4L
    }

    @Synchronized fun endFrame() {
        val stale = lastSeenFrame.filterValues { frameNumber - it > 4L }.keys.toList()
        stale.forEach { id ->
            views[id]?.let { try { if (it.parent != null) manager.removeView(it) } catch (_: Exception) { } }
            views.remove(id)
            params.remove(id)
            lastSeenFrame.remove(id)
        }
    }

    @Synchronized fun showStatus(text: String) {
        val id = "gamelingo-status"
        val view = views[id] ?: TextView(appContext).also {
            it.setTextColor(Color.WHITE); it.textSize = 13f; it.gravity = Gravity.CENTER
            it.setPadding(12, 8, 12, 8)
            it.background = GradientDrawable().apply { setColor(0xDD202536.toInt()); cornerRadius = 12f }
            views[id] = it
        }
        lastSeenFrame[id] = frameNumber
        view.text = text
        val layout = params[id] ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).also { params[id] = it }
        layout.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        layout.y = 80
        try { if (view.parent == null) manager.addView(view, layout) else manager.updateViewLayout(view, layout) } catch (_: Exception) { }
    }

    @Synchronized fun hide() {
        views.values.forEach { try { if (it.parent != null) manager.removeView(it) } catch (_: Exception) { } }
        views.clear(); params.clear(); lastSeenFrame.clear()
    }
}
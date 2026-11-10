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
import kotlin.math.min

/**
 * Places each translated line over the position where the source line was detected.
 * This is a visual overlay; Android does not let a normal third-party app edit another
 * app's actual text or guarantee a pixel-perfect background reconstruction.
 */
class TranslationOverlay(context: Context) {
    private val appContext = context
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val views = LinkedHashMap<String, TextView>()
    private val params = LinkedHashMap<String, WindowManager.LayoutParams>()
    private val activeThisFrame = HashSet<String>()

    @Suppress("DEPRECATION")
    private val overlayType = if (Build.VERSION.SDK_INT >= 26)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

    @Synchronized fun beginFrame() {
        activeThisFrame.clear()
    }

    @Synchronized fun showAt(
        id: String,
        text: String,
        sourceBounds: Rect,
        sampledBackground: Int,
        screenWidth: Int,
        screenHeight: Int
    ) {
        if (text.isBlank() || sourceBounds.isEmpty) return
        activeThisFrame.add(id)

        // Keep the translated Arabic line anchored to the original line's RIGHT edge
        // and vertical center. Arabic is RTL, so growing the box to the right shifts it
        // away from the source position on many game HUDs.
        val sourceLeft = sourceBounds.left.coerceIn(0, screenWidth - 1)
        val sourceRight = sourceBounds.right.coerceIn(sourceLeft + 1, screenWidth)
        val sourceTop = sourceBounds.top.coerceIn(0, screenHeight - 1)
        val sourceBottom = sourceBounds.bottom.coerceIn(sourceTop + 1, screenHeight)
        val sourceWidth = (sourceRight - sourceLeft).coerceAtLeast(24)
        val sourceHeight = (sourceBottom - sourceTop).coerceAtLeast(18)
        val textSizePx = (sourceHeight * 0.78f).coerceIn(13f, 42f)
        val desiredWidth = max(sourceWidth, (text.length * textSizePx * 0.62f).toInt() + 14)
        val width = desiredWidth.coerceAtMost(screenWidth).coerceAtLeast(24)
        val height = max(sourceHeight + 4, (textSizePx * 1.35f).toInt())
            .coerceAtMost(screenHeight).coerceAtLeast(22)
        val left = (sourceRight - width).coerceIn(0, screenWidth - width)
        val top = ((sourceTop + sourceBottom - height) / 2).coerceIn(0, screenHeight - height)

        val luminance = (Color.red(sampledBackground) * 299 +
            Color.green(sampledBackground) * 587 + Color.blue(sampledBackground) * 114) / 1000
        val foreground = if (luminance < 145) Color.WHITE else Color.BLACK
        // Fully opaque patch hides the original game glyphs instead of letting them show through.
        val backgroundColor = Color.rgb(Color.red(sampledBackground),
            Color.green(sampledBackground), Color.blue(sampledBackground))

        val view = views[id] ?: TextView(appContext).also { created ->
            created.gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            created.includeFontPadding = false
            created.setPadding(2, 0, 2, 0)
            created.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            created.textDirection = android.view.View.TEXT_DIRECTION_RTL
            created.maxLines = 1
            created.setTextColor(foreground)
            created.background = GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = 2f
            }
            views[id] = created
        }

        view.text = text
        view.textSize = (textSizePx / appContext.resources.displayMetrics.scaledDensity).coerceIn(10f, 28f)
        view.setTextColor(foreground)
        view.background = GradientDrawable().apply {
            setColor(backgroundColor)
            cornerRadius = 2f
        }

        val layout = params[id] ?: WindowManager.LayoutParams(
            width, height, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also { params[id] = it }

        layout.width = width
        layout.height = height
        layout.gravity = Gravity.TOP or Gravity.LEFT
        layout.x = left
        layout.y = top

        try {
            if (view.parent == null) manager.addView(view, layout)
            else manager.updateViewLayout(view, layout)
        } catch (_: Exception) {
            // Permission can be revoked by the user while capture is running.
        }
    }

    @Synchronized fun markActive(id: String) { activeThisFrame.add(id) }

    @Synchronized fun isActive(id: String): Boolean = id in activeThisFrame

    @Synchronized fun endFrame() {
        val stale = views.keys.filter { it !in activeThisFrame }
        stale.forEach { id ->
            views[id]?.let { view ->
                try { if (view.parent != null) manager.removeView(view) } catch (_: Exception) { }
            }
            views.remove(id)
            params.remove(id)
        }
    }

    @Synchronized fun showStatus(text: String) {
        val id = "gamelingo-status"
        val view = views[id] ?: TextView(appContext).also {
            it.setTextColor(Color.WHITE)
            it.textSize = 13f
            it.gravity = Gravity.CENTER
            it.setPadding(12, 8, 12, 8)
            it.background = GradientDrawable().apply {
                setColor(0xDD202536.toInt())
                cornerRadius = 12f
            }
            views[id] = it
        }
        view.text = text
        val layout = params[id] ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).also { params[id] = it }
        layout.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        layout.y = 80
        try {
            if (view.parent == null) manager.addView(view, layout)
            else manager.updateViewLayout(view, layout)
        } catch (_: Exception) { }
    }

    @Synchronized fun hide() {
        views.values.forEach { view ->
            try { if (view.parent != null) manager.removeView(view) } catch (_: Exception) { }
        }
        views.clear()
        params.clear()
        activeThisFrame.clear()
    }
}

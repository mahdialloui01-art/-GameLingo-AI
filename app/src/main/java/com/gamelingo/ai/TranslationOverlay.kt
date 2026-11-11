package com.gamelingo.ai

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** Places readable Arabic text around the detected source line and keeps it inside the display. */
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

    private fun drawAt(id: String, text: String, sampledBounds: Rect, sampledBackground: Int, screenWidth: Int, screenHeight: Int) {
        if (sampledBounds.isEmpty || screenWidth < 40 || screenHeight < 40) return
        lastSeenFrame[id] = frameNumber

        // Anchor the replacement to the OCR rectangle itself. Do not recenter or
        // widen it: either operation makes the Arabic drift over neighboring UI.
        val left = sampledBounds.left.coerceIn(0, screenWidth - 1)
        val right = sampledBounds.right.coerceIn(left + 1, screenWidth)
        val top = sampledBounds.top.coerceIn(0, screenHeight - 1)
        val bottom = sampledBounds.bottom.coerceIn(top + 1, screenHeight)
        val width = (right - left).coerceAtLeast(24)
        val sourceHeight = (bottom - top).coerceAtLeast(16)
        val availableHeight = (screenHeight - top - 4).coerceAtLeast(sourceHeight)

        val luminance = (Color.red(sampledBackground) * 299 + Color.green(sampledBackground) * 587 + Color.blue(sampledBackground) * 114) / 1000
        val foreground = if (luminance < 145) Color.WHITE else Color.BLACK
        val opaqueBackground = Color.rgb(Color.red(sampledBackground), Color.green(sampledBackground), Color.blue(sampledBackground))

        val view = views[id] ?: TextView(appContext).also {
            it.gravity = Gravity.TOP or Gravity.END
            it.textAlignment = TextView.TEXT_ALIGNMENT_VIEW_END
            it.includeFontPadding = false
            it.setPadding(2, 1, 2, 1)
            it.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            it.textDirection = android.view.View.TEXT_DIRECTION_RTL
            it.maxLines = 6
            it.setHorizontallyScrolling(false)
            views[id] = it
        }

        view.text = text
        view.setTextColor(foreground)
        view.background = GradientDrawable().apply { setColor(opaqueBackground); cornerRadius = 0f }

        val contentWidth = (width - view.paddingLeft - view.paddingRight).coerceAtLeast(12)
        var textPx = (sourceHeight * 0.82f).coerceIn(12f, 42f)
        var textLayout: StaticLayout? = null
        if (text.isNotBlank()) {
            // Reduce font size only when necessary; keep the original x/y anchor.
            while (textPx > 12f) {
                view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPx)
                textLayout = StaticLayout.Builder
                    .obtain(text, 0, text.length, view.paint, contentWidth)
                    .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
                    .setIncludePad(false)
                    .setMaxLines(6)
                    .build()
                if (textLayout.height + view.paddingTop + view.paddingBottom <= availableHeight) break
                textPx -= 1f
            }
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPx)
            textLayout = StaticLayout.Builder
                .obtain(text, 0, text.length, view.paint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
                .setIncludePad(false)
                .setMaxLines(6)
                .build()
        }

        // Keep the exact source width and top edge. Extra Arabic lines grow downward,
        // never upward or sideways into another label.
        val desiredHeight = max(sourceHeight, (textLayout?.height ?: 0) + view.paddingTop + view.paddingBottom)
        val height = min(availableHeight, desiredHeight).coerceAtLeast(sourceHeight)

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
        return frameNumber - seen <= 15L
    }

    @Synchronized fun endFrame() {
        val stale = lastSeenFrame.filterValues { frameNumber - it > 15L }.keys.toList()
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

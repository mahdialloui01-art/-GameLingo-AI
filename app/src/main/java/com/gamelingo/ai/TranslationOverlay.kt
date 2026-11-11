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

        // Keep a safety margin so neither the patch nor Arabic glyphs can be placed off-screen.
        val margin = 8
        val sourceLeft = sampledBounds.left.coerceIn(0, screenWidth - 1)
        val sourceRight = sampledBounds.right.coerceIn(sourceLeft + 1, screenWidth)
        val sourceTop = sampledBounds.top.coerceIn(0, screenHeight - 1)
        val sourceBottom = sampledBounds.bottom.coerceIn(sourceTop + 1, screenHeight)
        val sourceWidth = (sourceRight - sourceLeft).coerceAtLeast(24)
        val sourceHeight = (sourceBottom - sourceTop).coerceAtLeast(18)

        val luminance = (Color.red(sampledBackground) * 299 + Color.green(sampledBackground) * 587 + Color.blue(sampledBackground) * 114) / 1000
        val foreground = if (luminance < 145) Color.WHITE else Color.BLACK
        val opaqueBackground = Color.rgb(Color.red(sampledBackground), Color.green(sampledBackground), Color.blue(sampledBackground))

        val view = views[id] ?: TextView(appContext).also {
            it.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            it.textAlignment = TextView.TEXT_ALIGNMENT_VIEW_END
            it.includeFontPadding = false
            it.setPadding(8, 4, 8, 4)
            it.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            it.textDirection = android.view.View.TEXT_DIRECTION_RTL
            it.maxLines = 4
            it.setHorizontallyScrolling(false)
            views[id] = it
        }

        view.text = text
        view.setTextColor(foreground)
        view.background = GradientDrawable().apply { setColor(opaqueBackground); cornerRadius = 2f }

        // Use physical pixels, not SP. The previous SP conversion made text much too small
        // on high-density devices and could clip the tail of long Arabic translations.
        val desiredTextPx = (sourceHeight * 0.92f).coerceIn(18f, 54f)
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, desiredTextPx)

        val maxWidth = (screenWidth - margin * 2).coerceAtLeast(24)
        val measuredTextWidth = if (text.isBlank()) 0f else view.paint.measureText(text)
        // Short translations stay close to the source width; long ones wrap instead of
        // being stretched beyond the right/left edge of the phone.
        val width = min(maxWidth, max(sourceWidth + 12, min((measuredTextWidth + 24).toInt(), (screenWidth * 0.86f).toInt())))
            .coerceAtLeast(24)
        val contentWidth = (width - view.paddingLeft - view.paddingRight).coerceAtLeast(16)
        val textLayout = if (text.isBlank()) null else StaticLayout.Builder
            .obtain(text, 0, text.length, view.paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
            .setIncludePad(false)
            .setMaxLines(4)
            .build()
        val textHeight = textLayout?.height ?: 0
        val height = min(screenHeight - margin * 2,
            max(sourceHeight + 8, textHeight + view.paddingTop + view.paddingBottom + 4)
        ).coerceAtLeast(24)

        // Center the replacement around the original text, then clamp the complete box.
        val centerX = (sourceLeft + sourceRight) / 2
        val centerY = (sourceTop + sourceBottom) / 2
        val left = (centerX - width / 2).coerceIn(margin, screenWidth - width - margin)
        val top = (centerY - height / 2).coerceIn(margin, screenHeight - height - margin)

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

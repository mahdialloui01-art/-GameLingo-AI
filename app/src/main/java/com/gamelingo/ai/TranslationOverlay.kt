package com.gamelingo.ai

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class TranslationOverlay(context: Context) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = TextView(context).apply {
        text = "GameLingo AI"
        textSize = 17f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(18, 12, 18, 12)
        background = GradientDrawable().apply {
            setColor(0xE6192033.toInt())
            cornerRadius = 22f
            setStroke(2, 0xFF7770FF.toInt())
        }
        maxLines = 4
    }
    private var attached = false
    @Suppress("DEPRECATION")
    private val overlayType = if (Build.VERSION.SDK_INT >= 26)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = 72
    }

    @Synchronized fun show(text: String) {
        view.text = text
        try {
            if (!attached) { manager.addView(view, params); attached = true }
            else manager.updateViewLayout(view, params)
        } catch (_: Exception) { }
    }

    @Synchronized fun hide() {
        if (attached) {
            try { manager.removeView(view) } catch (_: Exception) { }
            attached = false
        }
    }
}

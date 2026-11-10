package com.gamelingo.ai

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val captureRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 21, 34)
        window.navigationBarColor = android.graphics.Color.rgb(16, 21, 34)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 52, 28, 28)
            setBackgroundColor(android.graphics.Color.rgb(16, 21, 34))
        }
        fun text(value: String, size: Float, color: Int) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
        }
        root.addView(text("GameLingo AI", 30f, android.graphics.Color.WHITE))
        root.addView(text("ترجم ألعابك أثناء اللعب", 20f, 0xFFB9B7FF.toInt()).apply {
            setPadding(0, 12, 0, 8)
        })
        root.addView(text("محرك ترجمة فوري: التقاط الشاشة ← التعرف على النص ← ترجمة محلية ← نافذة عائمة.",
            15f, 0xFFD0D5E2.toInt()).apply { setPadding(0, 8, 0, 24) })

        val start = Button(this).apply {
            text = "ابدأ الترجمة"
            setOnClickListener { beginCapture() }
        }
        root.addView(start, LinearLayout.LayoutParams(-1, -2))
        val stop = Button(this).apply {
            text = "إيقاف الترجمة"
            setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)) }
        }
        root.addView(stop, LinearLayout.LayoutParams(-1, -2))
        root.addView(text("ملاحظة: النسخة الأولى تترجم النص الإنجليزي الظاهر على الشاشة إلى العربية. أول استخدام قد يحتاج تنزيل نموذج اللغة.", 13f, 0xFF9BA6BA.toInt()).apply {
            setPadding(0, 28, 0, 0)
        })
        setContentView(root)
    }

    private fun beginCapture() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            Toast.makeText(this, "اسمح بالظهور فوق التطبيقات ثم اضغط ابدأ مجدداً", Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Activity result is used for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "بدأ محرك الترجمة", Toast.LENGTH_SHORT).show()
        }
    }
}

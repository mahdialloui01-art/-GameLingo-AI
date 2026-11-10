package com.gamelingo.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "gamelingo_capture"
        private const val NOTIFICATION_ID = 71
        // Limit OCR input size to reduce per-frame recognition time on phones.
        private const val MAX_OCR_DIMENSION = 1280
        private const val FRAME_INTERVAL_MS = 200L
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: Handler? = null
    private var thread: HandlerThread? = null
    private var overlay: TranslationOverlay? = null
    private var screenWidth = 1
    private var screenHeight = 1
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val translator: Translator by lazy {
        Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
            .setTargetLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ARABIC)
            .build())
    }
    private val cache = ConcurrentHashMap<String, String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val busy = AtomicBoolean(false)
    @Volatile private var modelReady = false
    private var lastFrameAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        thread = HandlerThread("GameLingo-OCR").also { it.start() }
        worker = Handler(thread!!.looper)
        overlay = TranslationOverlay(this)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                modelReady = true
                overlay?.showStatus("GameLingo: الترجمة جاهزة")
            }
            .addOnFailureListener {
                overlay?.showStatus("تعذر تنزيل نموذج العربية؛ تحقق من الإنترنت")
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            startCapture()
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels.coerceAtLeast(1)
        screenHeight = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi
        reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ source ->
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < FRAME_INTERVAL_MS || !busy.compareAndSet(false, true)) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastFrameAt = now
            val image = source.acquireLatestImage()
            if (image == null) { busy.set(false); return@setOnImageAvailableListener }
            worker?.post {
                try { processFrame(image) } catch (_: Exception) { busy.set(false) } finally {
                    image.close()
                }
            }
        }, worker)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, worker)
        display = projection?.createVirtualDisplay(
            "GameLingo-Capture", screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, worker
        )
    }

    private fun processFrame(image: Image) {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)
        val frame = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()

        // OCR on a downscaled copy is substantially lighter; coordinates are mapped
        // back to the full-resolution frame before placing translated text.
        val scale = minOf(1f, MAX_OCR_DIMENSION.toFloat() /
            maxOf(frame.width, frame.height).toFloat())
        val ocrBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                frame,
                (frame.width * scale).toInt().coerceAtLeast(1),
                (frame.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else frame

        recognizer.process(InputImage.fromBitmap(ocrBitmap, 0))
            .addOnSuccessListener { result ->
                overlay?.beginFrame()
                if (modelReady) {
                    var lineIndex = 0
                    result.textBlocks.forEach { block ->
                        block.lines.forEach inner@ { line ->
                            val detected = line.boundingBox ?: return@inner
                            val bounds = if (scale < 1f) Rect(
                                (detected.left / scale).toInt(),
                                (detected.top / scale).toInt(),
                                (detected.right / scale).toInt(),
                                (detected.bottom / scale).toInt()
                            ) else Rect(detected)
                            val source = line.text.trim().replace(Regex("\\s+"), " ")
                            if (source.isNotEmpty() && source.any { it.isLetter() }) {
                                val id = lineId(lineIndex++, bounds)
                                val background = sampleBackground(frame, bounds)
                                translateLine(source, id, bounds, background)
                            }
                        }
                    }
                } else {
                    overlay?.showStatus("جارٍ تجهيز الترجمة العربية…")
                }
                overlay?.endFrame()
            }
            .addOnFailureListener {
                // Keep the last successful frame rather than flashing an error.
            }
            .addOnCompleteListener {
                if (ocrBitmap !== frame) ocrBitmap.recycle()
                frame.recycle()
                busy.set(false)
            }
    }

    private fun lineId(index: Int, rect: Rect): String =
        "line-" + index + "-" + (rect.left / 24) + "-" + (rect.top / 24)

    private fun sampleBackground(bitmap: Bitmap, rect: Rect): Int {
        val x1 = rect.left.coerceIn(0, bitmap.width - 1)
        val y1 = rect.top.coerceIn(0, bitmap.height - 1)
        val x2 = (rect.right - 1).coerceIn(0, bitmap.width - 1)
        val y2 = (rect.bottom - 1).coerceIn(0, bitmap.height - 1)
        val points = intArrayOf(
            bitmap.getPixel(x1, y1), bitmap.getPixel(x2, y1),
            bitmap.getPixel(x1, y2), bitmap.getPixel(x2, y2)
        )
        val red = points.map { Color.red(it) }.average().toInt()
        val green = points.map { Color.green(it) }.average().toInt()
        val blue = points.map { Color.blue(it) }.average().toInt()
        return Color.rgb(red, green, blue)
    }

    private fun translateLine(source: String, id: String, bounds: Rect, background: Int) {
        overlay?.markActive(id)
        val key = source.lowercase().take(500)
        cache[key]?.let {
            overlay?.showAt(id, it, bounds, background, screenWidth, screenHeight)
            return
        }
        if (!modelReady || !pending.add(key)) return
        translator.translate(source)
            .addOnSuccessListener { translated ->
                cache[key] = translated
                if (overlay?.isActive(id) == true) {
                    overlay?.showAt(id, translated, bounds, background, screenWidth, screenHeight)
                }
            }
            .addOnCompleteListener { pending.remove(key) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "GameLingo screen translation",
                NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("GameLingo AI يعمل")
            .setContentText("ترجمة نصوص اللعبة في مواضعها")
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        display?.release()
        reader?.close()
        projection?.stop()
        overlay?.hide()
        recognizer.close()
        translator.close()
        thread?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

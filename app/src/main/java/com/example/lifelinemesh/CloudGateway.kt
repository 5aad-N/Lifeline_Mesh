package com.example.lifelinemesh

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object CloudGateway {

    private const val WEBHOOK_URL = BuildConfig.WEBHOOK_URL

    // We keep a lightweight RAM cache of IDs we've already uploaded so we don't spam the server
    private val uploadedMessageIds = mutableSetOf<String>()

    fun attemptOffload(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Check the local SQLite Database
                val dao = com.example.lifelinemesh.data.AppDatabase.getDatabase(context).messageDao()

                // Get all messages, filter for Priority 3 (Emergency)
                val pendingEmergencies = dao.getAllMessages().filter {
                    it.priority == 3 && !uploadedMessageIds.contains(it.id)
                }

                if (pendingEmergencies.isEmpty()) return@launch

                Log.d("CloudGateway", "Found ${pendingEmergencies.size} emergencies to offload!")

                // 2. Process and Upload each emergency
                for (msg in pendingEmergencies) {

                    var alertText = "🚨 **RAW ENCRYPTED DISTRESS SIGNAL**\nID: ${msg.id}\nPayload: ${msg.text}"

                    if (msg.text.startsWith("[P3_ENCRYPTED]")) {
                        val cipherText = msg.text.removePrefix("[P3_ENCRYPTED]")
                        val decrypted = CryptoHelper.decryptPriority3Payload(cipherText)

                        if (decrypted != null) {
                            // Extract all the fields, including the new text body!
                            val messageBody = decrypted.optString("text", "No message provided")
                            val lat = decrypted.optDouble("lat", 0.0)
                            val lng = decrypted.optDouble("lng", 0.0)
                            val name = decrypted.optString("name", "Unknown")
                            val phone = decrypted.optString("phone", "Unknown")

                            // Make the Google Maps link functional if coordinates exist
                            val mapsLink = if (lat != 0.0 && lng != 0.0) {
                                "[View on Google Maps](https://www.google.com/maps/search/?api=1&query=$lat,$lng)"
                            } else {
                                "No GPS coordinates provided."
                            }

                            alertText = """
                                🚨 **LIFELINE MESH EMERGENCY UPLOAD** 🚨
                                **Victim:** $name
                                **Phone:** $phone
                                **Message:** $messageBody
                                **Location:** $lat, $lng
                                $mapsLink
                            """.trimIndent()
                        }
                    }

                    // 3. Fire the HTTP POST request
                    val success = sendWebhook(alertText)
                    if (success) {
                        uploadedMessageIds.add(msg.id) // Mark as uploaded
                        Log.d("CloudGateway", "Successfully offloaded msg: ${msg.id}")
                    }
                }
            } catch (e: Exception) {
                Log.e("CloudGateway", "Offload failed: ${e.message}")
            }
        }
    }

    private fun sendWebhook(content: String): Boolean {
        return try {
            val url = URL(WEBHOOK_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val jsonPayload = JSONObject().apply {
                put("content", content)
            }

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(jsonPayload.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            connection.disconnect()

            responseCode in 200..299
        } catch (e: Exception) {
            false
        }
    }
}
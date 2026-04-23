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

    // Replace this with your own Discord or Telegram Webhook URL!
    // Example Discord: "https://discord.com/api/webhooks/12345/abcdefg"
    private const val WEBHOOK_URL = "https://discordapp.com/api/webhooks/1496883880505114695/Ps2qIokCjnfc0I03kq3l0CvmcIZfUFKk7oLzBRx2AgYu6iiKJOC50zy4Sa1aILs4qjEs"

    // We keep a lightweight RAM cache of IDs we've already uploaded so we don't spam the server
    private val uploadedMessageIds = mutableSetOf<String>()

    fun attemptOffload(context: Context) {
        if (WEBHOOK_URL == "YOUR_WEBHOOK_URL_HERE") return

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

                    // For the Viva Demo: We decrypt it here to make the phone notification look cool!
                    if (msg.text.startsWith("[P3_ENCRYPTED]")) {
                        val cipherText = msg.text.removePrefix("[P3_ENCRYPTED]")
                        val decrypted = CryptoHelper.decryptPriority3Payload(cipherText)

                        if (decrypted != null) {
                            val lat = decrypted.optDouble("lat", 0.0)
                            val lng = decrypted.optDouble("lng", 0.0)
                            val name = decrypted.optString("name", "Unknown")
                            val phone = decrypted.optString("phone", "Unknown")

                            alertText = """
                                🚨 **LIFELINE MESH EMERGENCY UPLOAD** 🚨
                                **Victim:** $name
                                **Phone:** $phone
                                **Location:** $lat, $lng
                                [Google Maps Link](https://www.google.com/maps/search/?api=1&query=$lat,$lng)
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

            // Format for Discord Webhook (Change key to "text" if using Telegram/Slack)
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
package com.example.lifelinemesh

import android.util.Base64
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {
    private const val EMERGENCY_KEY_STRING = "LifelineMeshEmergencyKey12345678"
    private val secretKey = SecretKeySpec(EMERGENCY_KEY_STRING.toByteArray(), "AES")

    fun encryptPriority3Payload(text: String, name: String, phone: String, lat: Double?, lng: Double?): String {
        // 1. Pack the sensitive PII and Coordinates into a JSON object
        val sensitiveData = JSONObject().apply {
            put("text", text)
            put("name", name)
            put("phone", phone)
            put("lat", lat)
            put("lng", lng)
        }.toString()

        // 2. Encrypt the JSON string
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encryptedBytes = cipher.doFinal(sensitiveData.toByteArray())

        // 3. Return as a clean Base64 String
        return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
    }

    fun decryptPriority3Payload(cipherText: String): JSONObject? {
        return try {
            val decodedBytes = Base64.decode(cipherText, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decryptedString = String(cipher.doFinal(decodedBytes))

            JSONObject(decryptedString)
        } catch (e: Exception) {
            null // Decryption failed or not a rescue worker
        }
    }
}
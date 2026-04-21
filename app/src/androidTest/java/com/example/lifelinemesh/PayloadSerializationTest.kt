import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PayloadSerializationTest {

    @Test
    fun testJsonSerializationAndDeserialization() {
        // 1. Create original dummy data (simulating an outgoing distress signal)
        val originalId = UUID.randomUUID().toString()
        val originalText = "Trapped under debris"
        val originalLat = 51.5243 // e.g., QMUL coordinates
        val originalLon = -0.0403

        // 2. Serialize: Pack it into a JSON Object as described in your implementation
        val outgoingJson = JSONObject()
        outgoingJson.put("id", originalId)
        outgoingJson.put("text", originalText)
        outgoingJson.put("latitude", originalLat)
        outgoingJson.put("longitude", originalLon)
        outgoingJson.put("hasLocation", true)

        // Convert to byte array (This is what actually travels over Bluetooth)
        val networkBytes = outgoingJson.toString().toByteArray(StandardCharsets.UTF_8)

        // --- AT THIS POINT, THE DATA IS "IN TRANSIT" ---

        // 3. Deserialize: Simulate the receiving node unpacking the bytes
        val receivedString = String(networkBytes, StandardCharsets.UTF_8)
        val incomingJson = JSONObject(receivedString)

        // 4. Assertions: Prove that absolutely no data was corrupted or lost in translation
        assertEquals("UUID must match perfectly", originalId, incomingJson.getString("id"))
        assertEquals("Text payload must match perfectly", originalText, incomingJson.getString("text"))

        // Prove that the boolean flag and double precision coordinates survived
        assertTrue("hasLocation flag must remain true", incomingJson.getBoolean("hasLocation"))
        assertEquals("Latitude must not lose precision", originalLat, incomingJson.getDouble("latitude"), 0.0001)
        assertEquals("Longitude must not lose precision", originalLon, incomingJson.getDouble("longitude"), 0.0001)
    }
}
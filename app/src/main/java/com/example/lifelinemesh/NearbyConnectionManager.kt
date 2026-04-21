package com.example.lifelinemesh

import android.content.Context
import android.content.Intent
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.lifelinemesh.data.AppDatabase
import com.example.lifelinemesh.data.MessageEntity
import kotlinx.coroutines.delay

class NearbyConnectionManager(
    private val context: Context,
    private val myName: String,
    private val onMessageReceived: (String, String, String, Double?, Double?) -> Unit,
    private val onSystemChatEvent: (String) -> Unit,
    private val onStatusUpdate: (String) -> Unit,
    private val onConnectionChanged: (Int) -> Unit
) {
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.example.lifelinemesh"

    private val connectedEndpoints = mutableListOf<String>()
    private val endpointNames = mutableMapOf<String, String>()
    private val pendingConnections = mutableSetOf<String>()
    private val seenMessageIds = mutableSetOf<String>()

    fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        Nearby.getConnectionsClient(context)
            .startAdvertising(myName, SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
            .addOnSuccessListener { onStatusUpdate("Advertising as $myName") }
            .addOnFailureListener { e -> onStatusUpdate("Advertising Failed: ${e.message}") }
    }

    fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        Nearby.getConnectionsClient(context)
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener { onStatusUpdate("Scanning for peers...") }
            .addOnFailureListener { e -> onStatusUpdate("Discovery Failed: ${e.message}") }
    }

    fun stopDiscovery() {
        // This stops the high-drain scanning, but leaves startAdvertising() running!
        Nearby.getConnectionsClient(context).stopDiscovery()
    }

    fun sendData(messageId: String, message: String, senderName: String, senderPhone: String, lat: Double? = null, lng: Double? = null) {
        seenMessageIds.add(messageId)

        val jsonEnvelope = JSONObject()
        jsonEnvelope.put("messageId", messageId) // <-- NEW: Stamp the envelope
        jsonEnvelope.put("text", message)
        jsonEnvelope.put("senderName", senderName)
        jsonEnvelope.put("senderPhone", senderPhone)

        if (lat != null && lng != null) {
            jsonEnvelope.put("latitude", lat)
            jsonEnvelope.put("longitude", lng)
            jsonEnvelope.put("hasLocation", true)
        } else {
            jsonEnvelope.put("hasLocation", false)
        }

        val bytes = jsonEnvelope.toString().toByteArray(StandardCharsets.UTF_8)
        val payload = Payload.fromBytes(bytes)

        if (connectedEndpoints.isNotEmpty()) {
            Nearby.getConnectionsClient(context).sendPayload(connectedEndpoints, payload)
        }
    }

    fun stop() {
        Nearby.getConnectionsClient(context).stopAllEndpoints()
        Nearby.getConnectionsClient(context).stopAdvertising()
        Nearby.getConnectionsClient(context).stopDiscovery()
        connectedEndpoints.clear()
        endpointNames.clear()
        pendingConnections.clear()
        seenMessageIds.clear()
        onConnectionChanged(0)
        onStatusUpdate("Radio Stopped")
    }

    private fun syncDatabaseWithPeer(newEndpointId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            // Give the Bluetooth socket 1.5 seconds to fully open before blasting data
            delay(1500)

            val dao = AppDatabase.getDatabase(context).messageDao()
            val pendingMessages = dao.getMessagesForForwarding()

            for (msg in pendingMessages) {
                val jsonEnvelope = JSONObject()
                jsonEnvelope.put("messageId", msg.id)
                jsonEnvelope.put("text", msg.text)
                jsonEnvelope.put("senderName", msg.senderName)
                jsonEnvelope.put("senderPhone", msg.senderPhone)

                if (msg.latitude != null && msg.longitude != null) {
                    jsonEnvelope.put("latitude", msg.latitude)
                    jsonEnvelope.put("longitude", msg.longitude)
                    jsonEnvelope.put("hasLocation", true)
                } else {
                    jsonEnvelope.put("hasLocation", false)
                }

                val bytes = jsonEnvelope.toString().toByteArray(StandardCharsets.UTF_8)
                val payload = Payload.fromBytes(bytes)

                Nearby.getConnectionsClient(context).sendPayload(newEndpointId, payload)
            }
        }
    }

    private fun updateServiceNotification(count: Int) {
        val statusText = if (count > 0) {
            "Connected to $count peer(s). Relaying data..."
        } else {
            "Scanning for nearby peers..."
        }

        val intent = Intent(context, MeshService::class.java).apply {
            putExtra("STATUS_TEXT", statusText)
        }
        context.startService(intent) // This triggers onStartCommand in the service
    }

    // --- CALLBACKS ---

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (connectedEndpoints.contains(endpointId) || pendingConnections.contains(endpointId)) return
            pendingConnections.add(endpointId)
            onStatusUpdate("Found ${info.endpointName}. Requesting...")

            Nearby.getConnectionsClient(context)
                .requestConnection(myName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e ->
                    pendingConnections.remove(endpointId)

                    if (e !is ApiException || e.statusCode != ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR) {
                        onStatusUpdate("Request Failed: ${e.message}")
                    }
                }
        }
        override fun onEndpointLost(endpointId: String) {
            pendingConnections.remove(endpointId)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingConnections.add(endpointId)
            endpointNames[endpointId] = info.endpointName
            onStatusUpdate("Incoming from ${info.endpointName}...")

            Nearby.getConnectionsClient(context).acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e ->
                    pendingConnections.remove(endpointId)

                    if (e !is ApiException || e.statusCode != ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR) {
                        onStatusUpdate("Request Failed: ${e.message}")
                    }
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingConnections.remove(endpointId)
            if (result.status.isSuccess) {
                if (!connectedEndpoints.contains(endpointId)) {
                    connectedEndpoints.add(endpointId)
                    val peerName = endpointNames[endpointId] ?: "Unknown Peer"
                    onSystemChatEvent("$peerName has joined the mesh")
                    onConnectionChanged(connectedEndpoints.size)
                    updateServiceNotification(connectedEndpoints.size)
                    onStatusUpdate("Connected to ${connectedEndpoints.size} device(s)")

                    // NEW: Trigger the targeted, delayed Store-and-Forward sync!
                    syncDatabaseWithPeer(endpointId)
                }
            } else if (result.status.statusCode != 8012) {
                onStatusUpdate("Connection Failed (${result.status.statusCode})")
            }
        }
        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            pendingConnections.remove(endpointId)
            val peerName = endpointNames.remove(endpointId) ?: "Unknown Peer"
            onSystemChatEvent("$peerName has left the mesh")
            onConnectionChanged(connectedEndpoints.size)
            updateServiceNotification(connectedEndpoints.size)
            onStatusUpdate("Disconnected from $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                try {
                    val jsonString = String(bytes, StandardCharsets.UTF_8)
                    val jsonEnvelope = JSONObject(jsonString)
                    val messageId = jsonEnvelope.getString("messageId")

                    if (seenMessageIds.contains(messageId)) {
                        return
                    }
                    seenMessageIds.add(messageId)

                    val text = jsonEnvelope.getString("text")
                    val senderName = jsonEnvelope.getString("senderName")
                    val senderPhone = jsonEnvelope.getString("senderPhone")
                    val hasLocation = jsonEnvelope.optBoolean("hasLocation", false)
                    val lat = if (hasLocation) jsonEnvelope.getDouble("latitude") else null
                    val lng = if (hasLocation) jsonEnvelope.getDouble("longitude") else null

                    // Save incoming message to local database so this device acts as a Data Mule
                    CoroutineScope(Dispatchers.IO).launch {
                        val dao = AppDatabase.getDatabase(context).messageDao()
                        dao.insertMessage(
                            MessageEntity(
                                id = messageId,
                                text = text,
                                isFromMe = false,
                                senderName = senderName,
                                senderPhone = senderPhone,
                                latitude = lat,
                                longitude = lng,
                                timestamp = System.currentTimeMillis(),
                                priority = if (hasLocation) 3 else 1 // Infer priority
                            )
                        )
                    }

                    onMessageReceived(text, senderName, senderPhone, lat, lng)

                    val endpointsToForward = connectedEndpoints.filter { it != endpointId }
                    if (endpointsToForward.isNotEmpty()) {
                        Nearby.getConnectionsClient(context).sendPayload(endpointsToForward, payload)
                    }

                } catch (e: Exception) {
                    val rawText = String(bytes, StandardCharsets.UTF_8)
                    onMessageReceived(rawText, "Unknown", "Unknown", null, null)
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
}
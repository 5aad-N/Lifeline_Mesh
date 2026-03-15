package com.example.lifelinemesh

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.charset.StandardCharsets

class NearbyConnectionManager(
    private val context: Context,
    private val myName: String,
    private val onMessageReceived: (String) -> Unit,
    private val onSystemChatEvent: (String) -> Unit,
    private val onStatusUpdate: (String) -> Unit,
    private val onConnectionChanged: (Int) -> Unit
) {
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.example.lifelinemesh"

    private val connectedEndpoints = mutableListOf<String>()
    private val endpointNames = mutableMapOf<String, String>()
    private val pendingConnections = mutableSetOf<String>()

    fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()

        Nearby.getConnectionsClient(context)
            .startAdvertising(myName, SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
            .addOnSuccessListener {
                onStatusUpdate("Advertising as $myName")
            }
            .addOnFailureListener { e ->
                onStatusUpdate("Advertising Failed: ${e.message}")
            }
    }

    fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()

        Nearby.getConnectionsClient(context)
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener {
                onStatusUpdate("Scanning for peers...")
            }
            .addOnFailureListener { e ->
                onStatusUpdate("Discovery Failed: ${e.message}")
            }
    }

    fun sendData(message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
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
        onConnectionChanged(0)
        onStatusUpdate("Radio Stopped")
    }

    // --- CALLBACKS ---

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (connectedEndpoints.contains(endpointId) || pendingConnections.contains(endpointId)) {
                return
            }

            pendingConnections.add(endpointId)

            onStatusUpdate("Found ${info.endpointName}. Requesting...")

            Nearby.getConnectionsClient(context)
                .requestConnection(myName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e ->
                    pendingConnections.remove(endpointId)
                    onStatusUpdate("Request Failed: ${e.message}")
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
                    onStatusUpdate("Accept Failed: ${e.message}")
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
                    onStatusUpdate("Connected to ${connectedEndpoints.size} device(s)")
                }
            } else {
                if (result.status.statusCode != 8012) {
                    onStatusUpdate("Connection Failed (${result.status.statusCode})")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            pendingConnections.remove(endpointId)

            val peerName = endpointNames.remove(endpointId) ?: "Unknown Peer"
            onSystemChatEvent("$peerName has left the mesh")

            onConnectionChanged(connectedEndpoints.size)
            onStatusUpdate("Disconnected from $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let {
                val message = String(it, StandardCharsets.UTF_8)
                onMessageReceived(message)
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
}
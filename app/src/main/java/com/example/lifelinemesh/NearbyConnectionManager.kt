package com.example.lifelinemesh

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.charset.StandardCharsets

class NearbyConnectionManager(
    private val context: Context,
    private val onMessageReceived: (String) -> Unit
) {
    // 1. USE CLUSTER (Best for Mesh/Offline flexibility)
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.example.lifelinemesh"

    private val connectedEndpoints = mutableListOf<String>()

    fun startAdvertising(user: String) {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()

        Nearby.getConnectionsClient(context)
            .startAdvertising(user, SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
            .addOnSuccessListener {
                onMessageReceived("SYSTEM: Advertising Started")
            }
            .addOnFailureListener { e ->
                onMessageReceived("ERROR: Advertising Failed (${e.message})")
            }
    }

    fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()

        Nearby.getConnectionsClient(context)
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener {
                onMessageReceived("SYSTEM: Discovery Started")
            }
            .addOnFailureListener { e ->
                onMessageReceived("ERROR: Discovery Failed (${e.message})")
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
        onMessageReceived("SYSTEM: Radio Stopped")
    }

    // --- CALLBACKS ---

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            onMessageReceived("SYSTEM: Found ${info.endpointName}. Requesting connection...")

            // 2. THE FIX: ADD ERROR LISTENER TO REQUEST
            Nearby.getConnectionsClient(context)
                .requestConnection("LifelineUser", endpointId, connectionLifecycleCallback)
                .addOnSuccessListener {
                    // NOTE: This doesn't mean connected yet. It means request SENT.
                    onMessageReceived("DEBUG: Request Sent to ${info.endpointName}")
                }
                .addOnFailureListener { e ->
                    // THIS IS WHERE THE HANG HAPPENS
                    onMessageReceived("ERROR: Request Failed: ${e.message}")
                }
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            onMessageReceived("SYSTEM: Incoming Connection from ${info.endpointName}...")

            // 3. THE FIX: ADD ERROR LISTENER TO ACCEPT
            Nearby.getConnectionsClient(context).acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e ->
                    onMessageReceived("ERROR: Could not Accept Connection: ${e.message}")
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                onMessageReceived("SUCCESS: Connected to ${endpointId}")

                // Stop discovery once connected to save battery/bandwidth (Optional)
                // Nearby.getConnectionsClient(context).stopDiscovery()
            } else {
                if (result.status.statusCode != 8012) {
                    onMessageReceived("ERROR: Connection Failed (${result.status.statusCode})")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            onMessageReceived("SYSTEM: Disconnected from $endpointId")
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
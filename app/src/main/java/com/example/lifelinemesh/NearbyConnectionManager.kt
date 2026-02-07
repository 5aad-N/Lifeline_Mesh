package com.example.lifelinemesh

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.charset.StandardCharsets

class NearbyConnectionManager(
    private val context: Context,
    private val onMessageReceived: (String) -> Unit // We use this to send status updates too!
) {

    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.example.lifelinemesh"

    private val connectedEndpoints = mutableListOf<String>()

    // 1. START ADVERTISING
    fun startAdvertising(user: String) {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()

        Nearby.getConnectionsClient(context)
            .startAdvertising(user, SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
            .addOnSuccessListener {
                Log.d("Lifeline", "Advertising started")
                // VISUAL CONFIRMATION:
                onMessageReceived("SYSTEM: Radio ON (Advertising as $user)")
            }
            .addOnFailureListener { e ->
                Log.e("Lifeline", "Advertising failed")
                onMessageReceived("ERROR: Could not start Advertising (${e.message})")
            }
    }

    // 2. START DISCOVERY
    fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()

        Nearby.getConnectionsClient(context)
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener {
                Log.d("Lifeline", "Discovery started")
                // VISUAL CONFIRMATION:
                onMessageReceived("SYSTEM: Scanning for nearby peers...")
            }
            .addOnFailureListener { e ->
                Log.e("Lifeline", "Discovery failed")
                onMessageReceived("ERROR: Could not start Scanning (${e.message})")
            }
    }

    // 3. SEND DATA
    fun sendData(message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        val payload = Payload.fromBytes(bytes)
        if (connectedEndpoints.isNotEmpty()) {
            Nearby.getConnectionsClient(context).sendPayload(connectedEndpoints, payload)
        }
    }

    fun stop() {
        Nearby.getConnectionsClient(context).stopAdvertising()
        Nearby.getConnectionsClient(context).stopDiscovery()
        Nearby.getConnectionsClient(context).stopAllEndpoints()
        connectedEndpoints.clear()
        onMessageReceived("SYSTEM: Radio Stopped")
    }

    // --- CALLBACKS ---

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            onMessageReceived("SYSTEM: Found Device (${info.endpointName})")
            Nearby.getConnectionsClient(context)
                .requestConnection("LifelineUser", endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Nearby.getConnectionsClient(context).acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                // VISUAL CONFIRMATION OF CONNECTION:
                onMessageReceived("SYSTEM: Connected to ${endpointId}")
            } else {
                onMessageReceived("SYSTEM: Connection Failed")
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
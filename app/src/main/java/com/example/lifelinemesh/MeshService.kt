package com.example.lifelinemesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch
import java.util.UUID

class MeshService : Service() {
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private lateinit var nearbyManager: NearbyConnectionManager
    private var isSurvivalModeActive = false
    private var userName = "Unknown"

    // 1. The Battery Listener is now safely in the Background Service!
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = level * 100 / scale.toFloat()

                if (batteryPct <= 20.0f && !isSurvivalModeActive) {
                    isSurvivalModeActive = true
                    Log.d("SurvivalMode", "Beacon Mode Activated: Stopping Discovery")

                    if (::nearbyManager.isInitialized) nearbyManager.stopDiscovery()
                    broadcastStatusUpdate("🚨 Beacon Mode: Scanning Suspended", null)

                } else if (batteryPct > 20.0f && isSurvivalModeActive) {
                    isSurvivalModeActive = false
                    Log.d("SurvivalMode", "Battery recovered: Resuming BLE Discovery")

                    if (::nearbyManager.isInitialized) nearbyManager.startDiscovery()
                    broadcastStatusUpdate("Radio Active", null)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Start Foreground Notification so Android doesn't kill this service
        createNotificationChannel()
        val notification = buildNotification("Lifeline Mesh Active")
        startForeground(1, notification)

        // Register Battery Listener
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d("CloudGateway", "Internet Connected! Attempting database offload...")
                // The moment Wi-Fi or Cellular is found, blast the distress signals!
                CloudGateway.attemptOffload(applicationContext)
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // When the UI first opens, it passes the user's name
        if (intent?.hasExtra("USER_NAME") == true) {
            userName = intent.getStringExtra("USER_NAME") ?: "Unknown"

            // 2. Initialize the Radio if it hasn't been started yet
            if (!::nearbyManager.isInitialized) {
                nearbyManager = NearbyConnectionManager(
                    context = this,
                    myName = userName,
                    onMessageReceived = { incomingId, incomingText, incomingName, incomingPhone, lat, lng ->

                        val isEncryptedP3 = incomingText.startsWith("[P3_ENCRYPTED]")
                        val priorityLevel = if (isEncryptedP3) 3 else 1

                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            val dao = com.example.lifelinemesh.data.AppDatabase.getDatabase(applicationContext).messageDao()

                            dao.insertMessage(
                                com.example.lifelinemesh.data.MessageEntity(
                                    id = incomingId,
                                    text = incomingText,
                                    isFromMe = false,
                                    senderName = incomingName,
                                    senderPhone = incomingPhone,
                                    latitude = lat,
                                    longitude = lng,
                                    timestamp = System.currentTimeMillis(),
                                    priority = priorityLevel
                                )
                            )

                            // NEW: If this is an emergency and the Mule ALREADY has internet, upload it!
                            if (priorityLevel == 3) {
                                CloudGateway.attemptOffload(applicationContext)
                            }
                        }

                        // 3. Pass it to the UI
                        val uiIntent = Intent("MESH_MESSAGE_RECEIVED").apply {
                            putExtra("text", incomingText)
                            putExtra("name", incomingName)
                            putExtra("phone", incomingPhone)
                            if (lat != null) putExtra("lat", lat)
                            if (lng != null) putExtra("lng", lng)
                        }
                        sendBroadcast(uiIntent)
                    },
                    onSystemChatEvent = { /* Optional system events */ },
                    onStatusUpdate = { status ->
                        broadcastStatusUpdate(status, null)
                    },
                    onConnectionChanged = { count ->
                        broadcastStatusUpdate(null, count)
                        updateNotification("Radio Active, $count Connected Peers")
                    }
                )
                nearbyManager.startAdvertising()
                nearbyManager.startDiscovery()
                broadcastStatusUpdate("Radio Active", 0)
            } else {
                val currentConnections = nearbyManager.getConnectedEndpointsCount()

                val statusText = if (currentConnections > 0) {
                    "Connected to $currentConnections device(s)"
                } else {
                    "Radio Active"
                }

                broadcastStatusUpdate(statusText, currentConnections)
            }
        }

        // 3. Catch Intents from the UI when the user presses the "Send" button
        if (intent?.action == "SEND_PAYLOAD") {
            if (::nearbyManager.isInitialized) {
                val id = intent.getStringExtra("id") ?: UUID.randomUUID().toString()
                val text = intent.getStringExtra("text") ?: ""
                val name = intent.getStringExtra("name") ?: ""
                val phone = intent.getStringExtra("phone") ?: ""

                // Check if this is a standard message or an emergency
                val isEmergency = intent.getBooleanExtra("isEmergency", false)

                if (isEmergency) {
                    val lat = intent.getDoubleExtra("lat", 0.0)
                    val lng = intent.getDoubleExtra("lng", 0.0)

                    // ENCRYPT THE SENSITIVE DATA
                    val cipherData = CryptoHelper.encryptPriority3Payload(text, name, phone, lat, lng)
                    val payloadText = "[P3_ENCRYPTED]$cipherData"

                    // Send the encrypted payload over the radio. Metadata is anonymized!
                    nearbyManager.sendData(id, payloadText, "Encrypted User", "Hidden", null, null)
                } else {
                    // Send standard message in plaintext
                    nearbyManager.sendData(id, text, name, phone, null, null)
                }
            }
        }
        // If the OS kills us for memory, START_STICKY tells it to resurrect us!
        return START_STICKY
    }

    private fun broadcastStatusUpdate(status: String?, connections: Int?) {
        val intent = Intent("MESH_STATUS_UPDATE")
        status?.let { intent.putExtra("status", it) }
        connections?.let { intent.putExtra("connections", it) }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)

        if (::connectivityManager.isInitialized) {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }

        if (::nearbyManager.isInitialized) {
            nearbyManager.stop()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We aren't using bound services
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "mesh_channel",
                "Mesh Network Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "mesh_channel")
            .setContentTitle("Lifeline Mesh")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // Feel free to change icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, notification)
    }
}
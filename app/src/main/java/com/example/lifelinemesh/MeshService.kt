package com.example.lifelinemesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.UUID

class MeshService : Service() {

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
                    onMessageReceived = { text, name, phone, lat, lng ->
                        // Pass incoming payloads to the UI
                        val uiIntent = Intent("MESH_MESSAGE_RECEIVED").apply {
                            putExtra("text", text)
                            putExtra("name", name)
                            putExtra("phone", phone)
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
            }
        }

        // 3. Catch Intents from the UI when the user presses the "Send" button
        if (intent?.action == "SEND_PAYLOAD") {
            if (::nearbyManager.isInitialized) {
                val id = intent.getStringExtra("id") ?: UUID.randomUUID().toString()
                val text = intent.getStringExtra("text") ?: ""
                val name = intent.getStringExtra("name") ?: ""
                val phone = intent.getStringExtra("phone") ?: ""
                val lat = if (intent.hasExtra("lat")) intent.getDoubleExtra("lat", 0.0) else null
                val lng = if (intent.hasExtra("lng")) intent.getDoubleExtra("lng", 0.0) else null

                nearbyManager.sendData(id, text, name, phone, lat, lng)
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
package com.example.lifelinemesh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MeshService : Service() {
    private var nearbyManager: NearbyConnectionManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Build the persistent notification
        val notification = NotificationCompat.Builder(this, "MESH_CHANNEL_ID")
            .setContentTitle("Lifeline Mesh Active")
            .setContentText("Acting as a Data Mule in the background.")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Default icon, replace with your own later
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        // Start the service in the foreground with the required specific type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val userName = intent?.getStringExtra("USER_NAME") ?: "Mule"
        val statusText = intent?.getStringExtra("STATUS_TEXT")

        if (nearbyManager == null && intent?.hasExtra("USER_NAME") == true) {
            // The Service now owns the mesh radio!
            nearbyManager = NearbyConnectionManager(
                context = this,
                myName = userName,
                onMessageReceived = { text, name, phone, lat, lng ->
                    // Broadcast to UI if it's open
                    sendBroadcast(Intent("MESH_MESSAGE_RECEIVED").apply {
                        putExtra("text", text)
                        putExtra("name", name)
                        putExtra("phone", phone)
                        putExtra("lat", lat)
                        putExtra("lng", lng)
                    })
                },
                onSystemChatEvent = { /* Log or broadcast */ },
                onStatusUpdate = { /* Update notification */ },
                onConnectionChanged = { count -> updateNotification("Connected to $count peers") }
            )
            nearbyManager?.startAdvertising()
            nearbyManager?.startDiscovery()
        }

        if (statusText != null) updateNotification(statusText)
        return START_STICKY
    }

    private fun updateNotification(contentText: String) {
        val notification = NotificationCompat.Builder(this, "MESH_CHANNEL_ID")
            .setContentTitle("Lifeline Mesh") // Keep title consistent
            .setContentText(contentText)      // Change this based on state
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification) // Using '1' updates the existing notification instead of creating a new one
    }

    override fun onBind(intent: Intent?): IBinder? {
        // We don't need UI binding for this implementation
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "MESH_CHANNEL_ID",
                "Mesh Network Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the mesh network scanning in the background"
            }
            val manager: NotificationManager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
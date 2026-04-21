package com.example.lifelinemesh

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.lifelinemesh.ui.theme.LifelineMeshTheme
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.lifelinemesh.data.AppDatabase
import com.example.lifelinemesh.data.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import kotlinx.coroutines.withContext
import android.annotation.SuppressLint
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isFromMe: Boolean,
    val senderName: String = "",
    val senderPhone: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isSystemEvent: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class UserProfile(
    val name: String,
    val phoneNumber: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LifelineMeshTheme {
                val focusManager = LocalFocusManager.current

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                focusManager.clearFocus()
                            })
                        },
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    AppContent(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun AppContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lifeline_prefs", Context.MODE_PRIVATE) }
    var currentUser by remember {
        mutableStateOf(
            prefs.getString("user_name", null)?.let { name ->
                UserProfile(name, prefs.getString("user_phone", "") ?: "")
            }
        )
    }

    if (currentUser == null) {
        LoginScreen(
            onLoginSuccess = { name, phone ->
                // Save to disk
                prefs.edit().putString("user_name", name).putString("user_phone", phone).apply()
                currentUser = UserProfile(name, phone)
            },
            modifier = modifier
        )
    } else {
        PermissionWrapper(
            onPermissionsGranted = {
                ChatScreen(user = currentUser!!, modifier = modifier)
            }
        )
    }
}

@Composable
fun PermissionWrapper(onPermissionsGranted: @Composable () -> Unit) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(false) }
    var isLocationServiceEnabled by remember { mutableStateOf(false) }

    val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    if (Build.VERSION.SDK_INT >= 33) {
        requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun checkLocationService(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { perms ->
            val locationGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (locationGranted) {
                hasPermissions = true
                isLocationServiceEnabled = checkLocationService()
            } else {
                Toast.makeText(context, "Location permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    )

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isLocationServiceEnabled = checkLocationService()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            hasPermissions = true
            isLocationServiceEnabled = checkLocationService()
        } else {
            launcher.launch(requiredPermissions.toTypedArray())
        }
    }

    if (hasPermissions && isLocationServiceEnabled) {
        onPermissionsGranted()
    }
    else if (hasPermissions) {
        AlertDialog(
            onDismissRequest = { /* Prevent dismissal */ },
            title = { Text("Turn on Location") },
            text = { Text("To find nearby devices, this phone needs Location Services (GPS) turned on.") },
            confirmButton = {
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }) {
                    Text("Open Settings")
                }
            }
        )
    }
    else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun LoginScreen(
    onLoginSuccess: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var nameInput by remember { mutableStateOf("") }
    var phoneInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Lifeline Mesh",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text("Offline Emergency Network")

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = it },
            label = { Text("Full Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = phoneInput,
            onValueChange = { phoneInput = it },
            label = { Text("Phone Number") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (errorMessage.isNotEmpty()) {
            Text(errorMessage, color = Color.Red)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = {
                if (nameInput.isNotBlank() && phoneInput.isNotBlank()) {
                    onLoginSuccess(nameInput, phoneInput)
                } else {
                    errorMessage = "Please fill in all fields."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Join Network")
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ChatScreen(user: UserProfile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val messages = remember { mutableStateListOf<ChatMessage>() }

    LaunchedEffect(user) {
        val serviceIntent = Intent(context, MeshService::class.java).apply {
            putExtra("USER_NAME", user.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    LaunchedEffect(Unit) {
        val dao = AppDatabase.getDatabase(context).messageDao()
        val savedMessages = dao.getAllMessages()

        for (msg in savedMessages) {
            messages.add(
                ChatMessage(
                    id = msg.id,
                    text = msg.text,
                    isFromMe = msg.isFromMe,
                    senderName = msg.senderName,
                    senderPhone = msg.senderPhone,
                    latitude = msg.latitude,
                    longitude = msg.longitude,
                    timestamp = msg.timestamp
                )
            )
        }
    }

    // Listen for background messages from the MeshService
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val text = intent?.getStringExtra("text") ?: ""
                val name = intent?.getStringExtra("name") ?: "Unknown"
                val phone = intent?.getStringExtra("phone") ?: ""
                val lat = if (intent?.hasExtra("lat") == true) intent.getDoubleExtra("lat", 0.0) else null
                val lng = if (intent?.hasExtra("lng") == true) intent.getDoubleExtra("lng", 0.0) else null

                messages.add(
                    ChatMessage(
                        text = text,
                        isFromMe = false,
                        senderName = name,
                        senderPhone = phone,
                        latitude = lat,
                        longitude = lng
                    )
                )
            }
        }

        val filter = android.content.IntentFilter("MESH_MESSAGE_RECEIVED")

        // FIX: Explicitly check for API 33+ (Tiramisu) and provide the NOT_EXPORTED flag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // For older versions, the flag isn't required/supported
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    var currentText by remember { mutableStateOf("") }
    var systemStatus by remember { mutableStateOf("Initializing Radio...") }
    var activeConnections by remember { mutableIntStateOf(0) }

    val focusManager = LocalFocusManager.current

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val listState = rememberLazyListState()

    // 2. The tracker that measures how tall the keyboard is right now
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    // 3. The Auto-Scroll Trigger!
    // This fires instantly whenever the message list grows OR the keyboard pops up
    LaunchedEffect(messages.size, imeBottom) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val nearbyManager = remember {
        NearbyConnectionManager(
            context = context,
            myName = user.name,
            onMessageReceived = { incomingText, senderName, senderPhone, lat, lng ->
                messages.add(
                    ChatMessage(
                        text = incomingText,
                        isFromMe = false,
                        senderName = senderName,
                        senderPhone = senderPhone,
                        latitude = lat,
                        longitude = lng
                    )
                )
            },
            onSystemChatEvent = { eventText ->
                messages.add(ChatMessage(text = eventText, isFromMe = false, isSystemEvent = true))
            },
            onStatusUpdate = { status ->
                systemStatus = status
            },
            onConnectionChanged = { count ->
                activeConnections = count
            }
        )
    }

    DisposableEffect(context) {
        // 1. Initialise the radio normally when the app opens
        nearbyManager.startAdvertising()
        nearbyManager.startDiscovery()
        var isSurvivalModeActive = false

        // 2. Create the Battery Listener
        val batteryReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val batteryPct = level * 100 / scale.toFloat()

                    // BEACON MODE LOGIC: Below 20%, stop scanning but KEEP advertising
                    if (batteryPct <= 20.0f && !isSurvivalModeActive) {
                        isSurvivalModeActive = true
                        systemStatus = "🚨 Beacon Mode: Scanning Suspended (Low Battery)"
                        Log.d("SurvivalMode", "Beacon Mode Activated: Stopping Discovery, keeping Advertising active")

                        // Stop looking for others (saves battery) but stay visible to rescuers!
                        nearbyManager.stopDiscovery()
                    }
                    // RECOVERY LOGIC: Above 20%, turn scanning back on
                    else if (batteryPct > 20.0f && isSurvivalModeActive) {
                        isSurvivalModeActive = false
                        systemStatus = "Radio Active"
                        Log.d("SurvivalMode", "Battery recovered: Resuming BLE Discovery")

                        // Resume full DTN operations
                        nearbyManager.startDiscovery()
                    }
                }
            }
        }

        // 3. Register the listener with the Android OS
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)

        // 4. Clean up when the UI is closed
        onDispose {
            context.unregisterReceiver(batteryReceiver)
            nearbyManager.stop()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {

        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "User: ${user.name}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (activeConnections > 0) Color(0xFF4CAF50) else Color.Red,
                            modifier = Modifier.size(10.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$activeConnections Connected",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        // Clear Database Button
                        IconButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    // 1. Wipe the physical SQLite database
                                    AppDatabase.getDatabase(context).messageDao().clearAllMessages()

                                    // 2. Wipe the UI state on the Main thread
                                    withContext(Dispatchers.Main) {
                                        messages.clear()
                                        Toast.makeText(context, "Mesh History Cleared", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear Mesh",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = systemStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(8.dp),
            state = listState
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No messages yet. Waiting for peers...", color = Color.Gray)
                    }
                }
            }

            // itemsIndexed lets us look at the previous message!
            itemsIndexed(messages) { index, message ->
                val isFirstMessage = index == 0
                val previousMessage = if (!isFirstMessage) messages[index - 1] else null

                // Decide if we should show the header based on the previous message
                val showHeader = isFirstMessage ||
                        previousMessage?.isSystemEvent == true ||
                        previousMessage?.senderName != message.senderName ||
                        previousMessage.isFromMe != message.isFromMe

                MessageBubble(message = message, showHeader = showHeader)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    Toast.makeText(context, "Acquiring GPS lock...", Toast.LENGTH_SHORT).show()
                    try {
                        // FIX: Add explicit permission check to satisfy Android Studio Lint
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                                .addOnSuccessListener { location ->
                                    if (location != null) {
                                        val alertText = "🚨 Emergency Location Shared"
                                        val newLocationMessage = ChatMessage(
                                            text = alertText,
                                            isFromMe = true,
                                            senderName = user.name,
                                            senderPhone = user.phoneNumber,
                                            latitude = location.latitude,
                                            longitude = location.longitude
                                        )
                                        messages.add(newLocationMessage)

                                        // 1. Save to Database (Store)
                                        scope.launch(Dispatchers.IO) {
                                            val dao = AppDatabase.getDatabase(context).messageDao()
                                            dao.insertMessage(
                                                MessageEntity(
                                                    id = newLocationMessage.id,
                                                    text = alertText,
                                                    isFromMe = true,
                                                    senderName = user.name,
                                                    senderPhone = user.phoneNumber,
                                                    latitude = location.latitude,
                                                    longitude = location.longitude,
                                                    timestamp = System.currentTimeMillis(),
                                                    priority = 3 // priority for GPS
                                                )
                                            )
                                        }

                                        // 2. Attempt to send immediately (Forward)
                                        nearbyManager.sendData(
                                            messageId = newLocationMessage.id,
                                            message = alertText,
                                            senderName = user.name,
                                            senderPhone = user.phoneNumber,
                                            lat = location.latitude,
                                            lng = location.longitude
                                        )
                                    } else {
                                        Toast.makeText(context, "Ensure GPS is turned on and you are outdoors.", Toast.LENGTH_LONG).show()
                                    }
                                }
                                .addOnFailureListener {
                                    Toast.makeText(context, "Failed to get location.", Toast.LENGTH_SHORT).show()
                                }
                        } else {
                            Toast.makeText(context, "Location permission missing.", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: SecurityException) {
                        Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Text("📍", fontSize = 24.sp)
            }

            TextField(
                value = currentText,
                onValueChange = { currentText = it },
                placeholder = { Text("Type distress signal...") },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()
                    if (currentText.isNotBlank()) {
                        val msgToSend = currentText
                        val newChatMessage = ChatMessage(
                            text = msgToSend,
                            isFromMe = true,
                            senderName = user.name,
                            senderPhone = user.phoneNumber
                        )
                        messages.add(newChatMessage)

                        // 1. Save to Database (Store)
                        scope.launch(Dispatchers.IO) {
                            val dao = AppDatabase.getDatabase(context).messageDao()
                            dao.insertMessage(
                                MessageEntity(
                                    id = newChatMessage.id,
                                    text = msgToSend,
                                    isFromMe = true,
                                    senderName = user.name,
                                    senderPhone = user.phoneNumber,
                                    latitude = null,
                                    longitude = null,
                                    timestamp = System.currentTimeMillis(),
                                    priority = 1 // NORMAL priority for text
                                )
                            )
                        }

                        // 2. Attempt to send immediately (Forward)
                        nearbyManager.sendData(
                            messageId = newChatMessage.id,
                            message = msgToSend,
                            senderName = user.name,
                            senderPhone = user.phoneNumber,
                            lat = null,
                            lng = null
                        )
                        currentText = ""
                    }
                }
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage, showHeader: Boolean = true) {
    if (message.isSystemEvent) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = message.text, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        }
    } else {
        val bubbleColor = if (message.isFromMe) MaterialTheme.colorScheme.primary else Color.LightGray
        val textColor = if (message.isFromMe) Color.White else Color.Black
        val alignment = if (message.isFromMe) Alignment.End else Alignment.Start

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = alignment
        ) {
            Surface(
                color = bubbleColor,
                // Dynamic corners for that WhatsApp "tail" effect
                shape = RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 12.dp,
                    bottomStart = if (message.isFromMe || !showHeader) 12.dp else 2.dp,
                    bottomEnd = if (!message.isFromMe || !showHeader) 12.dp else 2.dp
                ),
                // Tighter vertical padding if it's a grouped message
                modifier = Modifier.padding(
                    start = 8.dp,
                    top = if (showHeader) 8.dp else 2.dp,
                    end = 8.dp,
                    bottom = 2.dp
                )
            ) {
                Column(modifier = Modifier.padding(8.dp)) {

                    // --- THE WHATSAPP HEADER ---
                    // Only show on incoming messages when showHeader is true
                    if (showHeader && !message.isFromMe && message.senderName.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = message.senderName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary, // Pop of color for the name
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "~ ${message.senderPhone}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.DarkGray
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    // --- THE MESSAGE TEXT ---
                    Text(
                        text = message.text,
                        color = textColor
                    )

                    // Draw the location data if it exists
                    if (message.latitude != null && message.longitude != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = Color.Black.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Lat: ${"%.4f".format(message.latitude)}\nLng: ${"%.4f".format(message.longitude)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = textColor,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AppPreview() {
    LifelineMeshTheme {
        LoginScreen(onLoginSuccess = { _, _ -> })
    }
}
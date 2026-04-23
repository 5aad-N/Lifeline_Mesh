package com.example.lifelinemesh

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.material.icons.filled.ExitToApp

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

// ADDED isRescueWorker FLAG
data class UserProfile(
    val name: String,
    val phoneNumber: String,
    val isRescueWorker: Boolean = false
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

    // UPDATED to load the rescue worker flag from storage
    var currentUser by remember {
        mutableStateOf(
            prefs.getString("user_name", null)?.let { name ->
                UserProfile(
                    name = name,
                    phoneNumber = prefs.getString("user_phone", "") ?: "",
                    isRescueWorker = prefs.getBoolean("is_rescue_worker", false)
                )
            }
        )
    }

    if (currentUser == null) {
        LoginScreen(
            onLoginSuccess = { name, phone, isRescueWorker ->
                // UPDATED to save the flag to disk
                prefs.edit()
                    .putString("user_name", name)
                    .putString("user_phone", phone)
                    .putBoolean("is_rescue_worker", isRescueWorker)
                    .apply()
                currentUser = UserProfile(name, phone, isRescueWorker)
            },
            modifier = modifier
        )
    } else {
        PermissionWrapper(
            onPermissionsGranted = {
                ChatScreen(
                    user = currentUser!!,
                    onLogout = {
                        // Wipe the local disk
                        prefs.edit().clear().apply()
                        // Reset the state so the UI instantly jumps back to LoginScreen
                        currentUser = null
                    },
                    modifier = modifier
                )
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

// UPDATED to accept the boolean flag
@Composable
fun LoginScreen(
    onLoginSuccess: (String, String, Boolean) -> Unit,
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
                    // SECRET ADMIN LOGIN LOGIC
                    if (nameInput == "ADMIN-RESCUE-999") {
                        onLoginSuccess("Rescue", phoneInput, true)
                    } else {
                        onLoginSuccess(nameInput, phoneInput, false)
                    }
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
fun ChatScreen(user: UserProfile, onLogout: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<ChatMessage>() }

    var currentText by remember { mutableStateOf("") }
    var systemStatus by remember { mutableStateOf("Initializing Radio...") }
    var activeConnections by remember { mutableIntStateOf(0) }

    val focusManager = LocalFocusManager.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val listState = rememberLazyListState()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    // Start the Background Service the moment the UI loads
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

    // Load Database History
    LaunchedEffect(Unit) {
        val dao = AppDatabase.getDatabase(context).messageDao()
        val savedMessages = dao.getAllMessages()
        messages.clear()
        for (msg in savedMessages) {
            messages.add(
                ChatMessage(
                    id = msg.id, text = msg.text, isFromMe = msg.isFromMe,
                    senderName = msg.senderName, senderPhone = msg.senderPhone,
                    latitude = msg.latitude, longitude = msg.longitude, timestamp = msg.timestamp
                )
            )
        }
    }

    // Listen for Intents broadcasted by the MeshService
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "MESH_MESSAGE_RECEIVED" -> {
                        val text = intent.getStringExtra("text") ?: ""
                        val name = intent.getStringExtra("name") ?: "Unknown"
                        val phone = intent.getStringExtra("phone") ?: ""
                        val lat = if (intent.hasExtra("lat")) intent.getDoubleExtra("lat", 0.0) else null
                        val lng = if (intent.hasExtra("lng")) intent.getDoubleExtra("lng", 0.0) else null

                        messages.add(
                            ChatMessage(text = text, isFromMe = false, senderName = name, senderPhone = phone, latitude = lat, longitude = lng)
                        )
                    }
                    "MESH_STATUS_UPDATE" -> {
                        if (intent.hasExtra("status")) systemStatus = intent.getStringExtra("status")!!
                        if (intent.hasExtra("connections")) activeConnections = intent.getIntExtra("connections", 0)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("MESH_MESSAGE_RECEIVED")
            addAction("MESH_STATUS_UPDATE")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Auto-Scroll Trigger
    LaunchedEffect(messages.size, imeBottom) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
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
                        IconButton(
                            onClick = { onLogout() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ExitToApp, // Or any edit/logout icon
                                contentDescription = "Edit Profile",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        IconButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    AppDatabase.getDatabase(context).messageDao().clearAllMessages()
                                    withContext(Dispatchers.Main) {
                                        messages.clear()
                                        Toast.makeText(context, "Mesh History Cleared", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Delete, "Clear Mesh", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(systemStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
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

            itemsIndexed(messages) { index, message ->
                val isFirstMessage = index == 0
                val previousMessage = if (!isFirstMessage) messages[index - 1] else null
                val showHeader = isFirstMessage || previousMessage?.isSystemEvent == true ||
                        previousMessage?.senderName != message.senderName || previousMessage.isFromMe != message.isFromMe

                // PASS THE isRescueWorker FLAG TO THE BUBBLE!
                MessageBubble(message = message, showHeader = showHeader, isRescueWorker = user.isRescueWorker)
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
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                                .addOnSuccessListener { location ->
                                    if (location != null) {
                                        val alertText = "🚨 Emergency Location Shared"

                                        // 1. ENCRYPT THE DATA IMMEDIATELY (Before it touches the UI or DB!)
                                        val cipherData = CryptoHelper.encryptPriority3Payload(
                                            text = alertText,
                                            name = user.name,
                                            phone = user.phoneNumber,
                                            lat = location.latitude,
                                            lng = location.longitude
                                        )
                                        val securePayload = "[P3_ENCRYPTED]$cipherData"

                                        // 2. Create the ChatMessage for the UI (Coordinates are NULL so they stay off the screen)
                                        val newLocationMessage = ChatMessage(
                                            text = securePayload,
                                            isFromMe = true,
                                            senderName = user.name,
                                            senderPhone = user.phoneNumber,
                                            latitude = null, // DO NOT STORE IN MEMORY
                                            longitude = null // DO NOT STORE IN MEMORY
                                        )
                                        messages.add(newLocationMessage)

                                        // 3. Save the SECURE version to the Local SQLite Database
                                        scope.launch(Dispatchers.IO) {
                                            val dao = AppDatabase.getDatabase(context).messageDao()
                                            dao.insertMessage(
                                                MessageEntity(
                                                    id = newLocationMessage.id,
                                                    text = securePayload, // This is now the unreadable ciphertext!
                                                    isFromMe = true,
                                                    senderName = user.name,
                                                    senderPhone = user.phoneNumber,
                                                    latitude = null, // SAFE!
                                                    longitude = null, // SAFE!
                                                    timestamp = System.currentTimeMillis(),
                                                    priority = 3
                                                )
                                            )
                                        }

                                        // 4. Send to MeshService (Pass false for isEmergency because we already encrypted it here!)
                                        val sendIntent = Intent(context, MeshService::class.java).apply {
                                            action = "SEND_PAYLOAD"
                                            putExtra("id", newLocationMessage.id)
                                            putExtra("text", securePayload)
                                            putExtra("name", "Encrypted User") // Scrub metadata
                                            putExtra("phone", "Hidden") // Scrub metadata
                                            putExtra("isEmergency", false) // Prevent double-encryption
                                        }
                                        context.startService(sendIntent)

                                    } else {
                                        Toast.makeText(context, "Ensure GPS is turned on and you are outdoors.", Toast.LENGTH_LONG).show()
                                    }
                                }
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
                        val newChatMessage = ChatMessage(text = msgToSend, isFromMe = true, senderName = user.name, senderPhone = user.phoneNumber)
                        messages.add(newChatMessage)

                        scope.launch(Dispatchers.IO) {
                            val dao = AppDatabase.getDatabase(context).messageDao()
                            dao.insertMessage(
                                MessageEntity(
                                    id = newChatMessage.id, text = msgToSend, isFromMe = true,
                                    senderName = user.name, senderPhone = user.phoneNumber,
                                    latitude = null, longitude = null,
                                    timestamp = System.currentTimeMillis(), priority = 1
                                )
                            )
                        }

                        // Standard message: no isEmergency flag.
                        val sendIntent = Intent(context, MeshService::class.java).apply {
                            action = "SEND_PAYLOAD"
                            putExtra("id", newChatMessage.id)
                            putExtra("text", msgToSend)
                            putExtra("name", user.name)
                            putExtra("phone", user.phoneNumber)
                        }
                        context.startService(sendIntent)

                        currentText = ""
                    }
                }
            ) {
                Text("Send")
            }
        }
    }
}

// UPDATED: Dynamically changes view depending on if user is Sender, Mule, or Rescue Worker
@Composable
fun MessageBubble(message: ChatMessage, showHeader: Boolean = true, isRescueWorker: Boolean = false) {

    if (message.isSystemEvent) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = message.text, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        }
        return
    }

    val isEncrypted = message.text.startsWith("[P3_ENCRYPTED]")

    var displayText = message.text
    var displayLat = message.latitude
    var displayLng = message.longitude
    var displayName = message.senderName
    var displayPhone = message.senderPhone
    var showLockIcon = false

    if (isEncrypted) {
        if (message.isFromMe) {
            // Sender sees their own message normally, just with a lock icon.
            displayText = "🚨 Emergency Location Shared"
            showLockIcon = true
        } else if (isRescueWorker) {
            // Rescue Worker intercepts and decrypts!
            val cipherText = message.text.removePrefix("[P3_ENCRYPTED]")
            val decryptedData = CryptoHelper.decryptPriority3Payload(cipherText)

            if (decryptedData != null) {
                displayText = decryptedData.optString("text", "Decrypted Signal")
                displayName = decryptedData.optString("name", "Unknown")
                displayPhone = decryptedData.optString("phone", "Unknown")
                displayLat = if (decryptedData.has("lat") && !decryptedData.isNull("lat")) decryptedData.getDouble("lat") else null
                displayLng = if (decryptedData.has("lng") && !decryptedData.isNull("lng")) decryptedData.getDouble("lng") else null
                showLockIcon = true
            } else {
                displayText = "⚠️ Decryption Failed"
            }
        } else {
            // Data Mule sees a generic lock message. PII is hidden.
            displayText = "🔒 Encrypted Distress Signal Routing to Authorities."
            displayName = "Encrypted User"
            displayPhone = "Hidden"
            displayLat = null
            displayLng = null
        }
    }

    val bubbleColor = if (message.isFromMe) MaterialTheme.colorScheme.primary else Color.LightGray
    val textColor = if (message.isFromMe) Color.White else Color.Black
    val alignment = if (message.isFromMe) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (message.isFromMe || !showHeader) 12.dp else 2.dp,
                bottomEnd = if (!message.isFromMe || !showHeader) 12.dp else 2.dp
            ),
            modifier = Modifier.padding(
                start = 8.dp,
                top = if (showHeader) 8.dp else 2.dp,
                end = 8.dp,
                bottom = 2.dp
            )
        ) {
            Column(modifier = Modifier.padding(8.dp)) {

                if (showHeader && !message.isFromMe && displayName.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "~ $displayPhone",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.DarkGray
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }

                if (showLockIcon) {
                    Text(
                        text = "🔒 Secured via Asymmetric Key",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.isFromMe) Color.LightGray else Color(0xFF2E7D32)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                Text(text = displayText, color = textColor)

                if (displayLat != null && displayLng != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = Color.Black.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Lat: ${"%.4f".format(displayLat)}\nLng: ${"%.4f".format(displayLng)}",
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

@Preview(showBackground = true)
@Composable
fun AppPreview() {
    LifelineMeshTheme {
        LoginScreen(onLoginSuccess = { _, _, _ -> })
    }
}
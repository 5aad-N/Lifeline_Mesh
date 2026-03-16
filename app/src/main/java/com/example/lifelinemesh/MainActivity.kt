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

data class ChatMessage(
    val text: String,
    val isFromMe: Boolean,
    val senderName: String = "",
    val senderPhone: String = "",
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
    var currentUser by remember { mutableStateOf<UserProfile?>(null) }

    if (currentUser == null) {
        LoginScreen(
            onLoginSuccess = { name, phone ->
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

@Composable
fun ChatScreen(user: UserProfile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var currentText by remember { mutableStateOf("") }
    var systemStatus by remember { mutableStateOf("Initializing Radio...") }
    var activeConnections by remember { mutableStateOf(0) }

    val focusManager = LocalFocusManager.current

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
            // NEW: We receive the text, name, and phone from the JSON envelope
            onMessageReceived = { incomingText, senderName, senderPhone ->
                messages.add(
                    ChatMessage(
                        text = incomingText,
                        isFromMe = false,
                        senderName = senderName,
                        senderPhone = senderPhone // Actually save the phone number!
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

    DisposableEffect(Unit) {
        nearbyManager.startAdvertising()
        nearbyManager.startDiscovery()
        onDispose { nearbyManager.stop() }
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

            // NEW: itemsIndexed lets us look at the previous message!
            itemsIndexed(messages) { index, message ->
                val isFirstMessage = index == 0
                val previousMessage = if (!isFirstMessage) messages[index - 1] else null

                // Decide if we should show the header based on the previous message
                val showHeader = isFirstMessage ||
                        previousMessage?.isSystemEvent == true ||
                        previousMessage?.senderName != message.senderName ||
                        previousMessage?.isFromMe != message.isFromMe

                MessageBubble(message = message, showHeader = showHeader)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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

                        messages.add(ChatMessage(msgToSend, isFromMe = true, senderName = user.name, senderPhone = user.phoneNumber))

                        nearbyManager.sendData(msgToSend, user.name, user.phoneNumber)

                        currentText = ""
                    }
                },
                enabled = activeConnections > 0
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
                // NEW: Dynamic corners for that WhatsApp "tail" effect
                shape = RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 12.dp,
                    bottomStart = if (message.isFromMe || !showHeader) 12.dp else 2.dp,
                    bottomEnd = if (!message.isFromMe || !showHeader) 12.dp else 2.dp
                ),
                // NEW: Tighter vertical padding if it's a grouped message
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
package com.example.lifelinemesh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.lifelinemesh.ui.theme.LifelineMeshTheme
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ... (Data classes ChatMessage and UserProfile remain the same) ...
data class ChatMessage(val text: String, val isFromMe: Boolean, val timestamp: Long = System.currentTimeMillis())
data class UserProfile(val name: String, val phoneNumber: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LifelineMeshTheme {
                // 1. Get the Focus Manager
                val focusManager = LocalFocusManager.current

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        // 2. THE FIX: Detect taps on the background
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                focusManager.clearFocus() // <--- This closes the keyboard
                            })
                        }
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

    // This handles the transition: Login -> Chat
    if (currentUser == null) {
        LoginScreen(
            onLoginSuccess = { name, phone ->
                currentUser = UserProfile(name, phone)
            },
            modifier = modifier
        )
    } else {
        // Once logged in, we immediately check/ask for permissions
        PermissionWrapper(
            onPermissionsGranted = {
                // Only show chat if permissions are good
                ChatScreen(user = currentUser!!, modifier = modifier)
            }
        )
    }
}

// --- NEW COMPONENT: HANDLES PERMISSIONS ---
@Composable
fun PermissionWrapper(onPermissionsGranted: @Composable () -> Unit) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(false) }
    var isLocationServiceEnabled by remember { mutableStateOf(false) }

    // 1. Define the Permissions we need
    // (Using your 'Nuclear' list for safety on Honor phones)
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

    // 2. Helper function to check if GPS/Location Switch is ON
    fun checkLocationService(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // 3. Permission Launcher
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { perms ->
            val locationGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (locationGranted) {
                hasPermissions = true
                isLocationServiceEnabled = checkLocationService() // Re-check service status
            } else {
                Toast.makeText(context, "Location permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    )

    // 4. Lifecycle Observer (To detect when user comes back from Settings)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // When user returns to app, re-check everything
                isLocationServiceEnabled = checkLocationService()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 5. Initial Check on App Start
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

    // 6. THE UI DECISION TREE
    if (hasPermissions && isLocationServiceEnabled) {
        // SCENARIO A: Everything is good. Show Chat.
        onPermissionsGranted()
    }
    else if (hasPermissions && !isLocationServiceEnabled) {
        // SCENARIO B: Permission Good, but Switch is OFF. Show Prompt.
        AlertDialog(
            onDismissRequest = { /* Prevent dismissal */ },
            title = { Text("Turn on Location") },
            text = { Text("To find nearby devices, this phone needs Location Services (GPS) turned on.") },
            confirmButton = {
                Button(onClick = {
                    // Send user to Android Location Settings
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }) {
                    Text("Open Settings")
                }
            }
        )
    }
    else {
        // SCENARIO C: Still waiting for permission
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

// ---------------------------------------------------------
// SCREEN 1: THE LOGIN PAGE
// ---------------------------------------------------------
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

        // Name Field
        OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = it },
            label = { Text("Full Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Phone Field (Restricted to Numbers)
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

// ---------------------------------------------------------
// SCREEN 2: THE CHAT PAGE (Updated)
// ---------------------------------------------------------
@Composable
fun ChatScreen(user: UserProfile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 1. SETUP THE MESSAGES LIST
    val messages = remember { mutableStateListOf(
        ChatMessage("System: Mesh Network Starting...", false)
    )}

    // 2. INITIALIZE THE MANAGER
    // We create it once, and tell it: "When you get a msg, add it to our list"
    val nearbyManager = remember {
        NearbyConnectionManager(context) { incomingText ->
            // This runs when another phone sends us data
            messages.add(ChatMessage(incomingText, isFromMe = false))
        }
    }

    // 3. START THE RADIO (When this screen opens)
    DisposableEffect(Unit) {
        nearbyManager.startAdvertising(user.name)
        nearbyManager.startDiscovery()

        // Clean up when the app closes
        onDispose { nearbyManager.stop() }
    }

    var currentText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier.fillMaxSize()) {

        // Header
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Logged in as: ${user.name}",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Chat List
        LazyColumn(
            modifier = Modifier.weight(1f).padding(8.dp),
        ) {
            items(messages) { message -> MessageBubble(message) }
        }

        // Input Bar
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

            Button(onClick = {
                focusManager.clearFocus()
                if (currentText.isNotBlank()) {
                    val msgToSend = currentText

                    // A. Update our own screen
                    messages.add(ChatMessage(msgToSend, true))

                    // B. Send via Bluetooth!
                    nearbyManager.sendData(msgToSend)

                    currentText = ""
                }
            }) {
                Text("Send")
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val bubbleColor = if (message.isFromMe) MaterialTheme.colorScheme.primary else Color.LightGray
    val textColor = if (message.isFromMe) Color.White else Color.Black
    val alignment = if (message.isFromMe) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(10.dp),
                color = textColor
            )
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
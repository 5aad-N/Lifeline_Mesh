package com.example.lifelinemesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lifelinemesh.ui.theme.LifelineMeshTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 1. Data Models
data class ChatMessage(
    val text: String,
    val isFromMe: Boolean,
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
                // Main container for the app
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // This is the "App Navigation" logic
                    AppContent(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun AppContent(modifier: Modifier = Modifier) {
    // STATE: Keeps track of the logged-in user.
    // If null, we show Login. If set, we show Chat.
    var currentUser by remember { mutableStateOf<UserProfile?>(null) }
    if (currentUser == null) {
        // SCREEN 1: Login
        LoginScreen(
            onLoginSuccess = { name, phone ->
                currentUser = UserProfile(name, phone)
            },
            modifier = modifier
        )
    } else {
        // SCREEN 2: Chat (We pass the user info so we can use it later)
        ChatScreen(
            user = currentUser!!,
            modifier = modifier
        )
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
    val messages = remember { mutableStateListOf(
        ChatMessage("System: Welcome, ${user.name}. Mesh Active.", false)
    )}

    var currentText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {

        // Header Bar to show who is logged in
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Logged in as: ${user.name} (${user.phoneNumber})",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Chat List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(8.dp),
        ) {
            items(messages) { message ->
                MessageBubble(message)
            }
        }

        // Input Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
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
                if (currentText.isNotBlank()) {
                    val sentText = currentText
                    messages.add(ChatMessage(sentText, true))
                    currentText = ""

                    // Ghost Reply
                    scope.launch {
                        delay(2000)
                        messages.add(
                            ChatMessage(
                                text = "Ghost Reply to ${user.name}: Received '$sentText'",
                                isFromMe = false
                            )
                        )
                    }
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
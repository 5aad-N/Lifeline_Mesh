# Lifeline Mesh - Disaster Communication Network

## 1. Submission Overview & File Guide

This repository contains the complete source code for the **Lifeline Mesh** project.

Below is a guide to the key files and directories included in this submission:

### 1.1 Android Mobile Application (Kotlin)
Located in `/app/src/main/java/com/example/lifelinemesh/`
* **`NearbyConnectionManager.kt`**: The core networking engine. Handles Google Nearby Connections, BLE scanning, peer discovery and autonomous payload exchange.
* **`CloudGateway.kt`**: The background OS-level listener. Detects when the device escapes the blackout zone (restored Wi-Fi/Cellular) and automatically offloads cached priority payloads to external rescue services via Webhook.
* **`data/AppDatabase.kt` & `MessageDao.kt`**: The local SQLite implementation using Android Room. Acts as the persistent storage buffer for the Store-and-Forward routing protocol.

### 1.2 Theoretical Validation (Python)
Located in the `app/simulation/` directory.
* **`python_simulation.py`**: The custom Python simulation used to mathematically validate the Store-and-Forward architecture. Models a 9km² disaster zone with POI human mobility, 25 rubble zones, and battery-driven survival mode

---

## 2. Executable Requirement & Running the Software

**Environment Setup (Configuring the Webhook URL)**
Before compiling the application, you must provide a valid Webhook URL so the CloudGateway knows where to offload emergency distress signals when internet connectivity is restored.

Open the project directory.

Locate the local.properties file in the root folder (if it does not exist, create a new file named local.properties).

Add your Webhook URL environment variable to this file. For example:

Properties
WEBHOOK_URL="https://your-rescue-server-endpoint.com/webhook"
(Note: If you do not have a live server, you can use a free testing service like Webhook.site to monitor incoming payloads).

**Why there is no traditional executable (.exe / .app):**
Because this project is a native Android mobile application, it cannot be compiled into a traditional desktop executable file. Instead, Android applications are compiled into **.apk** (Android Package) files. Furthermore, because the application relies on physical hardware sensors (Bluetooth Low Energy, Wi-Fi Direct, and GPS antennas), it cannot be accurately tested on a desktop computer.

To evaluate this software, examiners can:

### Build from Source via Android Studio
To review the codebase, view Logcat terminal outputs, and compile the app manually:
1. Extract the submission folder.
2. Download and install **Android Studio** (https://developer.android.com/studio).
3. Open Android Studio, select **File > Open**, and navigate to the extracted `LifelineMesh` project folder.
4. Allow Gradle to sync and download necessary dependencies (requires an internet connection).
5. Enable **Developer Options** and **USB Debugging** on a physical Android device.
6. Connect the Android device to your computer via USB.
7. Click the green **Play (Run)** button in the top toolbar to compile and install the application directly onto the device.

*Testing Note: When testing the application, ensure both Bluetooth and Location services are physically enabled on the test devices, as the Google Nearby Connections API requires these to establish the peer-to-peer mesh.*

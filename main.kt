@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.bluesync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.*

class MainActivity : ComponentActivity() {

    // Bluetooth and connection variables
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val TAG = "BluetoothCarControl"

    // Existing state variables
    private var selectedDevice: BluetoothDevice? by mutableStateOf(null)
    private var isConnected by mutableStateOf(false)
    private var isConnecting by mutableStateOf(false)
    private var pairedDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    private var speed by mutableIntStateOf(128) // Default mid speed
    private var lastCommand by mutableStateOf('S')
    private var connectionStatus by mutableStateOf("Disconnected")

    private var isCommandRunning = false
    private val scope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0

    // Command rate limiting
    private var lastCommandTime = 0L
    private val COMMAND_DELAY = 100L // 100ms delay between commands

    // New state variables for control modes
    private var controlMode by mutableStateOf(ControlMode.BLUETOOTH)
    private var isGestureInitialized by mutableStateOf(false)
    private var isWebcamInitialized by mutableStateOf(false)

    // Define control modes
    enum class ControlMode {
        BLUETOOTH, GESTURE, WEBCAM
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        handler.post {
            if (allGranted) {
                loadPairedDevices()
            } else {
                showToast("Required permissions not granted")
                connectionStatus = "Permissions required"
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lock orientation to portrait mode
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        initializeBluetooth()
        checkPermissions()

        setContent {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                color = MaterialTheme.colorScheme.background
            ) {
                CarControlScreen(
                    pairedDevices = pairedDevices,
                    selectedDevice = selectedDevice,
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    speed = speed,
                    connectionStatus = connectionStatus,
                    controlMode = controlMode,
                    isGestureInitialized = isGestureInitialized,
                    isWebcamInitialized = isWebcamInitialized,
                    onModeChange = { newMode -> changeControlMode(newMode) },
                    onDeviceSelected = { device -> selectDevice(device) },
                    onConnectClick = { connectToDevice() },
                    onControlPress = { command -> startCommand(command) },
                    onControlRelease = { stopCommand() },
                    onSpeedChange = { newSpeed -> updateSpeed(newSpeed) }
                )
            }
        }
    }

    private fun initializeBluetooth() {
        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter
            if (!bluetoothAdapter.isEnabled) {
                connectionStatus = "Bluetooth is disabled"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth initialization failed: ${e.message}")
            connectionStatus = "Bluetooth not available"
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        try {
            pairedDevices = bluetoothAdapter.bondedDevices.toList()
            if (pairedDevices.isEmpty()) {
                connectionStatus = "No paired devices"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading paired devices: ${e.message}")
            connectionStatus = "Error loading devices"
        }
    }

    private fun selectDevice(device: BluetoothDevice) {
        selectedDevice = device
        connectionStatus = "Device selected: ${device.name}"
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missingPermissions.isEmpty()) {
            loadPairedDevices()
        } else {
            requestPermissionLauncher.launch(missingPermissions)
        }
    }

    private fun updateSpeed(newSpeed: Int) {
        speed = newSpeed
        if (isCommandRunning && lastCommand != 'S') {
            sendCommand(lastCommand)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice() {
        val device = selectedDevice ?: return
        if (isConnecting) return

        isConnecting = true
        connectionStatus = "Connecting..."

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    bluetoothSocket?.close()
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                    bluetoothSocket?.connect()
                    outputStream = bluetoothSocket?.outputStream
                }
                withContext(Dispatchers.Main) {
                    isConnected = true
                    isConnecting = false
                    reconnectAttempts = 0
                    connectionStatus = "Connected to ${device.name}"
                    sendCommand('S')
                    startConnectionMonitoring()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
                withContext(Dispatchers.Main) {
                    isConnected = false
                    isConnecting = false
                    connectionStatus = "Connection failed"
                    reconnect()
                }
            }
        }
    }

    private fun sendCommand(command: Char) {
        if (!isConnected || bluetoothSocket?.isConnected != true) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCommandTime < COMMAND_DELAY) return
        lastCommandTime = currentTime

        scope.launch(Dispatchers.IO) {
            try {
                val commandString = when (command) {
                    'F', 'B', 'L', 'R' -> "$command$speed\n"
                    'S' -> "S\n"
                    else -> return@launch
                }
                outputStream?.write(commandString.toByteArray())
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Send failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    isConnected = false
                    connectionStatus = "Connection lost"
                    reconnect()
                }
            }
        }
    }

    private fun startCommand(command: Char) {
        if (!isConnected) return
        isCommandRunning = true
        lastCommand = command
        sendCommand(command)
    }

    private fun stopCommand() {
        isCommandRunning = false
        lastCommand = 'S'
        sendCommand('S')
    }

    private fun startConnectionMonitoring() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (bluetoothSocket?.isConnected == true) {
                    handler.postDelayed(this, 2000)
                } else {
                    isConnected = false
                    connectionStatus = "Connection lost"
                    reconnect()
                }
            }
        }, 2000)
    }

    private fun reconnect() {
        if (reconnectAttempts < 3) {
            handler.postDelayed({
                reconnectAttempts++
                connectionStatus = "Reconnecting... Attempt $reconnectAttempts"
                connectToDevice()
            }, 3000)
        } else {
            connectionStatus = "Reconnection failed"
        }
    }

    // Updated changeControlMode method with stop command sent before mode change and sending mode change command
    private fun changeControlMode(newMode: ControlMode) {
        // Send stop command before changing modes
        if (isConnected) {
            sendCommand('S')
            lastCommand = 'S'
            isCommandRunning = false

            // Send mode change command
            val modeCommand = when (newMode) {
                ControlMode.BLUETOOTH -> "MB\n"
                ControlMode.GESTURE -> "MG\n"
                ControlMode.WEBCAM -> "MW\n"
            }

            scope.launch(Dispatchers.IO) {
                try {
                    outputStream?.write(modeCommand.toByteArray())
                    outputStream?.flush()
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to send mode change command: ${e.message}")
                    withContext(Dispatchers.Main) {
                        isConnected = false
                        connectionStatus = "Connection lost"
                        reconnect()
                    }
                }
            }
        }

        when (newMode) {
            ControlMode.BLUETOOTH -> {
                stopGestureRecognition()
                stopWebcamProcessing()
                controlMode = ControlMode.BLUETOOTH
            }
            ControlMode.GESTURE -> {
                if (!isGestureInitialized) {
                    initializeGestureRecognition()
                }
                stopWebcamProcessing()
                controlMode = ControlMode.GESTURE
            }
            ControlMode.WEBCAM -> {
                if (!isWebcamInitialized) {
                    initializeWebcam()
                }
                stopGestureRecognition()
                controlMode = ControlMode.WEBCAM
            }
        }
    }

    private fun initializeGestureRecognition() {
        // Implement gesture recognition initialization here
        isGestureInitialized = true
    }

    private fun initializeWebcam() {
        // Implement webcam initialization here
        isWebcamInitialized = true
    }

    private fun stopGestureRecognition() {
        if (isGestureInitialized) {
            // Clean up gesture recognition resources
            isGestureInitialized = false
        }
    }

    private fun stopWebcamProcessing() {
        if (isWebcamInitialized) {
            // Clean up webcam resources
            isWebcamInitialized = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopGestureRecognition()
        stopWebcamProcessing()
        handler.removeCallbacksAndMessages(null)
        scope.launch(Dispatchers.IO) {
            try {
                bluetoothSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing socket: ${e.message}")
            }
        }
        isConnected = false
    }
}

@Composable
fun CarControlScreen(
    pairedDevices: List<BluetoothDevice>,
    selectedDevice: BluetoothDevice?,
    isConnected: Boolean,
    isConnecting: Boolean,
    speed: Int,
    connectionStatus: String,
    controlMode: MainActivity.ControlMode,
    isGestureInitialized: Boolean,
    isWebcamInitialized: Boolean,
    onModeChange: (MainActivity.ControlMode) -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onConnectClick: () -> Unit,
    onControlPress: (Char) -> Unit,
    onControlRelease: () -> Unit,
    onSpeedChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Made by the Students of Makhdoom Bilawal Intermediate Science College, T.M.K.",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 50.dp),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Mode Toggle Controls
        ModeToggleButtons(
            selectedMode = controlMode,
            onModeSelected = onModeChange
        )

        Spacer(modifier = Modifier.height(16.dp))

        when (controlMode) {
            MainActivity.ControlMode.BLUETOOTH -> {
                DeviceSelector(
                    pairedDevices = pairedDevices,
                    selectedDevice = selectedDevice,
                    onDeviceSelected = onDeviceSelected
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onConnectClick,
                    enabled = selectedDevice != null && !isConnected && !isConnecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isConnecting) "Connecting..." else "Connect")
                }
                Text(
                    text = connectionStatus,
                    color = when {
                        isConnected -> Color.Green
                        isConnecting -> Color.Blue
                        else -> Color.Red
                    },
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                SpeedControl(
                    speed = speed,
                    enabled = isConnected,
                    onSpeedChange = onSpeedChange
                )
                ControlButtons(
                    enabled = isConnected,
                    onControlPress = onControlPress,
                    onControlRelease = onControlRelease
                )
            }
            MainActivity.ControlMode.GESTURE -> {
                GestureControlView(isInitialized = isGestureInitialized)
            }
            MainActivity.ControlMode.WEBCAM -> {
                WebcamControlView(isInitialized = isWebcamInitialized)
            }
        }
    }
}

@Composable
fun ModeToggleButtons(
    selectedMode: MainActivity.ControlMode,
    onModeSelected: (MainActivity.ControlMode) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = selectedMode == MainActivity.ControlMode.BLUETOOTH,
            onClick = { onModeSelected(MainActivity.ControlMode.BLUETOOTH) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
        ) {
            Text("Bluetooth")
        }
        SegmentedButton(
            selected = selectedMode == MainActivity.ControlMode.GESTURE,
            onClick = { onModeSelected(MainActivity.ControlMode.GESTURE) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
        ) {
            Text("Gesture")
        }
        SegmentedButton(
            selected = selectedMode == MainActivity.ControlMode.WEBCAM,
            onClick = { onModeSelected(MainActivity.ControlMode.WEBCAM) },
            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
        ) {
            Text("Webcam")
        }
    }
}

@Composable
fun DeviceSelector(
    pairedDevices: List<BluetoothDevice>,
    selectedDevice: BluetoothDevice?,
    onDeviceSelected: (BluetoothDevice) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = pairedDevices.isNotEmpty()
        ) {
            Text(selectedDevice?.name ?: "Select Device", textAlign = TextAlign.Center)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth()
        ) {
            pairedDevices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.name ?: "Unknown Device") },
                    onClick = {
                        onDeviceSelected(device)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun SpeedControl(
    speed: Int,
    enabled: Boolean,
    onSpeedChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        Text(
            text = "Speed: ${((speed / 255f) * 100).toInt()}%",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Slider(
            value = speed.toFloat(),
            onValueChange = { onSpeedChange(it.toInt()) },
            valueRange = 0f..255f,
            enabled = enabled,
            steps = 254,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("0%")
            Text("100%")
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ControlButtons(
    enabled: Boolean,
    onControlPress: (Char) -> Unit,
    onControlRelease: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ControlButton(
            text = "Forward",
            command = 'F',
            enabled = enabled,
            onControlPress = onControlPress,
            onControlRelease = onControlRelease
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ControlButton(
                text = "Left",
                command = 'L',
                enabled = enabled,
                onControlPress = onControlPress,
                onControlRelease = onControlRelease,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            ControlButton(
                text = "Right",
                command = 'R',
                enabled = enabled,
                onControlPress = onControlPress,
                onControlRelease = onControlRelease,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        ControlButton(
            text = "Backward",
            command = 'B',
            enabled = enabled,
            onControlPress = onControlPress,
            onControlRelease = onControlRelease
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ControlButton(
    text: String,
    command: Char,
    enabled: Boolean,
    onControlPress: (Char) -> Unit,
    onControlRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = { },
        enabled = enabled,
        modifier = modifier
            .height(64.dp)
            .pointerInteropFilter { event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        onControlPress(command)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        onControlRelease()
                        true
                    }
                    else -> false
                }
            }
    ) {
        Text(text = text, textAlign = TextAlign.Center)
    }
}

@Composable
fun GestureControlView(isInitialized: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isInitialized) "Gesture Control Active" else "Initializing Gesture Control...",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        // Add your gesture control UI components here
    }
}

@Composable
fun WebcamControlView(isInitialized: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isInitialized) "Webcam Control Active" else "Initializing Webcam...",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        // Add your webcam control UI components here
    }
}

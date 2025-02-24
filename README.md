# BlueSync: Bluetooth Car Control

BlueSync is a multi-mode remote control system for a Bluetooth-controlled car. This project consists of two parts:

1. **Android App** – Built using Kotlin and Jetpack Compose, the app connects via Bluetooth to control the car. It sends commands that include a movement command, speed value, and a mode toggle. The app is focused solely on Bluetooth control while displaying information about separate gesture and webcam control systems.

2. **ESP32 Firmware** – The ESP32 receives the Bluetooth commands, parses the command, speed, and mode toggle, and prints debug information to the Serial Monitor. In Bluetooth mode (mode 0), it processes movement commands; in other modes (gesture or webcam), it ignores the commands and displays the active mode.

## Features

- **Bluetooth Connection:**  
  The Android app connects to the ESP32 via Bluetooth using the RFCOMM protocol.

- **Command Rate Limiting:**  
  Commands are sent no more frequently than every 100ms to prevent flooding the connection.

- **Mode Toggle:**  
  The app sends a mode toggle (0 for Bluetooth, 1 for Gesture, 2 for Webcam) along with movement commands. The ESP32 processes movement commands only when in Bluetooth mode.

- **Responsive UI:**  
  The Android app UI is built with Jetpack Compose for responsiveness and locks the orientation to portrait mode.

- **Debug Output:**  
  The ESP32 firmware prints the received command, speed, and current mode for debugging purposes.

## Requirements

### Hardware
- ESP32 development board
- Android device running Android 6.0 (Marshmallow) or later
- Bluetooth connectivity (built into the ESP32 and the Android device)
- (Optional) Car motor driver, battery, and chassis for a complete car control system

### Software
- Arduino IDE (or PlatformIO) for ESP32 development
- Android Studio for building the Android app
- Kotlin and Jetpack Compose Material3 libraries

## Android App Setup

1. Clone or download the repository.
2. Open the project in Android Studio.
3. Ensure that your device’s Bluetooth is enabled.
4. Build and run the app on your Android device.
5. Use the device selector to choose a paired Bluetooth device (your ESP32).
6. Press "Connect" to establish the connection.
7. Use the on-screen control buttons (Forward, Backward, Left, Right, Stop) to send commands.  
   The command string format is:  
   `<command><speed>M<mode>`  
   For example, in Bluetooth mode (mode 0) pressing Forward with speed 128 sends `F128M0\n`.

## ESP32 Firmware Setup

1. Clone or download the repository.
2. Open the provided ESP32 code in the Arduino IDE or PlatformIO.
3. Connect your ESP32 board.
4. Upload the firmware to your ESP32.
5. Open the Serial Monitor at 115200 baud.
6. The ESP32 will print debug information based on the incoming commands:  
   - In Bluetooth mode (mode 0), it prints the received command and speed.
   - In Gesture (mode 1) or Webcam (mode 2) modes, it prints a message indicating the active mode and ignores control commands.

## Bluetooth Command Format

The Android app sends commands in the following format:

- **command:** A character representing the movement (e.g., `F` for forward, `B` for backward, `L` for left, `R` for right, `S` for stop).
- **speed:** An integer (0–255) indicating the speed.
- **mode:** A digit after the letter `M` indicating the control mode:  
  - `0` for Bluetooth mode  
  - `1` for Gesture mode  
  - `2` for Webcam mode

**Example:**  
`F128M0` means move forward with speed 128 in Bluetooth mode.

## Note
- *Webcam mode is not functional yet.*

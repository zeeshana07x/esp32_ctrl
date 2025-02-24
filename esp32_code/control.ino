#include <BluetoothSerial.h>
#include <SPI.h>
#include <nRF24L01.h>
#include <RF24.h>

BluetoothSerial SerialBT;

// nRF24L01 radio configuration
RF24 radio(4, 5);  // Adjust CE and CSN pins as needed
const byte address[6] = "00001";  // Reading pipe address

// Motor A (Right Side)
const int motor1Pin1 = 25; // IN1
const int motor1Pin2 = 26; // IN2
const int enable1Pin  = 32; // Enable Pin ENA

// Motor B (Left Side)
const int motor2Pin1 = 33; // IN3 remains unchanged
const int motor2Pin2 = 16; // Instead of input‑only GPIO34, we use GPIO16
const int enable2Pin  = 17; // Instead of input‑only GPIO35, we use GPIO17

// RGB LED pins
const int ledPinR = 13;
const int ledPinG = 12;
const int ledPinB = 14;

int currentSpeed = 128; // Default speed (0-255)
char currentMode = 'B'; // 'B' for Bluetooth, 'G' for Gesture, 'W' for Webcam
char lastCommand = 'S'; // Last received command

void setup() {
  Serial.begin(115200);
  
  // Configure motor pins as outputs
  pinMode(motor1Pin1, OUTPUT);
  pinMode(motor1Pin2, OUTPUT);
  pinMode(motor2Pin1, OUTPUT);
  pinMode(motor2Pin2, OUTPUT);
  pinMode(enable1Pin, OUTPUT);
  pinMode(enable2Pin, OUTPUT);
  
  // Configure RGB LED pins as outputs
  pinMode(ledPinR, OUTPUT);
  pinMode(ledPinG, OUTPUT);
  pinMode(ledPinB, OUTPUT);
  // Start with LED off
  analogWrite(ledPinR, 0);
  analogWrite(ledPinG, 0);
  analogWrite(ledPinB, 0);
  
  // Start Bluetooth
  SerialBT.begin("ESP32_Car");
  Serial.println("Device started. Pair with Bluetooth!");
  
  // Initialize nRF24L01 radio for gesture commands
  radio.begin();
  radio.setPALevel(RF24_PA_LOW);
  radio.openReadingPipe(1, address);
  radio.startListening();
  
  // Ensure motors are stopped at startup
  stopMotors();
}

void loop() {
  // Process Bluetooth commands
  if (SerialBT.available()) {
    String input = SerialBT.readStringUntil('\n');
    input.trim();
    
    if (input.length() > 0) {
      char command = input.charAt(0);
      
      // Always allow mode change via Bluetooth
      if (command == 'M') {
        if (input.length() > 1) {
          currentMode = input.charAt(1);
          Serial.print("Mode changed to: ");
          Serial.println(currentMode);
        }
        return;
      }
      
      // Process movement commands only when in Bluetooth mode
      if (currentMode == 'B') {
        if (input.length() > 1 && command != 'S') {
          currentSpeed = input.substring(1).toInt();
          currentSpeed = constrain(currentSpeed, 0, 255);
        }
        executeCommand(command);
        Serial.print("Bluetooth Command: ");
        Serial.print(command);
        Serial.print(" Speed: ");
        Serial.println(currentSpeed);
      } else {
        Serial.println("Bluetooth command received but Bluetooth is not the active mode.");
      }
    }
  }
  
  // Process nRF (gesture) commands when in Gesture mode
  if (currentMode == 'G') {
    if (radio.available()) {
      char gestureInput[32] = {0};
      radio.read(&gestureInput, sizeof(gestureInput));
      String input = String(gestureInput);
      input.trim();
      
      if (input.length() > 0) {
        char command = input.charAt(0);
        if (input.length() > 1 && command != 'S') {
          currentSpeed = input.substring(1).toInt();
          currentSpeed = 200;
        }
        executeCommand(command);
        Serial.print("Gesture Command: ");
        Serial.print(command);
        Serial.print(" Speed: ");
        Serial.println(currentSpeed);
      }
    }
  }
  
  // Process commands for Webcam mode (if needed) via Bluetooth
  if (currentMode == 'W') {
    if (SerialBT.available()) {
      String input = SerialBT.readStringUntil('\n');
      input.trim();
      
      if (input.length() > 0) {
        char command = input.charAt(0);
        if (input.length() > 1 && command != 'S') {
          currentSpeed = input.substring(1).toInt();
          currentSpeed = constrain(currentSpeed, 0, 255);
        }
        executeCommand(command);
        Serial.print("Webcam Command: ");
        Serial.print(command);
        Serial.print(" Speed: ");
        Serial.println(currentSpeed);
      }
    }
  }
}

// Execute the command, move the motors (if applicable), and change the LED color
void executeCommand(char command) {
  switch (command) {
    case 'F':  // Forward → green
      setLEDColor(0, 255, 0);
      moveForward();
      break;
    case 'B':  // Backward → red
      setLEDColor(255, 0, 0);
      moveBackward();
      break;
    case 'L':  // Left → blue
      setLEDColor(0, 0, 255);
      turnLeft();
      break;
    case 'R':  // Right → orange
      setLEDColor(255, 165, 0);
      turnRight();
      break;
    case 'S':  // Stop → LED off
      setLEDColor(0, 0, 0);
      stopMotors();
      break;
    case 'G':  // Gesture-specific command → purple (adjust behavior as needed)
      setLEDColor(128, 0, 128);
      // Optionally add movement behavior here if required.
      break;
    case 'W':  // Webcam-specific command → cyan (adjust behavior as needed)
      setLEDColor(0, 255, 255);
      // Optionally add movement behavior here if required.
      break;
    default:
      // Unknown command – optionally, you could set a default LED color.
      break;
  }
  lastCommand = command;
}

// Helper to set the RGB LED color
void setLEDColor(int redVal, int greenVal, int blueVal) {
  redVal   = constrain(redVal, 0, 255);
  greenVal = constrain(greenVal, 0, 255);
  blueVal  = constrain(blueVal, 0, 255);
  
  analogWrite(ledPinR, redVal);
  analogWrite(ledPinG, greenVal);
  analogWrite(ledPinB, blueVal);
  
  Serial.print("LED Color set to R:");
  Serial.print(redVal);
  Serial.print(" G:");
  Serial.print(greenVal);
  Serial.print(" B:");
  Serial.println(blueVal);
}

void moveForward() {
  Serial.println("Moving Forward");
  digitalWrite(motor1Pin1, HIGH);
  digitalWrite(motor1Pin2, LOW);
  digitalWrite(motor2Pin1, HIGH);
  digitalWrite(motor2Pin2, LOW);
  analogWrite(enable1Pin, currentSpeed);
  analogWrite(enable2Pin, currentSpeed);
}

void moveBackward() {
  Serial.println("Moving Backward");
  digitalWrite(motor1Pin1, LOW);
  digitalWrite(motor1Pin2, HIGH);
  digitalWrite(motor2Pin1, LOW);
  digitalWrite(motor2Pin2, HIGH);
  analogWrite(enable1Pin, currentSpeed);
  analogWrite(enable2Pin, currentSpeed);
}

void turnLeft() {
  Serial.println("Turning Left");
  digitalWrite(motor1Pin1, HIGH);
  digitalWrite(motor1Pin2, LOW);
  digitalWrite(motor2Pin1, LOW);
  digitalWrite(motor2Pin2, HIGH);
  analogWrite(enable1Pin, currentSpeed);
  analogWrite(enable2Pin, currentSpeed);
}

void turnRight() {
  Serial.println("Turning Right");
  digitalWrite(motor1Pin1, LOW);
  digitalWrite(motor1Pin2, HIGH);
  digitalWrite(motor2Pin1, HIGH);
  digitalWrite(motor2Pin2, LOW);
  analogWrite(enable1Pin, currentSpeed);
  analogWrite(enable2Pin, currentSpeed);
}

void stopMotors() {
  Serial.println("Stopping");
  digitalWrite(motor1Pin1, LOW);
  digitalWrite(motor1Pin2, LOW);
  digitalWrite(motor2Pin1, LOW);
  digitalWrite(motor2Pin2, LOW);
  analogWrite(enable1Pin, 0);
  analogWrite(enable2Pin, 0);
}

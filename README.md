# SensorStream (PhoneSensor)

An Android app that turns your phone into a real-time sensor streaming device. It captures GPS and IMU data and streams it over a TCP socket to your laptop via ADB.

## What It Does

- Streams **GPS** data (latitude, longitude, altitude, accuracy, speed, bearing) at up to 10 Hz
- Streams **IMU** data (accelerometer, gyroscope, magnetometer) at ~50 Hz
- Runs as a **headless foreground service** — no UI, starts automatically on boot
- Communicates over a local TCP socket (port 5000) forwarded via `adb`

## Requirements

- Android 8.0+ (API 26)
- USB debugging enabled
- Google Play Services (for fused location provider)
- Location permission granted at runtime

## Setup

### 1. Build and Install

Open the project in Android Studio and run it on your device, or build an APK:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. Grant Location Permission

```bash
adb shell pm grant com.h2x.sensor android.permission.ACCESS_FINE_LOCATION
```

### 3. Start the Service

```bash
adb shell am startservice com.h2x.sensor/.SensorService
```

The service also starts automatically on device boot.

### 4. Forward the Port

```bash
adb forward tcp:5000 tcp:5000
```

### 5. Connect a Client

Connect to `localhost:5000` and read newline-delimited JSON packets:

```json
{"type":"GPS","lat":37.7749,"lon":-122.4194,"alt":10.5,"acc":3.2,"spd":0.0,"brg":0.0,"ts":1711900000000}
{"type":"IMU","ax":0.12,"ay":-0.03,"az":9.81,"gx":0.001,"gy":0.002,"gz":-0.001,"mx":23.1,"my":-10.4,"mz":44.2,"ts":1711900000020}
```

## Data Format

| Field | Type    | Description                       |
|-------|---------|-----------------------------------|
| `type`| string  | `"GPS"` or `"IMU"`                |
| `lat` | float   | Latitude (degrees)                |
| `lon` | float   | Longitude (degrees)               |
| `alt` | float   | Altitude (meters)                 |
| `acc` | float   | Accuracy radius (meters)          |
| `spd` | float   | Speed (m/s)                       |
| `brg` | float   | Bearing (degrees)                 |
| `ax/ay/az` | float | Accelerometer (m/s²)         |
| `gx/gy/gz` | float | Gyroscope (rad/s)            |
| `mx/my/mz` | float | Magnetometer (µT)            |
| `ts`  | long    | Unix timestamp (milliseconds)     |

## Project Structure

```
app/src/main/java/com/h2x/sensor/
├── SensorService.kt     # Foreground service: sensors + GPS + TCP socket server
└── BootReceiver.kt      # BroadcastReceiver: auto-starts service on boot
```

## Companion App

Use [gps_receiver](https://github.com/dhruvilB01/gps_receiver) (Python) to receive and display the sensor stream on your laptop.

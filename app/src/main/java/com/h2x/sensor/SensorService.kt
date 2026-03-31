package com.h2x.sensor

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket

class SensorService : Service(), SensorEventListener {

    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var outputStream: java.io.OutputStream? = null

    // Latest sensor readings (updated from sensor thread, read on emit)
    @Volatile private var lastAcc = FloatArray(3)
    @Volatile private var lastGyr = FloatArray(3)
    @Volatile private var lastMag = FloatArray(3)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { emit(gpsJson(it)) }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        postForegroundNotification()
        startSocketServer()
        setupSensors()
        setupGps()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        serverSocket?.runCatching { close() }
        clientSocket?.runCatching { close() }
        sensorManager.unregisterListener(this)
        fusedLocation.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }

    // ── Foreground notification (required by Android, kept silent) ────────────

    private fun postForegroundNotification() {
        val channelId = "sensor_stream"
        val channel = NotificationChannel(
            channelId, "Sensor Stream", NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("GPS + IMU streaming")
            .setContentText("Waiting for laptop connection on port 5000")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setSilent(true)
            .build()

        startForeground(1, notification)
    }

    // ── IMU sensors ──────────────────────────────────────────────────────────

    private fun setupSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        // SENSOR_DELAY_GAME ≈ 50 Hz — good balance of rate vs battery
        val delay = SensorManager.SENSOR_DELAY_GAME

        listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD
        ).forEach { type ->
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(this, it, delay)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER  -> lastAcc = event.values.clone()
            Sensor.TYPE_GYROSCOPE      -> lastGyr = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> lastMag = event.values.clone()
        }
        // Emit one IMU packet per gyroscope sample (≈50 Hz)
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            emit(imuJson(event.timestamp))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── GPS ──────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun setupGps() {
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 100L)
            .setMinUpdateIntervalMillis(100L)
            .build()
        fusedLocation.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    // ── TCP socket server ────────────────────────────────────────────────────

    private fun startSocketServer() {
        scope.launch {
            try {
                val ss = ServerSocket(5000).also { it.reuseAddress = true }
                serverSocket = ss

                while (isActive) {
                    // Wait for laptop to connect (via `adb forward tcp:5000 tcp:5000`)
                    val client = ss.accept()
                    clientSocket?.runCatching { close() }
                    clientSocket = client
                    client.soTimeout = 0
                    outputStream = client.getOutputStream()

                    // Block until laptop disconnects, then loop back to accept()
                    try { client.inputStream.read() } catch (_: Exception) {}
                    outputStream = null
                    clientSocket?.runCatching { close() }
                    clientSocket = null
                }
            } catch (_: Exception) {}
        }
    }

    // ── Emit helpers ─────────────────────────────────────────────────────────

    private fun emit(json: String) {
        val os = outputStream ?: return
        scope.launch {
            try {
                os.write((json + "\n").toByteArray(Charsets.UTF_8))
                os.flush()
            } catch (_: Exception) {
                outputStream = null
                clientSocket?.runCatching { close() }
                clientSocket = null
            }
        }
    }

    private fun gpsJson(loc: Location): String = JSONObject().run {
        put("type", "GPS")
        put("lat",  loc.latitude)
        put("lon",  loc.longitude)
        put("alt",  loc.altitude)
        put("acc",  loc.accuracy.toDouble())
        put("spd",  if (loc.hasSpeed())   loc.speed.toDouble()   else 0.0)
        put("brg",  if (loc.hasBearing()) loc.bearing.toDouble() else 0.0)
        put("ts",   loc.time)
        toString()
    }

    private fun imuJson(nanos: Long): String = JSONObject().run {
        put("type", "IMU")
        put("ax",   lastAcc[0].toDouble())
        put("ay",   lastAcc[1].toDouble())
        put("az",   lastAcc[2].toDouble())
        put("gx",   lastGyr[0].toDouble())
        put("gy",   lastGyr[1].toDouble())
        put("gz",   lastGyr[2].toDouble())
        put("mx",   lastMag[0].toDouble())
        put("my",   lastMag[1].toDouble())
        put("mz",   lastMag[2].toDouble())
        put("ts",   nanos / 1_000_000L)   // nanoseconds → milliseconds
        toString()
    }
}

package com.example.imurecorder2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import kotlin.math.max


class ImuStreamService : Service(), SensorEventListener {

    private val mqttManager = MqttManager()
    private val classifier = ActivityClassifier()

    private lateinit var sensorManager: SensorManager

    private var accSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var magSensor: Sensor? = null

    private val acc = FloatArray(3)
    private val gyro = FloatArray(3)
    private val mag = FloatArray(3)

    private var lastSampleTsNanos: Long = 0L
    private var lastPublishElapsedMs: Long = 0L

    private var publishIntervalMs: Long = 50L

    private var topicPrefix: String = "imu"
    private var deviceId: String = "device"

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        classifier.init(this)
        classifier.requestRetrain(this)

        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
        }
        return START_STICKY
    }

    private fun handleStart(intent: Intent) {
        val host = intent.getStringExtra(EXTRA_HOST) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, 1883)
        val username = intent.getStringExtra(EXTRA_USER) ?: ""
        val password = intent.getStringExtra(EXTRA_PASS) ?: ""

        topicPrefix = intent.getStringExtra(EXTRA_PREFIX) ?: "imu"
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: "device"

        val rateHz = max(1, intent.getIntExtra(EXTRA_RATE_HZ, 20))
        publishIntervalMs = (1000L / rateHz)

        startForeground(NOTIF_ID, buildNotification("Streaming"))

        mqttManager.connect(host = host, port = port, username = username, password = password)

        registerSensors()

        broadcastStatus("started")
    }

    private fun handleStop() {
        unregisterSensors()
        mqttManager.disconnect()
        broadcastStatus("stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerSensors() {

        val samplingPeriodUs = max(5_000, (publishIntervalMs * 1000L).toInt())

        accSensor?.let { sensorManager.registerListener(this, it, samplingPeriodUs) }
        gyroSensor?.let { sensorManager.registerListener(this, it, samplingPeriodUs) }
        magSensor?.let { sensorManager.registerListener(this, it, samplingPeriodUs) }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        unregisterSensors()
        mqttManager.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                acc[0] = event.values[0]
                acc[1] = event.values[1]
                acc[2] = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyro[0] = event.values[0]
                gyro[1] = event.values[1]
                gyro[2] = event.values[2]
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                mag[0] = event.values[0]
                mag[1] = event.values[1]
                mag[2] = event.values[2]
            }
        }

        lastSampleTsNanos = event.timestamp

        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastPublishElapsedMs < publishIntervalMs) return
        lastPublishElapsedMs = nowMs

        // Build sample for classifier
        val sample = ImuSample(
            ax = acc[0], ay = acc[1], az = acc[2],
            gx = gyro[0], gy = gyro[1], gz = gyro[2],
            mx = mag[0], my = mag[1], mz = mag[2],
            tsNanos = lastSampleTsNanos
        )
        classifier.push(sample)

        val (label, conf) = classifier.getLastPrediction()

        // MQTT payload
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("tsNanos", lastSampleTsNanos)
            put("ax", acc[0]); put("ay", acc[1]); put("az", acc[2])
            put("gx", gyro[0]); put("gy", gyro[1]); put("gz", gyro[2])
            put("mx", mag[0]); put("my", mag[1]); put("mz", mag[2])
            put("label", label)
            put("conf", conf.toDouble())
        }.toString()

        val topic = "$topicPrefix/$deviceId"
        mqttManager.publish(topic, payload)

        val ui = Intent(ACTION_STATUS).apply {
            putExtra("label", label)
            putExtra("conf", conf.toFloat())
            putExtra("ax", acc[0]); putExtra("ay", acc[1]); putExtra("az", acc[2])
            putExtra("gx", gyro[0]); putExtra("gy", gyro[1]); putExtra("gz", gyro[2])
            putExtra("mx", mag[0]); putExtra("my", mag[1]); putExtra("mz", mag[2])
        }
        sendBroadcast(ui)
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(ACTION_STATUS).apply { putExtra("status", status) })
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "IMU streaming",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("ImuRecorder2")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.imurecorder2.action.START"
        const val ACTION_STOP = "com.example.imurecorder2.action.STOP"
        const val ACTION_STATUS = "com.example.imurecorder2.action.STATUS"

        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_USER = "extra_user"
        const val EXTRA_PASS = "extra_pass"
        const val EXTRA_PREFIX = "extra_prefix"
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_RATE_HZ = "extra_rate_hz"

        private const val NOTIF_CHANNEL_ID = "imu_stream_channel"
        private const val NOTIF_ID = 1001
    }
}

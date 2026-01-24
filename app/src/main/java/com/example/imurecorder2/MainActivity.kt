package com.example.imurecorder2

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Environment
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

class MainActivity : AppCompatActivity(), SensorEventListener, MqttManager.Listener {

    // UI containers (tabs)
    private lateinit var containerLive: ScrollView
    private lateinit var containerRecord: ScrollView
    private lateinit var containerStream: ScrollView

    // Bottom tabs
    private lateinit var btnTabLive: Button
    private lateinit var btnTabRecord: Button
    private lateinit var btnTabStream: Button

    // LIVE UI
    private lateinit var tvLiveActivity: TextView
    private lateinit var tvLiveConfidence: TextView
    private lateinit var chartAcc: LineChart
    private lateinit var chartGyro: LineChart
    private lateinit var chartMag: LineChart

    private lateinit var spinnerLabel: Spinner
    private lateinit var btnStartStop: Button
    private lateinit var tvRecordingStatus: TextView
    @Volatile private var isRecording = false

    private val recordLock = Any()
    private var recordWriterInternal: BufferedWriter? = null
    private var recordWriterExternal: BufferedWriter? = null
    private var recordFileInternal: File? = null
    private var recordFileExternal: File? = null
    private var recordLabel: String = "unknown"

    // STREAM UI
    private lateinit var etBrokerHost: EditText
    private lateinit var etBrokerPort: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etTopicPrefix: EditText
    private lateinit var btnMqttConnect: Button
    private lateinit var tvMqttStatus: TextView
    private lateinit var tvWireFormatHelp: TextView

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var acc: Sensor? = null
    private var gyro: Sensor? = null
    private var mag: Sensor? = null

    // Background thread for sensors
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler

    // UI update timer
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiUpdatePeriodMs = 100L

    // Classifier
    private val classifier = ActivityClassifier(windowSize = 120)

    // MQTT (activity only for status)
    private val mqttManager = MqttManager()

    // Stream settings persistence
    private lateinit var streamPrefs: SharedPreferences

    // Chart datasets
    private lateinit var accX: LineDataSet
    private lateinit var accY: LineDataSet
    private lateinit var accZ: LineDataSet

    private lateinit var gyroX: LineDataSet
    private lateinit var gyroY: LineDataSet
    private lateinit var gyroZ: LineDataSet

    private lateinit var magX: LineDataSet
    private lateinit var magY: LineDataSet
    private lateinit var magZ: LineDataSet

    private var sampleIndex = 0f

    @Volatile private var lastGx = 0f
    @Volatile private var lastGy = 0f
    @Volatile private var lastGz = 0f
    @Volatile private var lastMx = 0f
    @Volatile private var lastMy = 0f
    @Volatile private var lastMz = 0f

    // Queues (drained on UI thread)
    private val lock = Any()
    private val pendingAcc = ArrayList<FloatArray>(256)
    private val pendingGyro = ArrayList<FloatArray>(256)
    private val pendingMag = ArrayList<FloatArray>(256)

    private val uiTick = object : Runnable {
        override fun run() {
            drainAndRender()
            uiHandler.postDelayed(this, uiUpdatePeriodMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        streamPrefs = getSharedPreferences("stream_settings", MODE_PRIVATE)

        bindUi()
        setupTabs()
        setupLiveCharts()
        setupRecordUi()
        setupStreamUi()

        mqttManager.setListener(this)
        requestNotifPermissionIfNeeded()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        sensorThread = HandlerThread("ImuSensorThread", Process.THREAD_PRIORITY_MORE_FAVORABLE)
        sensorThread.start()
        sensorHandler = Handler(sensorThread.looper)


        classifier.init(this)
        classifier.requestRetrain(this)
    }

    override fun onResume() {
        super.onResume()
        registerSensorsForLive()
        uiHandler.post(uiTick)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiTick)
        sensorManager.unregisterListener(this)

        if (isRecording) {
            stopDatasetRecording()
            runOnUiThread {
                btnStartStop.text = "Start"
                tvRecordingStatus.text = "Not recording"
                tvRecordingStatus.setTextColor(Color.parseColor("#CFCFD6"))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorThread.quitSafely()
    }


    private fun bindUi() {
        containerLive = findViewById(R.id.containerLive)
        containerRecord = findViewById(R.id.containerRecord)
        containerStream = findViewById(R.id.containerStream)

        btnTabLive = findViewById(R.id.btnTabLive)
        btnTabRecord = findViewById(R.id.btnTabRecord)
        btnTabStream = findViewById(R.id.btnTabStream)

        tvLiveActivity = findViewById(R.id.tvLiveActivity)
        tvLiveConfidence = findViewById(R.id.tvLiveConfidence)

        chartAcc = findViewById(R.id.chartAcc)
        chartGyro = findViewById(R.id.chartGyro)
        chartMag = findViewById(R.id.chartMag)

        spinnerLabel = findViewById(R.id.spinnerLabel)
        btnStartStop = findViewById(R.id.btnStartStop)
        tvRecordingStatus = findViewById(R.id.tvRecordingStatus)

        etBrokerHost = findViewById(R.id.etBrokerHost)
        etBrokerPort = findViewById(R.id.etBrokerPort)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        etTopicPrefix = findViewById(R.id.etTopicPrefix)
        btnMqttConnect = findViewById(R.id.btnMqttConnect)
        tvMqttStatus = findViewById(R.id.tvMqttStatus)
        tvWireFormatHelp = findViewById(R.id.tvWireFormatHelp)
    }

    private fun setupTabs() {
        fun setTabSelected(tab: String) {
            val selectedBg = Color.parseColor("#BDA8FF")
            val unselectedBg = Color.parseColor("#1E1E1E")
            val selectedText = Color.parseColor("#000000")
            val unselectedText = Color.parseColor("#FFFFFF")

            fun apply(btn: Button, selected: Boolean) {
                btn.setBackgroundColor(if (selected) selectedBg else unselectedBg)
                btn.setTextColor(if (selected) selectedText else unselectedText)
            }

            apply(btnTabLive, tab == "live")
            apply(btnTabRecord, tab == "record")
            apply(btnTabStream, tab == "stream")
        }

        fun show(tab: String) {
            containerLive.visibility = if (tab == "live") android.view.View.VISIBLE else android.view.View.GONE
            containerRecord.visibility = if (tab == "record") android.view.View.VISIBLE else android.view.View.GONE
            containerStream.visibility = if (tab == "stream") android.view.View.VISIBLE else android.view.View.GONE

            setTabSelected(tab)
        }
        btnTabLive.setOnClickListener { show("live") }
        btnTabRecord.setOnClickListener { show("record") }
        btnTabStream.setOnClickListener { show("stream") }
        show("live")
    }

    private fun setupLiveCharts() {
        accX = createDataSet("ACC_X")
        accY = createDataSet("ACC_Y")
        accZ = createDataSet("ACC_Z")

        gyroX = createDataSet("GYRO_X")
        gyroY = createDataSet("GYRO_Y")
        gyroZ = createDataSet("GYRO_Z")

        magX = createDataSet("MAG_X")
        magY = createDataSet("MAG_Y")
        magZ = createDataSet("MAG_Z")

        setupChart(chartAcc, listOf(accX, accY, accZ))
        setupChart(chartGyro, listOf(gyroX, gyroY, gyroZ))
        setupChart(chartMag, listOf(magX, magY, magZ))
    }

    private fun createDataSet(label: String): LineDataSet {
        val color = when {
            label.endsWith("_X") -> Color.parseColor("#FF5252")
            label.endsWith("_Y") -> Color.parseColor("#4CAF50")
            label.endsWith("_Z") -> Color.parseColor("#448AFF")
            else -> Color.WHITE
        }

        return LineDataSet(mutableListOf(), label).apply {
            setDrawValues(false)
            setDrawCircles(false)
            lineWidth = 2f
            setColor(color)
        }
    }

    private fun setupChart(chart: LineChart, sets: List<LineDataSet>) {
        chart.setTouchEnabled(true)
        chart.description.isEnabled = false
        chart.setDrawGridBackground(false)
        chart.setPinchZoom(true)

        chart.axisLeft.textColor = 0xFFFFFFFF.toInt()
        chart.axisLeft.gridColor = 0x33FFFFFF
        chart.axisLeft.axisLineColor = 0x55FFFFFF
        chart.axisRight.isEnabled = false

        chart.xAxis.textColor = 0xFFFFFFFF.toInt()
        chart.xAxis.gridColor = 0x22FFFFFF
        chart.xAxis.axisLineColor = 0x55FFFFFF

        chart.legend.apply {
            isEnabled = true
            textColor = 0xFFFFFFFF.toInt()
            verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
            horizontalAlignment = Legend.LegendHorizontalAlignment.LEFT
            orientation = Legend.LegendOrientation.HORIZONTAL
            setDrawInside(false)
        }

        chart.data = LineData(sets)
        chart.invalidate()
    }

    private fun setupRecordUi() {
        val labels = listOf(
            "standing",
            "walking",
            "running",
            "stairs_down",
            "stairs_up"
        )
        spinnerLabel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        btnStartStop.setOnClickListener {
            val selectedLabel = spinnerLabel.selectedItem?.toString() ?: "unknown"

            if (!isRecording) {
                startDatasetRecording(selectedLabel)
            } else {
                stopDatasetRecording()
                classifier.requestRetrain(this)
            }

            btnStartStop.text = if (isRecording) "Stop" else "Start"

            tvRecordingStatus.text = if (isRecording) {
                "Recording: $selectedLabel"
            } else {
                "Not recording"
            }
            tvRecordingStatus.setTextColor(
                if (isRecording) Color.parseColor("#BDA8FF") else Color.parseColor("#CFCFD6")
            )
        }

        // default state
        tvRecordingStatus.setTextColor(Color.parseColor("#CFCFD6"))
    }

    // ---------------- Dataset recording (CSV) ----------------

    private fun startDatasetRecording(label: String) {
        val tsMs = System.currentTimeMillis()
        val fname = "dataset_${tsMs}.csv"
        recordLabel = label

        // 1) Internal storage: used for training/retrain.
        val internalDir = File(filesDir, "datasets/$label").apply { mkdirs() }
        val fInternal = File(internalDir, fname)

        // 2) External app-specific storage: visible to the user on device.
        //    /storage/emulated/0/Android/data/<package>/files/Documents/datasets/<label>/...
        val extBase = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val externalDir = if (extBase != null) File(extBase, "datasets/$label").apply { mkdirs() } else null
        val fExternal = externalDir?.let { File(it, fname) }

        synchronized(recordLock) {
            closeRecordWritersLocked()

            recordFileInternal = fInternal
            recordWriterInternal = BufferedWriter(OutputStreamWriter(FileOutputStream(fInternal, true)))

            recordFileExternal = fExternal
            recordWriterExternal = fExternal?.let {
                BufferedWriter(OutputStreamWriter(FileOutputStream(it, true)))
            }

            isRecording = true
        }
    }

    private fun stopDatasetRecording() {
        synchronized(recordLock) {
            isRecording = false
            closeRecordWritersLocked()
        }
    }

    private fun closeRecordWritersLocked() {
        runCatching { recordWriterInternal?.flush() }
        runCatching { recordWriterExternal?.flush() }
        runCatching { recordWriterInternal?.close() }
        runCatching { recordWriterExternal?.close() }
        recordWriterInternal = null
        recordWriterExternal = null
    }

    private fun appendSampleToDataset(sample: ImuSample) {

        val line = buildString(128) {
            append(sample.tsNanos)
            append(','); append(sample.ax)
            append(','); append(sample.ay)
            append(','); append(sample.az)
            append(','); append(sample.gx)
            append(','); append(sample.gy)
            append(','); append(sample.gz)
            append(','); append(sample.mx)
            append(','); append(sample.my)
            append(','); append(sample.mz)
            append('\n')
        }

        synchronized(recordLock) {
            if (!isRecording) return
            runCatching { recordWriterInternal?.write(line) }
            runCatching { recordWriterExternal?.write(line) }
        }
    }

    private fun setupStreamUi() {
        tvWireFormatHelp.text =
            "Topics:\n" +
                    "  <prefix>/raw\n" +
                    "  <prefix>/activity\n\n" +
                    "RAW payload (JSON):\n" +
                    "{ deviceId, seq, rateHz, t0, samples:[ {dt, acc:[x,y,z], gyro:[x,y,z], mag:[x,y,z]} ... ] }\n\n" +
                    "ACTIVITY payload (JSON):\n" +
                    "{ deviceId, ts, label, confidence }"

        btnMqttConnect.setOnClickListener {
            startStreamService()
        }

        // Restore defaults / last entered values
        val host = streamPrefs.getString("host", "broker.hivemq.com") ?: "broker.hivemq.com"
        val port = streamPrefs.getInt("port", 1883)
        val user = streamPrefs.getString("user", "") ?: ""
        val pass = streamPrefs.getString("pass", "") ?: ""
        val prefix = streamPrefs.getString("prefix", "imu/phone1") ?: "imu/phone1"

        etBrokerHost.setText(host)
        etBrokerPort.setText(port.toString())
        etUsername.setText(user)
        etPassword.setText(pass)
        etTopicPrefix.setText(prefix)
    }

    private fun startStreamService() {
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!ok) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
                tvMqttStatus.text = "Notification permission required"
                return
            }
        }

        val host = etBrokerHost.text.toString().trim().ifEmpty { "broker.hivemq.com" }
        val port = etBrokerPort.text.toString().trim().toIntOrNull() ?: 1883
        val user = etUsername.text.toString().trim().ifEmpty { null }
        val pass = etPassword.text.toString()
        val prefix = etTopicPrefix.text.toString().trim().ifEmpty { "imu/phone1" }

        // Persist last values
        streamPrefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("user", user ?: "")
            .putString("pass", pass)
            .putString("prefix", prefix)
            .apply()

        val i = Intent(this, ImuStreamService::class.java).apply {
            action = ImuStreamService.ACTION_START
            putExtra(ImuStreamService.EXTRA_HOST, host)
            putExtra(ImuStreamService.EXTRA_PORT, port)
            putExtra(ImuStreamService.EXTRA_USER, user)
            putExtra(ImuStreamService.EXTRA_PASS, pass)
            putExtra(ImuStreamService.EXTRA_PREFIX, prefix)
            putExtra(ImuStreamService.EXTRA_DEVICE_ID, "phone1")
            putExtra(ImuStreamService.EXTRA_RATE_HZ, 50)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        tvMqttStatus.text = "Connecting..."
    }

    // ---------------- Sensors ----------------

    private fun registerSensorsForLive() {
        val samplingPeriodUs = 20_000 // 50Hz

        acc?.let { sensorManager.registerListener(this, it, samplingPeriodUs, sensorHandler) }
        gyro?.let { sensorManager.registerListener(this, it, samplingPeriodUs, sensorHandler) }
        mag?.let { sensorManager.registerListener(this, it, samplingPeriodUs, sensorHandler) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                lastGx = event.values[0]
                lastGy = event.values[1]
                lastGz = event.values[2]
                synchronized(lock) {
                    pendingGyro.add(floatArrayOf(event.values[0], event.values[1], event.values[2]))
                    if (pendingGyro.size > 2000) pendingGyro.clear()
                }
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                lastMx = event.values[0]
                lastMy = event.values[1]
                lastMz = event.values[2]
                synchronized(lock) {
                    pendingMag.add(floatArrayOf(event.values[0], event.values[1], event.values[2]))
                    if (pendingMag.size > 2000) pendingMag.clear()
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                synchronized(lock) {
                    pendingAcc.add(floatArrayOf(event.values[0], event.values[1], event.values[2], event.timestamp.toFloat()))
                    if (pendingAcc.size > 2000) pendingAcc.clear()
                }

                val sample = ImuSample(
                    ax = event.values[0], ay = event.values[1], az = event.values[2],
                    gx = lastGx, gy = lastGy, gz = lastGz,
                    mx = lastMx, my = lastMy, mz = lastMz,
                    tsNanos = event.timestamp
                )

                // Save raw samples into dataset (CSV) while recording.
                if (isRecording) {
                    appendSampleToDataset(sample)
                }

                val pred = classifier.push(sample)

                if (pred != null) {
                    uiHandler.post {
                        tvLiveActivity.text = "Activity: ${pred.label}"
                        tvLiveConfidence.text = "Confidence: ${"%.2f".format(pred.confidence)}"
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------- Render (UI thread) ----------------

    private fun drainAndRender() {
        val accLocal = ArrayList<FloatArray>()
        val gyroLocal = ArrayList<FloatArray>()
        val magLocal = ArrayList<FloatArray>()

        synchronized(lock) {
            if (pendingAcc.isNotEmpty()) {
                accLocal.addAll(pendingAcc)
                pendingAcc.clear()
            }
            if (pendingGyro.isNotEmpty()) {
                gyroLocal.addAll(pendingGyro)
                pendingGyro.clear()
            }
            if (pendingMag.isNotEmpty()) {
                magLocal.addAll(pendingMag)
                pendingMag.clear()
            }
        }

        // Add ACC points
        for (v in accLocal) {
            addEntry(accX, v[0])
            addEntry(accY, v[1])
            addEntry(accZ, v[2])
            sampleIndex += 1f
        }

        // Add GYRO points
        for (v in gyroLocal) {
            addEntry(gyroX, v[0])
            addEntry(gyroY, v[1])
            addEntry(gyroZ, v[2])
        }

        // Add MAG points
        for (v in magLocal) {
            addEntry(magX, v[0])
            addEntry(magY, v[1])
            addEntry(magZ, v[2])
        }

        refreshChart(chartAcc, sampleIndex)
        refreshChart(chartGyro, sampleIndex)
        refreshChart(chartMag, sampleIndex)
    }

    private fun addEntry(ds: LineDataSet, y: Float) {
        ds.addEntry(Entry(sampleIndex, y))
        if (ds.entryCount > 800) ds.removeFirst()
    }

    private fun refreshChart(chart: LineChart, x: Float) {
        val data = chart.data ?: return
        data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(250f)
        chart.moveViewToX(x)
    }

    // ---------------- MQTT callbacks ----------------

    override fun onConnected() {
        runOnUiThread { tvMqttStatus.text = "Connected" }
    }

    override fun onDisconnected() {
        runOnUiThread { tvMqttStatus.text = "Disconnected" }
    }

    override fun onError(err: String) {
        runOnUiThread { tvMqttStatus.text = "Error" }
    }

    // ---------------- Permissions ----------------

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!ok) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }
}

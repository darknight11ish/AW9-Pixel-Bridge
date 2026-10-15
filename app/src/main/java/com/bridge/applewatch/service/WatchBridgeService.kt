package com.bridge.applewatch.service

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.records.HeartRateRecord
import com.bridge.applewatch.MainActivity
import com.bridge.applewatch.ble.BlePacketParser
import com.bridge.applewatch.control.PixelControlDispatcher
import com.bridge.applewatch.health.HealthConnectManager
import com.bridge.applewatch.model.WatchTelemetryState
import com.bridge.applewatch.model.WebhookTelemetryPayload
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.time.Instant
import java.time.LocalTime
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class WatchBridgeService : Service() {

    companion object {
        private val _telemetry = MutableStateFlow(WatchTelemetryState())
        val telemetry = _telemetry.asStateFlow()

        const val APPLE_COMPANY_ID = 0x004C
        val HRS_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val RSC_SERVICE_UUID: UUID = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
        val RSC_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002a53-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun appendExternalLog(message: String, xmlCountDelta: Int = 0) {
            val current = _telemetry.value
            val updatedLogs = (listOf("[${LocalTime.now().withNano(0)}] $message") + current.recentLogs).take(25)
            _telemetry.value = current.copy(
                xmlImportedRecords = current.xmlImportedRecords + xmlCountDelta,
                healthConnectSyncedCount = current.healthConnectSyncedCount + xmlCountDelta,
                lastSyncStatus = message,
                recentLogs = updatedLogs
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var healthManager: HealthConnectManager
    private lateinit var controlDispatcher: PixelControlDispatcher
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var activeGatt: BluetoothGatt? = null
    private var serverSocket: ServerSocket? = null

    private val gattOperationQueue = ConcurrentLinkedQueue<Runnable>()
    @Volatile private var isGattOperationInProgress = false

    private val rrHistoryMs = ArrayDeque<Double>(32)
    private val hrSampleBuffer = Collections.synchronizedList(mutableListOf<HeartRateRecord.Sample>())
    private var lastCumulativeSteps = 0L

    override fun onCreate() {
        super.onCreate()
        healthManager = HealthConnectManager(this)
        controlDispatcher = PixelControlDispatcher(this, serviceScope)

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        startForegroundNotification()
        startBleContinuityAndGattScan()
        startLocalHttpServer(8484)
        startPeriodicHealthConnectFlush()
        logEvent("WatchBridgeService started on BLE + HTTP :8484")
    }

    private fun startForegroundNotification() {
        val channelId = "aw9_pixel_bridge_channel"
        val channel = NotificationChannel(
            channelId,
            "Apple Watch Series 9 Telemetry Bridge",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AW9 ⇄ Pixel 9 Bridge Active")
            .setContentText("BLE Continuity/GATT + Webhook :8484 → Health Connect")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun logEvent(msg: String) {
        val current = _telemetry.value
        val logs = (listOf("[${LocalTime.now().withNano(0)}] $msg") + current.recentLogs).take(25)
        _telemetry.value = current.copy(lastSyncStatus = msg, recentLogs = logs)
    }

    private fun startBleContinuityAndGattScan() {
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            bleScanner?.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            logEvent("BLE Scan Init Error: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val rssi = result.rssi
            val distMeters = BlePacketParser.estimateDistanceMeters(rssi)

            val appleBytes = record.getManufacturerSpecificData(APPLE_COMPANY_ID)
            if (appleBytes != null) {
                BlePacketParser.parseAppleContinuityManufacturerData(appleBytes)?.let { parsed ->
                    _telemetry.value = _telemetry.value.copy(
                        rssi = rssi,
                        estimatedMeters = distMeters,
                        continuityState = parsed.activityLevelDescription
                    )
                }
            }

            val uuids = record.serviceUuids?.map { it.uuid } ?: emptyList()
            val devName = result.device.name ?: ""
            if (!_telemetry.value.isBleConnected &&
                (uuids.contains(HRS_SERVICE_UUID) || uuids.contains(RSC_SERVICE_UUID) ||
                    devName.contains("Apple Watch", ignoreCase = true))
            ) {
                _telemetry.value = _telemetry.value.copy(
                    watchName = devName.ifEmpty { "Apple Watch Series 9" },
                    watchMacAddress = result.device.address
                )
                activeGatt = result.device.connectGatt(
                    this@WatchBridgeService,
                    true,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            }
        }
    }

    private fun enqueueGattOperation(op: Runnable) {
        gattOperationQueue.add(op)
        if (!isGattOperationInProgress) {
            executeNextGattOperation()
        }
    }

    private fun executeNextGattOperation() {
        val next = gattOperationQueue.poll()
        if (next == null) {
            isGattOperationInProgress = false
            return
        }
        isGattOperationInProgress = true
        next.run()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _telemetry.value = _telemetry.value.copy(
                    isBleConnected = true,
                    watchName = gatt.device.name ?: "Apple Watch Series 9",
                    watchMacAddress = gatt.device.address
                )
                logEvent("BLE GATT Connected to ${gatt.device.address}")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isGattOperationInProgress = false
                gattOperationQueue.clear()
                _telemetry.value = _telemetry.value.copy(
                    isBleConnected = false,
                    sensorContactOnWrist = false
                )
                logEvent("BLE GATT Disconnected — Auto-reconnect armed")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            gatt.getService(HRS_SERVICE_UUID)?.getCharacteristic(HR_MEASUREMENT_CHAR_UUID)?.let { char ->
                enqueueGattOperation(Runnable {
                    gatt.setCharacteristicNotification(char, true)
                    val desc = char.getDescriptor(CCCD_UUID)
                    if (desc != null) {
                        gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        executeNextGattOperation()
                    }
                })
            }

            gatt.getService(RSC_SERVICE_UUID)?.getCharacteristic(RSC_MEASUREMENT_CHAR_UUID)?.let { char ->
                enqueueGattOperation(Runnable {
                    gatt.setCharacteristicNotification(char, true)
                    val desc = char.getDescriptor(CCCD_UUID)
                    if (desc != null) {
                        gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        executeNextGattOperation()
                    }
                })
            }

            gatt.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)?.let { char ->
                enqueueGattOperation(Runnable {
                    gatt.readCharacteristic(char)
                })
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            executeNextGattOperation()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID && value.isNotEmpty()) {
                val pct = (value[0].toInt() and 0xFF).coerceIn(0, 100)
                _telemetry.value = _telemetry.value.copy(batteryPercent = pct)
            }
            executeNextGattOperation()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicPayload(characteristic.uuid, value)
        }

        @Deprecated("Fallback for older Android stacks")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value ?: return
            handleCharacteristicPayload(characteristic.uuid, bytes)
        }
    }

    private fun handleCharacteristicPayload(uuid: UUID, bytes: ByteArray) {
        when (uuid) {
            HR_MEASUREMENT_CHAR_UUID -> {
                val parsed = BlePacketParser.parseHeartRateMeasurement(bytes) ?: return
                var latestRmssd = _telemetry.value.hrvRmssdMs

                if (parsed.rrIntervalsMs.isNotEmpty()) {
                    synchronized(rrHistoryMs) {
                        parsed.rrIntervalsMs.forEach { rr ->
                            if (rrHistoryMs.size >= 30) rrHistoryMs.removeFirst()
                            rrHistoryMs.addLast(rr)
                        }
                        latestRmssd = BlePacketParser.computeRmssd(rrHistoryMs.toList())
                    }
                    if (latestRmssd > 0.0) {
                        serviceScope.launch { healthManager.writeHrvRmssd(latestRmssd) }
                    }
                }

                if (parsed.bpm in 30..240) {
                    hrSampleBuffer.add(
                        HeartRateRecord.Sample(Instant.now(), parsed.bpm.toLong())
                    )
                    _telemetry.value = _telemetry.value.copy(
                        heartRateBpm = parsed.bpm,
                        sensorContactOnWrist = parsed.contactDetected,
                        hrvRmssdMs = latestRmssd
                    )
                }
            }
            RSC_MEASUREMENT_CHAR_UUID -> {
                val rsc = BlePacketParser.parseRscMeasurement(bytes) ?: return
                _telemetry.value = _telemetry.value.copy(
                    runningSpeedMps = rsc.instantaneousSpeedMps,
                    runningCadenceSpm = rsc.instantaneousCadenceSpm,
                    distanceMeters = rsc.totalDistanceMeters ?: _telemetry.value.distanceMeters
                )
            }
        }
    }

    private fun startLocalHttpServer(port: Int) {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket(port)
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleHttpConnection(client) }
                }
            } catch (e: Exception) {
                logEvent("HTTP Server Error: ${e.message}")
            }
        }
    }

    private suspend fun handleHttpConnection(socket: java.net.Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(client.getOutputStream(), true)

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            var contentLength = 0
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                line = reader.readLine()
            }

            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val r = reader.read(buf, totalRead, contentLength - totalRead)
                    if (r == -1) break
                    totalRead += r
                }
                String(buf, 0, totalRead)
            } else ""

            val responseJson = when {
                method == "GET" && path.startsWith("/status") -> {
                    val s = _telemetry.value
                    """{"status":"online","bleConnected":${s.isBleConnected},"bpm":${s.heartRateBpm},"hcSynced":${s.healthConnectSyncedCount}}"""
                }
                method == "POST" && path.startsWith("/control") -> {
                    val json = JSONObject(body.ifEmpty { "{}" })
                    val cmd = json.optString("command", "")
                    val outcome = controlDispatcher.executeCommand(cmd)
                    _telemetry.value = _telemetry.value.copy(lastControlAction = outcome)
                    logEvent("Watch Control → $outcome")
                    """{"status":"executed","action":"$outcome"}"""
                }
                else -> {
                    processWebhookTelemetryJson(body)
                    """{"status":"synced","hcTotal":${_telemetry.value.healthConnectSyncedCount}}"""
                }
            }

            writer.print(
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Content-Length: ${responseJson.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n" +
                    responseJson
            )
            writer.flush()
        }
    }

    private suspend fun processWebhookTelemetryJson(rawBody: String) {
        if (rawBody.isBlank()) return
        try {
            val json = JSONObject(rawBody)

            if (json.optBoolean("findMyPixel", false)) {
                val res = controlDispatcher.executeCommand("FIND_MY_PIXEL")
                _telemetry.value = _telemetry.value.copy(lastControlAction = res)
            }
            val cmd = json.optString("controlCommand", "")
            if (cmd.isNotBlank()) {
                val res = controlDispatcher.executeCommand(cmd)
                _telemetry.value = _telemetry.value.copy(lastControlAction = res)
            }

            val payload = WebhookTelemetryPayload(
                heartRate = json.optInt("heartRate", 0),
                steps = json.optLong("steps", -1L),
                distanceMeters = json.optDouble("distanceMeters", -1.0),
                activeCalories = json.optDouble("activeCalories", -1.0),
                spo2 = json.optDouble("spo2", -1.0),
                floorsClimbed = json.optDouble("floorsClimbed", -1.0),
                hrvMs = json.optDouble("hrvMs", -1.0),
                restingHeartRate = json.optInt("restingHeartRate", 0),
                respiratoryRate = json.optDouble("respiratoryRate", -1.0),
                sleepMinutes = json.optLong("sleepMinutes", -1L),
                battery = json.optInt("battery", -1),
                controlCommand = cmd
            )

            val stepDelta = if (payload.steps > lastCumulativeSteps && payload.steps > 0) {
                val d = payload.steps - lastCumulativeSteps
                lastCumulativeSteps = payload.steps
                d
            } else 0L

            val writtenCount = healthManager.writeTelemetryPayload(payload, stepDelta)

            val cur = _telemetry.value
            _telemetry.value = cur.copy(
                heartRateBpm = if (payload.heartRate > 0) payload.heartRate else cur.heartRateBpm,
                stepsToday = if (payload.steps >= 0) payload.steps else cur.stepsToday,
                distanceMeters = if (payload.distanceMeters >= 0) payload.distanceMeters else cur.distanceMeters,
                activeKcal = if (payload.activeCalories >= 0) payload.activeCalories else cur.activeKcal,
                spo2Percent = if (payload.spo2 >= 0) payload.spo2 else cur.spo2Percent,
                floorsClimbed = if (payload.floorsClimbed >= 0) payload.floorsClimbed else cur.floorsClimbed,
                hrvRmssdMs = if (payload.hrvMs > 0) payload.hrvMs else cur.hrvRmssdMs,
                restingHrBpm = if (payload.restingHeartRate > 0) payload.restingHeartRate else cur.restingHrBpm,
                respiratoryRateBrpm = if (payload.respiratoryRate > 0) payload.respiratoryRate else cur.respiratoryRateBrpm,
                sleepMinutes = if (payload.sleepMinutes >= 0) payload.sleepMinutes else cur.sleepMinutes,
                batteryPercent = if (payload.battery in 0..100) payload.battery else cur.batteryPercent,
                healthConnectSyncedCount = cur.healthConnectSyncedCount + writtenCount
            )
            logEvent("Webhook committed $writtenCount Health Connect records")
        } catch (e: Exception) {
            logEvent("Webhook JSON Error: ${e.message}")
        }
    }

    private fun startPeriodicHealthConnectFlush() {
        serviceScope.launch {
            while (isActive) {
                delay(15_000L)
                if (hrSampleBuffer.isNotEmpty()) {
                    val copy = synchronized(hrSampleBuffer) {
                        val list = hrSampleBuffer.toList()
                        hrSampleBuffer.clear()
                        list
                    }
                    try {
                        val count = healthManager.flushHeartRateSamples(copy)
                        if (count > 0) {
                            _telemetry.value = _telemetry.value.copy(
                                healthConnectSyncedCount = _telemetry.value.healthConnectSyncedCount + 1
                            )
                            logEvent("Flushed $count live BLE HR samples to Health Connect")
                        }
                    } catch (e: Exception) {
                        logEvent("HC Flush Error: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            bleScanner?.stopScan(scanCallback)
            activeGatt?.close()
            serverSocket?.close()
        } catch (_: Exception) {}
        serviceScope.cancel()
        super.onDestroy()
    }
}

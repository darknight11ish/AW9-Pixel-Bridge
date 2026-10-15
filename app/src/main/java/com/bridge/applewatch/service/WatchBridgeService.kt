package com.bridge.applewatch.service

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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

        /** Bumped by the GATT watchdog alongside a queued operation. */
        private const val WATCHDOG_TIMEOUT_MS = 3_000L

        /** How long to wait for a connection handshake before retrying (D-12 follow-up). */
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** Notified when the service is torn down, so tests can observe shutdown. */
        @Volatile
        var shuttingDownForTest: Boolean = false
            private set

        private const val PREFS_NAME = "aw9_bridge_prefs"
        private const val PREF_AUTH_TOKEN = "auth_token"

        /** Optional shared secret. Empty means the bridge accepts any private peer. */
        @Volatile
        var authToken: String? = null
            private set

        /** Loads the persisted token; empty/absent disables the check (backwards compatible). */
        fun loadAuthToken(context: Context) {
            val prefs: SharedPreferences =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            authToken = prefs.getString(PREF_AUTH_TOKEN, null)?.takeIf { it.isNotBlank() }
        }

        /** Test-only: drops the in-memory copy without touching storage. */
        internal fun authTokenForTestReset() {
            authToken = null
        }

        fun setAuthToken(context: Context, token: String?) {
            val cleaned = token?.trim().orEmpty()
            authToken = cleaned.ifEmpty { null }
            // Store a real absence rather than an empty string, so loadAuthToken
            // observes null and the bridge stays open.
            val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            if (cleaned.isEmpty()) {
                editor.remove(PREF_AUTH_TOKEN)
            } else {
                editor.putString(PREF_AUTH_TOKEN, cleaned)
            }
            editor.apply()
        }

        /** Removes the persisted token as well as the in-memory copy. */
        fun clearAuthToken(context: Context) {
            authToken = null
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_AUTH_TOKEN)
                .apply()
        }

        /** True only for loopback and RFC1918 peers. */
        fun isPrivateNetworkAddress(host: String?): Boolean =
            BridgePolicy.isPrivateNetworkAddress(host)

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

    /** Incremented every time an operation is pulled off the queue; the watchdog compares it. */
    @Volatile private var gattOperationToken = 0L
    private var gattWatchdogJob: Job? = null

    /** Prevents a connectGatt() storm while the first connection handshake is in flight. */
    @Volatile private var isBleConnecting = false

    /** Bounded reconnect bookkeeping so a silent controller cannot wedge the gate forever. */
    @Volatile private var connectAttempts = 0
    private var connectTimeoutJob: Job? = null

    /** Last advertised address we saw for a bridge-capable peripheral. */
    @Volatile private var lastConnectCandidate: BluetoothDevice? = null

    /** Set before closing the listener so a deliberate shutdown is not logged as an error. */
    @Volatile private var isShuttingDown = false

    /** MAC address of the device we are tracking, populated inside onScanResult. */
    @Volatile private var trackedWatchAddress: String? = null

    /** Exponential moving average of RSSI, 0.0 until the first sample. */
    @Volatile private var smoothedRssi: Double = 0.0

    private val rrHistoryMs = ArrayDeque<Double>(32)
    private val hrSampleBuffer = Collections.synchronizedList(mutableListOf<HeartRateRecord.Sample>())

    /**
     * Cumulative step counter seen in the last webhook. [BridgePolicy.NO_STEP_BASELINE]
     * means "not yet baselined", so the first payload establishes the baseline instead
     * of being written into Health Connect as a single window.
     */
    private var lastCumulativeSteps = BridgePolicy.NO_STEP_BASELINE

    override fun onCreate() {
        super.onCreate()
        shuttingDownForTest = false
        loadAuthToken(this)
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
            val deviceAddress = result.device.address

            val appleBytes = record.getManufacturerSpecificData(APPLE_COMPANY_ID)
            if (appleBytes != null &&
                BridgePolicy.shouldAcceptContinuityFrame(deviceAddress, trackedWatchAddress, rssi)
            ) {
                BlePacketParser.parseAppleContinuityManufacturerData(appleBytes)?.let { parsed ->
                    smoothedRssi = BridgePolicy.smoothRssi(smoothedRssi, rssi)
                    val smoothed = smoothedRssi.toInt()
                    _telemetry.value = _telemetry.value.copy(
                        rssi = smoothed,
                        estimatedMeters = BlePacketParser.estimateDistanceMeters(smoothed),
                        continuityState = parsed.activityLevelDescription
                    )
                }
            }

            val uuids = record.serviceUuids?.map { it.uuid } ?: emptyList()
            val devName = result.device.name ?: ""
            val matchesBridge =
                uuids.contains(HRS_SERVICE_UUID) || uuids.contains(RSC_SERVICE_UUID) ||
                    devName.contains("Apple Watch", ignoreCase = true)
            if (matchesBridge) {
                if (deviceAddress.isNotBlank()) trackedWatchAddress = deviceAddress
                lastConnectCandidate = result.device
                if (!_telemetry.value.isBleConnected && !isBleConnecting) {
                    beginGattConnection(result.device, devName, deviceAddress)
                }
            }
        }
    }

    private fun beginGattConnection(device: BluetoothDevice, devName: String, deviceAddress: String) {
        isBleConnecting = true
        _telemetry.value = _telemetry.value.copy(
            watchName = devName.ifEmpty { "Apple Watch Series 9" },
            watchMacAddress = deviceAddress
        )
        try {
            activeGatt = device.connectGatt(
                this,
                true,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
            if (activeGatt == null) {
                isBleConnecting = false
                logEvent("connectGatt returned null for $deviceAddress")
                return
            }
            armConnectTimeout()
        } catch (e: Exception) {
            isBleConnecting = false
            logEvent("connectGatt failed for $deviceAddress: ${e.message}")
        }
    }

    /**
     * If the controller never reports STATE_CONNECTED or STATE_DISCONNECTED, the
     * gate would stay latched and streaming would never resume. Close the attempt,
     * clear the gate, and retry a bounded number of times with backoff.
     */
    private fun armConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = serviceScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!isActive || !isBleConnecting) return@launch
            logEvent("GATT connect timed out after ${CONNECT_TIMEOUT_MS}ms")
            isBleConnecting = false
            try {
                activeGatt?.close()
            } catch (_: Exception) {
            }
            activeGatt = null
            scheduleConnectRetry()
        }
    }

    private fun scheduleConnectRetry() {
        val candidate = lastConnectCandidate ?: return
        if (connectAttempts >= BridgePolicy.MAX_CONNECT_ATTEMPTS) {
            logEvent("GATT reconnect budget exhausted — waiting for a fresh advertisement")
            connectAttempts = 0
            return
        }
        val delayMs = BridgePolicy.connectRetryDelayMs(connectAttempts)
        connectAttempts++
        logEvent("Scheduling GATT reconnect attempt $connectAttempts in ${delayMs}ms")
        serviceScope.launch {
            delay(delayMs)
            if (!isActive || isShuttingDown || isBleConnecting || _telemetry.value.isBleConnected) {
                return@launch
            }
            val device = lastConnectCandidate ?: return@launch
            beginGattConnection(device, device.name ?: "Apple Watch Series 9", device.address)
        }
    }

    private fun cancelConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
    }

    private fun enqueueGattOperation(op: Runnable) {
        gattOperationQueue.add(op)
        if (!isGattOperationInProgress) {
            executeNextGattOperation()
        }
    }

    private fun executeNextGattOperation() {
        gattWatchdogJob?.cancel()
        gattWatchdogJob = null
        val next = gattOperationQueue.poll()
        if (next == null) {
            isGattOperationInProgress = false
            return
        }
        val token = ++gattOperationToken
        isGattOperationInProgress = true
        armGattWatchdog(token)
        next.run()
    }

    /**
     * If the controller drops a callback (busy radio, link still encrypting) the
     * queue would otherwise stay locked forever. The watchdog advances it instead.
     */
    private fun armGattWatchdog(token: Long) {
        gattWatchdogJob = serviceScope.launch {
            delay(WATCHDOG_TIMEOUT_MS)
            if (isActive && token == gattOperationToken && isGattOperationInProgress) {
                logEvent("GATT operation timed out after ${WATCHDOG_TIMEOUT_MS}ms — advancing queue")
                executeNextGattOperation()
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                cancelConnectTimeout()
                isBleConnecting = false
                connectAttempts = 0
                _telemetry.value = _telemetry.value.copy(
                    isBleConnected = true,
                    watchName = gatt.device.name ?: "Apple Watch Series 9",
                    watchMacAddress = gatt.device.address
                )
                trackedWatchAddress = gatt.device.address
                logEvent("BLE GATT Connected to ${gatt.device.address}")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cancelConnectTimeout()
                isBleConnecting = false
                isGattOperationInProgress = false
                gattOperationQueue.clear()
                gattWatchdogJob?.cancel()
                gattWatchdogJob = null
                _telemetry.value = _telemetry.value.copy(
                    isBleConnected = false,
                    sensorContactOnWrist = false
                )
                logEvent("BLE GATT Disconnected — Auto-reconnect armed")
                // A dropped link is the expected path for a bounded retry.
                if (!isShuttingDown) scheduleConnectRetry()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            gatt.getService(HRS_SERVICE_UUID)?.getCharacteristic(HR_MEASUREMENT_CHAR_UUID)?.let { char ->
                enqueueGattOperation(Runnable {
                    gatt.setCharacteristicNotification(char, true)
                    val desc = char.getDescriptor(CCCD_UUID)
                    if (desc == null) {
                        executeNextGattOperation()
                    } else if (gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        != BluetoothStatusCodes.SUCCESS
                    ) {
                        logEvent("CCCD write rejected for 0x2A37 — skipping notify registration")
                        executeNextGattOperation()
                    }
                })
            }

            gatt.getService(RSC_SERVICE_UUID)?.getCharacteristic(RSC_MEASUREMENT_CHAR_UUID)?.let { char ->
                enqueueGattOperation(Runnable {
                    gatt.setCharacteristicNotification(char, true)
                    val desc = char.getDescriptor(CCCD_UUID)
                    if (desc == null) {
                        executeNextGattOperation()
                    } else if (gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        != BluetoothStatusCodes.SUCCESS
                    ) {
                        logEvent("CCCD write rejected for 0x2A53 — skipping notify registration")
                        executeNextGattOperation()
                    }
                })
            }

            gatt.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)?.let { char ->
                enqueueGattOperation(Runnable {
                    if (!gatt.readCharacteristic(char)) {
                        logEvent("Battery read rejected by controller")
                        executeNextGattOperation()
                    }
                })
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logEvent("CCCD descriptor write failed with status $status")
            }
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
                if (!isShuttingDown) {
                    logEvent("HTTP Server Error: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleHttpConnection(socket: java.net.Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(client.getOutputStream(), true)

            // Only loopback / private LAN peers may drive the bridge.
            val remoteHost = client.inetAddress?.hostAddress
            if (!BridgePolicy.isPrivateNetworkAddress(remoteHost)) {
                logEvent("HTTP connection refused from non-private peer $remoteHost")
                writeResponse(writer, 403, "Forbidden", """{"status":"forbidden","code":403}""")
                return
            }

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            var contentLength = 0
            var presentedToken: String? = null
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                when {
                    line.startsWith("Content-Length:", ignoreCase = true) ->
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    line.startsWith("X-Bridge-Token:", ignoreCase = true) ->
                        presentedToken = line.substringAfter(":").trim()
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

            // A JSON body may also carry the token, for clients that cannot set headers.
            if (presentedToken.isNullOrBlank() && body.isNotBlank()) {
                presentedToken = runCatching {
                    JSONObject(body).optString("authToken", "")
                }.getOrDefault("")
            }

            if (!BridgePolicy.tokenAccepted(authToken, presentedToken)) {
                logEvent("HTTP request rejected: bridge token mismatch")
                writeResponse(writer, 401, "Unauthorized", """{"status":"unauthorized","code":401}""")
                return
            }

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

            writeResponse(writer, 200, "OK", responseJson)
        }
    }

    private fun writeResponse(writer: PrintWriter, code: Int, reason: String, json: String) {
        writer.print(
            "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: application/json\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Headers: Content-Type, X-Bridge-Token\r\n" +
                "Content-Length: ${json.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                json
        )
        writer.flush()
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

            val advance = BridgePolicy.advanceStepBaseline(lastCumulativeSteps, payload.steps)
            if (lastCumulativeSteps == BridgePolicy.NO_STEP_BASELINE &&
                advance.baseline != BridgePolicy.NO_STEP_BASELINE
            ) {
                logEvent("Step baseline established at ${advance.baseline}")
            }
            lastCumulativeSteps = advance.baseline

            val writtenCount = healthManager.writeTelemetryPayload(payload, advance.delta)

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
                                healthConnectSyncedCount = _telemetry.value.healthConnectSyncedCount + count
                            )
                            logEvent("Flushed live BLE HR batch → $count record(s) in Health Connect")
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
        // Set before closing so the resulting SocketException is not logged as a
        // failure and the listener is not re-created by a racing coroutine.
        isShuttingDown = true
        shuttingDownForTest = true
        cancelConnectTimeout()
        gattWatchdogJob?.cancel()
        gattWatchdogJob = null
        try {
            bleScanner?.stopScan(scanCallback)
            activeGatt?.close()
            serverSocket?.close()
        } catch (_: Exception) {}
        serviceScope.cancel()
        super.onDestroy()
    }
}

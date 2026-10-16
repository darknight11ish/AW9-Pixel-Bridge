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
import com.bridge.applewatch.health.WebhookPayloadParser
import com.bridge.applewatch.model.WatchTelemetryState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.time.Instant
import java.time.LocalDate
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

        /** Separate store for the step baseline, so clearing auth cannot drop it. */
        private const val STEP_PREFS_NAME = "aw9_step_baseline"
        private const val PREF_STEP_EPOCH_DAY = "savedEpochDay"
        private const val PREF_STEP_COUNT = "savedSteps"

        /** Honest placeholder used when a peripheral never told us its name (OPEN-02). */
        const val UNKNOWN_SENSOR_NAME = "BLE Biometric Sensor"

        /**
         * Max concurrent HTTP handlers. Each connection costs a coroutine plus a
         * socket, so this is the cap that stops a flood of silent peers from
         * exhausting the service (OPEN-03).
         */
        const val HTTP_MAX_CONCURRENCY = 8

        /** Read timeout for a connected HTTP client, so a half-open peer cannot hang us. */
        private const val HTTP_READ_TIMEOUT_MS = 5_000

        /**
         * How long a request may wait for a concurrency permit before it is refused with
         * `503`. Above the read timeout on purpose: a request that has to wait longer
         * than a slow peer is allowed to hold its socket has already lost.
         */
        private const val HTTP_PERMIT_WAIT_MS = 6_000

        /** Poll interval while waiting for an HTTP concurrency permit. */
        private const val PERMIT_POLL_INTERVAL_MS = 25L

        /** Advertised on every response, including 401/403, so browsers can send the token. */
        private const val CORS_ALLOW_HEADERS =
            "Access-Control-Allow-Headers: Content-Type, X-Bridge-Token, Authorization"

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

        /**
         * Restores the step baseline persisted by a previous run (OPEN-04).
         *
         * The cumulative step counter is monotonic *within a day*, so a stored value is
         * only reusable while its epoch-day matches today. Across midnight the counter
         * restarts (and the watch may report a fresh day's total), so a stale baseline
         * would either emit a bogus negative delta or silently swallow the new day's
         * steps. Returning [BridgePolicy.NO_STEP_BASELINE] makes the first reading of
         * the new day re-establish the baseline instead.
         */
        fun loadPersistedStepBaseline(context: Context, todayEpochDay: Long): Long {
            val prefs = context.getSharedPreferences(STEP_PREFS_NAME, Context.MODE_PRIVATE)
            val savedEpochDay = prefs.getLong(PREF_STEP_EPOCH_DAY, Long.MIN_VALUE)
            if (savedEpochDay != todayEpochDay) return BridgePolicy.NO_STEP_BASELINE
            return prefs.getLong(PREF_STEP_COUNT, BridgePolicy.NO_STEP_BASELINE)
        }

        /** Persists the baseline against today's epoch-day so a restart can reuse it. */
        fun persistStepBaseline(context: Context, steps: Long, todayEpochDay: Long) {
            if (steps == BridgePolicy.NO_STEP_BASELINE) return
            context.getSharedPreferences(STEP_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(PREF_STEP_EPOCH_DAY, todayEpochDay)
                .putLong(PREF_STEP_COUNT, steps)
                .apply()
        }

        /** Test-only: drops the persisted baseline so tests start from a known state. */
        fun clearPersistedStepBaseline(context: Context) {
            context.getSharedPreferences(STEP_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
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

    /** Bounds concurrent /telemetry handlers so a silent client cannot pin resources. */
    private val httpSemaphore = Semaphore(HTTP_MAX_CONCURRENCY)

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
        // Reuse today's baseline so a service restart does not treat the watch's
        // cumulative counter as brand-new and dump a whole day into one window
        // (OPEN-04).
        lastCumulativeSteps = loadPersistedStepBaseline(this, LocalDate.now().toEpochDay())
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
            // Connect only when the advertisement really exposes a service we can
            // consume. Matching on the device *name* used to connect to every
            // "Apple Watch" in range (OPEN-01/OPEN-02): the watch cannot serve
            // 0x180D, so that produced a "connected" UI with zero data.
            val matchesBridge =
                uuids.contains(HRS_SERVICE_UUID) || uuids.contains(RSC_SERVICE_UUID)
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
        // The name is provisional until onServicesDiscovered proves the device is
        // usable, so record only what the stack actually reported.
        _telemetry.value = _telemetry.value.copy(
            watchName = devName.ifBlank { UNKNOWN_SENSOR_NAME },
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
        if (lastConnectCandidate == null) return
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
            beginGattConnection(device, device.name ?: UNKNOWN_SENSOR_NAME, device.address)
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
                trackedWatchAddress = gatt.device.address
                // A GATT link is NOT a usable biometric link (OPEN-01). Claiming
                // "connected" here was the actively deceptive state described in
                // the engagement report: the link succeeds, then no service is
                // found and no data ever arrives.
                logEvent(
                    "BLE link established with ${gatt.device.address} — " +
                        "verifying 0x180D/0x1814 services..."
                )
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logEvent("Service discovery failed with status $status — disconnecting")
                abandonGattLink(gatt)
                return
            }

            val hasHr = gatt.getService(HRS_SERVICE_UUID)
                ?.getCharacteristic(HR_MEASUREMENT_CHAR_UUID) != null
            val hasRsc = gatt.getService(RSC_SERVICE_UUID)
                ?.getCharacteristic(RSC_MEASUREMENT_CHAR_UUID) != null

            if (!hasHr && !hasRsc) {
                logEvent(
                    "BLE device ${gatt.device.address} has no 0x180D/0x1814 service — disconnecting"
                )
                abandonGattLink(gatt)
                return
            }

            // Only now is the link provably useful, so this is where "connected" is
            // earned. Never guess a product name (OPEN-02).
            _telemetry.value = _telemetry.value.copy(
                isBleConnected = true,
                watchName = gatt.device.name?.takeIf { it.isNotBlank() } ?: UNKNOWN_SENSOR_NAME,
                watchMacAddress = gatt.device.address
            )
            logEvent(
                "BLE GATT Connected to ${gatt.device.address} " +
                    "(0x180D=${if (hasHr) "yes" else "no"}, 0x1814=${if (hasRsc) "yes" else "no"})"
            )

            val hrChar = gatt.getService(HRS_SERVICE_UUID)?.getCharacteristic(HR_MEASUREMENT_CHAR_UUID)
            if (hrChar != null) {
                enqueueGattOperation(Runnable {
                    gatt.setCharacteristicNotification(hrChar, true)
                    val desc = hrChar.getDescriptor(CCCD_UUID)
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

            val rscChar =
                gatt.getService(RSC_SERVICE_UUID)?.getCharacteristic(RSC_MEASUREMENT_CHAR_UUID)
            if (rscChar != null) {
                enqueueGattOperation(Runnable {
                    gatt.setCharacteristicNotification(rscChar, true)
                    val desc = rscChar.getDescriptor(CCCD_UUID)
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

    /**
     * Tears down a GATT link that turned out not to expose a service we can consume.
     * [isBleConnected] is deliberately left false, and the queue is cleared so no
     * operation is left waiting on a closed client.
     */
    private fun abandonGattLink(gatt: BluetoothGatt) {
        isGattOperationInProgress = false
        gattOperationQueue.clear()
        gattWatchdogJob?.cancel()
        gattWatchdogJob = null
        _telemetry.value = _telemetry.value.copy(
            isBleConnected = false,
            sensorContactOnWrist = false
        )
        try {
            gatt.disconnect()
            gatt.close()
        } catch (e: Exception) {
            logEvent("GATT teardown error: ${e.message}")
        }
        if (activeGatt === gatt) activeGatt = null
        // Bounded retry: reconnecting forever to a device that will never expose
        // 0x180D is exactly the loop this change exists to stop.
        if (!isShuttingDown) scheduleConnectRetry()
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
                    // Bounded concurrency: an unbounded `launch` per connection let a
                    // handful of silent or malicious peers pin unlimited coroutines
                    // and sockets (OPEN-03).
                    launch { serveIfPermitted(client) }
                }
            } catch (e: Exception) {
                if (!isShuttingDown) {
                    logEvent("HTTP Server Error: ${e.message}")
                }
            }
        }
    }

    /**
     * Waits a bounded time for a concurrency permit, then serves the client.
     *
     * The semaphore already caps how many requests run at once, but an *unbounded*
     * `withPermit` means a connection accepted while every permit is busy simply waits,
     * holding its socket open for as long as the slow peers take. A flood of such
     * connections therefore converts the concurrency cap into an unbounded queue. Giving
     * the wait a deadline, and answering `503` when it expires, keeps the bound honest
     * and tells a client to retry instead of leaving it hanging.
     */
    private suspend fun serveIfPermitted(socket: java.net.Socket) {
        val acquired = awaitPermit()
        val overloaded = !acquired

        if (overloaded) {
            if (!isShuttingDown) {
                logEvent("HTTP request refused: concurrency limit reached (${HTTP_MAX_CONCURRENCY})")
                writeOverloadResponse(socket)
            }
            try {
                socket.close()
            } catch (_: Exception) {
            }
            return
        }

        try {
            // The service may have begun shutting down while this request waited.
            if (!isShuttingDown) handleHttpConnection(socket)
        } finally {
            httpSemaphore.release()
        }
    }

    /**
     * Waits up to [HTTP_PERMIT_WAIT_MS] for a permit, polling rather than using a
     * cancellation-based timeout. A permit is a resource that must be released by
     * exactly the code that took it, and polling keeps acquisition and release in one
     * linear path: if this returns true, the caller owns a permit and is responsible for
     * releasing it in a `finally`.
     */
    private suspend fun awaitPermit(): Boolean {
        val deadline = System.currentTimeMillis() + HTTP_PERMIT_WAIT_MS
        while (true) {
            if (isShuttingDown) return false
            if (httpSemaphore.tryAcquire()) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(PERMIT_POLL_INTERVAL_MS)
        }
    }

    /**
     * Answers a request we had no capacity for. Written on the raw socket because the
     * normal response path is entered only after a permit is held.
     */
    private fun writeOverloadResponse(socket: java.net.Socket) {
        try {
            val json = """{"status":"busy","code":503}"""
            val writer = PrintWriter(socket.getOutputStream(), true)
            writer.print(
                "HTTP/1.1 503 Service Unavailable\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                    "$CORS_ALLOW_HEADERS\r\n" +
                    "Retry-After: 1\r\n" +
                    "Content-Length: ${json.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n" +
                    json
            )
            writer.flush()
        } catch (_: Exception) {
            // The peer may already be gone; there is nothing useful to do about that.
        }
    }

    private suspend fun handleHttpConnection(socket: java.net.Socket) {
        try {
            socket.use { client ->
                // A silent or half-open peer must not hold a permit forever.
                client.soTimeout = HTTP_READ_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val writer = PrintWriter(client.getOutputStream(), true)

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"

                // CORS preflight first: browsers never send credentials on OPTIONS,
                // so it can neither be authenticated nor originate from a private
                // address exclusively.
                if (method.equals("OPTIONS", ignoreCase = true)) {
                    writeCorsPreflight(writer)
                    return
                }

                // Only loopback / private LAN peers may drive the bridge.
                val remoteHost = client.inetAddress?.hostAddress
                if (!BridgePolicy.isPrivateNetworkAddress(remoteHost)) {
                    logEvent("HTTP connection refused from non-private peer $remoteHost")
                    writeResponse(writer, 403, "Forbidden", """{"status":"forbidden","code":403}""")
                    return
                }

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
        } catch (e: Exception) {
            // A malformed request, a socket timeout or a peer that vanished mid-body
            // used to propagate out of the launched coroutine and leave no response
            // at all (OPEN-03).
            if (!isShuttingDown) {
                logEvent("HTTP Client Error (${e.javaClass.simpleName}): ${e.message}")
            }
        }
    }

    /** Answers a CORS preflight without touching auth or the private-peer gate. */
    private fun writeCorsPreflight(writer: PrintWriter) {
        writer.print(
            "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "$CORS_ALLOW_HEADERS\r\n" +
                "Access-Control-Max-Age: 86400\r\n" +
                "Connection: close\r\n\r\n"
        )
        writer.flush()
    }

    private fun writeResponse(writer: PrintWriter, code: Int, reason: String, json: String) {
        writer.print(
            "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: application/json\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "$CORS_ALLOW_HEADERS\r\n" +
                "Content-Length: ${json.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                json
        )
        writer.flush()
    }

    private suspend fun processWebhookTelemetryJson(rawBody: String) {
        if (rawBody.isBlank()) return
        try {
            // All three supported dialects (native camelCase, Apple Shortcuts
            // snake_case and Health Auto Export's nested metrics) are normalised by
            // the parser, so this method no longer reads JSON keys itself. The
            // legacy `findMyPixel: true` flag is likewise resolved to "FIND_MY_PIXEL".
            val payload = WebhookPayloadParser.parse(rawBody)

            if (payload.controlCommand.isNotBlank()) {
                val res = controlDispatcher.executeCommand(payload.controlCommand)
                _telemetry.value = _telemetry.value.copy(lastControlAction = res)
            }

            val advance = BridgePolicy.advanceStepBaseline(lastCumulativeSteps, payload.steps)
            if (lastCumulativeSteps == BridgePolicy.NO_STEP_BASELINE &&
                advance.baseline != BridgePolicy.NO_STEP_BASELINE
            ) {
                logEvent("Step baseline established at ${advance.baseline}")
            }
            lastCumulativeSteps = advance.baseline
            persistStepBaseline(this, advance.baseline, LocalDate.now().toEpochDay())

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

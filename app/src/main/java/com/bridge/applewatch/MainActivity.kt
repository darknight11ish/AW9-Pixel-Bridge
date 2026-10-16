package com.bridge.applewatch

import android.Manifest
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.bridge.applewatch.health.AppleHealthXmlImporter
import com.bridge.applewatch.health.HealthConnectManager
import com.bridge.applewatch.model.WatchTelemetryState
import com.bridge.applewatch.service.BridgePolicy
import com.bridge.applewatch.service.WatchBridgeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private lateinit var healthManager: HealthConnectManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        healthManager = HealthConnectManager(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by WatchBridgeService.telemetry.collectAsState()
                var hcAuthorized by remember { mutableStateOf(false) }
                // Seeded from the persisted value so a restart shows the real auth mode
                // rather than a false "no token" (OPEN-05).
                var authTokenConfigured by remember {
                    mutableStateOf(WatchBridgeService.authToken != null)
                }
                var webhookEndpoints by remember { mutableStateOf(resolveActiveEndpoints()) }

                val healthPermLauncher = rememberLauncherForActivityResult(
                    PermissionController.createRequestPermissionResultContract()
                ) { granted ->
                    hcAuthorized = granted.containsAll(HealthConnectManager.REQUIRED_PERMISSIONS)
                }

                val xmlFilePickerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    if (uri != null) {
                        importAppleHealthXmlUri(uri)
                    }
                }

                val runtimeBleLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted: Map<String, Boolean> ->
                    val bluetoothGranted =
                        granted[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                            granted[Manifest.permission.BLUETOOTH_SCAN] == true
                    if (bluetoothGranted) {
                        startForegroundService(Intent(this, WatchBridgeService::class.java))
                    } else {
                        // Starting a connectedDevice foreground service without these
                        // grants throws SecurityException on API 34+ (D-03).
                        WatchBridgeService.appendExternalLog(
                            "Bluetooth permissions required to start Foreground Service"
                        )
                    }
                    lifecycleScope.launch {
                        hcAuthorized = healthManager.hasAllPermissions()
                        if (!hcAuthorized) {
                            healthPermLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    runtimeBleLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    )
                }

                // Toggling Wi-Fi or the Pixel hotspot changes the address the watch must
                // POST to. Without this callback the card kept showing the address
                // resolved at startup, which is exactly the hotspot failure mode
                // described in the engagement report (OPEN-06).
                DisposableEffect(Unit) {
                    val manager = getSystemService(ConnectivityManager::class.java)
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        private fun refresh() {
                            lifecycleScope.launch(Dispatchers.IO) {
                                webhookEndpoints = resolveActiveEndpoints()
                            }
                        }

                        override fun onAvailable(network: Network) = refresh()
                        override fun onLost(network: Network) = refresh()
                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: android.net.NetworkCapabilities
                        ) = refresh()
                    }
                    // Wildcard request: the bridge server has no network dependency
                    // itself, so any interface coming or going is what matters.
                    val request = NetworkRequest.Builder().build()
                    manager?.registerNetworkCallback(request, callback)
                    onDispose { manager?.unregisterNetworkCallback(callback) }
                }

                WatchBridgeDashboard(
                    state = state,
                    webhookEndpoints = webhookEndpoints,
                    authTokenConfigured = authTokenConfigured,
                    hcAuthorized = hcAuthorized,
                    onRefreshEndpoints = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            webhookEndpoints = resolveActiveEndpoints()
                        }
                    },
                    onSaveAuthToken = { rawToken ->
                        val cleaned = rawToken.trim()
                        WatchBridgeService.setAuthToken(this, cleaned)
                        authTokenConfigured = WatchBridgeService.authToken != null
                        WatchBridgeService.appendExternalLog("Updated X-Bridge-Token requirement")
                    },
                    onClearAuthToken = {
                        WatchBridgeService.clearAuthToken(this)
                        authTokenConfigured = false
                        WatchBridgeService.appendExternalLog("Cleared X-Bridge-Token (LAN-only mode)")
                    },
                    onAuthorizeHealthConnect = {
                        healthPermLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
                    },
                    onImportXmlClick = {
                        xmlFilePickerLauncher.launch("text/xml")
                    }
                )
            }
        }
    }

    private fun importAppleHealthXmlUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                WatchBridgeService.appendExternalLog("Parsing Apple Health export.xml...")
                contentResolver.openInputStream(uri)?.use { stream ->
                    val parsedRecords = AppleHealthXmlImporter.parseExportXmlStream(stream, maxRecords = 5000)
                    val inserted = healthManager.insertBatchRecords(parsedRecords)
                    WatchBridgeService.appendExternalLog(
                        "Imported $inserted historical Apple Health XML records into Health Connect",
                        xmlCountDelta = inserted
                    )
                }
            } catch (e: Exception) {
                WatchBridgeService.appendExternalLog("XML Import Error: ${e.message}")
            }
        }
    }

    /**
     * Every private IPv4 address this phone currently holds, formatted with the hotspot
     * first so the address the watch must POST to is the one shown (OPEN-06).
     *
     * The old `resolveLocalWifiIp()` returned whichever interface the enumeration
     * happened to yield first, which on a phone running its own hotspot could be an
     * address the watch cannot reach.
     */
    private fun resolveActiveEndpoints(): String = try {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                    .map { iface.name to (it.hostAddress ?: "") }
            }
            .filter { (_, ip) -> ip.isNotBlank() }
        BridgePolicy.sortAndFormatEndpoints(candidates)
    } catch (_: Exception) {
        BridgePolicy.sortAndFormatEndpoints(emptyList())
    }
}

@Composable
fun WatchBridgeDashboard(
    state: WatchTelemetryState,
    webhookEndpoints: String,
    authTokenConfigured: Boolean,
    hcAuthorized: Boolean,
    onRefreshEndpoints: () -> Unit,
    onSaveAuthToken: (String) -> Unit,
    onClearAuthToken: () -> Unit,
    onAuthorizeHealthConnect: () -> Unit,
    onImportXmlClick: () -> Unit
) {
    var tokenInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Apple Watch S9 ⇄ Pixel 9 Bridge",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (state.isBleConnected) "● BLE GATT Connected: ${state.watchName} (${state.watchMacAddress})"
                    else "◌ Scanning BLE 0x004C Continuity & 0x180D GATT...",
                    color = if (state.isBleConnected) Color(0xFF3FB950) else Color(0xFFD29922),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = "Continuity State: ${state.continuityState}",
                    color = Color(0xFF8B949E),
                    fontSize = 13.sp
                )
                Text(
                    text = "Proximity Radar: ${state.rssi} dBm (~${"%.1f".format(state.estimatedMeters)} m) | Watch Battery: ${if (state.batteryPercent >= 0) "${state.batteryPercent}%" else "--"}",
                    color = Color(0xFF8B949E),
                    fontSize = 13.sp
                )
                Text(
                    text = "Webhook endpoints (hotspot first)",
                    color = Color(0xFF8B949E),
                    fontSize = 12.sp
                )
                Text(
                    text = webhookEndpoints,
                    color = Color(0xFF58A6FF),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onRefreshEndpoints) {
                        Text("Refresh IP", color = Color(0xFF58A6FF), fontSize = 12.sp)
                    }
                }
            }
        }

        // Webhook security: the token was previously settable only by editing code,
        // which made it effectively unusable (OPEN-05).
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Webhook Security (X-Bridge-Token)",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = if (authTokenConfigured) "● Token Auth Active (X-Bridge-Token required)"
                    else "◌ Open Private LAN / Hotspot (No Token Set)",
                    color = if (authTokenConfigured) Color(0xFF3FB950) else Color(0xFFD29922),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("Shared secret token", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onSaveAuthToken(tokenInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Token", color = Color.White, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            tokenInput = ""
                            onClearAuthToken()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear Token", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onAuthorizeHealthConnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (hcAuthorized) Color(0xFF21262D) else Color(0xFF238636)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (hcAuthorized) "✓ Health Connect Ready" else "Grant Health Connect",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
            Button(
                onClick = onImportXmlClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                modifier = Modifier.weight(1f)
            ) {
                Text("Import export.xml", color = Color.White, fontSize = 12.sp)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                label = "LIVE HEART RATE",
                value = "${state.heartRateBpm} BPM",
                sub = if (state.sensorContactOnWrist) "On-Wrist Contact Verified" else "BLE 0x2A37 / Webhook",
                color = Color(0xFFFF7B72),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = "HRV (RMSSD)",
                value = "${"%.1f".format(state.hrvRmssdMs)} ms",
                sub = "1/1024s RR-Intervals",
                color = Color(0xFFD2A8FF),
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                label = "STEPS TODAY",
                value = "${state.stepsToday}",
                sub = "Health Connect StepsRecord",
                color = Color(0xFF79C0FF),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = "ACTIVE ENERGY",
                value = "${"%.0f".format(state.activeKcal)} kcal",
                sub = "ActiveCaloriesBurnedRecord",
                color = Color(0xFFFFA657),
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                label = "BLOOD OXYGEN (SpO2)",
                value = "${"%.1f".format(state.spo2Percent)}%",
                sub = "OxygenSaturationRecord",
                color = Color(0xFF56D364),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = "DISTANCE & FLOORS",
                value = "${"%.0f".format(state.distanceMeters)}m / ${"%.0f".format(state.floorsClimbed)}fl",
                sub = "CoreMotion Pedometer",
                color = Color(0xFFA5D6FF),
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                label = "SPEED & CADENCE",
                value = "${"%.1f".format(state.runningSpeedMps)} m/s | ${state.runningCadenceSpm} spm",
                sub = "BLE 0x1814 RSC Profile",
                color = Color(0xFFE3B341),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = "RESTING HR & SLEEP",
                value = "${state.restingHrBpm} BPM | ${state.sleepMinutes}m",
                sub = "Resp: ${"%.1f".format(state.respiratoryRateBrpm)} brpm",
                color = Color(0xFF7EE787),
                modifier = Modifier.weight(1f)
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Google Health Connect & Remote Control Receipts",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = "Total Committed Records: ${state.healthConnectSyncedCount} (XML Imported:${state.xmlImportedRecords})",
                    color = Color(0xFF3FB950),
                    fontSize = 12.sp
                )
                Text(
                    text = "Last Watch Control Action: ${state.lastControlAction}",
                    color = Color(0xFF58A6FF),
                    fontSize = 12.sp
                )
                HorizontalDivider(color = Color(0xFF30363D), modifier = Modifier.padding(vertical = 4.dp))
                state.recentLogs.take(8).forEach { entry ->
                    Text(
                        text = entry,
                        color = Color(0xFF8B949E),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    sub: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = Color(0xFF8B949E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(sub, color = Color(0xFF6E7681), fontSize = 10.sp)
        }
    }
}

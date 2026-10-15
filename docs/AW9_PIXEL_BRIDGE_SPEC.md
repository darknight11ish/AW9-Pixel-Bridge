# AUTONOMOUS EXECUTION SPECIFICATION: AW9-Pixel-Bridge
Target Agent: DeepSeek Flash 4.1 (Autonomous Software Engineering & GitHub Deployment Harness)
Target GitHub Account / Repo: darknight11ish/AW9-Pixel-Bridge
Target Hardware: Google Pixel 9 (Android 14/15+, API 34-35) <-> Apple Watch Series 9 (watchOS 10/11+)

---

## 0. SYSTEM DIRECTIVE & AUTONOMOUS EXECUTION RULES

You are operating as an autonomous, end-to-end Android and watchOS systems engineer. Execute every phase in this document sequentially without placeholders, stubs, or TODO comments:

1. SCAFFOLD the complete `AW9-Pixel-Bridge` repository locally matching Section 1.
2. GENERATE every configuration file, Gradle script, Android Kotlin class, Jetpack Compose UI, standalone watchOS Swift companion, JUnit test suite, GitHub Actions workflow, and documentation file verbatim from Sections 2 through 7.
3. VERIFY & DEBUG by running `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease`. If any compiler, lint, manifest, or dependency issue arises, autonomously diagnose and patch the codebase until all Gradle tasks exit with code `0`.
4. AUDIT the complete codebase against the 8-Point Static & Concurrency Review Checklist in Section 6.
5. DEPLOY the verified repository to GitHub at `darknight11ish/AW9-Pixel-Bridge`, push the `main` branch and `v1.0.0` tag, and monitor the GitHub Actions CI workflow until the sideloadable APK artifacts are published.

---

## 1. REPOSITORY ARCHITECTURE & FILE TREE

Create this exact directory structure:

~~~text
AW9-Pixel-Bridge/
├── .github/
│   └── workflows/
│       └── android-ci.yml
├── .gitignore
├── README.md
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── watchos-companion/
│   └── WatchBridgeAgent.swift
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/bridge/applewatch/
        │   │   ├── MainActivity.kt
        │   │   ├── PermissionsRationaleActivity.kt
        │   │   ├── service/
        │   │   │   └── WatchBridgeService.kt
        │   │   ├── ble/
        │   │   │   └── BlePacketParser.kt
        │   │   ├── health/
        │   │   │   ├── HealthConnectManager.kt
        │   │   │   └── AppleHealthXmlImporter.kt
        │   │   ├── control/
        │   │   │   └── PixelControlDispatcher.kt
        │   │   └── model/
        │   │       └── TelemetryModels.kt
        │   └── res/
        │       ├── drawable/
        │       │   └── ic_watch_bridge.xml
        │       └── values/
        │           └── strings.xml
        └── test/
            └── java/com/bridge/applewatch/
                └── BlePacketParserTest.kt
~~~

---

## 2. PHASE 1: ROOT & GRADLE TOOLCHAIN CONFIGURATION

### 2.1 File: `settings.gradle.kts`
~~~kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "AW9-Pixel-Bridge"
include(":app")
~~~

### 2.2 File: `build.gradle.kts`
~~~kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
~~~

### 2.3 File: `gradle.properties`
~~~properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
~~~

### 2.4 File: `.gitignore`
~~~gitignore
*.iml
.gradle
/local.properties
/.idea
.DS_Store
/build
/captures
.externalNativeBuild
.cxx
local.properties
app/build/
*.apk
*.aab
*.jks
~~~

### 2.5 File: `app/build.gradle.kts`
~~~kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bridge.applewatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bridge.applewatch"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign release builds with debug config by default so CI APKs are immediately sideloadable
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Google Health Connect SDK (Stable 1.1.0)
    implementation("androidx.health.connect:connect-client:1.1.0")

    // Jetpack Compose & Material 3
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
~~~

### 2.6 File: `app/proguard-rules.pro`
~~~pro
-keep class com.bridge.applewatch.model.** { *; }
-keep class androidx.health.connect.client.** { *; }
-dontwarn kotlin.**
~~~

### 2.7 File: `app/src/main/res/values/strings.xml`
~~~xml
<resources>
    <string name="app_name">AW9 Pixel Bridge</string>
    <string name="rationale_title">Health Connect Permissions Rationale</string>
    <string name="rationale_body">AW9 Pixel Bridge writes live and historical Apple Watch Series 9 biometrics (Heart Rate, HRV RMSSD, Steps, Distance, Active Calories, SpO2, Floors Climbed, Resting Heart Rate, Respiratory Rate, and Sleep) directly to your local Google Health Connect database on your Pixel 9. No data leaves your local network.</string>
</resources>
~~~

### 2.8 File: `app/src/main/res/drawable/ic_watch_bridge.xml`
~~~xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#58A6FF"
        android:pathData="M16,2H8L7,6.1C5.2,7.4 4,9.6 4,12s1.2,4.6 3,5.9L8,22h8l1,-4.1c1.8,-1.3 3,-3.5 3,-5.9s-1.2,-4.6 -3,-5.9L16,2zM12,18c-3.3,0 -6,-2.7 -6,-6s2.7,-6 6,-6s6,2.7 6,6S15.3,18 12,18zM12.5,8H11v5l4.2,2.5l0.8,-1.2l-3.5,-2.1V8z"/>
</vector>
~~~

### 2.9 File: `app/src/main/AndroidManifest.xml`
~~~xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Bluetooth Low Energy & Continuity Radar -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

    <!-- Local Webhook Server, Foreground Service & Device Alerts -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.VIBRATE" />

    <!-- Google Health Connect Read/Write Permissions -->
    <uses-permission android:name="android.permission.health.WRITE_HEART_RATE" />
    <uses-permission android:name="android.permission.health.READ_HEART_RATE" />
    <uses-permission android:name="android.permission.health.WRITE_HEART_RATE_VARIABILITY" />
    <uses-permission android:name="android.permission.health.READ_HEART_RATE_VARIABILITY" />
    <uses-permission android:name="android.permission.health.WRITE_STEPS" />
    <uses-permission android:name="android.permission.health.READ_STEPS" />
    <uses-permission android:name="android.permission.health.WRITE_DISTANCE" />
    <uses-permission android:name="android.permission.health.READ_DISTANCE" />
    <uses-permission android:name="android.permission.health.WRITE_ACTIVE_CALORIES_BURNED" />
    <uses-permission android:name="android.permission.health.READ_ACTIVE_CALORIES_BURNED" />
    <uses-permission android:name="android.permission.health.WRITE_OXYGEN_SATURATION" />
    <uses-permission android:name="android.permission.health.READ_OXYGEN_SATURATION" />
    <uses-permission android:name="android.permission.health.WRITE_FLOORS_CLIMBED" />
    <uses-permission android:name="android.permission.health.READ_FLOORS_CLIMBED" />
    <uses-permission android:name="android.permission.health.WRITE_RESTING_HEART_RATE" />
    <uses-permission android:name="android.permission.health.READ_RESTING_HEART_RATE" />
    <uses-permission android:name="android.permission.health.WRITE_RESPIRATORY_RATE" />
    <uses-permission android:name="android.permission.health.WRITE_SLEEP" />

    <application
        android:allowBackup="true"
        android:icon="@drawable/ic_watch_bridge"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Required by Android 14+ Health Connect to authorize sideloaded apps -->
        <activity
            android:name=".PermissionsRationaleActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />
                <category android:name="android.intent.category.HEALTH_PERMISSIONS" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.WatchBridgeService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
    </application>
</manifest>
~~~

---

## 3. PHASE 2: FULL PRODUCTION ANDROID SOURCE CODE

### 3.1 File: `app/src/main/java/com/bridge/applewatch/model/TelemetryModels.kt`
~~~kotlin
package com.bridge.applewatch.model

data class WatchTelemetryState(
    val isBleConnected: Boolean = false,
    val watchName: String = "Scanning for Apple Watch...",
    val watchMacAddress: String = "--",
    val continuityState: String = "Passive Scan Active",
    val rssi: Int = -100,
    val estimatedMeters: Double = 0.0,
    val batteryPercent: Int = -1,
    val heartRateBpm: Int = 0,
    val sensorContactOnWrist: Boolean = false,
    val hrvRmssdMs: Double = 0.0,
    val runningSpeedMps: Double = 0.0,
    val runningCadenceSpm: Int = 0,
    val stepsToday: Long = 0L,
    val distanceMeters: Double = 0.0,
    val activeKcal: Double = 0.0,
    val spo2Percent: Double = 0.0,
    val floorsClimbed: Double = 0.0,
    val restingHrBpm: Int = 0,
    val respiratoryRateBrpm: Double = 0.0,
    val sleepMinutes: Long = 0L,
    val xmlImportedRecords: Int = 0,
    val healthConnectSyncedCount: Int = 0,
    val lastControlAction: String = "None",
    val lastSyncStatus: String = "Idle — Waiting for BLE or HTTP :8484",
    val recentLogs: List<String> = emptyList()
)

data class ParsedHeartRatePacket(
    val bpm: Int,
    val contactSupported: Boolean,
    val contactDetected: Boolean,
    val energyExpendedKj: Int?,
    val rrIntervalsMs: List<Double>
)

data class ParsedRscPacket(
    val instantaneousSpeedMps: Double,
    val instantaneousCadenceSpm: Int,
    val strideLengthMeters: Double?,
    val totalDistanceMeters: Double?,
    val isRunning: Boolean
)

data class ParsedContinuityPacket(
    val frameType: Int,
    val statusFlags: Int,
    val activityLevelDescription: String,
    val isUnlockedOnWrist: Boolean
)

data class WebhookTelemetryPayload(
    val heartRate: Int = 0,
    val steps: Long = -1L,
    val distanceMeters: Double = -1.0,
    val activeCalories: Double = -1.0,
    val spo2: Double = -1.0,
    val floorsClimbed: Double = -1.0,
    val hrvMs: Double = -1.0,
    val restingHeartRate: Int = 0,
    val respiratoryRate: Double = -1.0,
    val sleepMinutes: Long = -1L,
    val battery: Int = -1,
    val controlCommand: String = ""
)
~~~

### 3.2 File: `app/src/main/java/com/bridge/applewatch/ble/BlePacketParser.kt`
~~~kotlin
package com.bridge.applewatch.ble

import com.bridge.applewatch.model.ParsedContinuityPacket
import com.bridge.applewatch.model.ParsedHeartRatePacket
import com.bridge.applewatch.model.ParsedRscPacket
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object BlePacketParser {

    /**
     * Parses Bluetooth SIG Heart Rate Measurement (0x2A37) payload.
     * Supports 8-bit/16-bit BPM, Sensor Contact bits, Energy Expended (kJ), and 1/1024s RR-Intervals.
     */
    fun parseHeartRateMeasurement(data: ByteArray): ParsedHeartRatePacket? {
        if (data.size < 2) return null

        val flags = data[0].toInt() and 0xFF
        val is16BitBpm = (flags and 0x01) != 0
        val contactDetected = (flags and 0x02) != 0
        val contactSupported = (flags and 0x04) != 0
        val energyPresent = (flags and 0x08) != 0
        val rrPresent = (flags and 0x10) != 0

        var offset = 1
        val bpm: Int
        if (is16BitBpm) {
            if (data.size < offset + 2) return null
            bpm = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
        } else {
            bpm = data[offset].toInt() and 0xFF
            offset += 1
        }

        var energyKj: Int? = null
        if (energyPresent) {
            if (data.size >= offset + 2) {
                energyKj = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
            }
        }

        val rrList = mutableListOf<Double>()
        if (rrPresent) {
            while (offset + 1 < data.size) {
                val rawRr = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
                val rrMs = (rawRr.toDouble() / 1024.0) * 1000.0
                if (rrMs in 250.0..2500.0) {
                    rrList.add(rrMs)
                }
            }
        }

        return ParsedHeartRatePacket(
            bpm = bpm,
            contactSupported = contactSupported,
            contactDetected = if (contactSupported) contactDetected else true,
            energyExpendedKj = energyKj,
            rrIntervalsMs = rrList
        )
    }

    /**
     * Parses Bluetooth SIG Running Speed and Cadence Measurement (0x2A53) payload.
     */
    fun parseRscMeasurement(data: ByteArray): ParsedRscPacket? {
        if (data.size < 4) return null
        val flags = data[0].toInt() and 0xFF
        val stridePresent = (flags and 0x01) != 0
        val totalDistancePresent = (flags and 0x02) != 0
        val isRunning = (flags and 0x04) != 0

        val rawSpeed = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        val speedMps = rawSpeed.toDouble() / 256.0
        val cadenceSpm = data[3].toInt() and 0xFF

        var offset = 4
        var strideMeters: Double? = null
        if (stridePresent && data.size >= offset + 2) {
            val rawStrideCm = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            strideMeters = rawStrideCm.toDouble() / 100.0
            offset += 2
        }

        var totalDistanceMeters: Double? = null
        if (totalDistancePresent && data.size >= offset + 4) {
            val rawDistDm = (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
            totalDistanceMeters = rawDistDm.toDouble() / 10.0
        }

        return ParsedRscPacket(
            instantaneousSpeedMps = speedMps,
            instantaneousCadenceSpm = cadenceSpm,
            strideLengthMeters = strideMeters,
            totalDistanceMeters = totalDistanceMeters,
            isRunning = isRunning
        )
    }

    /**
     * Parses Apple Continuity Manufacturer Data (0x004C), specifically Nearby Info (0x10) and Nearby Action (0x0F).
     */
    fun parseAppleContinuityManufacturerData(data: ByteArray): ParsedContinuityPacket? {
        if (data.size < 3) return null
        val frameType = data[0].toInt() and 0xFF
        val statusFlags = data[2].toInt() and 0xFF

        return when (frameType) {
            0x10 -> {
                val unlocked = (statusFlags and 0x20) != 0
                val screenOn = (statusFlags and 0x08) != 0
                val watchOnWrist = (statusFlags and 0x04) != 0
                val desc = when {
                    unlocked && screenOn -> "Active & Unlocked (Screen On)"
                    unlocked -> "Unlocked on Wrist"
                    watchOnWrist -> "Locked on Wrist (Idle)"
                    else -> "Nearby (Continuity 0x10)"
                }
                ParsedContinuityPacket(
                    frameType = frameType,
                    statusFlags = statusFlags,
                    activityLevelDescription = desc,
                    isUnlockedOnWrist = unlocked || watchOnWrist
                )
            }
            0x0F -> ParsedContinuityPacket(
                frameType = frameType,
                statusFlags = statusFlags,
                activityLevelDescription = "Nearby Action Broadcast (0x0F)",
                isUnlockedOnWrist = false
            )
            else -> ParsedContinuityPacket(
                frameType = frameType,
                statusFlags = statusFlags,
                activityLevelDescription = "Apple BLE Frame 0x${frameType.toString(16).uppercase()}",
                isUnlockedOnWrist = false
            )
        }
    }

    /**
     * Computes Root Mean Square of Successive Differences (RMSSD) in ms from RR-intervals.
     */
    fun computeRmssd(rrIntervalsMs: List<Double>): Double {
        if (rrIntervalsMs.size < 3) return 0.0
        var sumSquaredDiffs = 0.0
        var validPairs = 0
        for (i in 0 until rrIntervalsMs.size - 1) {
            val diff = rrIntervalsMs[i + 1] - rrIntervalsMs[i]
            if (kotlin.math.abs(diff) <= 350.0) {
                sumSquaredDiffs += diff * diff
                validPairs++
            }
        }
        if (validPairs == 0) return 0.0
        return (sqrt(sumSquaredDiffs / validPairs) * 10.0).roundToInt() / 10.0
    }

    /**
     * Estimates distance in meters from BLE RSSI using the Log-Distance Path Loss equation.
     */
    fun estimateDistanceMeters(rssi: Int, txPowerAtOneMeter: Int = -59, pathLossExponent: Double = 2.2): Double {
        if (rssi >= 0 || rssi < -120) return 0.0
        val raw = 10.0.pow((txPowerAtOneMeter - rssi).toDouble() / (10.0 * pathLossExponent))
        return (raw * 10.0).roundToInt() / 10.0
    }
}
~~~

### 3.3 File: `app/src/main/java/com/bridge/applewatch/health/HealthConnectManager.kt`
~~~kotlin
package com.bridge.applewatch.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.*
import com.bridge.applewatch.model.WebhookTelemetryPayload
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectManager(context: Context) {

    val client: HealthConnectClient = HealthConnectClient.getOrCreate(context)

    companion object {
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getWritePermission(FloorsClimbedRecord::class),
            HealthPermission.getReadPermission(FloorsClimbedRecord::class),
            HealthPermission.getWritePermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getWritePermission(RespiratoryRateRecord::class),
            HealthPermission.getWritePermission(SleepSessionRecord::class)
        )
    }

    suspend fun hasAllPermissions(): Boolean {
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(REQUIRED_PERMISSIONS)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun flushHeartRateSamples(samples: List<HeartRateRecord.Sample>): Int {
        if (samples.isEmpty()) return 0
        val sorted = samples.sortedBy { it.time }
        val start = sorted.first().time
        val end = sorted.last().time.plusMillis(10)
        val offset = ZoneOffset.systemDefault().rules.getOffset(start)

        val record = HeartRateRecord(
            startTime = start,
            startZoneOffset = offset,
            endTime = end,
            endZoneOffset = offset,
            samples = sorted,
            metadata = Metadata.manualEntry(clientRecordId = "aw9_hr_${start.toEpochMilli()}")
        )
        client.insertRecords(listOf(record))
        return sorted.size
    }

    suspend fun writeHrvRmssd(rmssdMs: Double, timestamp: Instant = Instant.now()) {
        if (rmssdMs !in 1.0..250.0) return
        val offset = ZoneOffset.systemDefault().rules.getOffset(timestamp)
        val record = HeartRateVariabilityRmssdRecord(
            time = timestamp,
            zoneOffset = offset,
            heartRateVariabilityMillis = rmssdMs,
            metadata = Metadata.manualEntry(clientRecordId = "aw9_hrv_${timestamp.epochSecond}")
        )
        client.insertRecords(listOf(record))
    }

    suspend fun writeTelemetryPayload(
        payload: WebhookTelemetryPayload,
        stepDelta: Long,
        now: Instant = Instant.now()
    ): Int {
        val offset = ZoneOffset.systemDefault().rules.getOffset(now)
        val windowStart = now.minusSeconds(60)
        val records = mutableListOf<Record>()

        if (payload.heartRate in 30..240) {
            records.add(
                HeartRateRecord(
                    startTime = windowStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    samples = listOf(HeartRateRecord.Sample(now, BeatsPerMinute.beatsPerMinute(payload.heartRate.toLong()))),
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_wh_hr_${now.epochSecond}")
                )
            )
        }

        if (stepDelta > 0) {
            records.add(
                StepsRecord(
                    startTime = windowStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    count = stepDelta,
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_steps_${now.epochSecond}")
                )
            )
        }

        if (payload.distanceMeters > 0.0) {
            records.add(
                DistanceRecord(
                    startTime = windowStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    distance = Length.meters(payload.distanceMeters),
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_dist_${now.epochSecond}")
                )
            )
        }

        if (payload.activeCalories > 0.0) {
            records.add(
                ActiveCaloriesBurnedRecord(
                    startTime = windowStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    energy = Energy.kilocalories(payload.activeCalories),
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_kcal_${now.epochSecond}")
                )
            )
        }

        if (payload.spo2 in 70.0..100.0) {
            records.add(
                OxygenSaturationRecord(
                    time = now,
                    zoneOffset = offset,
                    percentage = Percentage(payload.spo2),
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_spo2_${now.epochSecond}")
                )
            )
        }

        if (payload.floorsClimbed > 0.0) {
            records.add(
                FloorsClimbedRecord(
                    startTime = windowStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    floors = payload.floorsClimbed,
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_floors_${now.epochSecond}")
                )
            )
        }

        if (payload.hrvMs in 1.0..250.0) {
            records.add(
                HeartRateVariabilityRmssdRecord(
                    time = now,
                    zoneOffset = offset,
                    heartRateVariabilityMillis = payload.hrvMs,
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_wh_hrv_${now.epochSecond}")
                )
            )
        }

        if (payload.restingHeartRate in 30..150) {
            records.add(
                RestingHeartRateRecord(
                    time = now,
                    zoneOffset = offset,
                    beatsPerMinute = BeatsPerMinute.beatsPerMinute(payload.restingHeartRate.toLong()),
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_rhr_${now.epochSecond}")
                )
            )
        }

        if (payload.respiratoryRate in 4.0..60.0) {
            records.add(
                RespiratoryRateRecord(
                    time = now,
                    zoneOffset = offset,
                    rate = payload.respiratoryRate,
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_resp_${now.epochSecond}")
                )
            )
        }

        if (payload.sleepMinutes > 0L) {
            val sleepStart = now.minusSeconds(payload.sleepMinutes * 60)
            records.add(
                SleepSessionRecord(
                    startTime = sleepStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    title = "Apple Watch Series 9 Sleep",
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_sleep_${sleepStart.epochSecond}")
                )
            )
        }

        if (records.isNotEmpty()) {
            client.insertRecords(records)
        }
        return records.size
    }

    suspend fun insertBatchRecords(records: List<Record>): Int {
        if (records.isEmpty()) return 0
        var totalInserted = 0
        records.chunked(500).forEach { chunk ->
            client.insertRecords(chunk)
            totalInserted += chunk.size
        }
        return totalInserted
    }
}
~~~

### 3.4 File: `app/src/main/java/com/bridge/applewatch/health/AppleHealthXmlImporter.kt`
~~~kotlin
package com.bridge.applewatch.health

import android.util.Xml
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.*
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object AppleHealthXmlImporter {

    private val appleDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")

    fun parseExportXmlStream(inputStream: InputStream, maxRecords: Int = 5000): List<Record> {
        val records = mutableListOf<Record>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT && records.size < maxRecords) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "Record") {
                val type = parser.getAttributeValue(null, "type") ?: ""
                val startStr = parser.getAttributeValue(null, "startDate") ?: ""
                val endStr = parser.getAttributeValue(null, "endDate") ?: ""
                val valueStr = parser.getAttributeValue(null, "value") ?: ""

                val record = mapXmlNodeToRecord(type, startStr, endStr, valueStr)
                if (record != null) {
                    records.add(record)
                }
            }
            eventType = parser.next()
        }
        return records
    }

    internal fun mapXmlNodeToRecord(
        type: String,
        startDateStr: String,
        endDateStr: String,
        valueStr: String
    ): Record? {
        val startInstant = parseAppleTimestamp(startDateStr) ?: return null
        val endInstant = parseAppleTimestamp(endDateStr)?.let {
            if (it.isAfter(startInstant)) it else startInstant.plusSeconds(1)
        } ?: startInstant.plusSeconds(1)

        val offset = ZoneOffset.systemDefault().rules.getOffset(startInstant)
        val numericVal = valueStr.toDoubleOrNull() ?: 0.0
        val recordId = "xml_${type.substringAfterLast("Identifier")}_${startInstant.toEpochMilli()}"

        return when (type) {
            "HKQuantityTypeIdentifierHeartRate" -> {
                val bpm = numericVal.toLong().coerceIn(30L, 240L)
                HeartRateRecord(
                    startTime = startInstant,
                    startZoneOffset = offset,
                    endTime = endInstant,
                    endZoneOffset = offset,
                    samples = listOf(HeartRateRecord.Sample(startInstant, BeatsPerMinute.beatsPerMinute(bpm))),
                    metadata = Metadata.manualEntry(clientRecordId = recordId)
                )
            }
            "HKQuantityTypeIdentifierStepCount" -> {
                val count = numericVal.toLong()
                if (count <= 0L) null else StepsRecord(
                    startTime = startInstant,
                    startZoneOffset = offset,
                    endTime = endInstant,
                    endZoneOffset = offset,
                    count = count,
                    metadata = Metadata.manualEntry(clientRecordId = recordId)
                )
            }
            "HKQuantityTypeIdentifierDistanceWalkingRunning" -> {
                if (numericVal <= 0.0) null else DistanceRecord(
                    startTime = startInstant,
                    startZoneOffset = offset,
                    endTime = endInstant,
                    endZoneOffset = offset,
                    distance = Length.kilometers(numericVal),
                    metadata = Metadata.manualEntry(clientRecordId = recordId)
                )
            }
            "HKQuantityTypeIdentifierActiveEnergyBurned" -> {
                if (numericVal <= 0.0) null else ActiveCaloriesBurnedRecord(
                    startTime = startInstant,
                    startZoneOffset = offset,
                    endTime = endInstant,
                    endZoneOffset = offset,
                    energy = Energy.kilocalories(numericVal),
                    metadata = Metadata.manualEntry(clientRecordId = recordId)
                )
            }
            "HKQuantityTypeIdentifierOxygenSaturation" -> {
                val pct = if (numericVal <= 1.0) numericVal * 100.0 else numericVal
                if (pct !in 70.0..100.0) null else OxygenSaturationRecord(
                    time = startInstant,
                    zoneOffset = offset,
                    percentage = Percentage(pct),
                    metadata = Metadata.manualEntry(clientRecordId = recordId)
                )
            }
            "HKQuantityTypeIdentifierHeartRateVariabilitySDNN" -> {
                if (numericVal !in 1.0..250.0) null else HeartRateVariabilityRmssdRecord(
                    time = startInstant,
                    zoneOffset = offset,
                    heartRateVariabilityMillis = numericVal,
                    metadata = Metadata.manualEntry(clientRecordId = recordId)
                )
            }
            else -> null
        }
    }

    internal fun parseAppleTimestamp(raw: String): Instant? {
        return try {
            OffsetDateTime.parse(raw.trim(), appleDateFormatter).toInstant()
        } catch (_: Exception) {
            null
        }
    }
}
~~~

### 3.5 File: `app/src/main/java/com/bridge/applewatch/control/PixelControlDispatcher.kt`
~~~kotlin
package com.bridge.applewatch.control

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PixelControlDispatcher(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun executeCommand(command: String): String {
        return when (command.trim().uppercase()) {
            "FIND_MY_PIXEL", "RING" -> {
                triggerFindMyPixelAlarm()
                "Triggered Find My Pixel 9 Alarm (5s)"
            }
            "MEDIA_PLAY_PAUSE" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "Toggled Pixel 9 Media Play/Pause"
            }
            "MEDIA_NEXT" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                "Skipped to Next Track"
            }
            "MEDIA_PREV" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "Returned to Previous Track"
            }
            "VOLUME_UP" -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
                "Increased Pixel 9 Media Volume"
            }
            "VOLUME_DOWN" -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                "Decreased Pixel 9 Media Volume"
            }
            "CAMERA_SHUTTER" -> {
                triggerCameraOrVolumeShutter()
                "Dispatched Pixel 9 Camera Shutter KeyEvent"
            }
            else -> "Ignored Unknown Command: $command"
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    private fun triggerCameraOrVolumeShutter() {
        try {
            val intent = Intent("com.google.android.camera.action.SHUTTER").apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
            dispatchMediaKey(KeyEvent.KEYCODE_CAMERA)
        } catch (_: Exception) {}
    }

    private fun triggerFindMyPixelAlarm() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        vibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 800), -1)
        )
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
        ringtone?.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ringtone?.play()
        scope.launch {
            delay(5000L)
            ringtone?.stop()
        }
    }
}
~~~

### 3.6 File: `app/src/main/java/com/bridge/applewatch/service/WatchBridgeService.kt`
~~~kotlin
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
import androidx.health.connect.client.units.BeatsPerMinute
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
                        HeartRateRecord.Sample(Instant.now(), BeatsPerMinute.beatsPerMinute(parsed.bpm.toLong()))
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
~~~

### 3.7 File: `app/src/main/java/com/bridge/applewatch/PermissionsRationaleActivity.kt`
~~~kotlin
package com.bridge.applewatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0D1117))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = getString(R.string.rationale_title),
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = getString(R.string.rationale_body),
                        color = Color(0xFF8B949E),
                        fontSize = 15.sp
                    )
                    Button(onClick = { finish() }) {
                        Text("Close & Return to Health Connect")
                    }
                }
            }
        }
    }
}
~~~

### 3.8 File: `app/src/main/java/com/bridge/applewatch/MainActivity.kt`
~~~kotlin
package com.bridge.applewatch

import android.Manifest
import android.content.Intent
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
                val localIp = remember { resolveLocalWifiIp() }

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
                ) {
                    startForegroundService(Intent(this, WatchBridgeService::class.java))
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

                WatchBridgeDashboard(
                    state = state,
                    localIp = localIp,
                    hcAuthorized = hcAuthorized,
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

    private fun resolveLocalWifiIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress ?: "127.0.0.1"
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }
}

@Composable
fun WatchBridgeDashboard(
    state: WatchTelemetryState,
    localIp: String,
    hcAuthorized: Boolean,
    onAuthorizeHealthConnect: () -> Unit,
    onImportXmlClick: () -> Unit
) {
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
                    text = "Webhook Server: http://$localIp:8484/telemetry",
                    color = Color(0xFF58A6FF),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
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
                value = "${"\%.0f".format(state.distanceMeters)}m / ${"%.0f".format(state.floorsClimbed)}fl",
                sub = "CoreMotion Pedometer",
                color = Color(0xFFA5D6FF),
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                label = "SPEED & CADENCE",
                value = "${"\%.1f".format(state.runningSpeedMps)} m/s \vert{} ${state.runningCadenceSpm} spm",
                sub = "BLE 0x1814 RSC Profile",
                color = Color(0xFFE3B341),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = "RESTING HR & SLEEP",
                value = "${state.restingHrBpm} BPM \vert{}${state.sleepMinutes}m",
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
~~~

---

## 4. PHASE 3: STANDALONE WATCHOS COMPANION (`watchos-companion/WatchBridgeAgent.swift`)

### 4.1 File: `watchos-companion/WatchBridgeAgent.swift`
~~~swift
import SwiftUI
import HealthKit
import CoreBluetooth
import WatchKit

@main
struct WatchBridgeAgentApp: App {
    var body: some Scene {
        WindowGroup {
            WatchBridgeView()
        }
    }
}

final class WatchTelemetryStreamer: NSObject, ObservableObject, HKWorkoutSessionDelegate, HKLiveWorkoutBuilderDelegate, CBPeripheralManagerDelegate {
    private let healthStore = HKHealthStore()
    private var workoutSession: HKWorkoutSession?
    private var builder: HKLiveWorkoutBuilder?
    private var peripheralManager: CBPeripheralManager?
    private var hrCharacteristic: CBMutableCharacteristic?

    @Published var pixelIp: String = "192.168.1.100"
    @Published var liveBpm: Int = 0
    @Published var steps: Double = 0
    @Published var activeKcal: Double = 0
    @Published var distanceM: Double = 0
    @Published var spo2: Double = 98.0
    @Published var floors: Double = 0
    @Published var hrvMs: Double = 0
    @Published var isStreaming = false

    override init() {
        super.init()
        WKInterfaceDevice.current().isBatteryMonitoringEnabled = true
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    }

    func startStreaming() {
        let typesToShare: Set = [HKObjectType.workoutType()]
        let typesToRead: Set = [
            HKObjectType.quantityType(forIdentifier: .heartRate)!,
            HKObjectType.quantityType(forIdentifier: .stepCount)!,
            HKObjectType.quantityType(forIdentifier: .activeEnergyBurned)!,
            HKObjectType.quantityType(forIdentifier: .distanceWalkingRunning)!,
            HKObjectType.quantityType(forIdentifier: .oxygenSaturation)!,
            HKObjectType.quantityType(forIdentifier: .flightsClimbed)!,
            HKObjectType.quantityType(forIdentifier: .heartRateVariabilitySDNN)!
        ]

        healthStore.requestAuthorization(toShare: typesToShare, read: typesToRead) { success, _ in
            guard success else { return }
            let config = HKWorkoutConfiguration()
            config.activityType = .other
            config.locationType = .indoor

            do {
                self.workoutSession = try HKWorkoutSession(healthStore: self.healthStore, configuration: config)
                self.builder = self.workoutSession?.associatedWorkoutBuilder()
                self.builder?.dataSource = HKLiveWorkoutDataSource(healthStore: self.healthStore, workoutConfiguration: config)
                self.workoutSession?.delegate = self
                self.builder?.delegate = self
                self.workoutSession?.startActivity(with: Date())
                self.builder?.beginCollection(withStart: Date()) { _, _ in }
                DispatchQueue.main.async { self.isStreaming = true }
            } catch {
                print("HKWorkoutSession Error: \(error)")
            }
        }
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else { return }
        let serviceUUID = CBUUID(string: "180D")
        let charUUID = CBUUID(string: "2A37")
        hrCharacteristic = CBMutableCharacteristic(
            type: charUUID,
            properties: [.notify],
            value: nil,
            permissions: [.readable]
        )
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [hrCharacteristic!]
        peripheralManager?.add(service)
        peripheralManager?.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataLocalNameKey: "Apple Watch S9"
        ])
    }

    func workoutBuilder(_ workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOf collectedTypes: Set<HKSampleType>) {
        for type in collectedTypes {
            guard let quantityType = type as? HKQuantityType,
                  let stats = workoutBuilder.statistics(for: quantityType) else { continue }

            DispatchQueue.main.async {
                switch quantityType.identifier {
                case HKQuantityTypeIdentifier.heartRate.rawValue:
                    let bpmUnit = HKUnit.count().unitDivided(by: .minute())
                    self.liveBpm = Int(stats.mostRecentQuantity()?.doubleValue(for: bpmUnit) ?? 0)
                    self.broadcastBleHeartRate(bpm: self.liveBpm)
                case HKQuantityTypeIdentifier.stepCount.rawValue:
                    self.steps = stats.sumQuantity()?.doubleValue(for: .count()) ?? self.steps
                case HKQuantityTypeIdentifier.activeEnergyBurned.rawValue:
                    self.activeKcal = stats.sumQuantity()?.doubleValue(for: .kilocalorie()) ?? self.activeKcal
                case HKQuantityTypeIdentifier.distanceWalkingRunning.rawValue:
                    self.distanceM = stats.sumQuantity()?.doubleValue(for: .meter()) ?? self.distanceM
                case HKQuantityTypeIdentifier.flightsClimbed.rawValue:
                    self.floors = stats.sumQuantity()?.doubleValue(for: .count()) ?? self.floors
                default: break
                }
                self.postTelemetryToPixel(controlCommand: "")
            }
        }
    }

    private func broadcastBleHeartRate(bpm: Int) {
        guard let char = hrCharacteristic, bpm > 0 else { return }
        let packet = Data([0x06, UInt8(clamping: bpm)])
        peripheralManager?.updateValue(packet, for: char, onSubscribedCentrals: nil)
    }

    func postTelemetryToPixel(controlCommand: String) {
        guard let url = URL(string: "http://\(pixelIp):8484/telemetry") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let battery = Int(max(0, WKInterfaceDevice.current().batteryLevel) * 100)
        let payload: [String: Any] = [
            "heartRate": liveBpm,
            "steps": Int(steps),
            "activeCalories": activeKcal,
            "distanceMeters": distanceM,
            "spo2": spo2,
            "floorsClimbed": floors,
            "hrvMs": hrvMs,
            "battery": battery,
            "controlCommand": controlCommand
        ]
        req.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        URLSession.shared.dataTask(with: req).resume()
    }

    func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {}
    func workoutSession(_ workoutSession: HKWorkoutSession, didChangeTo toState: HKWorkoutSessionState, from fromState: HKWorkoutSessionState, date: Date) {}
    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {}
}

struct WatchBridgeView: View {
    @StateObject private var streamer = WatchTelemetryStreamer()

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                TextField("Pixel 9 IP", text: $streamer.pixelIp)
                Text("\(streamer.liveBpm) BPM")
                    .font(.system(size: 26, weight: .bold))
                    .foregroundColor(.red)
                Button(streamer.isStreaming ? "Streaming Active" : "Start Bridge") {
                    streamer.startStreaming()
                }.tint(.green)

                HStack {
                    Button("⏯") { streamer.postTelemetryToPixel(controlCommand: "MEDIA_PLAY_PAUSE") }
                    Button("⏭") { streamer.postTelemetryToPixel(controlCommand: "MEDIA_NEXT") }
                    Button("📸") { streamer.postTelemetryToPixel(controlCommand: "CAMERA_SHUTTER") }
                }
                Button("Ring Pixel 9") {
                    streamer.postTelemetryToPixel(controlCommand: "FIND_MY_PIXEL")
                }.tint(.orange)
            }
        }
    }
}
~~~

---

## 5. PHASE 4: AUTOMATED JUNIT TEST SUITE (`BlePacketParserTest.kt`)

### 5.1 File: `app/src/test/java/com/bridge/applewatch/BlePacketParserTest.kt`
~~~kotlin
package com.bridge.applewatch

import com.bridge.applewatch.ble.BlePacketParser
import org.junit.Assert.*
import org.junit.Test

class BlePacketParserTest {

    @Test
    fun `parse 8-bit Heart Rate with wrist contact and RR intervals`() {
        val packet = byteArrayOf(0x16, 0x48, 0x48, 0x03, 0x70, 0x03)
        val parsed = BlePacketParser.parseHeartRateMeasurement(packet)

        assertNotNull(parsed)
        assertEquals(72, parsed!!.bpm)
        assertTrue(parsed.contactSupported)
        assertTrue(parsed.contactDetected)
        assertEquals(2, parsed.rrIntervalsMs.size)
        assertEquals(820.3, parsed.rrIntervalsMs[0], 1.0)
        assertEquals(859.3, parsed.rrIntervalsMs[1], 1.0)
    }

    @Test
    fun `parse 16-bit Heart Rate with energy expended`() {
        val packet = byteArrayOf(0x09, 0xB9.toByte(), 0x00, 0xA4.toByte(), 0x01)
        val parsed = BlePacketParser.parseHeartRateMeasurement(packet)

        assertNotNull(parsed)
        assertEquals(185, parsed!!.bpm)
        assertEquals(420, parsed.energyExpendedKj)
    }

    @Test
    fun `compute RMSSD HRV accurately filters artifacts`() {
        val rrList = listOf(800.0, 820.0, 790.0, 830.0, 810.0)
        val rmssd = BlePacketParser.computeRmssd(rrList)
        assertEquals(28.7, rmssd, 0.2)
    }

    @Test
    fun `parse Apple Continuity 0x004C Nearby Info 0x10 frame`() {
        val data = byteArrayOf(0x10, 0x05, 0x28, 0x00, 0x00)
        val continuity = BlePacketParser.parseAppleContinuityManufacturerData(data)

        assertNotNull(continuity)
        assertEquals(0x10, continuity!!.frameType)
        assertTrue(continuity.isUnlockedOnWrist)
        assertTrue(continuity.activityLevelDescription.contains("Unlocked"))
    }

    @Test
    fun `parse BLE Running Speed and Cadence 0x2A53 packet`() {
        val packet = byteArrayOf(
            0x07,
            0x80.toByte(), 0x03,
            0xAC.toByte(),
            0x73, 0x00,
            0xD4.toByte(), 0x30, 0x00, 0x00
        )
        val rsc = BlePacketParser.parseRscMeasurement(packet)

        assertNotNull(rsc)
        assertEquals(3.5, rsc!!.instantaneousSpeedMps, 0.01)
        assertEquals(172, rsc.instantaneousCadenceSpm)
        assertEquals(1.15, rsc.strideLengthMeters!!, 0.01)
        assertEquals(1250.0, rsc.totalDistanceMeters!!, 0.1)
        assertTrue(rsc.isRunning)
    }
}
~~~

---

## 6. PHASE 5: AUTONOMOUS DEBUGGING, AUDIT & SELF-HEALING LOOP

### 6.1 Build & Test Execution Gate
Execute these commands locally and resolve any compiler, resource, or test failures before committing:
~~~bash
gradle wrapper --gradle-version 8.7
chmod +x ./gradlew
./gradlew testDebugUnitTest --stacktrace
./gradlew lintDebug --stacktrace
./gradlew assembleDebug assembleRelease --stacktrace
~~~

### 6.2 8-Point Audit & Self-Healing Checklist
1. BLE GATT Descriptor Write Serialization: Confirm `WatchBridgeService.kt` serializes all `writeDescriptor()` and `readCharacteristic()` calls through `enqueueGattOperation()` and `executeNextGattOperation()`.
2. Android 14/15 Health Connect Sideload Compatibility: Confirm `AndroidManifest.xml` binds both `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` and `android.intent.action.VIEW_PERMISSION_USAGE` (`category.HEALTH_PERMISSIONS`) to `PermissionsRationaleActivity`.
3. Health Connect Units API: Confirm `BeatsPerMinute.beatsPerMinute(...)`, `Length.meters(...)`, `Energy.kilocalories(...)`, and `Percentage(...)` match `androidx.health.connect:connect-client:1.1.0`.
4. Deduplication Metadata: Confirm every Health Connect record uses `Metadata.manualEntry(clientRecordId = ...)` so repeated syncs never duplicate data.
5. Foreground Service Type Compliance: Confirm `FOREGROUND_SERVICE_CONNECTED_DEVICE` is declared and `android:foregroundServiceType="connectedDevice"` is set on `WatchBridgeService`.
6. Streaming XML Parser: Confirm `AppleHealthXmlImporter` uses `XmlPullParser` so large `export.xml` archives never cause `OutOfMemoryError`.
7. Socket Cleanup: Confirm `ServerSocket(8484)` closes in `onDestroy()` and responds with `Access-Control-Allow-Origin: *`.
8. Unit Test Pass Gate: Confirm all 5 JUnit tests in `BlePacketParserTest` pass.

---

## 7. PHASE 6: CI/CD WORKFLOW, README & GITHUB DEPLOYMENT

### 7.1 File: `.github/workflows/android-ci.yml`
~~~yaml
name: Android CI & Sideload APK Builder

on:
  push:
    branches: [ "main" ]
    tags: [ "v*" ]
  pull_request:
    branches: [ "main" ]
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build-and-test:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout Repository
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Grant Execute Permission for Gradlew
        run: chmod +x ./gradlew

      - name: Run JUnit Test Suite (BLE, Continuity, RMSSD, RSC)
        run: ./gradlew testDebugUnitTest --stacktrace

      - name: Run Android Lint
        run: ./gradlew lintDebug --stacktrace

      - name: Assemble Sideloadable Debug & Release APKs
        run: ./gradlew assembleDebug assembleRelease --stacktrace

      - name: Upload Sideloadable Debug APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: AW9-Pixel-Bridge-debug-apk
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: error

      - name: Upload Signed Release APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: AW9-Pixel-Bridge-release-apk
          path: app/build/outputs/apk/release/*.apk
          if-no-files-found: error

      - name: Publish GitHub Release on Tag
        if: startsWith(github.ref, 'refs/tags/v')
        uses: softprops/action-gh-release@v2
        with:
          files: |
            app/build/outputs/apk/debug/*.apk
            app/build/outputs/apk/release/*.apk
          generate_release_notes: true
~~~

### 7.2 File: `README.md`
~~~markdown
# AW9-Pixel-Bridge (Apple Watch Series 9 ⇄ Google Pixel 9 Health Connect Bridge)

A sideloadable Android 14/15+ application (Kotlin & Jetpack Compose) and standalone watchOS companion agent that bridges Apple Watch Series 9 biometrics, proximity radar, and hardware remote controls directly into **Google Health Connect** on the Google Pixel 9.

## Tri-Modal Ingestion Architecture

1. **Passive Apple Continuity Sniffer (`0x004C` BLE):**
   - Decodes Apple Nearby Info (`0x10`) and Nearby Action (`0x0F`) BLE frames.
   - Provides real-time RSSI distance estimation and On-Wrist / Unlocked status detection with zero watch configuration.
2. **Active BLE GATT Biometric Client (`0x180D`, `0x1814`, `0x180F`):**
   - Connects to standard Bluetooth Heart Rate (`0x2A37`), Running Speed & Cadence (`0x2A53`), and Battery (`0x2A19`) services.
   - Extracts live BPM, Wrist Sensor Contact bit, and `1/1024s` RR-Intervals to compute live **HRV (RMSSD)** in milliseconds.
3. **Local Wi-Fi / Hotspot Webhook Server (Port `8484`) & XML Importer:**
   - Exposes `POST /telemetry`, `POST /control`, and `GET /status` on port `8484`.
   - Includes a streaming `XmlPullParser` engine to import historical Apple Health `export.xml` archives into Google Health Connect.

## Supported Google Health Connect Records
- `HeartRateRecord`
- `HeartRateVariabilityRmssdRecord`
- `StepsRecord`
- `DistanceRecord`
- `ActiveCaloriesBurnedRecord`
- `OxygenSaturationRecord` ($SpO_2$)
- `FloorsClimbedRecord`
- `RestingHeartRateRecord`
- `RespiratoryRateRecord`
- `SleepSessionRecord`

## Quick Webhook Test via `curl`
```bash
curl -X POST http://<PIXEL_9_IP>:8484/telemetry \
  -H "Content-Type: application/json" \
  -d '{"heartRate":76,"steps":4250,"activeCalories":310.5,"spo2":98.5,"floorsClimbed":6,"hrvMs":44.2,"battery":84,"controlCommand":"FIND_MY_PIXEL"}'
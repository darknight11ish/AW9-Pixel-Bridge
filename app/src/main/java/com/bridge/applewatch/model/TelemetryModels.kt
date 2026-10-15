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

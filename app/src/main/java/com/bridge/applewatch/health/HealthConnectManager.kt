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

    /**
     * Null when Health Connect is not installed, disabled, or needs an update (D-08).
     * Previously the eager `getOrCreate` threw from the constructor, which surfaced as
     * a crash before any user-facing explanation could be shown.
     */
    val client: HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }

    /** True when the on-device Health Connect provider is usable. */
    val isAvailable: Boolean get() = client != null

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
        val hc = client ?: return false
        return try {
            val granted = hc.permissionController.getGrantedPermissions()
            granted.containsAll(REQUIRED_PERMISSIONS)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun flushHeartRateSamples(samples: List<HeartRateRecord.Sample>): Int {
        val hc = client ?: return 0
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
        hc.insertRecords(listOf(record))
        return sorted.size
    }

    suspend fun writeHrvRmssd(rmssdMs: Double, timestamp: Instant = Instant.now()) {
        val hc = client ?: return
        if (rmssdMs !in 1.0..250.0) return
        val offset = ZoneOffset.systemDefault().rules.getOffset(timestamp)
        val record = HeartRateVariabilityRmssdRecord(
            time = timestamp,
            zoneOffset = offset,
            heartRateVariabilityMillis = rmssdMs,
            metadata = Metadata.manualEntry(clientRecordId = "aw9_hrv_${timestamp.epochSecond}")
        )
        hc.insertRecords(listOf(record))
    }

    suspend fun writeTelemetryPayload(
        payload: WebhookTelemetryPayload,
        stepDelta: Long,
        now: Instant = Instant.now()
    ): Int {
        val hc = client ?: return 0
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
                    samples = listOf(HeartRateRecord.Sample(now, payload.heartRate.toLong())),
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
                    beatsPerMinute = payload.restingHeartRate.toLong(),
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
            hc.insertRecords(records)
        }
        return records.size
    }

    suspend fun insertBatchRecords(records: List<Record>): Int {
        val hc = client ?: return 0
        if (records.isEmpty()) return 0
        var totalInserted = 0
        records.chunked(500).forEach { chunk ->
            hc.insertRecords(chunk)
            totalInserted += chunk.size
        }
        return totalInserted
    }
}

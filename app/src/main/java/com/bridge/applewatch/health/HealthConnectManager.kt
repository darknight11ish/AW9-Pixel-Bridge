package com.bridge.applewatch.health

import android.annotation.SuppressLint
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.units.*
import com.bridge.applewatch.model.WebhookTelemetryPayload
import com.bridge.applewatch.service.BridgePolicy
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
        /**
         * Maximum records submitted per `insertRecords` call. Health Connect rejects
         * very large batches, so a 5,000-record XML import is split into these.
         */
        const val BATCH_CHUNK_SIZE = 500

        /** Upper bound on a plausible sleep duration, in minutes (72 hours). */
        const val MAX_SLEEP_MINUTES = 72L * 60L

        /**
         * Upper bounds imposed by Health Connect's own record constructors.
         *
         * These were established by probing the resolved 1.1.0 artifact rather than from
         * documentation: `HeartRateVariabilityRmssdRecord` rejects > 200 ms,
         * `StepsRecord` > 1,000,000, and distance/energy/floors > 1,000,000 of their
         * unit. Exceeding any of them throws `IllegalArgumentException` *when the record
         * is constructed*, which means one bad value in a payload rejected the whole
         * batch and lost every valid record alongside it. Each metric is therefore
         * range-checked here, before construction.
         */
        const val MAX_HRV_MS = 200.0
        const val MAX_STEPS = 1_000_000L
        const val MAX_DISTANCE_METERS = 1_000_000.0
        const val MAX_ACTIVE_CALORIES = 1_000_000.0
        const val MAX_FLOORS = 1_000_000.0

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
        return 1
    }

    suspend fun writeHrvRmssd(rmssdMs: Double, timestamp: Instant = Instant.now()) {
        val hc = client ?: return
        // Must use the platform ceiling, not 250: HeartRateVariabilityRmssdRecord throws
        // IllegalArgumentException above 200 ms. This path has no try/catch and its only
        // caller launches it fire-and-forget, so an out-of-range value escaped as an
        // uncaught coroutine exception rather than a skipped record.
        if (rmssdMs !in 1.0..MAX_HRV_MS) return
        val offset = ZoneOffset.systemDefault().rules.getOffset(timestamp)
        val record = HeartRateVariabilityRmssdRecord(
            time = timestamp,
            zoneOffset = offset,
            heartRateVariabilityMillis = rmssdMs,
            // Millisecond granularity: this is called once per HR notification carrying
            // RR intervals, and a second-granular id made two bursts in the same second
            // share one record key, so one HRV value replaced the other (D-07).
            metadata = Metadata.manualEntry(clientRecordId = "aw9_hrv_${timestamp.toEpochMilli()}")
        )
        hc.insertRecords(listOf(record))
    }

    suspend fun writeTelemetryPayload(
        payload: WebhookTelemetryPayload,
        stepDelta: Long,
        now: Instant = Instant.now()
    ): Int {
        val hc = client ?: return 0
        val records = buildTelemetryRecords(payload, stepDelta, now)
        if (records.isNotEmpty()) {
            return insertAndCount(hc, records)
        }
        return 0
    }

    /**
     * Builds the records a telemetry payload should produce, applying every range and
     * sentinel gate.
     *
     * This is the write layer's safety surface: each guard here is what stops a
     * sentinel (`-1.0`, `0`, absent) or an out-of-range reading from becoming a
     * fabricated clinical record. It is deliberately separate from the insert so the
     * gates can be exercised directly — they were previously reachable only through a
     * live Health Connect client, which unit tests cannot construct, so the whole
     * contract went untested.
     *
     * `internal` rather than public: same module, so tests can call it, but not part of
     * the app's surface.
     */
    internal fun buildTelemetryRecords(
        payload: WebhookTelemetryPayload,
        stepDelta: Long,
        now: Instant = Instant.now()
    ): List<Record> {
        val offset = ZoneOffset.systemDefault().rules.getOffset(now)
        val windowStart = now.minusSeconds(60)
        val records = mutableListOf<Record>()

        // Millisecond-granular IDs: second-granular IDs collapsed two readings taken
        // within the same second into one record (D-07).
        val stamp = now.toEpochMilli()

        if (payload.heartRate in 30..240) {
            records.add(
                HeartRateRecord(
                    startTime = windowStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    samples = listOf(HeartRateRecord.Sample(now, payload.heartRate.toLong())),
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_wh_hr_$stamp")
                )
            )
        }

        if (stepDelta in 1..MAX_STEPS) {
            // Steps are interval data. Bucketing into a fixed hourly window keeps
            // Health Connect aggregation meaningful instead of turning every delta
            // into a 60-second spike (D-07b).
            //
            // The record id must still be unique per delta. Health Connect keys records
            // by `clientRecordId`, so using the *window* as the id made every delta in
            // the same hour collide, and only one of them survived (replace) or was
            // kept (ignore) — the hour never accumulated its steps. Each delta is
            // therefore written as its own interval inside the hour, which Health
            // Connect sums, while the window keeps the aggregation shape.
            val bucketStart = Instant.ofEpochSecond(
                BridgePolicy.stepWindowStart(now.epochSecond)
            )
            val bucketEnd = bucketStart.plusSeconds(BridgePolicy.STEP_WINDOW_SECONDS)
            val bucketOffset = ZoneOffset.systemDefault().rules.getOffset(bucketStart)
            val stepWindowStamp = now.toEpochMilli()
            records.add(
                StepsRecord(
                    startTime = bucketStart,
                    startZoneOffset = bucketOffset,
                    endTime = bucketEnd,
                    endZoneOffset = bucketOffset,
                    count = stepDelta,
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_steps_${stepWindowStamp}_$stepDelta")
                )
            )
        }

        if (payload.distanceMeters > 0.0 && payload.distanceMeters <= MAX_DISTANCE_METERS) {
            records.add(
                DistanceRecord(
                    startTime = windowStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    distance = Length.meters(payload.distanceMeters),
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_dist_$stamp")
                )
            )
        }

        if (payload.activeCalories > 0.0 && payload.activeCalories <= MAX_ACTIVE_CALORIES) {
            records.add(
                ActiveCaloriesBurnedRecord(
                    startTime = windowStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    energy = Energy.kilocalories(payload.activeCalories),
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_kcal_$stamp")
                )
            )
        }

        if (payload.spo2 in 70.0..100.0) {
            records.add(
                OxygenSaturationRecord(
                    time = now,
                    zoneOffset = offset,
                    percentage = Percentage(payload.spo2),
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_spo2_$stamp")
                )
            )
        }

        if (payload.floorsClimbed > 0.0 && payload.floorsClimbed <= MAX_FLOORS) {
            records.add(
                FloorsClimbedRecord(
                    startTime = windowStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    floors = payload.floorsClimbed,
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_floors_$stamp")
                )
            )
        }

        if (payload.hrvMs in 1.0..MAX_HRV_MS) {
            records.add(
                HeartRateVariabilityRmssdRecord(
                    time = now,
                    zoneOffset = offset,
                    heartRateVariabilityMillis = payload.hrvMs,
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_wh_hrv_$stamp")
                )
            )
        }

        if (payload.restingHeartRate in 30..150) {
            records.add(
                RestingHeartRateRecord(
                    time = now,
                    zoneOffset = offset,
                    beatsPerMinute = payload.restingHeartRate.toLong(),
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_rhr_$stamp")
                )
            )
        }

        if (payload.respiratoryRate in 4.0..60.0) {
            records.add(
                RespiratoryRateRecord(
                    time = now,
                    zoneOffset = offset,
                    rate = payload.respiratoryRate,
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_resp_$stamp")
                )
            )
        }

        // Bounded on purpose: an unbounded value overflows `sleepMinutes * 60`
        // (Long.MAX_VALUE * 60 == -60), which would place the session's start *after*
        // its end and fail the whole insert. 72 hours is longer than any real sleep.
        if (payload.sleepMinutes in 1..MAX_SLEEP_MINUTES) {
            val sleepStart = now.minusSeconds(payload.sleepMinutes * 60)
            records.add(
                SleepSessionRecord(
                    startTime = sleepStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    title = "Sleep (imported by AW9 Pixel Bridge)",
                    metadata = Metadata.manualEntry(clientRecordId = "aw9_sleep_${sleepStart.epochSecond}")
                )
            )
        }

        return records
    }

    /**
     * Inserts a batch and returns the number of records Health Connect actually
     * accepted. A bare `records.size` over-reports when the provider upserts or
     * deduplicates (D-07).
     */
    private suspend fun insertAndCount(hc: HealthConnectClient, records: List<Record>): Int =
        if (records.isEmpty()) 0 else hc.insertRecords(records).committedCount(records.size)

    /**
     * Splits a batch into the chunks Health Connect accepts in one `insertRecords` call.
     *
     * Pure and side-effect free so the chunk boundaries can be asserted directly
     * (OPEN-08), rather than only being reachable through a live client. Order is
     * preserved, so a caller that logs or retries by chunk index sees a stable sequence.
     */
    internal fun splitIntoBatches(
        records: List<Record>,
        chunkSize: Int = BATCH_CHUNK_SIZE
    ): List<List<Record>> = if (records.isEmpty()) emptyList() else records.chunked(chunkSize)

    suspend fun insertBatchRecords(records: List<Record>): Int {
        val hc = client ?: return 0
        if (records.isEmpty()) return 0
        var totalInserted = 0
        var failedChunks = 0
        // Per-chunk try/catch: the first exception used to propagate out of the loop, so
        // every later chunk was never attempted and the already-committed count was lost
        // with the exception — a 5,000-record import failing on chunk 3 left 1,000 records
        // in Health Connect while the UI reported a total failure and no count.
        splitIntoBatches(records).forEachIndexed { index, chunk ->
            try {
                totalInserted += insertAndCount(hc, chunk)
            } catch (e: Exception) {
                failedChunks += 1
                if (failedChunks == 1) {
                    // Report the first failure; a permanent problem would otherwise log
                    // once per remaining chunk.
                    lastBatchError = "chunk ${index + 1} failed: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
        // The caller needs to know a partial import happened; exposing it beats returning
        // a bare count that silently omits the failures.
        lastFailedChunkCount = failedChunks
        return totalInserted
    }

    /**
     * Number of chunks that failed during the most recent [insertBatchRecords] call.
     * Zero means the whole batch was attempted and every chunk was accepted.
     */
    @Volatile
    var lastFailedChunkCount: Int = 0
        private set

    /** Human-readable description of the first chunk failure, or null. */
    @Volatile
    var lastBatchError: String? = null
        private set
}

/**
 * How many record ids the provider returned for this insert.
 *
 * `InsertRecordsResponse.recordIdsList` is annotated `@RestrictTo`, i.e. it is not part
 * of the stable public contract (OPEN-04), so it is used in exactly one place instead of
 * leaking through the manager:
 *
 * - [@SuppressLint] documents the deliberate non-contract use, so the lint gate stays
 *   meaningful for every *other* RestrictedApi reference in this module.
 * - [runCatching] means a future `connect-client` that removes or renames the accessor
 *   degrades to [fallback] (the submitted count) instead of crashing the service
 *   mid-insert. Callers must pass the submitted count so the fallback cannot silently
 *   report 0.
 *
 * **Honest limitation:** on success this list carries one id per *submitted* record,
 * including records that replaced an existing `clientRecordId`, so it cannot distinguish
 * an insert from an upsert. The returned count is therefore "records accepted", not
 * "rows newly created" — the dashboard counter accumulates replacements too. Fully
 * closing D-07 would need local bookkeeping of already-written ids.
 */
@SuppressLint("RestrictedApi")
private fun InsertRecordsResponse.committedCount(fallback: Int): Int =
    runCatching { recordIdsList.size }.getOrDefault(fallback)

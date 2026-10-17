package com.bridge.applewatch.health

import android.annotation.SuppressLint
import android.content.Context
import android.health.connect.HealthConnectException
import kotlinx.coroutines.delay
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.units.*
import com.bridge.applewatch.model.WebhookTelemetryPayload
import com.bridge.applewatch.service.BridgePolicy
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectManager(private val context: Context) : HealthDataSink {

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
    override val isAvailable: Boolean get() = client != null

    companion object {
        /**
         * Maximum records submitted per `insertRecords` call. Health Connect rejects
         * very large batches, so a 5,000-record XML import is split into these.
         */
        const val BATCH_CHUNK_SIZE = 500

        /** Upper bound on a plausible sleep duration, in minutes (72 hours). */
        const val MAX_SLEEP_MINUTES = HealthMetricRanges.MAX_SLEEP_MINUTES

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
        const val MAX_HRV_MS = HealthMetricRanges.MAX_HRV_MS
        const val MAX_STEPS = HealthMetricRanges.MAX_STEPS
        const val MAX_DISTANCE_METERS = HealthMetricRanges.MAX_DISTANCE_METERS
        const val MAX_ACTIVE_CALORIES = HealthMetricRanges.MAX_ACTIVE_CALORIES
        const val MAX_FLOORS = HealthMetricRanges.MAX_FLOORS

        /** Pause before retrying a rate-limited chunk. */
        internal const val RATE_LIMIT_BACKOFF_MS = 1_500L

        /** Read-back sentinel: this app holds only WRITE permission for the type. */
        const val READ_NOT_GRANTED = -1

        /** Read-back sentinel: the read was attempted and failed for another reason. */
        const val READ_FAILED = -2

        /** Cap on read-back volume, so a huge history cannot exhaust memory. */
        private const val MAX_READ_RECORDS = 50_000

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

    /**
     * Metadata for a record this app measured or ingested from a device.
     *
     * Every write used `Metadata.manualEntry`, which sets `recordingMethod =
     * MANUAL_ENTRY`. Health Connect exposes that to the user and to any app that reads the
     * data, and readers commonly distrust or filter hand-entered records to prevent typed-in
     * step totals. These values are not typed in, so they are labelled as automatically
     * recorded from a watch.
     */
    private fun deviceRecord(clientRecordId: String) = Metadata.autoRecorded(
        Device(
            type = Device.TYPE_WATCH,
            manufacturer = "Apple",
            model = "Apple Watch"
        ),
        clientRecordId
    )

    suspend fun hasAllPermissions(): Boolean {
        val hc = client ?: return false
        return try {
            val granted = hc.permissionController.getGrantedPermissions()
            granted.containsAll(REQUIRED_PERMISSIONS)
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun flushHeartRateSamples(samples: List<HeartRateRecord.Sample>): Int {
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
            metadata = deviceRecord("aw9_hr_${start.toEpochMilli()}")
        )
        hc.insertRecords(listOf(record))
        return 1
    }

    override suspend fun writeHrvRmssd(rmssdMs: Double, timestamp: Instant) {
        val hc = client ?: return
        // Must use the platform ceiling, not 250: HeartRateVariabilityRmssdRecord throws
        // IllegalArgumentException above 200 ms. This path has no try/catch and its only
        // caller launches it fire-and-forget, so an out-of-range value escaped as an
        // uncaught coroutine exception rather than a skipped record.
        if (!HealthMetricRanges.isValidHrv(rmssdMs)) return
        val offset = ZoneOffset.systemDefault().rules.getOffset(timestamp)
        val record = HeartRateVariabilityRmssdRecord(
            time = timestamp,
            zoneOffset = offset,
            heartRateVariabilityMillis = rmssdMs,
            // Millisecond granularity: this is called once per HR notification carrying
            // RR intervals, and a second-granular id made two bursts in the same second
            // share one record key, so one HRV value replaced the other (D-07).
            metadata = deviceRecord("aw9_hrv_${timestamp.toEpochMilli()}")
        )
        hc.insertRecords(listOf(record))
    }

    override suspend fun writeTelemetryPayload(
        payload: WebhookTelemetryPayload,
        stepDelta: Long,
        now: Instant
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

        if (HealthMetricRanges.isValidHeartRate(payload.heartRate)) {
            records.add(
                HeartRateRecord(
                    startTime = windowStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    samples = listOf(HeartRateRecord.Sample(now, payload.heartRate.toLong())),
                    metadata = deviceRecord("aw9_wh_hr_$stamp")
                )
            )
        }

        if (HealthMetricRanges.isValidStepDelta(stepDelta)) {
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
                    metadata = deviceRecord("aw9_steps_${stepWindowStamp}_$stepDelta")
                )
            )
        }

        if (HealthMetricRanges.isValidDistance(payload.distanceMeters)) {
            records.add(
                DistanceRecord(
                    startTime = windowStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    distance = Length.meters(payload.distanceMeters),
                    metadata = deviceRecord("aw9_dist_$stamp")
                )
            )
        }

        if (HealthMetricRanges.isValidActiveCalories(payload.activeCalories)) {
            records.add(
                ActiveCaloriesBurnedRecord(
                    startTime = windowStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    energy = Energy.kilocalories(payload.activeCalories),
                    metadata = deviceRecord("aw9_kcal_$stamp")
                )
            )
        }

        if (HealthMetricRanges.isValidSpo2(payload.spo2)) {
            records.add(
                OxygenSaturationRecord(
                    time = now,
                    zoneOffset = offset,
                    percentage = Percentage(payload.spo2),
                    metadata = deviceRecord("aw9_spo2_$stamp")
                )
            )
        }

        if (HealthMetricRanges.isValidFloors(payload.floorsClimbed)) {
            records.add(
                FloorsClimbedRecord(
                    startTime = windowStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    floors = payload.floorsClimbed,
                    metadata = deviceRecord("aw9_floors_$stamp")
                )
            )
        }

        if (HealthMetricRanges.isValidHrv(payload.hrvMs)) {
            records.add(
                HeartRateVariabilityRmssdRecord(
                    time = now,
                    zoneOffset = offset,
                    heartRateVariabilityMillis = payload.hrvMs,
                    metadata = deviceRecord("aw9_wh_hrv_$stamp")
                )
            )
        }

        if (HealthMetricRanges.isValidRestingHeartRate(payload.restingHeartRate)) {
            records.add(
                RestingHeartRateRecord(
                    time = now,
                    zoneOffset = offset,
                    beatsPerMinute = payload.restingHeartRate.toLong(),
                    metadata = deviceRecord("aw9_rhr_$stamp")
                )
            )
        }

        if (HealthMetricRanges.isValidRespiratoryRate(payload.respiratoryRate)) {
            records.add(
                RespiratoryRateRecord(
                    time = now,
                    zoneOffset = offset,
                    rate = payload.respiratoryRate,
                    metadata = deviceRecord("aw9_resp_$stamp")
                )
            )
        }

        // Bounded on purpose: an unbounded value overflows `sleepMinutes * 60`
        // (Long.MAX_VALUE * 60 == -60), which would place the session's start *after*
        // its end and fail the whole insert. 72 hours is longer than any real sleep.
        if (HealthMetricRanges.isValidSleepMinutes(payload.sleepMinutes)) {
            val sleepStart = now.minusSeconds(payload.sleepMinutes * 60)
            records.add(
                SleepSessionRecord(
                    startTime = sleepStart,
                    startZoneOffset = offset,
                    endTime = now,
                    endZoneOffset = offset,
                    title = "Sleep (imported by AW9 Pixel Bridge)",
                    metadata = deviceRecord("aw9_sleep_${sleepStart.epochSecond}")
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


    /**
     * Inserts one chunk, retrying once after a delay if the provider rate-limits us.
     *
     * A real multi-year Apple export is thousands of records, i.e. many consecutive
     * `insertRecords` RPCs in a tight loop. Health Connect enforces a sliding-window rate
     * limit per app, so later chunks can come back `ERROR_RATE_LIMIT_EXCEEDED`. Without this
     * the import would simply stop part-way and report the remainder as failed.
     */
    private suspend fun insertChunkWithRateLimitRetry(
        hc: HealthConnectClient,
        chunk: List<Record>
    ): Int = try {
        insertAndCount(hc, chunk)
    } catch (e: HealthConnectException) {
        if (e.errorCode == HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED) {
            delay(RATE_LIMIT_BACKOFF_MS)
            insertAndCount(hc, chunk)
        } else {
            throw e
        }
    }


    /**
     * Reads back how many records of each type Health Connect actually stored since [since].
     *
     * This exists because every previous verification stopped at "the insert call returned".
     * The write path has been proven to submit records, but never that the provider stored
     * them, and the two are not the same thing (an upsert replaces, a rejected record
     * disappears silently). Used by the `GET /verify-hc` diagnostic.
     *
     * A count of `-1` means that type could not be read, which is itself useful: it usually
     * means the matching READ permission was not granted.
     */

    /**
     * Reads every record of [type] in the window, following `pageToken`.
     *
     * `readRecords` returns a single page (default page size 1000). Reading only the first
     * page silently under-reports, which is exactly the failure mode this diagnostic exists
     * to catch: an import of 3000 records was being reported as 1000.
     */
    private suspend fun <T : Record> readAllPages(
        hc: HealthConnectClient,
        type: kotlin.reflect.KClass<T>,
        filter: TimeRangeFilter
    ): List<T> {
        val all = mutableListOf<T>()
        var token: String? = null
        do {
            val response = hc.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = filter,
                    pageSize = 1_000,
                    pageToken = token
                )
            )
            all += response.records
            token = response.pageToken
        } while (token != null && all.size < MAX_READ_RECORDS)
        return all
    }

    override suspend fun countRecordsSince(since: Instant): Map<String, Int> {
        val hc = client ?: return emptyMap()
        val filter = TimeRangeFilter.between(since, Instant.now())
        val counts = LinkedHashMap<String, Int>()

        // The app deliberately holds only WRITE permission for some record types (sleep and
        // respiratory rate), so probing a read for those always fails. Report that as
        // "not readable" rather than as an error, so the diagnostic does not cry wolf.
        val granted = runCatching { hc.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())

        suspend fun <T : Record> count(label: String, type: kotlin.reflect.KClass<T>) {
            val readPermission = HealthPermission.getReadPermission(type)
            if (readPermission !in granted) {
                counts[label] = READ_NOT_GRANTED
                return
            }
            counts[label] = runCatching {
                readAllPages(hc, type, filter).size
            }.getOrDefault(READ_FAILED)
        }

        count("heartRate", HeartRateRecord::class)
        count("hrvRmssd", HeartRateVariabilityRmssdRecord::class)
        count("steps", StepsRecord::class)
        count("distance", DistanceRecord::class)
        count("activeCalories", ActiveCaloriesBurnedRecord::class)
        count("oxygenSaturation", OxygenSaturationRecord::class)
        count("floorsClimbed", FloorsClimbedRecord::class)
        count("restingHeartRate", RestingHeartRateRecord::class)
        count("respiratoryRate", RespiratoryRateRecord::class)
        count("sleep", SleepSessionRecord::class)
        return counts
    }


    /**
     * Reads back the *values* the provider stored, not just how many records there are.
     *
     * A count proves a record exists; it does not prove the record contains the right number.
     * The two are different failure modes, and the step path specifically needs the value
     * checked: a cumulative counter of 5200 then 5300 must store a single record with
     * count = 100, and a count-only check cannot tell that apart from one holding 5300.
     */
    override suspend fun totalsSince(since: Instant): Map<String, Double> {
        val hc = client ?: return emptyMap()
        val filter = TimeRangeFilter.between(since, Instant.now())
        val totals = LinkedHashMap<String, Double>()

        suspend fun <T : Record> read(type: kotlin.reflect.KClass<T>): List<T> =
            runCatching { readAllPages(hc, type, filter) }.getOrDefault(emptyList())

        totals["steps"] = read(StepsRecord::class).sumOf { it.count }.toDouble()
        totals["distanceMeters"] = read(DistanceRecord::class).sumOf { it.distance.inMeters }
        totals["activeKcal"] = read(ActiveCaloriesBurnedRecord::class).sumOf { it.energy.inKilocalories }
        totals["floorsClimbed"] = read(FloorsClimbedRecord::class).sumOf { it.floors }
        totals["sleepMinutes"] = read(SleepSessionRecord::class).sumOf {
            java.time.Duration.between(it.startTime, it.endTime).toMinutes().toDouble()
        }
        return totals
    }

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
                totalInserted += insertChunkWithRateLimitRetry(hc, chunk)
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

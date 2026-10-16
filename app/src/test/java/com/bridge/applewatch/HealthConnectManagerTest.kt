package com.bridge.applewatch

import android.app.Application
import android.content.Context
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
import androidx.test.core.app.ApplicationProvider
import com.bridge.applewatch.health.HealthConnectManager
import com.bridge.applewatch.model.WebhookTelemetryPayload
import com.bridge.applewatch.service.BridgePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

/**
 * Robolectric coverage for the Health Connect write layer (OPEN-08).
 *
 * Robolectric cannot talk to a real Health Connect provider, so `client` resolves to
 * null here and the insert paths cannot be exercised end to end. That constraint is
 * turned into the subject of several assertions (every write must degrade to a no-op
 * rather than throwing when the provider is absent), and the logic that used to be
 * reachable only through a live client — batch splitting and record-id construction —
 * is asserted directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HealthConnectManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private val now: Instant = Instant.parse("2026-10-16T09:15:00Z")
    private val offset: ZoneOffset = ZoneOffset.UTC

    private fun metadata(id: String) = Metadata.manualEntry(clientRecordId = id)

    private fun steps(count: Long, id: String = "aw9_steps_1") = StepsRecord(
        startTime = now,
        startZoneOffset = offset,
        endTime = now.plusSeconds(3600),
        endZoneOffset = offset,
        count = count,
        metadata = metadata(id)
    )

    // ---------------------------------------------------- provider availability

    @Test
    fun `an unavailable provider yields a manager with no client`() {
        val manager = HealthConnectManager(context)
        // This is the D-08 guarantee: constructing the manager must not throw when
        // Health Connect is missing, and every write must then be a no-op.
        assertNull(manager.client)
        assertFalse(manager.isAvailable)
    }

    @Test
    fun `every write degrades to a no-op without a provider`() = runBlocking {
        val manager = HealthConnectManager(context)

        assertFalse("permissions cannot be granted without a provider", manager.hasAllPermissions())
        assertEquals(0, manager.insertBatchRecords(listOf(steps(1200))))
        assertEquals(0, manager.flushHeartRateSamples(listOf(HeartRateRecord.Sample(now, 72L))))
        assertEquals(0, manager.flushHeartRateSamples(emptyList()))
        manager.writeHrvRmssd(45.0, now)
        assertEquals(
            0,
            manager.writeTelemetryPayload(
                com.bridge.applewatch.model.WebhookTelemetryPayload(
                    heartRate = 72,
                    steps = 9000,
                    spo2 = 98.0
                ),
                stepDelta = 250L,
                now = now
            )
        )
    }

    // ----------------------------------------------------------- batch chunking

    @Test
    fun `batch splitting honours the 500-record chunk boundary`() {
        val manager = HealthConnectManager(context)
        val records: List<Record> = (1..1200).map { steps(it.toLong(), "id_$it") }

        val batches = manager.splitIntoBatches(records)

        assertEquals("1200 records must split into 500 + 500 + 200", 3, batches.size)
        assertEquals(listOf(500, 500, 200), batches.map { it.size })
        // Concatenating the batches must reproduce the input in order, or a retry by
        // chunk index would silently reshuffle records.
        assertEquals(records, batches.flatten())
    }

    @Test
    fun `batch splitting boundaries are exact and order-preserving`() {
        val manager = HealthConnectManager(context)

        assertTrue("an empty batch yields no chunks", manager.splitIntoBatches(emptyList()).isEmpty())

        val exactlyOneChunk: List<Record> =
            (1..HealthConnectManager.BATCH_CHUNK_SIZE).map { steps(it.toLong(), "id_$it") }
        assertEquals(1, manager.splitIntoBatches(exactlyOneChunk).size)

        val oneOver: List<Record> =
            (1..(HealthConnectManager.BATCH_CHUNK_SIZE + 1)).map { steps(it.toLong(), "id_$it") }
        assertEquals(listOf(500, 1), manager.splitIntoBatches(oneOver).map { it.size })
    }

    @Test
    fun `the chunk size stays within what one insert call can carry`() {
        // Guard against a well-meaning bump: Health Connect rejects very large batches,
        // and the XML importer feeds up to 5,000 records through this.
        assertTrue(
            "chunk size must stay small enough for one insertRecords call",
            HealthConnectManager.BATCH_CHUNK_SIZE in 100..1000
        )
    }

    // -------------------------------------------------------- client record ids

    @Test
    fun `distinct client record ids survive round-trip through the record types`() {
        // The importer and webhook paths both build ids of the form
        // "<prefix>_<millis>". Two records written in the same millisecond with the
        // same id are the provider's dedup case (D-07), so the ids must differ whenever
        // the timestamps differ.
        val first = steps(10, "aw9_steps_${now.toEpochMilli()}")
        val second = steps(10, "aw9_steps_${now.plusMillis(1).toEpochMilli()}")

        val firstId = first.metadata.clientRecordId
        val secondId = second.metadata.clientRecordId
        assertNotNull(firstId)
        assertNotNull(secondId)
        assertFalse("millisecond-granular ids must not collide", firstId == secondId)

        // The id must be readable back off the record, which is what makes idempotent
        // re-import possible at all.
        assertEquals("aw9_steps_${now.toEpochMilli()}", first.metadata.clientRecordId)
    }

    @Test
    fun `unsigned metadata carries no client record id`() {
        val record = HeartRateRecord(
            startTime = now,
            startZoneOffset = offset,
            endTime = now.plusSeconds(60),
            endZoneOffset = offset,
            samples = listOf(HeartRateRecord.Sample(now, 70L)),
            metadata = Metadata.manualEntry()
        )
        // Documents the contract the manager relies on: every managed write passes an
        // explicit id, so a null here would be a developer error, not a runtime one.
        assertNull(record.metadata.clientRecordId)
    }

    // ------------------------------------------------------------ permission set

    @Test
    fun `the required permission set covers every written record type`() {
        val permissions = HealthConnectManager.REQUIRED_PERMISSIONS

        assertTrue("the gate must require at least one permission", permissions.isNotEmpty())
        assertEquals(
            "every record type the manager can write needs a permission request",
            18,
            permissions.size
        )
        // Permissions are plain strings of the form
        // "android.permission.health.WRITE_<RECORD>", so both directions must be present
        // and no entry may be blank or duplicated.
        assertEquals(
            "a duplicated permission would misreport readiness",
            permissions.size,
            permissions.distinct().size
        )
        assertTrue(
            "every entry must be a well-formed health permission string",
            permissions.all { it.startsWith("android.permission.health.") }
        )
        val writes = permissions.count { it.contains("WRITE_") }
        val reads = permissions.count { it.contains("READ_") }
        assertTrue("write permissions must be requested", writes >= 9)
        assertTrue("read permissions must be requested", reads >= 8)
        assertEquals("every entry is either a read or a write", permissions.size, writes + reads)
    }

    @Test
    fun `record types the manager writes are all constructible with a manual id`() {
        // Compile-and-construct smoke coverage: these are the exact record types the
        // write paths build, so a signature change in connect-client fails here rather
        // than on a device.
        val records: List<Record> = listOf(
            HeartRateRecord(
                startTime = now, startZoneOffset = offset,
                endTime = now.plusSeconds(60), endZoneOffset = offset,
                samples = listOf(HeartRateRecord.Sample(now, 72L)),
                metadata = metadata("hr")
            ),
            StepsRecord(
                startTime = now, startZoneOffset = offset,
                endTime = now.plusSeconds(60), endZoneOffset = offset,
                count = 100, metadata = metadata("steps")
            ),
            DistanceRecord(
                startTime = now, startZoneOffset = offset,
                endTime = now.plusSeconds(60), endZoneOffset = offset,
                distance = Length.kilometers(2.5), metadata = metadata("dist")
            ),
            ActiveCaloriesBurnedRecord(
                startTime = now, startZoneOffset = offset,
                endTime = now.plusSeconds(60), endZoneOffset = offset,
                energy = Energy.kilocalories(310.5), metadata = metadata("kcal")
            ),
            OxygenSaturationRecord(
                time = now, zoneOffset = offset,
                percentage = Percentage(98.0), metadata = metadata("spo2")
            ),
            FloorsClimbedRecord(
                startTime = now, startZoneOffset = offset,
                endTime = now.plusSeconds(60), endZoneOffset = offset,
                floors = 6.0, metadata = metadata("floors")
            ),
            HeartRateVariabilityRmssdRecord(
                time = now, zoneOffset = offset,
                heartRateVariabilityMillis = 44.2, metadata = metadata("hrv")
            ),
            RestingHeartRateRecord(
                time = now, zoneOffset = offset,
                beatsPerMinute = 52L, metadata = metadata("rhr")
            ),
            SleepSessionRecord(
                startTime = now.minusSeconds(27_300), startZoneOffset = offset,
                endTime = now, endZoneOffset = offset,
                title = "Apple Watch Series 9 Sleep", metadata = metadata("sleep")
            )
        )

        assertEquals(9, records.size)
        records.forEach { assertNotNull("every record must carry a metadata id", it.metadata) }
        // All nine share the common Record supertype, which is what insertBatchRecords
        // accepts, so they can be chunked and submitted together.
        assertEquals(
            9,
            HealthConnectManager(context).splitIntoBatches(records, chunkSize = 4)
                .sumOf { it.size }
        )
    }

    // ------------------------------------------------------- write gates (TW-1/TW-7)

    /**
     * The ten guards in `buildTelemetryRecords` are the last line between a sentinel
     * value and a fabricated clinical record. They used to be reachable only through a
     * live Health Connect client, which a JVM test cannot construct — so deleting every
     * one of them left the suite green. These tests pin each boundary.
     */
    @Test
    fun `the default payload writes nothing at all`() {
        val records = HealthConnectManager(context)
            .buildTelemetryRecords(WebhookTelemetryPayload(), stepDelta = 0L, now = now)

        assertEquals("an all-sentinel payload must produce no records", 0, records.size)
    }

    @Test
    fun `heart rate is gated to the 30 to 240 range`() {
        fun records(bpm: Int) = HealthConnectManager(context)
            .buildTelemetryRecords(WebhookTelemetryPayload(heartRate = bpm), 0L, now)
            .filterIsInstance<HeartRateRecord>()

        assertEquals("0 is the 'no reading' sentinel", 0, records(0).size)
        assertEquals(0, records(29).size)
        assertEquals(1, records(30).size)
        assertEquals(1, records(240).size)
        assertEquals(0, records(241).size)
    }

    @Test
    fun `spo2 is gated to the 70 to 100 range inclusively`() {
        fun records(spo2: Double) = HealthConnectManager(context)
            .buildTelemetryRecords(WebhookTelemetryPayload(spo2 = spo2), 0L, now)
            .filterIsInstance<OxygenSaturationRecord>()

        assertEquals("-1.0 is the 'no reading' sentinel", 0, records(-1.0).size)
        assertEquals(0, records(69.9).size)
        assertEquals(1, records(70.0).size)
        assertEquals(1, records(100.0).size)
        assertEquals(0, records(100.1).size)
    }

    @Test
    fun `hrv is gated to the 1 to 250 range`() {
        fun records(hrv: Double) = HealthConnectManager(context)
            .buildTelemetryRecords(WebhookTelemetryPayload(hrvMs = hrv), 0L, now)
            .filterIsInstance<HeartRateVariabilityRmssdRecord>()

        assertEquals(0, records(-1.0).size)
        assertEquals(0, records(0.9).size)
        assertEquals(1, records(1.0).size)
        // 200 ms is Health Connect's own ceiling: asking for more throws
        // IllegalArgumentException at construction, which would fail the entire batch.
        assertEquals(1, records(HealthConnectManager.MAX_HRV_MS).size)
        assertEquals(0, records(HealthConnectManager.MAX_HRV_MS + 0.1).size)
    }

    @Test
    fun `resting heart rate and respiratory rate are gated`() {
        fun resting(bpm: Int) = HealthConnectManager(context)
            .buildTelemetryRecords(WebhookTelemetryPayload(restingHeartRate = bpm), 0L, now)
            .filterIsInstance<RestingHeartRateRecord>()

        assertEquals("0 is the 'no reading' sentinel", 0, resting(0).size)
        assertEquals(0, resting(29).size)
        assertEquals(1, resting(30).size)
        assertEquals(1, resting(150).size)
        assertEquals(0, resting(151).size)

        fun respiratory(rate: Double) = HealthConnectManager(context)
            .buildTelemetryRecords(WebhookTelemetryPayload(respiratoryRate = rate), 0L, now)
            .filterIsInstance<RespiratoryRateRecord>()

        assertEquals(0, respiratory(-1.0).size)
        assertEquals(0, respiratory(3.9).size)
        assertEquals(1, respiratory(4.0).size)
        assertEquals(1, respiratory(60.0).size)
        assertEquals(0, respiratory(60.1).size)
    }

    @Test
    fun `deltas and distances must be positive to produce a record`() {
        val manager = HealthConnectManager(context)

        // A zero or negative step delta is not an increment.
        assertEquals(
            0,
            manager.buildTelemetryRecords(WebhookTelemetryPayload(), stepDelta = 0L, now)
                .filterIsInstance<StepsRecord>().size
        )
        assertEquals(
            1,
            manager.buildTelemetryRecords(WebhookTelemetryPayload(), stepDelta = 1L, now)
                .filterIsInstance<StepsRecord>().size
        )

        // -1.0 is the 'no reading' sentinel for every double metric.
        val sentinels = manager.buildTelemetryRecords(
            WebhookTelemetryPayload(
                distanceMeters = -1.0,
                activeCalories = -1.0,
                floorsClimbed = -1.0
            ),
            0L,
            now
        )
        assertEquals("sentinel doubles must not become records", 0, sentinels.size)

        // Zero is not a positive measurement either.
        val zeros = manager.buildTelemetryRecords(
            WebhookTelemetryPayload(distanceMeters = 0.0, activeCalories = 0.0, floorsClimbed = 0.0),
            0L,
            now
        )
        assertEquals(0, zeros.size)
    }

    @Test
    fun `sleep sessions are bounded so an overflow cannot invert the interval`() {
        fun sleep(minutes: Long) = HealthConnectManager(context)
            .buildTelemetryRecords(WebhookTelemetryPayload(sleepMinutes = minutes), 0L, now)
            .filterIsInstance<SleepSessionRecord>()

        assertEquals("0 and -1 are sentinels", 0, sleep(0).size)
        assertEquals(0, sleep(-1L).size)
        assertEquals(1, sleep(1L).size)
        assertEquals(1, sleep(HealthConnectManager.MAX_SLEEP_MINUTES).size)
        // Long.MAX_VALUE * 60 overflows to a negative offset, placing the start after the
        // end and failing the whole insert.
        assertEquals(0, sleep(HealthConnectManager.MAX_SLEEP_MINUTES + 1).size)
        assertEquals(0, sleep(Long.MAX_VALUE).size)

        // Whatever is produced must be a valid interval.
        val record = sleep(420L).single()
        assertTrue("start must not be after end", record.startTime.isBefore(record.endTime))
    }

    @Test
    fun `a fully populated payload produces every writable record type`() {
        val records = HealthConnectManager(context).buildTelemetryRecords(
            WebhookTelemetryPayload(
                heartRate = 72,
                distanceMeters = 7100.0,
                activeCalories = 420.0,
                spo2 = 97.5,
                floorsClimbed = 12.0,
                hrvMs = 41.5,
                restingHeartRate = 52,
                respiratoryRate = 14.5,
                sleepMinutes = 455
            ),
            stepDelta = 250L,
            now = now
        )

        assertEquals("one record per writable metric, plus the step delta", 10, records.size)
        // Every generated id must be unique within the batch, or the provider would
        // upsert one record over another.
        val ids = records.mapNotNull { it.metadata.clientRecordId }
        assertEquals(records.size, ids.size)
        assertEquals("record ids must be unique", ids.size, ids.distinct().size)
    }

    @Test
    fun `two step deltas in the same hour do not collide on their record id`() {
        val manager = HealthConnectManager(context)

        fun stepId(at: Instant): String = manager
            .buildTelemetryRecords(WebhookTelemetryPayload(), stepDelta = 100L, at)
            .filterIsInstance<StepsRecord>()
            .single()
            .metadata.clientRecordId!!

        // Ten minutes apart: same hour-aligned window, so a window-keyed id made the
        // second delta overwrite the first and the hour never accumulated its steps.
        val firstId = stepId(now)
        val secondId = stepId(now.plusSeconds(600))

        assertFalse(
            "step record ids must be unique per delta, not per hour",
            firstId == secondId
        )
    }

    @Test
    fun `a step record keeps the hour-aligned window as its interval`() {
        val record = HealthConnectManager(context)
            .buildTelemetryRecords(WebhookTelemetryPayload(), stepDelta = 100L, now)
            .filterIsInstance<StepsRecord>()
            .single()

        // The windowing itself is deliberate (D-07b): the interval stays hour-aligned
        // even though the id is now per-delta.
        val expectedStart = Instant.ofEpochSecond(BridgePolicy.stepWindowStart(now.epochSecond))
        assertEquals(expectedStart, record.startTime)
        assertEquals(expectedStart.plusSeconds(BridgePolicy.STEP_WINDOW_SECONDS), record.endTime)
        assertEquals(100L, record.count)
    }

    @Test
    fun `values above the platform's own ceilings are rejected before construction`() {
        // Health Connect's record constructors throw for these, and a throw while
        // building the batch loses every valid record alongside the bad one. Probed
        // limits: HRV 200 ms, steps 1e6, distance/energy/floors 1e6 of their unit.
        val manager = HealthConnectManager(context)

        fun steps(delta: Long) = manager
            .buildTelemetryRecords(WebhookTelemetryPayload(), stepDelta = delta, now)
            .filterIsInstance<StepsRecord>()
            .size

        assertEquals(1, steps(HealthConnectManager.MAX_STEPS))
        assertEquals(0, steps(HealthConnectManager.MAX_STEPS + 1))
        assertEquals("Long.MAX_VALUE would throw and fail the batch", 0, steps(Long.MAX_VALUE))

        fun distance(meters: Double) = manager
            .buildTelemetryRecords(WebhookTelemetryPayload(distanceMeters = meters), 0L, now)
            .filterIsInstance<DistanceRecord>()
            .size

        assertEquals(1, distance(HealthConnectManager.MAX_DISTANCE_METERS))
        assertEquals(0, distance(HealthConnectManager.MAX_DISTANCE_METERS + 1.0))
        assertEquals(0, distance(Double.MAX_VALUE))

        fun calories(kcal: Double) = manager
            .buildTelemetryRecords(WebhookTelemetryPayload(activeCalories = kcal), 0L, now)
            .filterIsInstance<ActiveCaloriesBurnedRecord>()
            .size

        assertEquals(1, calories(HealthConnectManager.MAX_ACTIVE_CALORIES))
        assertEquals(0, calories(HealthConnectManager.MAX_ACTIVE_CALORIES + 1.0))

        fun floors(value: Double) = manager
            .buildTelemetryRecords(WebhookTelemetryPayload(floorsClimbed = value), 0L, now)
            .filterIsInstance<FloorsClimbedRecord>()
            .size

        assertEquals(1, floors(HealthConnectManager.MAX_FLOORS))
        assertEquals(0, floors(HealthConnectManager.MAX_FLOORS + 1.0))
    }

    @Test
    fun `a payload with one out-of-range metric still writes every valid one`() {
        // The concrete consequence of the ceilings above: an over-limit HRV threw during
        // batch construction and the heart rate, steps and sleep in the same payload were
        // lost with it.
        val records = HealthConnectManager(context).buildTelemetryRecords(
            WebhookTelemetryPayload(
                heartRate = 72,
                spo2 = 98.0,
                hrvMs = 5_000.0, // rejected by the gate
                sleepMinutes = 420
            ),
            stepDelta = 500L,
            now = now
        )

        assertEquals(4, records.size)
        assertTrue(records.any { it is HeartRateRecord })
        assertTrue(records.any { it is OxygenSaturationRecord })
        assertTrue(records.any { it is StepsRecord })
        assertTrue(records.any { it is SleepSessionRecord })
        assertFalse(
            "the rejected metric must not appear",
            records.any { it is HeartRateVariabilityRmssdRecord }
        )
    }

    @Test
    fun `every written record type has a matching write permission requested`() {
        // A size-only assertion silently passed even when a permission was swapped for
        // an unrelated one, which would make that write fail at runtime.
        val written = HealthConnectManager(context).buildTelemetryRecords(
            WebhookTelemetryPayload(
                heartRate = 72,
                distanceMeters = 7100.0,
                activeCalories = 420.0,
                spo2 = 97.5,
                floorsClimbed = 12.0,
                hrvMs = 41.5,
                restingHeartRate = 52,
                respiratoryRate = 14.5,
                sleepMinutes = 455
            ),
            stepDelta = 250L,
            now = now
        )
        assertTrue(written.isNotEmpty())

        val required = HealthConnectManager.REQUIRED_PERMISSIONS
        assertEquals(
            "each written record type needs its own WRITE permission",
            written.size,
            written.map { it::class }.distinct().size
        )
        written.forEach { record ->
            val permission = androidx.health.connect.client.permission.HealthPermission
                .getWritePermission(record::class)
            assertTrue(
                "missing WRITE permission for ${record::class.simpleName}",
                required.contains(permission)
            )
        }
    }
}

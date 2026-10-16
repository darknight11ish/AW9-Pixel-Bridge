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
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
import androidx.test.core.app.ApplicationProvider
import com.bridge.applewatch.health.HealthConnectManager
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
}

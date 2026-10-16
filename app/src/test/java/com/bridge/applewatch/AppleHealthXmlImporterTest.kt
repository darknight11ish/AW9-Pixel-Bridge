package com.bridge.applewatch

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.units.Length
import com.bridge.applewatch.health.AppleHealthXmlImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Coverage for the streaming `export.xml` importer (OPEN-08).
 *
 * These tests read real Android-free Health Connect record classes on the JVM. The
 * expectations are the *round numbers* a clinician would sanity-check, so a silent
 * unit change (km vs m, fraction vs percent) fails the build instead of writing
 * wrong data into Health Connect.
 */
class AppleHealthXmlImporterTest {

    private val start = "2026-10-14 08:30:00 -0700"
    private val end = "2026-10-14 08:31:00 -0700"

    private fun map(
        type: String,
        value: String,
        includeSdnnAsRmssd: Boolean = false,
        startDate: String = start,
        endDate: String = end
    ) = AppleHealthXmlImporter.mapXmlNodeToRecord(
        type, startDate, endDate, value, includeSdnnAsRmssd
    )

    // ------------------------------------------------------------ timestamps

    @Test
    fun `apple timestamp parses to the exact instant and rejects junk`() {
        val parsed = AppleHealthXmlImporter.parseAppleTimestamp("2026-10-14 08:30:00 -0700")
        assertNotNull("a valid Apple timestamp must parse", parsed)
        // 08:30 at UTC-07:00 is 15:30 UTC; the offsets must cancel, not be dropped.
        val expected = OffsetDateTime.parse("2026-10-14T08:30:00-07:00").toInstant()
        assertEquals(expected, parsed)
        assertEquals(Instant.parse("2026-10-14T15:30:00Z"), parsed)

        assertNull(AppleHealthXmlImporter.parseAppleTimestamp("invalid-date"))
        assertNull(AppleHealthXmlImporter.parseAppleTimestamp(""))
        assertNull(AppleHealthXmlImporter.parseAppleTimestamp("2026-10-14"))
    }

    @Test
    fun `records with an unparseable start date are rejected`() {
        assertNull(
            map("HKQuantityTypeIdentifierStepCount", "1200", startDate = "not-a-date")
        )
    }

    // ------------------------------------------------------------- heart rate

    @Test
    fun `heart rate is clamped to a plausible range`() {
        val low = map("HKQuantityTypeIdentifierHeartRate", "15")
        assertTrue("15 bpm is not survivable and must clamp to 30", low is HeartRateRecord)
        assertEquals(30L, (low as HeartRateRecord).samples.first().beatsPerMinute)

        val high = map("HKQuantityTypeIdentifierHeartRate", "290")
        assertTrue(high is HeartRateRecord)
        assertEquals(240L, (high as HeartRateRecord).samples.first().beatsPerMinute)

        // An in-range value must pass through untouched.
        val normal = map("HKQuantityTypeIdentifierHeartRate", "72")
        assertEquals(72L, (normal as HeartRateRecord).samples.first().beatsPerMinute)
    }

    // ------------------------------------------------------- oxygen saturation

    @Test
    fun `oxygen saturation accepts both fraction and percentage forms`() {
        val fraction = map("HKQuantityTypeIdentifierOxygenSaturation", "0.98")
        assertTrue(fraction is OxygenSaturationRecord)
        // 0.98 is a fraction: Health Connect stores percentage, so 98.0.
        assertEquals(
            98.0,
            (fraction as OxygenSaturationRecord).percentage.value,
            1e-6
        )

        val percent = map("HKQuantityTypeIdentifierOxygenSaturation", "98.0")
        assertTrue(percent is OxygenSaturationRecord)
        assertEquals(98.0, (percent as OxygenSaturationRecord).percentage.value, 1e-6)

        // 40.0 can be neither a fraction nor a survivable percentage.
        assertNull(map("HKQuantityTypeIdentifierOxygenSaturation", "40.0"))
    }

    // -------------------------------------------------------------- step count

    @Test
    fun `step count requires a positive value`() {
        val record = map("HKQuantityTypeIdentifierStepCount", "1200")
        assertTrue(record is StepsRecord)
        assertEquals(1200L, (record as StepsRecord).count)

        assertNull("zero steps is not a record", map("HKQuantityTypeIdentifierStepCount", "0"))
        assertNull("negative steps is not a record", map("HKQuantityTypeIdentifierStepCount", "-5"))
    }

    // ---------------------------------------------------------------- distance

    @Test
    fun `walking distance maps kilometres onto the metres unit`() {
        val record = map("HKQuantityTypeIdentifierDistanceWalkingRunning", "2.5")
        assertTrue(record is DistanceRecord)
        val distance = (record as DistanceRecord).distance
        // Apple's export value for this type is kilometres, so 2.5 km is 2500 m.
        assertEquals(2.5, distance.inKilometers, 1e-6)
        assertEquals(2500.0, distance.inMeters, 1e-3)
        assertEquals(Length.kilometers(2.5), distance)
    }

    // -------------------------------------------------------------- SDNN gate

    @Test
    fun `sdnn is skipped by default and honoured only when explicitly enabled`() {
        // D-05: SDNN must never be silently written as RMSSD.
        assertNull(
            "SDNN must be gated off by default",
            map("HKQuantityTypeIdentifierHeartRateVariabilitySDNN", "45.0")
        )
        assertNull(
            map("HKQuantityTypeIdentifierHeartRateVariabilitySDNN", "45.0", includeSdnnAsRmssd = false)
        )

        val enabled = map(
            "HKQuantityTypeIdentifierHeartRateVariabilitySDNN", "45.0", includeSdnnAsRmssd = true
        )
        assertTrue(
            "with the gate explicitly opened the metric is imported",
            enabled is HeartRateVariabilityRmssdRecord
        )
        assertEquals(
            45.0,
            (enabled as HeartRateVariabilityRmssdRecord).heartRateVariabilityMillis,
            1e-6
        )

        // The importer's own default must stay closed.
        assertEquals(false, AppleHealthXmlImporter.IMPORT_SDNN_AS_RMSSD)
        assertNull(
            AppleHealthXmlImporter.mapXmlNodeToRecord(
                "HKQuantityTypeIdentifierHeartRateVariabilitySDNN", start, end, "45.0"
            )
        )
    }

    @Test
    fun `unknown types are ignored rather than guessed`() {
        assertNull(map("HKQuantityTypeIdentifierBodyMass", "80.0"))
        assertNull(map("", "80.0"))
    }
}

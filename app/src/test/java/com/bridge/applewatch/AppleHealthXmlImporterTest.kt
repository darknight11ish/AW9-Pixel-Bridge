package com.bridge.applewatch

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.units.Length
import com.bridge.applewatch.health.AppleHealthXmlImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Coverage for the streaming `export.xml` importer (OPEN-08).
 *
 * The expectations are the *round numbers* a clinician would sanity-check, so a silent
 * unit change (km vs m, fraction vs percent) fails the build instead of writing wrong
 * data into Health Connect.
 *
 * The governing rule for this importer is that it must never substitute a value: a
 * reading that is absent, unparseable or out of range is **skipped**, never clamped.
 * Several tests below exist specifically to pin that.
 */
class AppleHealthXmlImporterTest {

    private val start = "2026-10-14 08:30:00 -0700"
    private val end = "2026-10-14 08:31:00 -0700"

    private fun map(
        type: String,
        value: String,
        includeSdnnAsRmssd: Boolean = false,
        startDate: String = start,
        endDate: String = end,
        unit: String = "",
        sourceName: String = "Apple Watch"
    ) = AppleHealthXmlImporter.mapXmlNodeToRecord(
        type, startDate, endDate, value, unit, sourceName, includeSdnnAsRmssd
    )

    private fun parse(vararg records: Map<String, String>, maxRecords: Int = 5000) =
        AppleHealthXmlImporter.parseRecords(pullParser(*records), maxRecords)

    /**
     * Builds a scripted parser rather than a real one.
     *
     * The JVM unit-test runtime replaces both `android.util.Xml.newPullParser()` and
     * `org.xmlpull.v1.XmlPullParserFactory.newInstance()` with non-functional stubs, so
     * a test that parsed real XML here would observe zero records and pass for the wrong
     * reason. [ScriptedXmlPullParser] yields a fixed event sequence instead, which is
     * what these scan tests need. Real XML parsing is exercised at runtime.
     */
    private fun pullParser(vararg records: Map<String, String>): ScriptedXmlPullParser =
        ScriptedXmlPullParser(
            ScriptedXmlPullParser.document(
                *records.map { ScriptedXmlPullParser.record(it) }.toTypedArray()
            )
        )

    private fun recordAttributes(
        type: String,
        value: String?,
        unit: String = "",
        source: String = "Apple Watch",
        startDate: String = "2026-10-14 08:30:00 -0700",
        endDate: String = "2026-10-14 08:31:00 -0700"
    ): Map<String, String> = buildMap {
        put("type", type)
        put("sourceName", source)
        put("unit", unit)
        put("startDate", startDate)
        put("endDate", endDate)
        if (value != null) put("value", value)
    }

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
    fun `the record keeps its own UTC offset instead of the phone's zone`() {
        // The export says -0700. Writing the phone's current zone would mislabel every
        // local time for an export recorded elsewhere.
        val record = map("HKQuantityTypeIdentifierHeartRate", "72")
        assertTrue(record is HeartRateRecord)
        assertEquals(
            "the record's own offset must be preserved",
            ZoneOffset.ofHours(-7),
            (record as HeartRateRecord).startZoneOffset
        )
        assertEquals(ZoneOffset.ofHours(-7), record.endZoneOffset)
    }

    @Test
    fun `records with an unparseable start date are rejected`() {
        assertNull(map("HKQuantityTypeIdentifierStepCount", "1200", startDate = "not-a-date"))
    }

    // ------------------------------------------------------------- heart rate

    @Test
    fun `a missing or unparseable heart-rate value is skipped, never defaulted`() {
        // THE regression this importer was fixed for: `toDoubleOrNull() ?: 0.0` followed
        // by `coerceIn(30, 240)` turned an absent `value` attribute into a fabricated
        // 30 bpm reading — the same class of defect as the hardcoded spo2 = 98.0.
        assertNull("absent value must be skipped", map("HKQuantityTypeIdentifierHeartRate", ""))
        assertNull("non-numeric value must be skipped", map("HKQuantityTypeIdentifierHeartRate", "abc"))
        assertNull("whitespace value must be skipped", map("HKQuantityTypeIdentifierHeartRate", "   "))
    }

    @Test
    fun `out-of-range heart rates are skipped rather than clamped into a plausible value`() {
        // 15 and 290 are not survivable; rewriting them to 30/240 would invent a
        // clinical reading that was never measured.
        assertNull(map("HKQuantityTypeIdentifierHeartRate", "15"))
        assertNull(map("HKQuantityTypeIdentifierHeartRate", "290"))
        // The boundaries themselves are valid and must survive untouched.
        val low = map("HKQuantityTypeIdentifierHeartRate", "30")
        assertEquals(30L, (low as HeartRateRecord).samples.first().beatsPerMinute)
        val high = map("HKQuantityTypeIdentifierHeartRate", "240")
        assertEquals(240L, (high as HeartRateRecord).samples.first().beatsPerMinute)
        val normal = map("HKQuantityTypeIdentifierHeartRate", "72")
        assertEquals(72L, (normal as HeartRateRecord).samples.first().beatsPerMinute)
    }

    // ------------------------------------------------------- oxygen saturation

    @Test
    fun `oxygen saturation accepts both fraction and percentage forms`() {
        val fraction = map("HKQuantityTypeIdentifierOxygenSaturation", "0.98")
        assertTrue(fraction is OxygenSaturationRecord)
        assertEquals(98.0, (fraction as OxygenSaturationRecord).percentage.value, 1e-6)

        val percent = map("HKQuantityTypeIdentifierOxygenSaturation", "98.0")
        assertEquals(98.0, (percent as OxygenSaturationRecord).percentage.value, 1e-6)

        // 40.0 can be neither a fraction nor a survivable percentage.
        assertNull(map("HKQuantityTypeIdentifierOxygenSaturation", "40.0"))
        // Inclusive upper bound.
        val top = map("HKQuantityTypeIdentifierOxygenSaturation", "100.0")
        assertEquals(100.0, (top as OxygenSaturationRecord).percentage.value, 1e-6)
    }

    // -------------------------------------------------------------- step count

    @Test
    fun `step count requires a positive value`() {
        val record = map("HKQuantityTypeIdentifierStepCount", "1200")
        assertTrue(record is StepsRecord)
        assertEquals(1200L, (record as StepsRecord).count)

        assertNull("zero steps is not a record", map("HKQuantityTypeIdentifierStepCount", "0"))
        assertNull("negative steps is not a record", map("HKQuantityTypeIdentifierStepCount", "-5"))
        assertNull("missing steps is not a record", map("HKQuantityTypeIdentifierStepCount", ""))
    }

    // ---------------------------------------------------------------- distance

    @Test
    fun `walking distance maps kilometres onto the metres unit`() {
        val record = map("HKQuantityTypeIdentifierDistanceWalkingRunning", "2.5", unit = "km")
        assertTrue(record is DistanceRecord)
        val distance = (record as DistanceRecord).distance
        assertEquals(2.5, distance.inKilometers, 1e-6)
        assertEquals(2500.0, distance.inMeters, 1e-3)
        assertEquals(Length.kilometers(2.5), distance)
    }

    @Test
    fun `distance honours the record's own unit, including miles`() {
        // Apple's export `unit` follows the iPhone's locale settings, so a mi export
        // must not be written as kilometres.
        val miles = map("HKQuantityTypeIdentifierDistanceWalkingRunning", "5.0", unit = "mi")
        assertEquals(
            5.0 * 1.609344 * 1000.0,
            (miles as DistanceRecord).distance.inMeters,
            1e-3
        )
        val meters = map("HKQuantityTypeIdentifierDistanceWalkingRunning", "800.0", unit = "m")
        assertEquals(800.0, (meters as DistanceRecord).distance.inMeters, 1e-6)
    }

    @Test
    fun `an unknown distance unit is rejected rather than guessed`() {
        // Guessing metres would silently write a wrong magnitude.
        assertNull(
            map("HKQuantityTypeIdentifierDistanceWalkingRunning", "5.0", unit = "furlongs")
        )
    }

    @Test
    fun `an over-limit value is skipped rather than throwing and ending the scan`() {
        // Health Connect's constructors reject these magnitudes. Because they throw while
        // the record is being built *inside* the scan, an over-limit node used to be caught
        // by parseRecords' handler and abandon the remainder of the file.
        assertNull(
            "steps above 1e6 exceed Health Connect's ceiling",
            map("HKQuantityTypeIdentifierStepCount", "2000000")
        )
        assertNull(
            "1000 miles is over the distance ceiling once converted",
            map("HKQuantityTypeIdentifierDistanceWalkingRunning", "1000", unit = "mi")
        )
        assertNull(
            "energy above 1e6 kcal exceeds the ceiling",
            map("HKQuantityTypeIdentifierActiveEnergyBurned", "2000000", unit = "kcal")
        )

        // The boundary itself is still importable.
        assertTrue(map("HKQuantityTypeIdentifierStepCount", "1000000") is StepsRecord)
    }

    // -------------------------------------------------------------- SDNN gate

    @Test
    fun `sdnn is skipped by default and honoured only when explicitly enabled`() {
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
        assertTrue(enabled is HeartRateVariabilityRmssdRecord)
        assertEquals(45.0, (enabled as HeartRateVariabilityRmssdRecord).heartRateVariabilityMillis, 1e-6)

        assertEquals(false, AppleHealthXmlImporter.IMPORT_SDNN_AS_RMSSD)
    }

    @Test
    fun `unknown types are ignored rather than guessed`() {
        assertNull(map("HKQuantityTypeIdentifierBodyMass", "80.0"))
        assertNull(map("", "80.0"))
    }

    // ------------------------------------------------------------ record ids

    @Test
    fun `record ids distinguish the source, the interval and the value`() {
        // Apple's export contains the same metric from several sources at second
        // granularity (iPhone and Watch both record steps), so an id keyed only on
        // type + start-millisecond collapsed distinct readings or double-counted them.
        val watch = map("HKQuantityTypeIdentifierStepCount", "100", sourceName = "Apple Watch")
        val phone = map("HKQuantityTypeIdentifierStepCount", "100", sourceName = "iPhone")
        val watchId = (watch as StepsRecord).metadata.clientRecordId
        val phoneId = (phone as StepsRecord).metadata.clientRecordId
        assertNotNull(watchId)
        assertNotNull(phoneId)
        assertFalse("different sources must not collide", watchId == phoneId)

        // Same source, same instant, different value: still distinct.
        val a = map("HKQuantityTypeIdentifierStepCount", "100", sourceName = "Apple Watch")
        val b = map("HKQuantityTypeIdentifierStepCount", "250", sourceName = "Apple Watch")
        assertFalse(
            "different values at the same instant must not collide",
            (a as StepsRecord).metadata.clientRecordId == (b as StepsRecord).metadata.clientRecordId
        )

        // Deterministic, so re-importing the same file stays idempotent.
        val again = map("HKQuantityTypeIdentifierStepCount", "100", sourceName = "Apple Watch")
        assertEquals(watchId, (again as StepsRecord).metadata.clientRecordId)
    }

    // ------------------------------------------------------- stream resilience

    @Test
    fun `a truncated document returns what was parsed and reports incompleteness`() {
        // One bad byte in a multi-hundred-megabyte export used to discard everything:
        // the parser threw out of the whole scan and the caller inserted nothing.
        val parser = ScriptedXmlPullParser(
            listOf(
                ScriptedXmlPullParser.Event(2, "HealthData"),
                ScriptedXmlPullParser.record(recordAttributes("HKQuantityTypeIdentifierHeartRate", "72")),
                ScriptedXmlPullParser.record(recordAttributes("HKQuantityTypeIdentifierStepCount", "500")),
                ScriptedXmlPullParser.endDocument
            ),
            // The first record scans, then the "file" ends mid-stream on the next read.
            truncateAfterNextCalls = 1
        )

        val result = AppleHealthXmlImporter.parseRecords(parser, 5000)

        assertFalse("a truncated document must not be reported as complete", result.complete)
        assertNotNull(result.stopReason)
        assertEquals("the records parsed before the break must survive", 1, result.records.size)
        assertTrue(result.records.first() is HeartRateRecord)
    }

    @Test
    fun `a complete document reports completeness`() {
        val result = parse(
            recordAttributes("HKQuantityTypeIdentifierHeartRate", "72"),
            recordAttributes(
                "HKQuantityTypeIdentifierStepCount",
                "500",
                startDate = "2026-10-14 09:00:00 -0700",
                endDate = "2026-10-14 10:00:00 -0700"
            )
        )
        assertTrue(result.complete)
        assertNull(result.stopReason)
        assertEquals(2, result.records.size)
    }

    @Test
    fun `reaching maxRecords is reported as incomplete instead of looking complete`() {
        val records = (1..5).map { i ->
            ScriptedXmlPullParser.record(
                recordAttributes(
                    "HKQuantityTypeIdentifierStepCount",
                    "100",
                    startDate = "2026-10-14 0$i:00:00 -0700",
                    endDate = "2026-10-14 0$i:59:00 -0700"
                )
            )
        }.toTypedArray()

        val result = AppleHealthXmlImporter.parseRecords(
            ScriptedXmlPullParser(ScriptedXmlPullParser.document(*records)),
            maxRecords = 3
        )

        assertFalse("hitting the limit means the import is partial", result.complete)
        assertEquals(3, result.records.size)
        assertTrue(result.stopReason!!.contains("3"))
    }

    @Test
    fun `a record with no value attribute produces no record and no crash`() {
        val result = parse(recordAttributes("HKQuantityTypeIdentifierHeartRate", value = null))
        assertTrue(result.complete)
        assertTrue(
            "a Record with no value must be skipped, not turned into a reading",
            result.records.isEmpty()
        )
    }
}

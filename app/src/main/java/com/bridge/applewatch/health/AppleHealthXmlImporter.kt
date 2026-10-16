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
import java.util.Locale

/**
 * Streaming importer for Apple Health `export.xml`.
 *
 * Two rules govern everything below, because this writes clinical records:
 *
 * 1. **Never substitute a value.** A reading that is missing, unparseable, or outside a
 *    survivable range is *skipped*, never clamped into range and never defaulted. The
 *    original implementation did both (`valueStr.toDoubleOrNull() ?: 0.0` followed by
 *    `coerceIn(30L, 240L)`), which turned a `<Record>` with no `value` attribute into a
 *    fabricated **30 bpm** reading — the same class of defect as the hardcoded
 *    `spo2 = 98.0` removed in v1.0.1.
 * 2. **Never discard what was already parsed.** A truncated or malformed document
 *    returns the records read so far together with a flag saying the import was
 *    incomplete, rather than throwing away a whole multi-hundred-megabyte export.
 */
object AppleHealthXmlImporter {

    private val appleDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.US)

    /**
     * Apple's HRV metric is SDNN while Health Connect's only record type is
     * [HeartRateVariabilityRmssdRecord]; the two are not interchangeable (D-05).
     * SDNN import therefore requires an explicit opt-in and is skipped by default.
     */
    const val IMPORT_SDNN_AS_RMSSD: Boolean = false

    /** Kilometres per mile, for Apple exports whose distance unit is `mi`. */
    private const val KM_PER_MILE = 1.609344

    /** Plausible bounds for a heart rate, in beats per minute. */
    private val HEART_RATE_RANGE = 30L..240L

    /**
     * Outcome of a streaming import.
     *
     * @param records everything parsed successfully.
     * @param complete false when the scan stopped before the end of the document —
     *   either because [maxRecords] was reached or because the parser threw on
     *   malformed input. Callers must surface this; a partial import reported as a
     *   complete one is a silent data loss.
     * @param stopReason human-readable reason when [complete] is false.
     */
    data class ImportResult(
        val records: List<Record>,
        val complete: Boolean,
        val stopReason: String? = null
    )

    /**
     * Parses `export.xml`, returning whatever was successfully read.
     *
     * The parser is deliberately fault-tolerant: a single malformed byte in a large
     * export yields the records collected so far plus `complete = false`, instead of an
     * exception that loses all of them.
     */
    fun parseExportXmlStream(
        inputStream: InputStream,
        maxRecords: Int = 5000,
        includeSdnnAsRmssd: Boolean = IMPORT_SDNN_AS_RMSSD
    ): ImportResult {
        val parser = Xml.newPullParser()
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, null)
        } catch (e: Exception) {
            return ImportResult(emptyList(), complete = false, stopReason = "Unreadable document: ${e.message}")
        }
        return parseRecords(parser, maxRecords, includeSdnnAsRmssd)
    }

    /**
     * The scan itself, over an already-configured parser.
     *
     * Split out so the parsing rules can be exercised directly: `Xml.newPullParser()`
     * returns Android's parser, which is a non-functional stub in plain JVM unit tests,
     * so a test that went through [parseExportXmlStream] would silently observe zero
     * records and pass for the wrong reason.
     */
    internal fun parseRecords(
        parser: XmlPullParser,
        maxRecords: Int = 5000,
        includeSdnnAsRmssd: Boolean = IMPORT_SDNN_AS_RMSSD
    ): ImportResult {
        val records = mutableListOf<Record>()
        var complete = true
        var stopReason: String? = null

        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (records.size >= maxRecords) {
                    complete = false
                    stopReason = "Reached the $maxRecords record limit" +
                        " (earlier records were still imported)"
                    break
                }
                if (eventType == XmlPullParser.START_TAG && parser.name == "Record") {
                    mapXmlNodeToRecord(
                        type = parser.getAttributeValue(null, "type") ?: "",
                        startDateStr = parser.getAttributeValue(null, "startDate") ?: "",
                        endDateStr = parser.getAttributeValue(null, "endDate") ?: "",
                        valueStr = parser.getAttributeValue(null, "value") ?: "",
                        unitStr = parser.getAttributeValue(null, "unit") ?: "",
                        sourceName = parser.getAttributeValue(null, "sourceName") ?: "",
                        includeSdnnAsRmssd = includeSdnnAsRmssd
                    )?.let { records.add(it) }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // A truncated copy or a malformed document: keep everything parsed so far.
            complete = false
            stopReason = "Document ended early (${e.javaClass.simpleName}: ${e.message})"
        }

        return ImportResult(records, complete, stopReason)
    }

    /**
     * Maps one `<Record>` into a Health Connect record, or null when the node carries no
     * usable reading.
     *
     * [unitStr] is Apple's own `unit` attribute. It changes with the iPhone's locale
     * settings, so a distance export recorded in miles must be converted rather than
     * assumed to be kilometres. Unknown units are rejected instead of guessed.
     */
    internal fun mapXmlNodeToRecord(
        type: String,
        startDateStr: String,
        endDateStr: String,
        valueStr: String,
        unitStr: String = "",
        sourceName: String = "",
        includeSdnnAsRmssd: Boolean = IMPORT_SDNN_AS_RMSSD
    ): Record? {
        val startOffset = parseAppleOffsetDateTime(startDateStr) ?: return null
        val startInstant = startOffset.toInstant()
        val endOffset = parseAppleOffsetDateTime(endDateStr)
        val endInstant = endOffset?.toInstant()?.takeIf { it.isAfter(startInstant) }
            ?: startInstant.plusSeconds(1)

        // Use the record's OWN offset, not the phone's current zone: an export recorded
        // elsewhere would otherwise have every local time mislabelled.
        val startZoneOffset = startOffset.offset
        val endZoneOffset = endOffset?.offset ?: startOffset.offset

        // No numeric value means no reading. Returning null here is what prevents the
        // fabricated 30 bpm record; do not reintroduce a default.
        val numericVal = valueStr.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null

        val unit = unitStr.trim().lowercase(Locale.US)
        val recordId = buildRecordId(type, sourceName, startInstant, endInstant, valueStr)

        return when (type) {
            "HKQuantityTypeIdentifierHeartRate" -> {
                val bpm = numericVal.toLong()
                // Out-of-range readings are dropped rather than rewritten into range:
                // clamping would invent a plausible-looking clinical value.
                if (bpm !in HEART_RATE_RANGE) return null
                HeartRateRecord(
                    startTime = startInstant,
                    startZoneOffset = startZoneOffset,
                    endTime = endInstant,
                    endZoneOffset = endZoneOffset,
                    samples = listOf(HeartRateRecord.Sample(startInstant, bpm)),
                    metadata = Metadata.manualEntry(clientRecordId = recordId)
                )
            }
            "HKQuantityTypeIdentifierStepCount" -> {
                val count = numericVal.toLong()
                // Ceilings match Health Connect's constructors: exceeding one throws inside
                // this scan and the catch would abandon the rest of the file.
                if (count !in 1..HealthConnectManager.MAX_STEPS) null else StepsRecord(
                    startTime = startInstant,
                    startZoneOffset = startZoneOffset,
                    endTime = endInstant,
                    endZoneOffset = endZoneOffset,
                    count = count,
                    metadata = Metadata.manualEntry(clientRecordId = recordId)
                )
            }
            "HKQuantityTypeIdentifierDistanceWalkingRunning" -> {
                val meters = distanceMeters(numericVal, unit) ?: return null
                if (meters <= 0.0 || meters > HealthConnectManager.MAX_DISTANCE_METERS) null else DistanceRecord(
                    startTime = startInstant,
                    startZoneOffset = startZoneOffset,
                    endTime = endInstant,
                    endZoneOffset = endZoneOffset,
                    distance = Length.meters(meters),
                    metadata = Metadata.manualEntry(clientRecordId = recordId)
                )
            }
            "HKQuantityTypeIdentifierActiveEnergyBurned" -> {
                val kcal = energyKilocalories(numericVal, unit) ?: return null
                if (kcal <= 0.0 || kcal > HealthConnectManager.MAX_ACTIVE_CALORIES) null else ActiveCaloriesBurnedRecord(
                    startTime = startInstant,
                    startZoneOffset = startZoneOffset,
                    endTime = endInstant,
                    endZoneOffset = endZoneOffset,
                    energy = Energy.kilocalories(kcal),
                    metadata = Metadata.manualEntry(clientRecordId = recordId)
                )
            }
            "HKQuantityTypeIdentifierOxygenSaturation" -> {
                // Apple stores percentages as fractions (98.5 % -> 0.985).
                val pct = if (numericVal <= 1.0) numericVal * 100.0 else numericVal
                if (pct !in 70.0..100.0) null else OxygenSaturationRecord(
                    time = startInstant,
                    zoneOffset = startZoneOffset,
                    percentage = Percentage(pct),
                    metadata = Metadata.manualEntry(clientRecordId = recordId)
                )
            }
            "HKQuantityTypeIdentifierHeartRateVariabilitySDNN" -> {
                // Health Connect has no SDNN record type; writing SDNN into
                // HeartRateVariabilityRmssdRecord would mislabel the metric (D-05).
                if (!includeSdnnAsRmssd || numericVal !in 1.0..HealthConnectManager.MAX_HRV_MS) null
                else HeartRateVariabilityRmssdRecord(
                    time = startInstant,
                    zoneOffset = startZoneOffset,
                    heartRateVariabilityMillis = numericVal,
                    metadata = Metadata.manualEntry(clientRecordId = recordId)
                )
            }
            else -> null
        }
    }

    /**
     * Converts a distance reading to metres using the record's own unit.
     *
     * Returns null for a unit we do not recognise: guessing would silently write a
     * wrong magnitude, which is worse than importing nothing.
     */
    private fun distanceMeters(value: Double, unit: String): Double? = when (unit) {
        "", "km" -> value * 1000.0
        "m" -> value
        "mi" -> value * KM_PER_MILE * 1000.0
        "yd" -> value * 0.9144
        "ft" -> value * 0.3048
        else -> null
    }

    /** Converts an energy reading to kilocalories using the record's own unit. */
    private fun energyKilocalories(value: Double, unit: String): Double? = when (unit) {
        "", "kcal" -> value
        "kj" -> value / 4.184
        "cal" -> value / 1000.0
        else -> null
    }

    /**
     * Builds an id that is stable for the same source record but distinct for different
     * ones.
     *
     * Apple's export contains overlapping records from multiple sources (iPhone and
     * Watch both record steps and distance) at second granularity, so keying on
     * type + start-millisecond alone either collapsed two devices' readings into one or
     * double-counted them. Including the source, the end time and the value
     * distinguishes them while remaining deterministic, so re-importing the same file
     * stays idempotent.
     */
    private fun buildRecordId(
        type: String,
        sourceName: String,
        startInstant: Instant,
        endInstant: Instant,
        valueStr: String
    ): String {
        val kind = type.substringAfterLast("Identifier")
        val source = sourceName.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_")
        val value = valueStr.trim().replace(Regex("[^0-9a-zA-Z.\\-]+"), "_")
        return "xml_${kind}_${source}_${startInstant.toEpochMilli()}_${endInstant.toEpochMilli()}_$value"
    }

    /** Parses Apple's `yyyy-MM-dd HH:mm:ss Z`, preserving the stated UTC offset. */
    internal fun parseAppleOffsetDateTime(raw: String): OffsetDateTime? = try {
        OffsetDateTime.parse(raw.trim(), appleDateFormatter)
    } catch (_: Exception) {
        null
    }

    /** Parses Apple's timestamp to an [Instant], or null when it is not parseable. */
    internal fun parseAppleTimestamp(raw: String): Instant? = parseAppleOffsetDateTime(raw)?.toInstant()

    /** Kept for callers that only need the zone offset of a parsed timestamp. */
    internal fun parseAppleZoneOffset(raw: String): ZoneOffset? = parseAppleOffsetDateTime(raw)?.offset
}

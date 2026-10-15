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

    /**
     * Apple's HRV metric is SDNN while Health Connect's only record type is
     * [HeartRateVariabilityRmssdRecord]; the two are not interchangeable (D-05).
     * SDNN import therefore requires an explicit opt-in and is skipped by default.
     */
    const val IMPORT_SDNN_AS_RMSSD: Boolean = false

    fun parseExportXmlStream(
        inputStream: InputStream,
        maxRecords: Int = 5000,
        includeSdnnAsRmssd: Boolean = IMPORT_SDNN_AS_RMSSD
    ): List<Record> {
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

                val record = mapXmlNodeToRecord(type, startStr, endStr, valueStr, includeSdnnAsRmssd)
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
        valueStr: String,
        includeSdnnAsRmssd: Boolean = IMPORT_SDNN_AS_RMSSD
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
                    samples = listOf(HeartRateRecord.Sample(startInstant, bpm)),
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
                // Health Connect has no SDNN record type; writing SDNN into
                // HeartRateVariabilityRmssdRecord would mislabel the metric (D-05).
                if (!includeSdnnAsRmssd || numericVal !in 1.0..250.0) null else HeartRateVariabilityRmssdRecord(
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

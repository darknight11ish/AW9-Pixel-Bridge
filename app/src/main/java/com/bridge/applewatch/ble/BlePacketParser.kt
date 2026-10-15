package com.bridge.applewatch.ble

import com.bridge.applewatch.model.ParsedContinuityPacket
import com.bridge.applewatch.model.ParsedHeartRatePacket
import com.bridge.applewatch.model.ParsedRscPacket
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object BlePacketParser {

    /**
     * Parses Bluetooth SIG Heart Rate Measurement (0x2A37) payload.
     * Supports 8-bit/16-bit BPM, Sensor Contact bits, Energy Expended (kJ), and 1/1024s RR-Intervals.
     */
    fun parseHeartRateMeasurement(data: ByteArray): ParsedHeartRatePacket? {
        if (data.size < 2) return null

        val flags = data[0].toInt() and 0xFF
        val is16BitBpm = (flags and 0x01) != 0
        val contactDetected = (flags and 0x02) != 0
        val contactSupported = (flags and 0x04) != 0
        val energyPresent = (flags and 0x08) != 0
        val rrPresent = (flags and 0x10) != 0

        var offset = 1
        val bpm: Int
        if (is16BitBpm) {
            if (data.size < offset + 2) return null
            bpm = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
        } else {
            bpm = data[offset].toInt() and 0xFF
            offset += 1
        }

        var energyKj: Int? = null
        if (energyPresent) {
            if (data.size >= offset + 2) {
                energyKj = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
            }
        }

        val rrList = mutableListOf<Double>()
        if (rrPresent) {
            while (offset + 1 < data.size) {
                val rawRr = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
                val rrMs = (rawRr.toDouble() / 1024.0) * 1000.0
                if (rrMs in 250.0..2500.0) {
                    rrList.add(rrMs)
                }
            }
        }

        return ParsedHeartRatePacket(
            bpm = bpm,
            contactSupported = contactSupported,
            contactDetected = if (contactSupported) contactDetected else true,
            energyExpendedKj = energyKj,
            rrIntervalsMs = rrList
        )
    }

    /**
     * Parses Bluetooth SIG Running Speed and Cadence Measurement (0x2A53) payload.
     */
    fun parseRscMeasurement(data: ByteArray): ParsedRscPacket? {
        if (data.size < 4) return null
        val flags = data[0].toInt() and 0xFF
        val stridePresent = (flags and 0x01) != 0
        val totalDistancePresent = (flags and 0x02) != 0
        val isRunning = (flags and 0x04) != 0

        val rawSpeed = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        val speedMps = rawSpeed.toDouble() / 256.0
        val cadenceSpm = data[3].toInt() and 0xFF

        var offset = 4
        var strideMeters: Double? = null
        if (stridePresent && data.size >= offset + 2) {
            val rawStrideCm = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            strideMeters = rawStrideCm.toDouble() / 100.0
            offset += 2
        }

        var totalDistanceMeters: Double? = null
        if (totalDistancePresent && data.size >= offset + 4) {
            val rawDistDm = (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
            totalDistanceMeters = rawDistDm.toDouble() / 10.0
        }

        return ParsedRscPacket(
            instantaneousSpeedMps = speedMps,
            instantaneousCadenceSpm = cadenceSpm,
            strideLengthMeters = strideMeters,
            totalDistanceMeters = totalDistanceMeters,
            isRunning = isRunning
        )
    }

    /**
     * Parses Apple Continuity Manufacturer Data (0x004C), specifically Nearby Info (0x10) and Nearby Action (0x0F).
     */
    fun parseAppleContinuityManufacturerData(data: ByteArray): ParsedContinuityPacket? {
        if (data.size < 3) return null
        val frameType = data[0].toInt() and 0xFF
        val statusFlags = data[2].toInt() and 0xFF

        return when (frameType) {
            0x10 -> {
                val unlocked = (statusFlags and 0x20) != 0
                val screenOn = (statusFlags and 0x08) != 0
                val watchOnWrist = (statusFlags and 0x04) != 0
                val desc = when {
                    unlocked && screenOn -> "Active & Unlocked (Screen On)"
                    unlocked -> "Unlocked on Wrist"
                    watchOnWrist -> "Locked on Wrist (Idle)"
                    else -> "Nearby (Continuity 0x10)"
                }
                ParsedContinuityPacket(
                    frameType = frameType,
                    statusFlags = statusFlags,
                    activityLevelDescription = desc,
                    isUnlockedOnWrist = unlocked || watchOnWrist
                )
            }
            0x0F -> ParsedContinuityPacket(
                frameType = frameType,
                statusFlags = statusFlags,
                activityLevelDescription = "Nearby Action Broadcast (0x0F)",
                isUnlockedOnWrist = false
            )
            else -> ParsedContinuityPacket(
                frameType = frameType,
                statusFlags = statusFlags,
                activityLevelDescription = "Apple BLE Frame 0x${frameType.toString(16).uppercase()}",
                isUnlockedOnWrist = false
            )
        }
    }

    /**
     * Computes Root Mean Square of Successive Differences (RMSSD) in ms from RR-intervals.
     */
    fun computeRmssd(rrIntervalsMs: List<Double>): Double {
        if (rrIntervalsMs.size < 3) return 0.0
        var sumSquaredDiffs = 0.0
        var validPairs = 0
        for (i in 0 until rrIntervalsMs.size - 1) {
            val diff = rrIntervalsMs[i + 1] - rrIntervalsMs[i]
            if (kotlin.math.abs(diff) <= 350.0) {
                sumSquaredDiffs += diff * diff
                validPairs++
            }
        }
        if (validPairs == 0) return 0.0
        return (sqrt(sumSquaredDiffs / validPairs) * 10.0).roundToInt() / 10.0
    }

    /**
     * Estimates distance in meters from BLE RSSI using the Log-Distance Path Loss equation.
     */
    fun estimateDistanceMeters(rssi: Int, txPowerAtOneMeter: Int = -59, pathLossExponent: Double = 2.2): Double {
        if (rssi >= 0 || rssi < -120) return 0.0
        val raw = 10.0.pow((txPowerAtOneMeter - rssi).toDouble() / (10.0 * pathLossExponent))
        return (raw * 10.0).roundToInt() / 10.0
    }
}

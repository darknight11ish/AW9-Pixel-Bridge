package com.bridge.applewatch

import com.bridge.applewatch.ble.BlePacketParser
import org.junit.Assert.*
import org.junit.Test

class BlePacketParserTest {

    @Test
    fun `parse 8-bit Heart Rate with wrist contact and RR intervals`() {
        val packet = byteArrayOf(0x16, 0x48, 0x48, 0x03, 0x70, 0x03)
        val parsed = BlePacketParser.parseHeartRateMeasurement(packet)

        assertNotNull(parsed)
        assertEquals(72, parsed!!.bpm)
        assertTrue(parsed.contactSupported)
        assertTrue(parsed.contactDetected)
        assertEquals(2, parsed.rrIntervalsMs.size)
        assertEquals(820.3, parsed.rrIntervalsMs[0], 1.0)
        assertEquals(859.3, parsed.rrIntervalsMs[1], 1.0)
    }

    @Test
    fun `parse 16-bit Heart Rate with energy expended`() {
        val packet = byteArrayOf(0x09, 0xB9.toByte(), 0x00, 0xA4.toByte(), 0x01)
        val parsed = BlePacketParser.parseHeartRateMeasurement(packet)

        assertNotNull(parsed)
        assertEquals(185, parsed!!.bpm)
        assertEquals(420, parsed.energyExpendedKj)
    }

    @Test
    fun `compute RMSSD HRV accurately filters artifacts`() {
        val rrList = listOf(800.0, 820.0, 790.0, 830.0, 810.0)
        val rmssd = BlePacketParser.computeRmssd(rrList)
        assertEquals(28.7, rmssd, 0.2)
    }

    @Test
    fun `parse Apple Continuity 0x004C Nearby Info 0x10 frame`() {
        val data = byteArrayOf(0x10, 0x05, 0x28, 0x00, 0x00)
        val continuity = BlePacketParser.parseAppleContinuityManufacturerData(data)

        assertNotNull(continuity)
        assertEquals(0x10, continuity!!.frameType)
        assertTrue(continuity.isUnlockedOnWrist)
        assertTrue(continuity.activityLevelDescription.contains("Unlocked"))
    }

    @Test
    fun `parse BLE Running Speed and Cadence 0x2A53 packet`() {
        val packet = byteArrayOf(
            0x07,
            0x80.toByte(), 0x03,
            0xAC.toByte(),
            0x73, 0x00,
            0xD4.toByte(), 0x30, 0x00, 0x00
        )
        val rsc = BlePacketParser.parseRscMeasurement(packet)

        assertNotNull(rsc)
        assertEquals(3.5, rsc!!.instantaneousSpeedMps, 0.01)
        assertEquals(172, rsc.instantaneousCadenceSpm)
        assertEquals(1.15, rsc.strideLengthMeters!!, 0.01)
        assertEquals(1250.0, rsc.totalDistanceMeters!!, 0.1)
        assertTrue(rsc.isRunning)
    }

    // ---------------------------------------------------------------------
    // v1.0.1 hardening tests (reviewer Q6)
    // ---------------------------------------------------------------------

    @Test
    fun `computeRmssd rejects ectopic jumps above 350ms`() {
        // Deltas: +20, +580, -570. Only the +20 ms pair is admissible, so the
        // 580 ms ectopic beat must be excluded rather than inflating RMSSD:
        // sqrt(400 / 1) = 20.0 ms. Without the >350 ms filter this would be
        // sqrt(674800 / 3)*10 -> 474.1 ms.
        val rmssd = BlePacketParser.computeRmssd(listOf(800.0, 820.0, 1400.0, 830.0))
        assertEquals(20.0, rmssd, 0.1)

        // An entirely ectopic window has no admissible pairs -> 0.0
        assertEquals(0.0, BlePacketParser.computeRmssd(listOf(800.0, 1300.0, 1800.0)), 0.001)

        // [800, 1000, 1500, 1800] has deltas +200, +500, +300. The filter is
        // abs(diff) <= 350, so +200 and +300 are admissible and only +500 is
        // dropped: sqrt((200^2 + 300^2) / 2) = sqrt(65000) = 254.95 -> 255.0 ms.
        // Without the filter it would be sqrt(380000 / 3) = 355.9 ms.
        val mixed = BlePacketParser.computeRmssd(listOf(800.0, 1000.0, 1500.0, 1800.0))
        assertEquals(255.0, mixed, 0.1)
    }

    @Test
    fun `computeRmssd returns zero when fewer than three intervals`() {
        assertEquals(0.0, BlePacketParser.computeRmssd(listOf(800.0, 810.0)), 0.001)
        assertEquals(0.0, BlePacketParser.computeRmssd(listOf(800.0)), 0.001)
        assertEquals(0.0, BlePacketParser.computeRmssd(emptyList()), 0.001)
    }

    @Test
    fun `truncated 16-bit Heart Rate packet returns null instead of throwing`() {
        // Flags advertise a 16-bit BPM value but only one payload byte is present.
        assertNull(BlePacketParser.parseHeartRateMeasurement(byteArrayOf(0x01, 0x50)))
        assertNull(BlePacketParser.parseHeartRateMeasurement(byteArrayOf(0x01)))
        assertNull(BlePacketParser.parseHeartRateMeasurement(ByteArray(0)))
    }

    @Test
    fun `truncated RSC and Continuity packets return null instead of throwing`() {
        assertNull(BlePacketParser.parseRscMeasurement(byteArrayOf(0x07, 0x80.toByte(), 0x03)))
        assertNull(BlePacketParser.parseAppleContinuityManufacturerData(byteArrayOf(0x10, 0x05)))
    }

    @Test
    fun `estimateDistanceMeters honours boundary conditions`() {
        // Out-of-range RSSI values are rejected outright.
        assertEquals(0.0, BlePacketParser.estimateDistanceMeters(0), 0.001)
        assertEquals(0.0, BlePacketParser.estimateDistanceMeters(-125), 0.001)

        // At the calibrated reference power the log-distance model yields 1.0 m.
        assertEquals(1.0, BlePacketParser.estimateDistanceMeters(-59), 0.001)

        // Weaker signal must resolve to a strictly greater distance.
        val near = BlePacketParser.estimateDistanceMeters(-59)
        val far = BlePacketParser.estimateDistanceMeters(-90)
        assertTrue("expected $far > $near", far > near)
    }

    @Test
    fun `estimateDistanceMeters is monotonic across the accepted range`() {
        var previous = 0.0
        for (rssi in -60 downTo -110) {
            val d = BlePacketParser.estimateDistanceMeters(rssi)
            assertTrue("distance must not decrease as RSSI weakens at $rssi dBm", d >= previous)
            previous = d
        }
    }
}

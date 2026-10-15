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
}

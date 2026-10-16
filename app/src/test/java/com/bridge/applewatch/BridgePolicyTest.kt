package com.bridge.applewatch

import com.bridge.applewatch.service.BridgePolicy
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-JVM tests for the decision logic extracted out of WatchBridgeService.
 * These cover the rules behind D-02, D-07b, D-13 and D-14.
 */
class BridgePolicyTest {

    // ---------------- peer address gate (D-02) ----------------

    @Test
    fun `loopback and RFC1918 peers are accepted`() {
        val allowed = listOf(
            "127.0.0.1", "127.5.5.5", "::1",
            "10.0.0.5", "10.255.255.254",
            "192.168.0.1", "192.168.1.100",
            "169.254.10.10",
            "172.16.0.1", "172.20.10.1", "172.31.255.255",
            "::ffff:127.0.0.1", "::ffff:192.168.1.7",
            "fe80::1", "FE80::ABCD", "fc00::1", "fd12:3456::1"
        )
        allowed.forEach { host ->
            assertTrue("expected $host to be treated as private", BridgePolicy.isPrivateNetworkAddress(host))
        }
    }

    @Test
    fun `public and boundary peers are rejected`() {
        val denied = listOf(
            "8.8.8.8", "1.1.1.1", "203.0.113.9",
            "172.15.0.1",   // just below the 172.16/12 range
            "172.32.0.1",   // just above the 172.16/12 range
            "192.169.0.1",  // misspelled neighbour of 192.168/16
            "11.0.0.1", "100.64.0.1",  // CGNAT is not site-local
            "2001:4860:4860::8888",
            "", " ", "not-an-address"
        )
        denied.forEach { host ->
            assertFalse("expected $host to be rejected", BridgePolicy.isPrivateNetworkAddress(host))
        }
        assertFalse(BridgePolicy.isPrivateNetworkAddress(null))
    }

    // ---------------- continuity crosstalk + EMA (D-13) ----------------

    @Test
    fun `framewise continuity acceptance honours the MAC lock and signal floor`() {
        val watch = "AA:BB:CC:DD:EE:FF"
        // No tracked watch yet: only a strong enough signal is trusted.
        assertTrue(BridgePolicy.shouldAcceptContinuityFrame("11:22:33:44:55:66", null, -70))
        assertFalse(BridgePolicy.shouldAcceptContinuityFrame("11:22:33:44:55:66", null, -85))
        assertFalse(BridgePolicy.shouldAcceptContinuityFrame("11:22:33:44:55:66", "", -81))
        // Tracked MAC locks the source regardless of signal strength.
        assertTrue(BridgePolicy.shouldAcceptContinuityFrame(watch, watch, -95))
        assertTrue(BridgePolicy.shouldAcceptContinuityFrame(watch.lowercase(), watch.uppercase(), -95))
        // A different Apple device is ignored once we know the watch.
        assertFalse(BridgePolicy.shouldAcceptContinuityFrame("11:22:33:44:55:66", watch, -40))
    }

    @Test
    fun `rssi smoothing seeds on first sample and then converges`() {
        // First sample seeds the average, so a cold start is not read as far away.
        assertEquals(-60.0, BridgePolicy.smoothRssi(0.0, -60), 0.001)
        // 0.3 * new + 0.7 * previous = 0.3 * -50 + 0.7 * -60 = -57.0
        assertEquals(-57.0, BridgePolicy.smoothRssi(-60.0, -50), 0.001)
        // Repeated equal samples stay put.
        assertEquals(-70.0, BridgePolicy.smoothRssi(-70.0, -70), 0.001)
        // The average must lag a single spike rather than following it exactly.
        val smoothed = BridgePolicy.smoothRssi(-80.0, -40)
        assertTrue(smoothed > -80.0 && smoothed < -40.0)
    }

    // ---------------- step baseline + windows (D-07b, D-14) ----------------

    @Test
    fun `first cumulative reading only establishes the baseline`() {
        val first = BridgePolicy.advanceStepBaseline(BridgePolicy.NO_STEP_BASELINE, 5000L)
        assertEquals(5000L, first.baseline)
        assertEquals("a day of backlog must not be written", 0L, first.delta)

        // A zero-step first reading is still a valid baseline.
        val zero = BridgePolicy.advanceStepBaseline(BridgePolicy.NO_STEP_BASELINE, 0L)
        assertEquals(0L, zero.baseline)
        assertEquals(0L, zero.delta)
    }

    @Test
    fun `subsequent readings emit only the increment`() {
        assertEquals(120L, BridgePolicy.advanceStepBaseline(5000L, 5120L).delta)
        // Unchanged or decreasing counters (watch reset, midnight rollover) emit nothing
        // and keep the existing baseline rather than writing a bogus delta.
        assertEquals(0L, BridgePolicy.advanceStepBaseline(5000L, 5000L).delta)
        val reset = BridgePolicy.advanceStepBaseline(5000L, 12L)
        assertEquals(0L, reset.delta)
        assertEquals(5000L, reset.baseline)
    }

    @Test
    fun `absent step data leaves the baseline untouched`() {
        val missing = BridgePolicy.advanceStepBaseline(1234L, -1L)
        assertEquals(1234L, missing.baseline)
        assertEquals(0L, missing.delta)
        val missingNoBaseline = BridgePolicy.advanceStepBaseline(BridgePolicy.NO_STEP_BASELINE, -1L)
        assertEquals(BridgePolicy.NO_STEP_BASELINE, missingNoBaseline.baseline)
        assertEquals(0L, missingNoBaseline.delta)
    }

    @Test
    fun `step windows align to hour boundaries`() {
        // 2026-10-15T12:34:56Z -> 12:00:00Z
        assertEquals(1_761_883_200L, BridgePolicy.stepWindowStart(1_761_886_496L))
        assertEquals(0L, BridgePolicy.stepWindowStart(0L))
        assertEquals(0L, BridgePolicy.stepWindowStart(3599L))
        assertEquals(3600L, BridgePolicy.stepWindowStart(3600L))
        assertEquals(3600L, BridgePolicy.stepWindowStart(7199L))
        // Idempotent
        val once = BridgePolicy.stepWindowStart(1_761_886_496L)
        assertEquals(once, BridgePolicy.stepWindowStart(once))
    }

    // ---------------- reconnect backoff (Q4) ----------------

    @Test
    fun `reconnect backoff doubles and is bounded`() {
        assertEquals(1000L, BridgePolicy.connectRetryDelayMs(0))
        assertEquals(2000L, BridgePolicy.connectRetryDelayMs(1))
        assertEquals(4000L, BridgePolicy.connectRetryDelayMs(2))
        // Negative or absurd attempt indices are clamped rather than shifting wildly.
        assertEquals(1000L, BridgePolicy.connectRetryDelayMs(-3))
        assertEquals(32_000L, BridgePolicy.connectRetryDelayMs(99))
        assertTrue("attempt budget must be small", BridgePolicy.MAX_CONNECT_ATTEMPTS in 1..5)
    }

    // ---------------- optional shared token (Q3) ----------------

    @Test
    fun `token check is permissive when unconfigured and strict when set`() {
        // Unconfigured bridge keeps working, so the watch is not broken by this change.
        assertTrue(BridgePolicy.tokenAccepted(null, null))
        assertTrue(BridgePolicy.tokenAccepted("", "anything"))
        assertTrue(BridgePolicy.tokenAccepted("   ", null))

        // Configured: exact match required, whitespace tolerated at both ends.
        assertTrue(BridgePolicy.tokenAccepted("s3cret", "s3cret"))
        assertTrue(BridgePolicy.tokenAccepted("s3cret", "  s3cret  "))
        assertFalse(BridgePolicy.tokenAccepted("s3cret", "s3cres"))
        assertFalse(BridgePolicy.tokenAccepted("s3cret", "s3cre"))
        assertFalse(BridgePolicy.tokenAccepted("s3cret", null))
        assertFalse(BridgePolicy.tokenAccepted("s3cret", ""))
    }

    // ------------------------------- webhook endpoint ranking (OPEN-06) -------------

    @Test
    fun `hotspot interface outranks primary wifi`() {
        // The Pixel hotspot gateway the watch actually has to reach.
        assertEquals(
            BridgePolicy.ENDPOINT_PRIORITY_HOTSPOT,
            BridgePolicy.classifyInterfacePriority("ap0", "192.168.43.1")
        )
        // SoftAP/tethering names beyond plain "ap".
        listOf("softap0", "swlan0", "wlan1", "rndis0").forEach { name ->
            assertEquals(
                "$name must rank as hotspot",
                BridgePolicy.ENDPOINT_PRIORITY_HOTSPOT,
                BridgePolicy.classifyInterfacePriority(name, "10.0.0.2")
            )
        }
        // Vendor naming varies, so the gateway range is matched too.
        assertEquals(
            BridgePolicy.ENDPOINT_PRIORITY_HOTSPOT,
            BridgePolicy.classifyInterfacePriority("wlan9", "192.168.43.1")
        )
        assertEquals(
            BridgePolicy.ENDPOINT_PRIORITY_HOTSPOT,
            BridgePolicy.classifyInterfacePriority("wlan9", "192.168.49.1")
        )

        // 192.168.1.x is an ordinary router LAN, not this phone's hotspot.
        assertEquals(
            BridgePolicy.ENDPOINT_PRIORITY_WIFI,
            BridgePolicy.classifyInterfacePriority("wlan0", "192.168.1.25")
        )
        assertEquals(
            BridgePolicy.ENDPOINT_PRIORITY_WIFI,
            BridgePolicy.classifyInterfacePriority("en0", "10.1.2.3")
        )
        assertEquals(
            BridgePolicy.ENDPOINT_PRIORITY_OTHER,
            BridgePolicy.classifyInterfacePriority("rmnet0", "172.20.10.1")
        )
    }

    @Test
    fun `ap0 sorts ahead of wlan0 and both urls are formatted on one line`() {
        val candidates = listOf(
            "wlan0" to "192.168.1.25",
            "ap0" to "192.168.43.1"
        )

        // Both active interfaces are enumerated, hotspot first, separated by " | ".
        val formatted = BridgePolicy.sortAndFormatEndpoints(candidates)
        assertEquals(
            "Hotspot (ap0): http://192.168.43.1:8484/telemetry" +
                " | Wi-Fi (wlan0): http://192.168.1.25:8484/telemetry",
            formatted
        )
        // One line: the card renders this as a single monospace row.
        assertFalse("the endpoint list must not introduce line breaks", formatted.contains("\n"))

        // Order must not depend on the order the interfaces happened to be enumerated in.
        val reversed = BridgePolicy.sortAndFormatEndpoints(candidates.reversed())
        assertEquals(formatted, reversed)
    }

    @Test
    fun `every active private interface is enumerated and labelled by role`() {
        val formatted = BridgePolicy.sortAndFormatEndpoints(
            listOf(
                "rmnet0" to "172.20.10.4",
                "wlan0" to "192.168.1.42",
                "ap0" to "192.168.43.1",
                "en0" to "10.1.2.3"
            )
        )
        val parts = formatted.split(BridgePolicy.ENDPOINT_SEPARATOR)
        assertEquals("all four eligible interfaces must be listed", 4, parts.size)
        // Hotspot, then Wi-Fi/Ethernet by name, then the remaining private LAN.
        assertEquals("Hotspot (ap0): http://192.168.43.1:8484/telemetry", parts[0])
        assertEquals("Wi-Fi (en0): http://10.1.2.3:8484/telemetry", parts[1])
        assertEquals("Wi-Fi (wlan0): http://192.168.1.42:8484/telemetry", parts[2])
        assertEquals("LAN (rmnet0): http://172.20.10.4:8484/telemetry", parts[3])
    }

    @Test
    fun `ranked endpoints preserve priority order and de-duplicate`() {
        val ranked = BridgePolicy.rankEndpoints(
            listOf(
                "wlan0" to "192.168.1.5",
                "wlan1" to "192.168.1.5",
                "ap0" to "192.168.43.1"
            )
        )
        assertEquals(listOf("ap0" to "192.168.43.1", "wlan0" to "192.168.1.5"), ranked)
    }

    @Test
    fun `a single endpoint is labelled through the same formatter`() {
        assertEquals(
            "Hotspot (ap0): http://192.168.43.1:8484/telemetry",
            BridgePolicy.formatEndpoint("ap0", "192.168.43.1")
        )
        assertEquals(
            "Wi-Fi (wlan0): http://192.168.1.9:9000/telemetry",
            BridgePolicy.formatEndpoint("wlan0", "192.168.1.9", port = 9000)
        )
        assertEquals(
            "LAN (rmnet0): http://172.20.10.4:8484/telemetry",
            BridgePolicy.formatEndpoint("rmnet0", "172.20.10.4")
        )
    }

    @Test
    fun `only private addresses are offered as webhook endpoints`() {
        // A public address is either a carrier interface or a measurement error; the
        // watch could never reach it over the private-LAN bridge anyway.
        val formatted = BridgePolicy.sortAndFormatEndpoints(
            listOf("rmnet0" to "100.64.1.5", "wlan0" to "8.8.8.8", "ap0" to "192.168.43.1")
        )
        assertEquals("Hotspot (ap0): http://192.168.43.1:8484/telemetry", formatted)
    }

    @Test
    fun `an interface with no usable address falls back to an explanatory loopback url`() {
        val expected = "http://127.0.0.1:8484/telemetry (Enable Wi-Fi or 2.4GHz Hotspot)"
        assertEquals(expected, BridgePolicy.sortAndFormatEndpoints(emptyList()))
        assertEquals(expected, BridgePolicy.sortAndFormatEndpoints(listOf("wlan0" to "")))
        // A non-default port must be reflected rather than hardcoded.
        assertEquals(
            "Hotspot (ap0): http://192.168.43.1:9999/telemetry",
            BridgePolicy.sortAndFormatEndpoints(listOf("ap0" to "192.168.43.1"), port = 9999)
        )
    }

    @Test
    fun `duplicate addresses are reported once`() {
        val formatted = BridgePolicy.sortAndFormatEndpoints(
            listOf("wlan0" to "192.168.1.5", "wlan1" to "192.168.1.5")
        )
        assertEquals(1, formatted.split(BridgePolicy.ENDPOINT_SEPARATOR).size)
    }
}

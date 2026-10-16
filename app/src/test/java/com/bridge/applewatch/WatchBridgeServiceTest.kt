package com.bridge.applewatch

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bridge.applewatch.service.BridgePolicy
import com.bridge.applewatch.service.WatchBridgeService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDate

/**
 * Robolectric coverage for the Android-framework surface of WatchBridgeService:
 * the optional shared token (Q3), the peer gate, the step-baseline persistence added
 * for OPEN-04, and a clean service lifecycle.
 *
 * The service's decision logic is covered separately and more thoroughly by
 * BridgePolicyTest; these tests only prove the framework wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WatchBridgeServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WatchBridgeService.clearAuthToken(context)
        WatchBridgeService.clearPersistedStepBaseline(context)
    }

    @After
    fun tearDown() {
        WatchBridgeService.clearAuthToken(context)
        WatchBridgeService.clearPersistedStepBaseline(context)
    }

    @Test
    fun `token defaults to disabled and persists once set`() {
        assertNull("an unconfigured bridge must not require a token", WatchBridgeService.authToken)

        WatchBridgeService.setAuthToken(context, "pixel-secret")
        assertEquals("pixel-secret", WatchBridgeService.authToken)

        // Prove it actually persisted to disk rather than only living in memory.
        WatchBridgeService.authTokenForTestReset()
        assertNull(WatchBridgeService.authToken)
        WatchBridgeService.loadAuthToken(context)
        assertEquals("pixel-secret", WatchBridgeService.authToken)
    }

    @Test
    fun `blank token clears the requirement`() {
        WatchBridgeService.setAuthToken(context, "pixel-secret")
        WatchBridgeService.setAuthToken(context, "   ")
        assertNull(WatchBridgeService.authToken)

        // A reload must also observe "no token" rather than an empty string.
        WatchBridgeService.authTokenForTestReset()
        WatchBridgeService.loadAuthToken(context)
        assertNull(WatchBridgeService.authToken)

        // clearAuthToken must remove the persisted value too, or a restart would
        // resurrect a token the user thought they had removed.
        WatchBridgeService.setAuthToken(context, "pixel-secret")
        WatchBridgeService.clearAuthToken(context)
        WatchBridgeService.loadAuthToken(context)
        assertNull(WatchBridgeService.authToken)
    }

    @Test
    fun `service starts foreground and shuts down cleanly`() {
        val controller = Robolectric.buildService(WatchBridgeService::class.java)
        val service = controller.create().get()

        assertNotNull("service instance should be created", service)
        assertFalse("service must not be marked shutting down while alive",
            WatchBridgeService.shuttingDownForTest)

        // onDestroy must complete without throwing and must flag the shutdown so the
        // accept loop suppresses its "HTTP Server Error" log.
        controller.destroy()
        assertTrue("onDestroy should record shutdown", WatchBridgeService.shuttingDownForTest)
    }

    @Test
    fun `peer gate is reachable through the service facade`() {
        assertTrue(WatchBridgeService.isPrivateNetworkAddress("192.168.1.20"))
        assertTrue(WatchBridgeService.isPrivateNetworkAddress("127.0.0.1"))
        assertFalse(WatchBridgeService.isPrivateNetworkAddress("8.8.8.8"))
        assertFalse(WatchBridgeService.isPrivateNetworkAddress(null))
    }

    // ------------------------------------- persisted step baseline (OPEN-04)

    @Test
    fun `step baseline is restored across a service restart`() {
        val today = LocalDate.now().toEpochDay()

        // A restart must observe the same baseline the previous run persisted,
        // otherwise the watch's cumulative counter is re-baselined and one whole
        // inter-restart interval of steps is lost.
        assertEquals(
            "a fresh install must report the no-baseline sentinel, not a fake 0",
            BridgePolicy.NO_STEP_BASELINE,
            persistedBaseline()
        )

        WatchBridgeService.persistStepBaseline(context, 9_000L, today)
        assertEquals(9_000L, persistedBaseline())
    }

    @Test
    fun `service onCreate adopts the persisted baseline`() {
        val today = LocalDate.now().toEpochDay()
        WatchBridgeService.persistStepBaseline(context, 4_242L, today)

        // onCreate must read the store, not start from NO_STEP_BASELINE.
        val service = Robolectric.buildService(WatchBridgeService::class.java).create().get()
        try {
            assertEquals(4_242L, lastCumulativeSteps(service))
        } finally {
            destroy(service)
        }
    }

    @Test
    fun `a baseline from a previous day is discarded`() {
        val yesterday = LocalDate.now().toEpochDay() - 1L
        WatchBridgeService.persistStepBaseline(context, 12_000L, yesterday)

        // The counter restarts at midnight, so yesterday's baseline must NOT be
        // reused: doing so would either emit a bogus delta or swallow today's steps.
        assertEquals(BridgePolicy.NO_STEP_BASELINE, persistedBaseline())

        val service = Robolectric.buildService(WatchBridgeService::class.java).create().get()
        try {
            assertEquals(BridgePolicy.NO_STEP_BASELINE, lastCumulativeSteps(service))
        } finally {
            destroy(service)
        }
    }

    @Test
    fun `the no-baseline sentinel is never persisted`() {
        val today = LocalDate.now().toEpochDay()
        WatchBridgeService.persistStepBaseline(context, BridgePolicy.NO_STEP_BASELINE, today)
        // Writing the sentinel would make a later load look like a real baseline.
        assertEquals(BridgePolicy.NO_STEP_BASELINE, persistedBaseline())
        val prefs = context.getSharedPreferences("aw9_step_baseline", Context.MODE_PRIVATE)
        assertFalse(
            "storing the sentinel would resurrect it on the next load",
            prefs.contains("savedSteps")
        )
    }

    // ------------------------------------------------- HTTP hardening (OPEN-03)

    @Test
    fun `the http port answers a cors preflight without a token`() {
        val server = startBridge()
        try {
            // A browser never attaches credentials to OPTIONS, so the preflight must
            // be answered before the auth check or no browser client can ever work.
            WatchBridgeService.setAuthToken(context, "required-secret")

            val response = rawRequest(server.port, "OPTIONS /telemetry HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertNotNull(
                "expected the bridge to accept an HTTP connection on :${server.port} " +
                    "(last error: $lastSocketError)",
                response
            )
            assertTrue(
                "expected a 204 preflight, got: $response",
                response!!.startsWith("HTTP/1.1 204")
            )
            assertTrue(
                "preflight must advertise the token header",
                response.contains(
                    "Access-Control-Allow-Headers: Content-Type, X-Bridge-Token, Authorization"
                )
            )
            assertTrue(response.contains("Access-Control-Allow-Methods: GET, POST, OPTIONS"))
        } finally {
            destroy(server.service)
        }
    }

    @Test
    fun `a token-protected status request is refused without the token`() {
        val server = startBridge()
        try {
            WatchBridgeService.setAuthToken(context, "required-secret")

            val refused = rawRequest(server.port, "GET /status HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertNotNull(
                "expected the bridge to accept an HTTP connection (last error: $lastSocketError)",
                refused
            )
            assertTrue("expected 401, got: $refused", refused!!.startsWith("HTTP/1.1 401"))

            val allowed = rawRequest(
                server.port,
                "GET /status HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Bridge-Token: required-secret\r\n\r\n"
            )
            assertNotNull("expected the second request to be served (last error: $lastSocketError)", allowed)
            assertTrue("expected 200, got: $allowed", allowed!!.startsWith("HTTP/1.1 200"))
            assertTrue(allowed.contains("\"status\":\"online\""))
            // 401/403 responses must carry the CORS headers too, or a browser client
            // cannot read the rejection and retry with a token.
            assertTrue(
                refused.contains(
                    "Access-Control-Allow-Headers: Content-Type, X-Bridge-Token, Authorization"
                )
            )
        } finally {
            destroy(server.service)
        }
    }

    /** A running service plus the loopback port its listener actually bound. */
    private class RunningBridge(val service: WatchBridgeService, val port: Int)

    // ------------------------------------- parser wiring into the service

    @Test
    fun `a Shortcuts-shaped telemetry body reaches the service state`() {
        val server = startBridge()
        try {
            val body = """
                {"heart_rate":68,"walking_running_distance":2.4,
                 "blood_oxygen_saturation":0.985,"active_energy":310.5}
            """.trimIndent()
            val response = postTelemetry(server.port, body)
            assertNotNull("no response (last error: $lastSocketError)", response)
            assertTrue("expected 200, got: $response", response!!.startsWith("HTTP/1.1 200"))

            val state = WatchBridgeService.telemetry.value
            // Proves the service is wired to the universal parser and not to the old
            // camelCase-only reader: none of these keys are native field names.
            assertEquals(68, state.heartRateBpm)
            assertEquals(2400.0, state.distanceMeters, 1e-6)
            assertEquals(98.5, state.spo2Percent, 1e-6)
            assertEquals(310.5, state.activeKcal, 1e-6)
        } finally {
            destroy(server.service)
        }
    }

    @Test
    fun `a Health Auto Export body is summed and committed`() {
        val server = startBridge()
        try {
            val body = """{"data":{"metrics":[
                 {"name":"heart_rate","units":"bpm","data":[{"Avg":74}]},
                 {"name":"step_count","units":"count","data":[{"qty":400},{"qty":350}]}]}}"""
            val response = postTelemetry(server.port, body)
            assertNotNull("no response (last error: $lastSocketError)", response)
            assertTrue("expected 200, got: $response", response!!.startsWith("HTTP/1.1 200"))

            val state = WatchBridgeService.telemetry.value
            assertEquals(74, state.heartRateBpm)
            assertEquals(750L, state.stepsToday)
        } finally {
            destroy(server.service)
        }
    }

    @Test
    fun `a malformed body is answered and leaves the known state intact`() {
        val server = startBridge()
        try {
            postTelemetry(server.port, """{"heart_rate":71}""")
            assertEquals(71, WatchBridgeService.telemetry.value.heartRateBpm)

            // The handler swallows malformed JSON into a log line rather than letting
            // it escape the connection, so the client still gets a response.
            val response = postTelemetry(server.port, "{definitely not json")
            assertNotNull("malformed body must still be answered (last error: $lastSocketError)", response)
            assertTrue(response!!.startsWith("HTTP/1.1 200"))
            assertEquals(71, WatchBridgeService.telemetry.value.heartRateBpm)
        } finally {
            destroy(server.service)
        }
    }

    /** POSTs a JSON body to the bridge's real listener and returns the raw response. */
    private fun postTelemetry(port: Int, body: String): String? {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return rawRequest(
            port,
            "POST /telemetry HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "\r\n" +
                body
        )
    }

    /**
     * Starts the service and opens a second listener on an **ephemeral** port for the
     * test to talk to.
     *
     * The service's own listener binds the fixed production port 8484, and a
     * predecessor's socket is not released instantly, so binding that port per test is
     * inherently racy. [WatchBridgeService] is not hard-wired to one port, so the test
     * starts its own listener on port 0 instead and reads back the port the OS
     * assigned. The handler, the peer gate, the auth check and the CORS preflight are
     * all exercised exactly as in production; only the port number differs.
     */
    private fun startBridge(): RunningBridge {
        val service = Robolectric.buildService(WatchBridgeService::class.java).create().get()
        val start = WatchBridgeService::class.java
            .getDeclaredMethod("startLocalHttpServer", Int::class.javaPrimitiveType)
        start.isAccessible = true
        start.invoke(service, 0)

        val field = WatchBridgeService::class.java.getDeclaredField("serverSocket")
        field.isAccessible = true
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val socket = field.get(service) as? java.net.ServerSocket
            if (socket != null && socket.isBound && !socket.isClosed) {
                return RunningBridge(service, socket.localPort)
            }
            Thread.sleep(20)
        }
        destroy(service)
        throw AssertionError(
            "the bridge never bound a listener; recent logs: " +
                WatchBridgeService.telemetry.value.recentLogs.take(5)
        )
    }

    /** Opens the bridge port and returns the raw response, or null if it is unreachable. */
    private fun rawRequest(port: Int, request: String): String? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.soTimeout = 2_000
            // Deliberately not closed with `use`: closing the writer closes the
            // underlying socket, so the response could never be read back.
            val out = PrintWriter(socket.getOutputStream(), true)
            out.print(request)
            // PrintWriter only auto-flushes on println, so flush explicitly or the
            // server never sees the request line.
            out.flush()
            BufferedReader(InputStreamReader(socket.getInputStream())).use { it.readText() }
        }
    } catch (e: Exception) {
        lastSocketError = "${e.javaClass.name}: ${e.message}"
        null
    }

    private var lastSocketError: String = "none"

    private fun persistedBaseline(): Long =
        WatchBridgeService.loadPersistedStepBaseline(context, LocalDate.now().toEpochDay())

    /**
     * Closes the listener. Every service instantiated here is destroyed before the test
     * ends, so a still-bound predecessor cannot make a later service fail to bind the
     * fixed production port.
     */
    private fun destroy(service: WatchBridgeService) {
        try {
            service.onDestroy()
        } catch (_: Exception) {
        }
    }

    /** Reads the service's private baseline field so onCreate's adoption is observable. */
    private fun lastCumulativeSteps(service: WatchBridgeService): Long {
        val field = WatchBridgeService::class.java.getDeclaredField("lastCumulativeSteps")
        field.isAccessible = true
        return field.getLong(service)
    }
}

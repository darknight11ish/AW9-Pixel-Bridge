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
 * for OPEN-04, the hardened HTTP surface (OPEN-03) and the parser wiring (OPEN-07).
 *
 * The service's decision logic is covered separately and more thoroughly by
 * BridgePolicyTest; these tests only prove the framework wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WatchBridgeServiceTest {

    private lateinit var context: Context

    /**
     * The one listener every HTTP test in this class talks to.
     *
     * The service binds the fixed production port 8484 on `onCreate`, and binding that
     * port once per test is inherently racy because a predecessor's socket is not
     * released instantly. It is therefore started once here, on an ephemeral port via
     * the same `startLocalHttpServer` path the service uses, and reused for every
     * request in the class. [stopBridge] closes it again.
     */
    private var bridge: WatchBridgeService? = null
    private var bridgePort: Int = -1

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WatchBridgeService.clearAuthToken(context)
        WatchBridgeService.clearPersistedStepBaseline(context)
    }

    @After
    fun tearDown() {
        stopBridge()
        WatchBridgeService.clearAuthToken(context)
        WatchBridgeService.clearPersistedStepBaseline(context)
    }

    // ------------------------------------------------------------ token (Q3)

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

    // --------------------------------------------------------- lifecycle

    @Test
    fun `service starts foreground and shuts down cleanly`() {
        val controller = Robolectric.buildService(WatchBridgeService::class.java)
        val service = controller.create().get()

        assertNotNull("service instance should be created", service)
        assertFalse(
            "service must not be marked shutting down while alive",
            WatchBridgeService.shuttingDownForTest
        )

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
        // A browser never attaches credentials to OPTIONS, so the preflight must be
        // answered before the auth check or no browser client can ever work.
        WatchBridgeService.setAuthToken(context, "required-secret")

        attempt("OPTIONS preflight") {
            val response = rawRequest("OPTIONS /telemetry HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertNotNull("no response (last error: $lastSocketError)", response)
            assertTrue("expected 204, got: $response", response!!.startsWith("HTTP/1.1 204"))
            assertTrue(
                "preflight must advertise the token header",
                response.contains(
                    "Access-Control-Allow-Headers: Content-Type, X-Bridge-Token, Authorization"
                )
            )
            assertTrue(response.contains("Access-Control-Allow-Methods: GET, POST, OPTIONS"))
        }
    }

    @Test
    fun `a token-protected status request is refused without the token`() {
        WatchBridgeService.setAuthToken(context, "required-secret")

        attempt("token gate") {
            val refused = rawRequest("GET /status HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertNotNull("no response (last error: $lastSocketError)", refused)
            assertTrue("expected 401, got: $refused", refused!!.startsWith("HTTP/1.1 401"))
            // 401 responses must carry the CORS headers too, or a browser client cannot
            // read the rejection and retry with a token.
            assertTrue(
                refused.contains(
                    "Access-Control-Allow-Headers: Content-Type, X-Bridge-Token, Authorization"
                )
            )
        }
        attempt("token accepted") {
            val allowed = rawRequest(
                "GET /status HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Bridge-Token: required-secret\r\n\r\n"
            )
            assertNotNull("no response (last error: $lastSocketError)", allowed)
            assertTrue("expected 200, got: $allowed", allowed!!.startsWith("HTTP/1.1 200"))
            assertTrue(allowed.contains("\"status\":\"online\""))
        }
    }

    // ------------------------------------- parser wiring into the service

    @Test
    fun `a Shortcuts-shaped telemetry body reaches the service state`() {
        val body = """
            {"heart_rate":68,"walking_running_distance":2.4,
             "blood_oxygen_saturation":0.985,"active_energy":310.5}
        """.trimIndent()
        attempt("Shortcuts body") {
            val response = postTelemetry(body)
            assertNotNull("no response (last error: $lastSocketError)", response)
            assertTrue("expected 200, got: $response", response!!.startsWith("HTTP/1.1 200"))
        }

        val state = WatchBridgeService.telemetry.value
        // Proves the service is wired to the universal parser and not to the old
        // camelCase-only reader: none of these keys are native field names.
        assertEquals(68, state.heartRateBpm)
        assertEquals(2400.0, state.distanceMeters, 1e-6)
        assertEquals(98.5, state.spo2Percent, 1e-6)
        assertEquals(310.5, state.activeKcal, 1e-6)
    }

    @Test
    fun `a Health Auto Export body is summed and committed`() {
        val body = """{"data":{"metrics":[
             {"name":"heart_rate","units":"bpm","data":[{"Avg":74}]},
             {"name":"step_count","units":"count","data":[{"qty":400},{"qty":350}]}]}}"""
        attempt("Health Auto Export body") {
            val response = postTelemetry(body)
            assertNotNull("no response (last error: $lastSocketError)", response)
            assertTrue("expected 200, got: $response", response!!.startsWith("HTTP/1.1 200"))
        }

        val state = WatchBridgeService.telemetry.value
        assertEquals(74, state.heartRateBpm)
        assertEquals(750L, state.stepsToday)
    }

    @Test
    fun `a malformed body is answered and leaves the known state intact`() {
        attempt("well-formed body") {
            val response = postTelemetry("""{"heart_rate":71}""")
            assertNotNull("no response (last error: $lastSocketError)", response)
        }
        assertEquals(71, WatchBridgeService.telemetry.value.heartRateBpm)

        // The handler swallows malformed JSON into a log line rather than letting it
        // escape the connection, so the client still gets a response.
        attempt("malformed body") {
            val response = postTelemetry("{definitely not json")
            assertNotNull("malformed body must still be answered (last error: $lastSocketError)", response)
            assertTrue("expected 200, got: $response", response!!.startsWith("HTTP/1.1 200"))
        }
        assertEquals(71, WatchBridgeService.telemetry.value.heartRateBpm)
    }

    /**
     * Runs one client interaction against the shared bridge.
     *
     * The accepted socket and its semaphore permit are released asynchronously, so a
     * request that follows another immediately can time out on a loaded CI runner even
     * though the server is healthy. Retrying the whole interaction keeps the assertions
     * strict (a wrong status or body still fails) while removing that timing
     * sensitivity.
     */
    private fun attempt(what: String, block: () -> Unit) {
        var lastFailure: AssertionError? = null
        repeat(3) { attemptNumber ->
            try {
                block()
                return
            } catch (e: AssertionError) {
                lastFailure = e
                if (attemptNumber < 2) {
                    stopBridge()
                    Thread.sleep(200L * (attemptNumber + 1))
                }
            }
        }
        throw AssertionError("$what failed after 3 attempts: ${lastFailure?.message}", lastFailure)
    }

    // ------------------------------------------------------------- helpers

    /** POSTs a JSON body to the shared bridge listener and returns the raw response. */
    private fun postTelemetry(body: String): String? {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return rawRequest(
            "POST /telemetry HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "\r\n" +
                body
        )
    }

    /**
     * Starts the shared listener on an ephemeral port and returns the port it bound.
     *
     * The service's own listener binds the fixed production port 8484 on `onCreate`;
     * this adds a second one through the same code path on port 0, so the tests do not
     * contend for the production port. Everything else — peer gate, auth, CORS, the
     * handler — is exercised unchanged.
     */
    private fun startBridgeIfNeeded(): Int {
        bridge?.let { if (bridgePort > 0) return bridgePort }
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
                bridge = service
                bridgePort = socket.localPort
                return bridgePort
            }
            Thread.sleep(20)
        }
        destroy(service)
        throw AssertionError(
            "the bridge never bound a listener; recent logs: " +
                WatchBridgeService.telemetry.value.recentLogs.take(5)
        )
    }

    private fun stopBridge() {
        bridge?.let { destroy(it) }
        bridge = null
        bridgePort = -1
    }

    /**
     * Opens the bridge port and returns the raw response headers plus body, or null if
     * it is unreachable.
     *
     * The body is read by its declared `Content-Length` rather than by reading to EOF,
     * because relying on the server's `close()` to signal end-of-stream behaved
     * differently on the Linux CI runner than on the development host.
     */
    private fun rawRequest(request: String): String? {
        val port = startBridgeIfNeeded()
        return try {
            rawRequestOnce(port, request)
        } catch (e: Exception) {
            lastSocketError = "${e.javaClass.name}: ${e.message}"
            null
        }
    }

    private fun rawRequestOnce(port: Int, request: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 8_000
            // Deliberately not closed with `use`: closing the writer closes the
            // underlying socket, so the response could never be read back.
            val out = PrintWriter(socket.getOutputStream(), true)
            out.print(request)
            // PrintWriter only auto-flushes on println, so flush explicitly or the
            // server never sees the request line.
            out.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val headers = StringBuilder()
            var contentLength = -1
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headers.append(line).append("\r\n")
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                }
            }
            if (contentLength > 0) {
                val body = CharArray(contentLength)
                var total = 0
                while (total < contentLength) {
                    val read = reader.read(body, total, contentLength - total)
                    if (read == -1) break
                    total += read
                }
                headers.append("\r\n").append(String(body, 0, total))
            }
            return headers.toString()
        }
    }

    private var lastSocketError: String = "none"

    private fun persistedBaseline(): Long =
        WatchBridgeService.loadPersistedStepBaseline(context, LocalDate.now().toEpochDay())

    /** Closes a service's listener so a later instance can bind the same port. */
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

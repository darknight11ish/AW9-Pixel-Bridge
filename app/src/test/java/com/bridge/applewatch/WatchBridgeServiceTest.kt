package com.bridge.applewatch

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bridge.applewatch.health.HealthDataSink
import com.bridge.applewatch.model.WebhookTelemetryPayload
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
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    @Test
    fun `the persisted-token probe is truthful before the service starts`() {
        // The dashboard used to read the service's static `authToken`, which only
        // loadAuthToken() inside onCreate populates. Before the service started — or
        // permanently, if the Bluetooth permission was denied so it never started — the
        // card wrongly claimed "No Token Set" while a token was stored and enforced.
        assertFalse("nothing persisted yet", WatchBridgeService.hasPersistedAuthToken(context))

        WatchBridgeService.setAuthToken(context, "pixel-secret")
        // Drop the static to prove the probe reads storage rather than memory.
        WatchBridgeService.authTokenForTestReset()
        assertNull("the in-memory copy is gone", WatchBridgeService.authToken)
        assertTrue(
            "the probe must still see the persisted token",
            WatchBridgeService.hasPersistedAuthToken(context)
        )

        WatchBridgeService.clearAuthToken(context)
        assertFalse(WatchBridgeService.hasPersistedAuthToken(context))
    }

    @Test
    fun `an oversized declared body is refused without allocating for it`() {
        // The body buffer used to be sized from the client-supplied Content-Length before
        // a single byte was read, so this request allocated ~2 GB and killed the process
        // with an OutOfMemoryError (an Error, so the handler's `catch (e: Exception)`
        // never saw it). No body is sent: the refusal must happen first.
        val response = rawRequest(
            "POST /telemetry HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: 2000000000\r\n" +
                "\r\n"
        )
        assertNotNull("expected a response (last error: ${socketError.get()})", response)
        assertTrue(
            "expected 413 Payload Too Large, got: ${response!!.take(40)}",
            response.startsWith("HTTP/1.1 413")
        )
    }

    @Test
    fun `an oversized header block is refused`() {
        // An endless header line would otherwise grow the reader's buffer without limit:
        // the socket timeout bounds idle time, not total bytes.
        val hugeHeader = "X-Filler: " + "a".repeat(8 * 1024 + 1)
        val response = rawRequest(
            "GET /status HTTP/1.1\r\nHost: 127.0.0.1\r\n$hugeHeader\r\n\r\n"
        )
        assertNotNull("expected a response (last error: ${socketError.get()})", response)
        assertTrue(
            "expected 431 Request Header Fields Too Large, got: ${response!!.take(40)}",
            response.startsWith("HTTP/1.1 431")
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
            assertNotNull("no response (last error: ${socketError.get()})", response)
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
            assertNotNull("no response (last error: ${socketError.get()})", refused)
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
            assertNotNull("no response (last error: ${socketError.get()})", allowed)
            assertTrue("expected 200, got: $allowed", allowed!!.startsWith("HTTP/1.1 200"))
            assertTrue(allowed.contains("\"status\":\"online\""))
        }
    }

    // ------------------------------------- parser wiring into the service

    @Test
    fun `a Shortcuts-shaped body updates the dashboard even without Health Connect`() {
        val body = """
            {"heart_rate":68,"walking_running_distance":2.4,
             "blood_oxygen_saturation":0.985,"active_energy":310.5}
        """.trimIndent()
        attempt("Shortcuts body") {
            val response = postTelemetry(body)
            assertNotNull("no response (last error: ${socketError.get()})", response)
            // Health Connect cannot be constructed under Robolectric, so the honest
            // answer is a retryable 503 — NOT the `200 {"status":"synced"}` that shipped
            // and told clients data had been stored when nothing was written.
            assertTrue(
                "expected 503 when Health Connect is unavailable, got: $response",
                response!!.startsWith("HTTP/1.1 503")
            )
            assertTrue(
                "the failure must be identifiable: $response",
                response.contains("HEALTH_CONNECT_UNAVAILABLE")
            )
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
    fun `a Health Auto Export body is summed and reaches the dashboard`() {
        val body = """{"data":{"metrics":[
             {"name":"heart_rate","units":"bpm","data":[{"Avg":74}]},
             {"name":"step_count","units":"count","data":[{"qty":400},{"qty":350}]}]}}"""
        attempt("Health Auto Export body") {
            val response = postTelemetry(body)
            assertNotNull("no response (last error: ${socketError.get()})", response)
            assertTrue("expected 503, got: $response", response!!.startsWith("HTTP/1.1 503"))
        }

        val state = WatchBridgeService.telemetry.value
        assertEquals(74, state.heartRateBpm)
        assertEquals(750L, state.stepsToday)
    }

    @Test
    fun `a malformed body is answered and leaves the known state intact`() {
        attempt("well-formed body") {
            val response = postTelemetry("""{"heart_rate":71}""")
            assertNotNull("no response (last error: ${socketError.get()})", response)
        }
        assertEquals(71, WatchBridgeService.telemetry.value.heartRateBpm)

        // Malformed JSON is a client bug: it must be answered 400, not acknowledged as a
        // successful sync. It used to return `200 {"status":"synced"}` because the
        // non-strict parser turned any parse failure into an empty payload.
        attempt("malformed body") {
            val response = postTelemetry("{definitely not json")
            assertNotNull("malformed body must still be answered (last error: ${socketError.get()})", response)
            assertTrue("expected 400, got: $response", response!!.startsWith("HTTP/1.1 400"))
            assertTrue("body must say why: $response", response.contains("bad_request"))
        }
        // A rejected body must not disturb what the dashboard already shows.
        assertEquals(71, WatchBridgeService.telemetry.value.heartRateBpm)
    }

    @Test
    fun `an empty body is rejected rather than reported as synced`() {
        attempt("empty body") {
            val response = postTelemetry("")
            assertNotNull("no response (last error: ${socketError.get()})", response)
            assertTrue("expected 400, got: $response", response!!.startsWith("HTTP/1.1 400"))
        }
    }

    // ------------------------------------------------- routing (D3 field defect)

    @Test
    fun `an unknown path is a 404 and never runs the ingest`() {
        // The old dispatch ended in a catch-all `else` that ran the full telemetry
        // ingest, so `GET /unknown` was answered as a successful POST. On a real device a
        // GET carrying a JSON body reached Health Connect and attempted a write.
        attempt("unknown path") {
            val response = rawRequest("GET /unknown HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertNotNull("no response (last error: ${socketError.get()})", response)
            assertTrue("expected 404, got: ${response!!.take(40)}", response.startsWith("HTTP/1.1 404"))
        }
    }

    @Test
    fun `a GET to telemetry is 405 because GET must never mutate`() {
        attempt("GET /telemetry") {
            val response = rawRequest("GET /telemetry HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertNotNull("no response (last error: ${socketError.get()})", response)
            assertTrue("expected 405, got: ${response!!.take(40)}", response.startsWith("HTTP/1.1 405"))
            assertTrue("must advertise the allowed method: $response", response.contains("POST"))
        }
    }

    @Test
    fun `a POST to status is 405`() {
        attempt("POST /status") {
            val bytes = "{}".toByteArray()
            val response = rawRequest(
                "POST /status HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Content-Length: ${bytes.size}\r\n\r\n" + String(bytes)
            )
            assertNotNull("no response (last error: ${socketError.get()})", response)
            assertTrue("expected 405, got: ${response!!.take(40)}", response.startsWith("HTTP/1.1 405"))
        }
    }

    @Test
    fun `the root path is still a telemetry alias but only for POST`() {
        attempt("POST /") {
            val bytes = "{\"battery\":55}".toByteArray()
            val response = rawRequest(
                "POST / HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Content-Length: ${bytes.size}\r\n\r\n" + String(bytes)
            )
            assertNotNull("no response (last error: ${socketError.get()})", response)
            assertFalse(
                "root must not be a 404; it is a documented alias: ${response!!.take(40)}",
                response.startsWith("HTTP/1.1 404")
            )
        }
        assertEquals(55, WatchBridgeService.telemetry.value.batteryPercent)
    }

    // ------------------------------------------------- /control honesty (D4)

    @Test
    fun `an unknown control command is a 400, not executed`() {
        attempt("unknown command") {
            val response = postControl("""{"command":"nonsense"}""")
            assertNotNull("no response (last error: ${socketError.get()})", response)
            assertTrue(
                "an ignored command must not be reported as executed: ${response!!.take(60)}",
                response.startsWith("HTTP/1.1 400")
            )
            assertTrue("must be identifiable as rejected: $response", response.contains("rejected"))
        }
    }

    @Test
    fun `a control body with no command is a 400`() {
        attempt("missing command") {
            val response = postControl("""{"notACommand":true}""")
            assertNotNull("no response (last error: ${socketError.get()})", response)
            assertTrue("expected 400, got: ${response!!.take(40)}", response.startsWith("HTTP/1.1 400"))
        }
    }

    @Test
    fun `concurrent telemetry posts are all served under the concurrency limit`() {
        // Start the listener before any worker thread touches shared state.
        startBridgeIfNeeded()

        // Deliberately modest: this test is about the handler serving a burst without
        // dropping or refusing requests, not about loading the host. Sending far more than
        // the cap only measures how fast the machine retires sockets, which is what made
        // an earlier 3x version flaky on CI while passing locally.
        val requestCount = WatchBridgeService.HTTP_MAX_CONCURRENCY + 2
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val statuses = Collections.synchronizedList(mutableListOf<String>())
        val startGate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(requestCount)

        try {
            val futures = (1..requestCount).map { index ->
                executor.submit {
                    // Hold every worker at the gate so the burst is genuinely concurrent.
                    startGate.await(10, TimeUnit.SECONDS)
                    val response = try {
                        rawRequest(
                            "POST /telemetry HTTP/1.1\r\n" +
                                "Host: 127.0.0.1\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${"""{"heart_rate":$index}""".toByteArray().size}\r\n" +
                                "\r\n" +
                                """{"heart_rate":$index}"""
                        )
                    } catch (e: Exception) {
                        failures.add("request $index threw ${e.javaClass.simpleName}: ${e.message}")
                        return@submit
                    }
                    if (response == null) {
                        failures.add("request $index got no response (last: ${socketError.get()})")
                        return@submit
                    }
                    val status = response.lineSequence().firstOrNull() ?: "<empty>"
                    statuses.add(status)
                    if (!status.startsWith("HTTP/1.1 200")) {
                        failures.add("request $index -> $status")
                    }
                }
            }
            startGate.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(
            "every concurrent request must be served; failures: $failures",
            0,
            failures.size
        )
        assertEquals(requestCount, statuses.size)
        // The healthy path must never be the overload path: a 503 here would mean the
        // permit wait is too short for a burst this size.
        assertFalse("no request may be refused while capacity exists", statuses.any { it.contains("503") })
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


    // ------------------------------------- write-failure handling (D1 field defect)

    /**
     * The regression that matters most.
     *
     * On a real device with Health Connect permission ungranted, `insertRecords` threw
     * `SecurityException`. The service answered `200 {"status":"synced"}`, the dashboard
     * stayed at 0 BPM / 0 steps, and no test noticed, because under JVM/Robolectric the
     * Health Connect client is `null` so the write never threw. Installing a throwing
     * sink makes that path reachable — this is the only test that covers it.
     */
    @Test
    fun `a permission refusal is answered 403 and still updates the dashboard`() {
        startBridgeIfNeeded()
        bridge!!.overrideHealthSinkForTest(
            object : HealthDataSink {
                override val isAvailable = true
                override suspend fun writeTelemetryPayload(
                    payload: WebhookTelemetryPayload,
                    stepDelta: Long,
                    now: Instant
                ): Int = throw SecurityException(
                    "Caller requires android.permission.health.WRITE_STEPS to write " +
                        "record type class android.health.connect.datatypes.StepsRecord"
                )

                override suspend fun flushHeartRateSamples(
                    samples: List<androidx.health.connect.client.records.HeartRateRecord.Sample>
                ): Int = 0

                override suspend fun writeHrvRmssd(rmssdMs: Double, timestamp: Instant) = Unit
            }
        )

        val response = postTelemetry("""{"heartRate":72,"battery":62}""")
        assertNotNull("no response (last error: ${socketError.get()})", response)
        assertTrue(
            "a permission refusal must not be reported as a successful sync: ${response!!.take(60)}",
            response.startsWith("HTTP/1.1 403")
        )
        assertTrue("the failure must be identifiable: $response", response.contains("HEALTH_PERMISSION_DENIED"))

        // What arrived over the network is still true, and this is the only place the user
        // can see it. Before the fix this update was skipped entirely by the exception.
        val state = WatchBridgeService.telemetry.value
        assertEquals("the dashboard must reflect the received heart rate", 72, state.heartRateBpm)
        assertEquals("battery is never written to Health Connect, so it must always display", 62, state.batteryPercent)

        stopBridge()
    }

    @Test
    fun `a failed write does not consume the step delta`() {
        startBridgeIfNeeded()
        val sink = FlakySink()
        bridge!!.overrideHealthSinkForTest(sink)

        // 1. A successful sync establishes the baseline.
        val first = postTelemetry("""{"steps":5200}""")
        assertNotNull("no response (last error: ${socketError.get()})", first)
        assertTrue("expected 200 for a successful write, got: ${first!!.take(40)}", first.startsWith("HTTP/1.1 200"))
        assertEquals(5200L, persistedBaseline())

        // 2. The next sync fails, so its delta must NOT be consumed.
        sink.failNext = true
        val second = postTelemetry("""{"steps":5300}""")
        assertNotNull("no response (last error: ${socketError.get()})", second)
        assertTrue("expected 403, got: ${second!!.take(40)}", second.startsWith("HTTP/1.1 403"))
        assertEquals(
            "a failed write must not advance the baseline, or those 100 steps are lost",
            5200L,
            persistedBaseline()
        )

        // 3. Recovery: the next success writes the accumulated delta (5400 - 5200).
        sink.failNext = false
        val third = postTelemetry("""{"steps":5400}""")
        assertNotNull("no response (last error: ${socketError.get()})", third)
        assertTrue("expected 200, got: ${third!!.take(40)}", third.startsWith("HTTP/1.1 200"))
        assertEquals(
            "the delta must cover the steps rejected while the write was failing",
            200L,
            sink.lastStepDelta
        )
        assertEquals(5400L, persistedBaseline())

        stopBridge()
    }

    @Test
    fun `an implausible reading is never displayed`() {
        startBridgeIfNeeded()
        bridge!!.overrideHealthSinkForTest(FlakySink())

        // A 290 bpm heart rate and a 100.1 % SpO2 are both refused by the write layer.
        // They were still rendered on the dashboard, which is how the field test caught it.
        postTelemetry("""{"heartRate":290,"spo2":100.1}""")

        val state = WatchBridgeService.telemetry.value
        assertNotEquals("an impossible heart rate must not be shown as a live vital", 290, state.heartRateBpm)
        assertNotEquals("an impossible SpO2 must not be shown", 100.1, state.spo2Percent, 1e-9)

        stopBridge()
    }

    /** A sink whose failure can be toggled, to exercise retry and delta retention. */
    private class FlakySink : HealthDataSink {
        @Volatile var failNext = false
        @Volatile var lastStepDelta = 0L
        override val isAvailable = true

        override suspend fun writeTelemetryPayload(
            payload: WebhookTelemetryPayload,
            stepDelta: Long,
            now: Instant
        ): Int {
            if (failNext) throw SecurityException("Caller requires android.permission.health.WRITE_STEPS")
            lastStepDelta = stepDelta
            return if (stepDelta > 0) 1 else 0
        }

        override suspend fun flushHeartRateSamples(
            samples: List<androidx.health.connect.client.records.HeartRateRecord.Sample>
        ): Int = 0

        override suspend fun writeHrvRmssd(rmssdMs: Double, timestamp: Instant) = Unit
    }

    // ------------------------------------------------------------- helpers


    /** POSTs a JSON body to the shared bridge listener and returns the raw response. */
    private fun postControl(body: String): String? {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return rawRequest(
            "POST /control HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "\r\n" +
                body
        )
    }

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
    private fun rawRequest(request: String): String? = rawRequest(startBridgeIfNeeded(), request)

    private fun rawRequest(port: Int, request: String): String? {
        return try {
            rawRequestOnce(port, request)
        } catch (e: Exception) {
            socketError.set("${e.javaClass.name}: ${e.message}")
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

    /**
     * Last socket error for *this* thread. Thread-local because the concurrency test
     * drives the same helper from many worker threads at once.
     */
    private val socketError = ThreadLocal.withInitial { "none" }

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

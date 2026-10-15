package com.bridge.applewatch.service

/**
 * Pure, side-effect-free decision logic for [WatchBridgeService].
 *
 * Everything here is deliberately free of Android framework types so it can be
 * exercised by plain JVM unit tests. Before v1.0.2 this logic lived inline in the
 * service and was therefore untested.
 */
internal object BridgePolicy {

    /** Hop count never exceeds this, so the gate clears even if the stack stays silent. */
    const val MAX_CONNECT_ATTEMPTS = 2

    /** Base delay for the bounded reconnect backoff. */
    const val CONNECT_RETRY_BASE_DELAY_MS = 1_000L

    /** A 0x004C frame weaker than this is not considered the paired watch. */
    const val MIN_UNTRACKED_CONTINUITY_RSSI = -80

    /** Weight applied to the newest RSSI sample in the exponential moving average. */
    const val RSSI_EMA_ALPHA = 0.3

    /** Health Connect step records are bucketed into windows of this size. */
    const val STEP_WINDOW_SECONDS = 3_600L

    /** Sentinel meaning "no cumulative step counter has been observed yet". */
    const val NO_STEP_BASELINE = -1L

    /**
     * True only for loopback and RFC1918 (site-local) peers. 172.16/12 is not
     * covered by [java.net.InetAddress.isSiteLocalAddress], so it is matched
     * explicitly. This is defence in depth, not authentication.
     */
    fun isPrivateNetworkAddress(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        if (host == "::1" || host == "127.0.0.1" || host.startsWith("127.")) return true
        // IPv4-mapped IPv6 loopback, e.g. ::ffff:127.0.0.1
        if (host.startsWith("::ffff:")) return isPrivateNetworkAddress(host.substringAfter("::ffff:"))
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true
        if (host.startsWith("169.254.")) return true
        if (host.startsWith("172.")) {
            val second = host.split(".").getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        // Link-local IPv6 (fe80::/10) and unique-local IPv6 (fc00::/7)
        val lower = host.lowercase()
        if (lower.startsWith("fe8") || lower.startsWith("fe9") ||
            lower.startsWith("fea") || lower.startsWith("feb")
        ) return true
        if (lower.startsWith("fc") || lower.startsWith("fd")) return true
        return false
    }

    /**
     * Rejects 0x004C Continuity frames from nearby iPhones/Macs once the watch MAC
     * is known, and otherwise requires a strong enough signal to be plausibly the
     * watch rather than some other Apple device in the room.
     */
    fun shouldAcceptContinuityFrame(
        deviceAddress: String?,
        trackedAddress: String?,
        rssi: Int
    ): Boolean = when {
        trackedAddress.isNullOrBlank() -> rssi > MIN_UNTRACKED_CONTINUITY_RSSI
        deviceAddress != null && deviceAddress.equals(trackedAddress, ignoreCase = true) -> true
        else -> false
    }

    /**
     * Exponential moving average over RSSI. The first sample seeds the average so a
     * cold start does not read as an artificially distant device.
     */
    fun smoothRssi(previous: Double, sample: Int): Double =
        if (previous == 0.0) sample.toDouble() else RSSI_EMA_ALPHA * sample + (1.0 - RSSI_EMA_ALPHA) * previous

    /** Result of advancing the cumulative step counter. */
    data class StepAdvance(val baseline: Long, val delta: Long)

    /**
     * Advances the cumulative step baseline.
     *
     * A negative reading means "no step data in this payload" and leaves the
     * baseline untouched. The first non-negative reading only establishes the
     * baseline; writing it would dump a whole day of steps into one window.
     */
    fun advanceStepBaseline(baseline: Long, cumulativeSteps: Long): StepAdvance = when {
        cumulativeSteps < 0L -> StepAdvance(baseline, 0L)
        baseline == NO_STEP_BASELINE -> StepAdvance(cumulativeSteps, 0L)
        cumulativeSteps > baseline -> StepAdvance(cumulativeSteps, cumulativeSteps - baseline)
        else -> StepAdvance(baseline, 0L)
    }

    /**
     * Aligns a timestamp to the start of its step window. Step deltas are written
     * as intervals rather than instantaneous samples, so bucketing to a fixed
     * window keeps Health Connect's aggregation meaningful instead of distorting
     * every delta into a 60-second spike.
     */
    fun stepWindowStart(epochSeconds: Long, windowSeconds: Long = STEP_WINDOW_SECONDS): Long =
        epochSeconds - (epochSeconds % windowSeconds)

    /** Backoff before the nth reconnect attempt (0-based), doubling each time. */
    fun connectRetryDelayMs(attempt: Int): Long =
        CONNECT_RETRY_BASE_DELAY_MS shl attempt.coerceIn(0, 5)

    /**
     * Constant-time comparison for the optional shared bridge token. Returns true
     * when no token is configured, so an unconfigured bridge keeps working.
     */
    fun tokenAccepted(configuredToken: String?, presentedToken: String?): Boolean {
        val expected = configuredToken?.trim().orEmpty()
        if (expected.isEmpty()) return true
        val actual = presentedToken?.trim().orEmpty()
        if (actual.length != expected.length) return false
        var diff = 0
        for (i in expected.indices) {
            diff = diff or (expected[i].code xor actual[i].code)
        }
        return diff == 0
    }
}

package com.bridge.applewatch.health

import androidx.health.connect.client.records.HeartRateRecord
import com.bridge.applewatch.model.WebhookTelemetryPayload
import java.time.Instant

/**
 * The write surface `WatchBridgeService` needs from Health Connect.
 *
 * This exists so the service's *failure* handling can be tested. Previously the service
 * held a concrete [HealthConnectManager] whose client is `null` in every JVM test, so
 * `writeTelemetryPayload` always returned 0 without throwing and the `catch` block was
 * dead code. That is how a shipped build could discard the entire dashboard update and
 * still answer `{"status":"synced"}`: 115 tests passed because nothing ever threw.
 *
 * With this interface a test can install a sink that throws `SecurityException` on
 * `insertRecords` and assert what the service does about it.
 */
interface HealthDataSink {
    /** False when the Health Connect provider is absent or disabled on this device. */
    val isAvailable: Boolean

    suspend fun writeTelemetryPayload(
        payload: WebhookTelemetryPayload,
        stepDelta: Long,
        now: Instant = Instant.now()
    ): Int

    suspend fun flushHeartRateSamples(samples: List<HeartRateRecord.Sample>): Int

    suspend fun writeHrvRmssd(rmssdMs: Double, timestamp: Instant = Instant.now())

    /**
     * Reads back how many records of each type the provider actually stored since [since].
     * Defaults to empty so test doubles need not implement it.
     */
    suspend fun countRecordsSince(since: Instant): Map<String, Int> = emptyMap()
}

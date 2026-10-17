package com.bridge.applewatch.health

import com.bridge.applewatch.model.WebhookTelemetryPayload

/**
 * The single source of truth for which readings are physically plausible.
 *
 * These bounds are used in two places that previously disagreed:
 *
 * 1. the Health Connect write layer, which decides what may become a clinical record;
 * 2. the dashboard, which decides what to display as a live vital.
 *
 * Field testing showed the consequence of the disagreement: a `heartRate` of 290 was
 * correctly refused by the write layer (no record was created) yet still displayed as
 * "290 BPM", because the display update only checked `> 0`.
 *
 * The upper bounds are not arbitrary — they are the limits Health Connect's own record
 * constructors enforce, established by probing the resolved `connect-client:1.1.0`
 * artifact. Exceeding one of them throws `IllegalArgumentException` during record
 * construction, which would fail the whole insert batch.
 */
object HealthMetricRanges {
    const val MIN_HEART_RATE = 30
    const val MAX_HEART_RATE = 240

    const val MIN_RESTING_HEART_RATE = 30
    const val MAX_RESTING_HEART_RATE = 150

    const val MIN_RESPIRATORY_RATE = 4.0
    const val MAX_RESPIRATORY_RATE = 60.0

    const val MIN_SPO2 = 70.0
    const val MAX_SPO2 = 100.0

    /** Health Connect rejects an RMSSD above 200 ms. */
    const val MIN_HRV_MS = 1.0
    const val MAX_HRV_MS = 200.0

    /** Health Connect pads `StepsRecord` at 1,000,000 in each record. */
    const val MIN_STEPS = 1L
    const val MAX_STEPS = 1_000_000L

    /** Health Connect pads distance, energy and floors at 1,000,000 of their unit. */
    const val MAX_DISTANCE_METERS = 1_000_000.0
    const val MAX_ACTIVE_CALORIES = 1_000_000.0
    const val MAX_FLOORS = 1_000_000.0

    /** 72 hours; beyond this `sleepMinutes * 60` can overflow and invert the interval. */
    const val MAX_SLEEP_MINUTES = 72L * 60L

    fun isValidHeartRate(bpm: Int): Boolean = bpm in MIN_HEART_RATE..MAX_HEART_RATE

    fun isValidRestingHeartRate(bpm: Int): Boolean = bpm in MIN_RESTING_HEART_RATE..MAX_RESTING_HEART_RATE

    fun isValidRespiratoryRate(brpm: Double): Boolean = brpm in MIN_RESPIRATORY_RATE..MAX_RESPIRATORY_RATE

    fun isValidSpo2(percent: Double): Boolean = percent in MIN_SPO2..MAX_SPO2

    fun isValidHrv(ms: Double): Boolean = ms in MIN_HRV_MS..MAX_HRV_MS

    fun isValidStepDelta(delta: Long): Boolean = delta in MIN_STEPS..MAX_STEPS

    fun isValidDistance(meters: Double): Boolean = meters > 0.0 && meters <= MAX_DISTANCE_METERS

    fun isValidActiveCalories(kcal: Double): Boolean = kcal > 0.0 && kcal <= MAX_ACTIVE_CALORIES

    fun isValidFloors(floors: Double): Boolean = floors > 0.0 && floors <= MAX_FLOORS

    fun isValidSleepMinutes(minutes: Long): Boolean = minutes in 1..MAX_SLEEP_MINUTES

    fun isValidBattery(percent: Int): Boolean = percent in 0..100
}

/**
 * True when the payload carries at least one reading this app would write to Health
 * Connect (steps are handled separately because they are a delta, not an absolute).
 *
 * Used to decide whether an unavailable Health Connect should be reported as a failure:
 * a payload carrying only, say, the watch battery has nothing to write, so acknowledging
 * it is correct rather than misleading.
 */
fun WebhookTelemetryPayload.hasWritableHealthMetric(): Boolean =
    HealthMetricRanges.isValidHeartRate(heartRate) ||
        HealthMetricRanges.isValidRestingHeartRate(restingHeartRate) ||
        HealthMetricRanges.isValidRespiratoryRate(respiratoryRate) ||
        HealthMetricRanges.isValidSpo2(spo2) ||
        HealthMetricRanges.isValidHrv(hrvMs) ||
        HealthMetricRanges.isValidDistance(distanceMeters) ||
        HealthMetricRanges.isValidActiveCalories(activeCalories) ||
        HealthMetricRanges.isValidFloors(floorsClimbed) ||
        HealthMetricRanges.isValidSleepMinutes(sleepMinutes)

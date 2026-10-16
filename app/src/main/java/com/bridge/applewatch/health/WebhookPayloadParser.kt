package com.bridge.applewatch.health

import com.bridge.applewatch.model.WebhookTelemetryPayload
import org.json.JSONObject

/**
 * Universal `/telemetry` body adapter (OPEN-07).
 *
 * The Apple Watch cannot advertise the standard `0x180D` Heart Rate service, so the
 * only paths that deliver real watch data are HTTP pushes. Three different producers
 * exist in the field and each speaks a different JSON dialect:
 *
 * 1. **Native / this project's own watch agent** — flat camelCase, e.g. `{"heartRate":74}`.
 * 2. **Apple Shortcuts** ("Get Health Sample" -> "Get Contents of URL") — snake_case
 *    HealthKit names, e.g. `{"heart_rate":74,"oxygen_saturation":0.985}`.
 * 3. **Health Auto Export** (App Store) — nested `{"data":{"metrics":[{"name":..,"units":..,"data":[{"qty":..}]}]}}`.
 *
 * This parser normalises all three into a single [WebhookTelemetryPayload]. It is a
 * plain JVM object with no Android framework dependency so it is directly unit-testable,
 * and it never throws: malformed input yields [WebhookTelemetryPayload]'s defaults,
 * which downstream range checks (`spo2 in 70.0..100.0`, `steps < 0`) already treat as
 * "no reading" rather than writing fabricated values into Health Connect.
 *
 * Unit conventions, applied only when a producer omits explicit units:
 * - `blood_oxygen_saturation` / `oxygen_saturation` in `0.5..1.0` is a fraction and is
 *   scaled by 100 (HealthKit's `HKUnit.percent()` renders 98.5 % as `0.985`).
 * - `battery` / `battery_level` in `0.0..1.0` is a fraction and is scaled to 0-100.
 * - `distance_km` is kilometres and is scaled to metres.
 */
object WebhookPayloadParser {

    /**
     * Health Auto Export aggregates steps, energy, floors and distance as a *list* of
     * incremental quantities for the day, so those are summed. Every other metric is a
     * point-in-time reading and takes its latest sample.
     */
    private val SUMMED_METRICS = setOf(
        "step_count",
        "steps",
        "active_energy",
        "active_calories",
        "basal_energy_burned",
        "flights_climbed",
        "floors_climbed",
        "walking_running_distance",
        "distance"
    )

    /** Canonical command for the legacy boolean flag. */
    private const val FIND_MY_PIXEL = "FIND_MY_PIXEL"

    private const val NO_STEPS = -1L
    private const val NO_DOUBLE = -1.0
    private const val NO_INT = -1

    /**
     * Parses any supported `/telemetry` body. Never throws: a blank, truncated or
     * non-JSON body returns the default payload.
     */
    fun parse(rawJson: String): WebhookTelemetryPayload {
        if (rawJson.isBlank()) return WebhookTelemetryPayload()

        val root = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return WebhookTelemetryPayload()

        val control = resolveControlCommand(root)

        // Schema 3: Health Auto Export nests everything under data.metrics.
        val metrics = root.optJSONObject("data")?.optJSONArray("metrics")
        if (metrics != null) return parseHealthAutoExport(metrics, control)

        // Schemas 1 and 2 read from the same (possibly nested) object. `data` is
        // checked too, because some Shortcuts templates wrap the fields in it.
        val flat = root.optJSONObject("data") ?: root
        return parseFlat(flat, control)
    }

    // ------------------------------------------------------------------ schema 3

    private fun parseHealthAutoExport(
        metrics: org.json.JSONArray,
        control: String
    ): WebhookTelemetryPayload {
        var heartRate = 0
        var steps = NO_STEPS
        var distanceMeters = NO_DOUBLE
        var activeCalories = NO_DOUBLE
        var spo2 = NO_DOUBLE
        var floorsClimbed = NO_DOUBLE
        var hrvMs = NO_DOUBLE
        var restingHeartRate = 0
        var respiratoryRate = NO_DOUBLE
        var sleepMinutes = NO_STEPS
        var battery = NO_INT

        for (i in 0 until metrics.length()) {
            val metric = metrics.optJSONObject(i) ?: continue
            val name = stringOf(metric, "name").lowercase()
            if (name.isEmpty()) continue
            val units = stringOf(metric, "units").lowercase()

            val samples = metric.optJSONArray("data")
            // Some exports put the reading directly on the metric object rather than
            // in a `data` array; only one of the two shapes is ever present.
            val value = when {
                samples != null && samples.length() > 0 ->
                    if (name in SUMMED_METRICS) sumQuantities(samples)
                    else samples.optJSONObject(samples.length() - 1)?.let { readQuantity(it) }
                        ?: Double.NaN
                else -> metric.optDouble("qty", Double.NaN)
            }
            if (value.isNaN()) continue

            assignMetric(
                name, value, units,
                { heartRate = it.toInt() }, { steps = it.toLong() },
                { distanceMeters = it }, { activeCalories = it }, { spo2 = it },
                { floorsClimbed = it }, { hrvMs = it },
                { restingHeartRate = it.toInt() }, { respiratoryRate = it },
                { sleepMinutes = it.toLong() }, { battery = it.toInt() }
            )
        }

        return build(
            heartRate, steps, distanceMeters, activeCalories, spo2, floorsClimbed,
            hrvMs, restingHeartRate, respiratoryRate, sleepMinutes, battery, control
        )
    }

    /**
     * Applies one Health Auto Export metric to the accumulator slots. HealthKit names
     * are matched case-insensitively and the `units` field decides metre/kilometre and
     * fraction/percent scaling.
     */
    private inline fun assignMetric(
        name: String,
        raw: Double,
        units: String,
        setHeartRate: (Double) -> Unit,
        setSteps: (Double) -> Unit,
        setDistance: (Double) -> Unit,
        setCalories: (Double) -> Unit,
        setSpo2: (Double) -> Unit,
        setFloors: (Double) -> Unit,
        setHrv: (Double) -> Unit,
        setRestingHr: (Double) -> Unit,
        setRespiratory: (Double) -> Unit,
        setSleep: (Double) -> Unit,
        setBattery: (Double) -> Unit
    ): Boolean {
        when (name) {
            "heart_rate", "heartrate", "bpm" -> setHeartRate(raw)
            "resting_heart_rate", "restingheartrate" -> setRestingHr(raw)
            "heart_rate_variability", "heart_rate_variability_sdnn", "hrv", "hrv_sdnn", "hrv_ms" ->
                setHrv(raw)
            "respiratory_rate", "respiratoryrate" -> setRespiratory(raw)
            "step_count", "steps" -> setSteps(raw)
            "flights_climbed", "floors_climbed" -> setFloors(raw)
            "active_energy", "active_calories" -> setCalories(raw)
            "walking_running_distance", "distance" ->
                setDistance(if (units.startsWith("km")) raw * 1000.0 else raw)
            "blood_oxygen_saturation", "oxygen_saturation", "spo2" -> setSpo2(scaleSpo2(raw))
            "sleep_minutes", "sleep_analysis", "sleep" -> setSleep(raw)
            "battery_level", "battery" -> setBattery(scaleBattery(raw))
            else -> return false
        }
        return true
    }

    /**
     * The latest sample's reading. Health Auto Export uses `qty`, while heart-rate
     * exports commonly carry a pre-aggregated `Avg`.
     */
    private fun readQuantity(sample: JSONObject): Double {
        val direct = sample.optDouble("qty", Double.NaN)
        if (!direct.isNaN()) return direct
        val avg = sample.optDouble("Avg", Double.NaN)
        if (!avg.isNaN()) return avg
        // Shortcuts sometimes emits the raw number instead of an object field.
        val countOnly = sample.optDouble("count", Double.NaN)
        return countOnly
    }

    /** Sums incremental quantities, ignoring samples that are not numeric. */
    private fun sumQuantities(samples: org.json.JSONArray): Double {
        var total = 0.0
        var seen = false
        for (i in 0 until samples.length()) {
            val sample = samples.optJSONObject(i) ?: continue
            val qty = readQuantity(sample)
            if (qty.isNaN()) continue
            total += qty
            seen = true
        }
        return if (seen) total else Double.NaN
    }

    // ------------------------------------------------------------- schemas 1 & 2

    private fun parseFlat(root: JSONObject, control: String): WebhookTelemetryPayload {
        // Schema 1: flat camelCase native payload.
        var heartRate = intOf(root, "heartRate")
        var steps = longOf(root, "steps", NO_STEPS)
        var distanceMeters = doubleOf(root, "distanceMeters", NO_DOUBLE)
        var activeCalories = doubleOf(root, "activeCalories", NO_DOUBLE)
        var spo2 = doubleOf(root, "spo2", NO_DOUBLE)
        var floorsClimbed = doubleOf(root, "floorsClimbed", NO_DOUBLE)
        var hrvMs = doubleOf(root, "hrvMs", NO_DOUBLE)
        var restingHeartRate = intOf(root, "restingHeartRate")
        var respiratoryRate = doubleOf(root, "respiratoryRate", NO_DOUBLE)
        var sleepMinutes = longOf(root, "sleepMinutes", NO_STEPS)
        var battery = intOf(root, "battery", NO_INT)

        // Schema 2: Apple Shortcuts snake_case aliases. Each only fills a field the
        // native key did not already provide, so a mixed payload cannot be clobbered.
        if (heartRate == 0) heartRate = intOfAny(root, "heart_rate", "bpm")
        if (steps == NO_STEPS) steps = longOfAny(root, NO_STEPS, "step_count")
        if (activeCalories == NO_DOUBLE) {
            activeCalories = doubleOfAny(root, NO_DOUBLE, "active_energy", "active_calories")
        }
        if (distanceMeters == NO_DOUBLE) {
            // The native key is metres; HealthKit's own name is kilometres
            // (Apple Shortcuts reports it as km), so the two are not interchangeable.
            distanceMeters = doubleOfAny(root, NO_DOUBLE, "walking_running_distance")
            if (distanceMeters != NO_DOUBLE) {
                distanceMeters *= 1000.0
            } else {
                val km = doubleOfAny(root, NO_DOUBLE, "distance_km")
                if (km != NO_DOUBLE) distanceMeters = km * 1000.0
            }
        }
        if (spo2 == NO_DOUBLE) {
            spo2 = doubleOfAny(root, NO_DOUBLE, "blood_oxygen_saturation", "oxygen_saturation")
            if (spo2 != NO_DOUBLE) spo2 = scaleSpo2(spo2)
        }
        if (floorsClimbed == NO_DOUBLE) {
            floorsClimbed = doubleOfAny(root, NO_DOUBLE, "flights_climbed", "floors_climbed")
        }
        if (hrvMs == NO_DOUBLE) {
            hrvMs = doubleOfAny(root, NO_DOUBLE, "heart_rate_variability", "hrv_sdnn")
        }
        if (restingHeartRate == 0) restingHeartRate = intOfAny(root, "resting_heart_rate")
        if (respiratoryRate == NO_DOUBLE) respiratoryRate = doubleOfAny(root, NO_DOUBLE, "respiratory_rate")
        if (sleepMinutes == NO_STEPS) sleepMinutes = longOfAny(root, NO_STEPS, "sleep_minutes")
        if (battery == NO_INT) {
            // Scale while still a Double: truncating first would turn 0.62 into 0.
            val level = doubleOfAny(root, NO_DOUBLE, "battery_level")
            if (level != NO_DOUBLE) battery = scaleBattery(level).toInt()
        }

        return build(
            heartRate, steps, distanceMeters, activeCalories, spo2, floorsClimbed,
            hrvMs, restingHeartRate, respiratoryRate, sleepMinutes, battery, control
        )
    }

    // ------------------------------------------------------------------- helpers

    /**
     * Control commands may arrive as the native `controlCommand` string, the legacy
     * `findMyPixel: true` boolean, or a Shortcuts-friendly snake_case alias.
     */
    private fun resolveControlCommand(root: JSONObject): String {
        if (root.optBoolean("findMyPixel", false)) return FIND_MY_PIXEL

        val explicit = firstNonBlank(
            stringOf(root, "controlCommand"),
            stringOf(root, "control_command"),
            stringOf(root, "command")
        )
        if (explicit != null) return explicit

        if (root.optBoolean("find_my_pixel", false) || root.optBoolean("ring_pixel", false)) {
            return FIND_MY_PIXEL
        }
        return ""
    }

    /**
     * Android's `JSONObject.optString` returns `null` (not the supplied default) for an
     * absent key, so it cannot be used as a non-null Kotlin `String`.
     */
    private fun stringOf(json: JSONObject, key: String): String {
        val value = json.opt(key)
        if (value == null || value === JSONObject.NULL) return ""
        return value.toString().trim()
    }

    private fun firstNonBlank(vararg candidates: String): String? =
        candidates.firstOrNull { it.isNotBlank() }?.trim()

    private fun build(
        heartRate: Int,
        steps: Long,
        distanceMeters: Double,
        activeCalories: Double,
        spo2: Double,
        floorsClimbed: Double,
        hrvMs: Double,
        restingHeartRate: Int,
        respiratoryRate: Double,
        sleepMinutes: Long,
        battery: Int,
        controlCommand: String
    ) = WebhookTelemetryPayload(
        heartRate = heartRate,
        steps = steps,
        distanceMeters = distanceMeters,
        activeCalories = activeCalories,
        spo2 = spo2,
        floorsClimbed = floorsClimbed,
        hrvMs = hrvMs,
        restingHeartRate = restingHeartRate,
        respiratoryRate = respiratoryRate,
        sleepMinutes = sleepMinutes,
        battery = battery,
        controlCommand = controlCommand
    )

    /** HealthKit reports SpO2 as a 0-1 fraction; Health Connect wants 0-100 percent. */
    private fun scaleSpo2(raw: Double): Double =
        if (raw in 0.5..1.0) raw * 100.0 else raw

    /** Battery arrives either as 0-1 or as 0-100 depending on the producer. */
    private fun scaleBattery(raw: Double): Double = if (raw in 0.0..1.0) raw * 100.0 else raw

    private fun doubleOf(json: JSONObject, key: String, fallback: Double): Double =
        numeric(json.opt(key))?.toDouble() ?: fallback

    private fun doubleOfAny(json: JSONObject, fallback: Double, vararg keys: String): Double {
        for (key in keys) {
            val v = numeric(json.opt(key))?.toDouble()
            if (v != null) return v
        }
        return fallback
    }

    private fun intOf(json: JSONObject, key: String, fallback: Int = 0): Int =
        numeric(json.opt(key))?.toInt() ?: fallback

    private fun intOfAny(json: JSONObject, vararg keys: String): Int = intOfAny(json, 0, *keys)

    private fun intOfAny(json: JSONObject, fallback: Int, vararg keys: String): Int {
        for (key in keys) {
            val v = numeric(json.opt(key))?.toInt()
            if (v != null) return v
        }
        return fallback
    }

    private fun longOf(json: JSONObject, key: String, fallback: Long): Long =
        numeric(json.opt(key))?.toLong() ?: fallback

    private fun longOfAny(json: JSONObject, fallback: Long, vararg keys: String): Long {
        for (key in keys) {
            val v = numeric(json.opt(key))?.toLong()
            if (v != null) return v
        }
        return fallback
    }

    /**
     * Shortcuts' "Get Contents of URL" often stringifies numbers, so a quoted
     * `"74"` is accepted as well as the raw `74`. Anything else (booleans, objects,
     * arrays, `JSONObject.NULL`) is treated as absent.
     */
    private fun numeric(value: Any?): Number? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }
}

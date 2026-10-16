package com.bridge.applewatch

import com.bridge.applewatch.health.WebhookPayloadParser
import com.bridge.applewatch.model.WebhookTelemetryPayload
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for the three `/telemetry` JSON dialects (OPEN-07/OPEN-08).
 *
 * Every assertion here is derived from the documented shape of the producer, not from
 * the parser's own output: the point is that a Shortcuts or Health Auto Export push
 * lands in the same [WebhookTelemetryPayload] the native watch agent produces.
 */
class WebhookPayloadParserTest {

    // ------------------------------------------------ schema 1: native camelCase

    @Test
    fun `flat native payload maps all twelve fields`() {
        val payload = WebhookPayloadParser.parse(
            """
            {
              "heartRate": 74, "steps": 9120, "distanceMeters": 7100.5,
              "activeCalories": 420.25, "spo2": 97.5, "floorsClimbed": 12.0,
              "hrvMs": 41.5, "restingHeartRate": 52, "respiratoryRate": 14.5,
              "sleepMinutes": 455, "battery": 84, "controlCommand": "MEDIA_NEXT"
            }
            """.trimIndent()
        )

        assertEquals(74, payload.heartRate)
        assertEquals(9120L, payload.steps)
        assertEquals(7100.5, payload.distanceMeters, 1e-9)
        assertEquals(420.25, payload.activeCalories, 1e-9)
        assertEquals(97.5, payload.spo2, 1e-9)
        assertEquals(12.0, payload.floorsClimbed, 1e-9)
        assertEquals(41.5, payload.hrvMs, 1e-9)
        assertEquals(52, payload.restingHeartRate)
        assertEquals(14.5, payload.respiratoryRate, 1e-9)
        assertEquals(455L, payload.sleepMinutes)
        assertEquals(84, payload.battery)
        assertEquals("MEDIA_NEXT", payload.controlCommand)
    }

    @Test
    fun `legacy findMyPixel flag becomes the canonical control command`() {
        val payload = WebhookPayloadParser.parse("""{"heartRate":70,"findMyPixel":true}""")
        assertEquals("FIND_MY_PIXEL", payload.controlCommand)

        // A false flag must not synthesise a command.
        val quiet = WebhookPayloadParser.parse("""{"findMyPixel":false}""")
        assertEquals("", quiet.controlCommand)
    }

    // --------------------------------------------- schema 2: Shortcuts snake_case

    @Test
    fun `shortcuts snake_case payload scales fractional spo2 and kilometres`() {
        val payload = WebhookPayloadParser.parse(
            """
            {
              "heart_rate": 68,
              "step_count": 12345,
              "active_energy": 300.5,
              "walking_running_distance": 2.4,
              "blood_oxygen_saturation": 0.985,
              "flights_climbed": 7,
              "heart_rate_variability": 38.0,
              "resting_heart_rate": 50,
              "respiratory_rate": 13.5,
              "sleep_minutes": 420,
              "battery_level": 0.62
            }
            """.trimIndent()
        )

        assertEquals(68, payload.heartRate)
        assertEquals(12345L, payload.steps)
        assertEquals(300.5, payload.activeCalories, 1e-9)
        // HealthKit's walking/running distance is kilometres: 2.4 km -> 2400 m.
        assertEquals(2400.0, payload.distanceMeters, 1e-9)
        // 0.985 is a fraction, so it must become 98.5 %, not stay below 1.
        assertEquals(98.5, payload.spo2, 1e-9)
        assertEquals(7.0, payload.floorsClimbed, 1e-9)
        assertEquals(38.0, payload.hrvMs, 1e-9)
        assertEquals(50, payload.restingHeartRate)
        assertEquals(13.5, payload.respiratoryRate, 1e-9)
        assertEquals(420L, payload.sleepMinutes)
        // 0.62 is a fraction, so it must become 62 %.
        assertEquals(62, payload.battery)
    }

    @Test
    fun `explicit distance_km alias also converts to metres`() {
        val payload = WebhookPayloadParser.parse("""{"distance_km":2.4}""")
        assertEquals(2400.0, payload.distanceMeters, 1e-9)
    }

    @Test
    fun `bpm and spo2 percent aliases are honoured`() {
        val payload = WebhookPayloadParser.parse(
            """{"bpm":71,"oxygen_saturation":98.0,"hrv_sdnn":35.0,"active_calories":15.0}"""
        )
        assertEquals(71, payload.heartRate)
        // Already a percentage: it must NOT be multiplied again.
        assertEquals(98.0, payload.spo2, 1e-9)
        assertEquals(35.0, payload.hrvMs, 1e-9)
        assertEquals(15.0, payload.activeCalories, 1e-9)
    }

    // ------------------------------------------ schema 3: Health Auto Export JSON

    @Test
    fun `health auto export nested metrics are flattened with unit scaling`() {
        val payload = WebhookPayloadParser.parse(
            """
            {
              "data": {
                "metrics": [
                  { "name": "heart_rate", "units": "bpm",
                    "data": [ {"Avg": 74} ] },
                  { "name": "walking_running_distance", "units": "km",
                    "data": [ {"qty": 0.5}, {"qty": 1.0} ] },
                  { "name": "blood_oxygen_saturation", "units": "%",
                    "data": [ {"qty": 0.99} ] },
                  { "name": "step_count", "units": "count",
                    "data": [ {"qty": 400}, {"qty": 350} ] },
                  { "name": "flights_climbed", "units": "count",
                    "data": [ {"qty": 3}, {"qty": 4} ] },
                  { "name": "resting_heart_rate", "units": "bpm",
                    "data": [ {"qty": 51} ] },
                  { "name": "respiratory_rate", "units": "count/min",
                    "data": [ {"qty": 14.0} ] }
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(74, payload.heartRate)
        // The summed distance is 1.5 km, so 1500 m.
        assertEquals(1500.0, payload.distanceMeters, 1e-9)
        assertEquals(99.0, payload.spo2, 1e-9)
        // Steps and floors are incremental series, so they are summed.
        assertEquals(750L, payload.steps)
        assertEquals(7.0, payload.floorsClimbed, 1e-9)
        assertEquals(51, payload.restingHeartRate)
        assertEquals(14.0, payload.respiratoryRate, 1e-9)
    }

    @Test
    fun `health auto export distance already in metres is not rescaled`() {
        val payload = WebhookPayloadParser.parse(
            """{"data":{"metrics":[{"name":"walking_running_distance","units":"m","data":[{"qty":900}]}]}}"""
        )
        assertEquals(900.0, payload.distanceMeters, 1e-9)
    }

    // --------------------------------------------------------------- resilience

    @Test
    fun `blank and whitespace bodies return the default payload`() {
        val expected = WebhookTelemetryPayload()
        assertEquals(expected, WebhookPayloadParser.parse(""))
        assertEquals(expected, WebhookPayloadParser.parse("   \n\t "))
    }

    @Test
    fun `malformed json returns the default payload without throwing`() {
        val expected = WebhookTelemetryPayload()
        assertEquals(expected, WebhookPayloadParser.parse("{not json at all"))
        assertEquals(expected, WebhookPayloadParser.parse("""{"heartRate": }"""))
        assertEquals(expected, WebhookPayloadParser.parse("[1,2,3]"))
        assertEquals(expected, WebhookPayloadParser.parse("\u0000\u0001"))
    }

    @Test
    fun `absent fields keep the sentinel that downstream range checks skip`() {
        val payload = WebhookPayloadParser.parse("""{"heartRate":66}""")
        assertEquals(66, payload.heartRate)
        // These sentinels are what make HealthConnectManager skip the record instead
        // of writing a fabricated one, so they must not drift to 0.
        assertEquals(-1L, payload.steps)
        assertEquals(-1.0, payload.distanceMeters, 1e-9)
        assertEquals(-1.0, payload.spo2, 1e-9)
        assertEquals(-1.0, payload.activeCalories, 1e-9)
        assertEquals(-1.0, payload.respiratoryRate, 1e-9)
        assertEquals(-1, payload.battery)
    }

    @Test
    fun `stringified numbers from shortcuts are accepted`() {
        val payload = WebhookPayloadParser.parse("""{"heartRate":"74","steps":"1200","battery":"84"}""")
        assertEquals(74, payload.heartRate)
        assertEquals(1200L, payload.steps)
        assertEquals(84, payload.battery)
    }
}

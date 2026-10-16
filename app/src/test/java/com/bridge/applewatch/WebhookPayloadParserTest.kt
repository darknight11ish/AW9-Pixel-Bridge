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
              "hrv_rmssd": 38.0,
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
            """{"bpm":71,"oxygen_saturation":98.0,"active_calories":15.0}"""
        )
        assertEquals(71, payload.heartRate)
        // Already a percentage: it must NOT be multiplied again.
        assertEquals(98.0, payload.spo2, 1e-9)
        assertEquals(15.0, payload.activeCalories, 1e-9)
    }

    @Test
    fun `SDNN-named keys never populate the RMSSD field`() {
        // Apple's HRV is SDNN; Health Connect's only HRV record type stores RMSSD. The
        // XML importer gates SDNN (D-05), but the JSON paths accepted SDNN names and the
        // manager wrote them as HeartRateVariabilityRmssdRecord — a mislabelled clinical
        // record. Those aliases are now rejected; only genuinely RMSSD-named keys map.
        listOf(
            """{"hrv_sdnn":35.0}""",
            """{"heart_rate_variability":35.0}""",
            """{"heart_rate_variability_sdnn":35.0}"""
        ).forEach { body ->
            assertEquals(
                "$body must not become RMSSD",
                -1.0,
                WebhookPayloadParser.parse(body).hrvMs,
                1e-9
            )
        }

        // Health Auto Export's nested SDNN metric is rejected the same way.
        assertEquals(
            -1.0,
            WebhookPayloadParser.parse(
                """{"data":{"metrics":[{"name":"heart_rate_variability_sdnn","units":"ms","data":[{"qty":45}]}]}}"""
            ).hrvMs,
            1e-9
        )

        // RMSSD-named keys still work.
        assertEquals(35.0, WebhookPayloadParser.parse("""{"hrv_rmssd":35.0}""").hrvMs, 1e-9)
        assertEquals(35.0, WebhookPayloadParser.parse("""{"rmssd":35.0}""").hrvMs, 1e-9)
        assertEquals(35.0, WebhookPayloadParser.parse("""{"hrv":35.0}""").hrvMs, 1e-9)
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

    // ------------------------------------------------------ edge cases (OPEN-08)

    @Test
    fun `spo2 boundary values are passed through without clamping`() {
        // The parser must not clamp: 70.0 and 100.0 are inside the downstream
        // `in 70.0..100.0` gate, and a value outside it must stay outside so
        // HealthConnectManager skips the record instead of writing a fabricated one.
        assertEquals(70.0, WebhookPayloadParser.parse("""{"spo2":70.0}""").spo2, 1e-9)
        assertEquals(100.0, WebhookPayloadParser.parse("""{"spo2":100.0}""").spo2, 1e-9)
        assertEquals(69.9, WebhookPayloadParser.parse("""{"spo2":69.9}""").spo2, 1e-9)
        assertEquals(100.1, WebhookPayloadParser.parse("""{"spo2":100.1}""").spo2, 1e-9)
    }

    @Test
    fun `the native spo2 key is a percentage and is never rescaled`() {
        // Documented contract: `spo2` is this project's own field, so it always carries
        // a 0-100 percentage. Only the HealthKit-named keys are treated as fractions.
        // That is what disambiguates 1.0, which would otherwise be both 1 % and 100 %.
        assertEquals(1.0, WebhookPayloadParser.parse("""{"spo2":1.0}""").spo2, 1e-9)
        assertEquals(0.5, WebhookPayloadParser.parse("""{"spo2":0.5}""").spo2, 1e-9)
        assertEquals(0.4, WebhookPayloadParser.parse("""{"spo2":0.4}""").spo2, 1e-9)
        assertEquals(98.0, WebhookPayloadParser.parse("""{"spo2":98.0}""").spo2, 1e-9)
    }

    @Test
    fun `only the HealthKit-named spo2 keys are treated as fractions`() {
        // HKUnit.percent() renders 98.5 % as 0.985, so a 0.5-1.0 value under a HealthKit
        // name must be scaled to a percentage.
        assertEquals(
            98.5,
            WebhookPayloadParser.parse("""{"blood_oxygen_saturation":0.985}""").spo2,
            1e-9
        )
        assertEquals(
            100.0,
            WebhookPayloadParser.parse("""{"oxygen_saturation":1.0}""").spo2,
            1e-9
        )
        // Already a percentage: it must NOT be multiplied again.
        assertEquals(
            98.0,
            WebhookPayloadParser.parse("""{"oxygen_saturation":98.0}""").spo2,
            1e-9
        )
        // Below 0.5 is not a plausible fraction, so it is left alone and stays out of
        // the downstream range check rather than becoming a fabricated 40 %.
        assertEquals(
            0.4,
            WebhookPayloadParser.parse("""{"blood_oxygen_saturation":0.4}""").spo2,
            1e-9
        )
        // Health Auto Export's nested form scales the same way.
        assertEquals(
            99.0,
            WebhookPayloadParser.parse(
                """{"data":{"metrics":[{"name":"blood_oxygen_saturation","data":[{"qty":0.99}]}]}}"""
            ).spo2,
            1e-9
        )
    }

    @Test
    fun `negative readings are preserved rather than clamped to zero`() {
        // A negative sentinel is meaningful: it tells the writer there was no reading.
        val payload = WebhookPayloadParser.parse(
            """{"steps":-1,"distanceMeters":-5.0,"activeCalories":-2.5,"hrvMs":-3.0,"battery":-1}"""
        )
        assertEquals(-1L, payload.steps)
        assertEquals(-5.0, payload.distanceMeters, 1e-9)
        assertEquals(-2.5, payload.activeCalories, 1e-9)
        assertEquals(-3.0, payload.hrvMs, 1e-9)
        assertEquals(-1, payload.battery)
    }

    @Test
    fun `a partially populated payload leaves the untouched fields at their sentinels`() {
        val payload = WebhookPayloadParser.parse("""{"heart_rate":66,"steps":5000,"spo2":98.0}""")
        assertEquals(66, payload.heartRate)
        assertEquals(5000L, payload.steps)
        assertEquals(98.0, payload.spo2, 1e-9)
        // Everything else must stay "no reading".
        assertEquals(-1L, payload.sleepMinutes)
        assertEquals(0, payload.restingHeartRate)
        assertEquals(-1.0, payload.respiratoryRate, 1e-9)
        assertEquals(-1.0, payload.floorsClimbed, 1e-9)
        assertEquals(-1.0, payload.distanceMeters, 1e-9)
        assertEquals("", payload.controlCommand)
    }

    @Test
    fun `wrong value types are treated as absent instead of throwing`() {
        val payload = WebhookPayloadParser.parse(
            """{"heartRate":{"nested":1},"steps":[1,2],"spo2":true,
                "distanceMeters":"not-a-number","battery":null}"""
        )
        val expected = WebhookTelemetryPayload()
        assertEquals(expected, payload)
    }

    @Test
    fun `explicit json nulls are treated as absent`() {
        val payload = WebhookPayloadParser.parse(
            """{"heartRate":null,"steps":null,"spo2":null,"controlCommand":null}"""
        )
        val expected = WebhookTelemetryPayload()
        assertEquals(expected, payload)
        assertEquals("", payload.controlCommand)
    }

    @Test
    fun `malformed nested metric arrays are skipped without losing valid metrics`() {
        val payload = WebhookPayloadParser.parse(
            """
            {"data":{"metrics":[
              {"name":"heart_rate","units":"bpm","data":[{"Avg":72}]},
              {"name":"step_count"},
              {"data":[{"qty":10}]},
              {"name":"spo2","data":"not-an-array"},
              {"name":"flights_climbed","data":[{"qty":"abc"}]},
              {"name":"resting_heart_rate","data":[{"qty":49}]}
            ]}}
            """.trimIndent()
        )
        // The two well-formed metrics survive; the malformed ones are skipped silently.
        assertEquals(72, payload.heartRate)
        assertEquals(49, payload.restingHeartRate)
        assertEquals(-1L, payload.steps)
        assertEquals(-1.0, payload.spo2, 1e-9)
        assertEquals(-1.0, payload.floorsClimbed, 1e-9)
    }

    @Test
    fun `metrics with a missing name are ignored`() {
        val payload = WebhookPayloadParser.parse(
            """{"data":{"metrics":[{"units":"bpm","data":[{"qty":99}]},{"name":"heart_rate","data":[{"qty":61}]}]}}"""
        )
        assertEquals("a nameless metric must not be guessed", 61, payload.heartRate)
    }

    @Test
    fun `an empty metrics array yields the default payload`() {
        val expected = WebhookTelemetryPayload()
        assertEquals(expected, WebhookPayloadParser.parse("""{"data":{"metrics":[]}}"""))
        // `data` present but without `metrics` falls back to the flat reader.
        assertEquals(expected, WebhookPayloadParser.parse("""{"data":{}}"""))
    }

    @Test
    fun `a flat payload wrapped in data is still read`() {
        val payload = WebhookPayloadParser.parse("""{"data":{"heart_rate":64,"step_count":321}}""")
        assertEquals(64, payload.heartRate)
        assertEquals(321L, payload.steps)
    }

    @Test
    fun `control command aliases are resolved in a stable priority order`() {
        assertEquals(
            "MEDIA_NEXT",
            WebhookPayloadParser.parse("""{"controlCommand":"MEDIA_NEXT"}""").controlCommand
        )
        assertEquals(
            "MEDIA_NEXT",
            WebhookPayloadParser.parse("""{"control_command":"MEDIA_NEXT"}""").controlCommand
        )
        // The native key wins when both native and snake_case forms are present.
        assertEquals(
            "NATIVE_WINS",
            WebhookPayloadParser.parse(
                """{"controlCommand":"NATIVE_WINS","control_command":"SNAKE"}"""
            ).controlCommand
        )
        // A blank native key must not mask a usable snake_case one.
        assertEquals(
            "FROM_SNAKE",
            WebhookPayloadParser.parse(
                """{"controlCommand":"  ","control_command":"FROM_SNAKE"}"""
            ).controlCommand
        )
        assertEquals(
            "FIND_MY_PIXEL",
            WebhookPayloadParser.parse("""{"find_my_pixel":true}""").controlCommand
        )
        assertEquals(
            "FIND_MY_PIXEL",
            WebhookPayloadParser.parse("""{"ring_pixel":true}""").controlCommand
        )
    }

    @Test
    fun `non-finite numbers are rejected rather than poisoning a gate`() {
        // `toDoubleOrNull("1e999")` returns Infinity, which passes every `> 0.0` gate
        // and would construct an infinite Length/Energy record; `Infinity.toLong()`
        // saturates to Long.MAX_VALUE, which then poisons the persisted step baseline so
        // every later step delta becomes 0.
        val payload = WebhookPayloadParser.parse(
            """{"distanceMeters":"1e999","activeCalories":1e999,"steps":"1e999","spo2":1e999}"""
        )
        val expected = WebhookTelemetryPayload()
        assertEquals("infinities must be treated as absent", expected, payload)

        // NaN is also rejected.
        assertEquals(expected, WebhookPayloadParser.parse("""{"heartRate":"NaN"}"""))
    }

    @Test
    fun `sleep analysis durations are hours and are converted to minutes`() {
        // Health Auto Export documents sleep_analysis with units "hr"; reading it as
        // minutes turned a 7.5-hour night into a 7-minute session.
        val hours = WebhookPayloadParser.parse(
            """{"data":{"metrics":[{"name":"sleep_analysis","units":"hr","data":[{"qty":7.5}]}]}}"""
        )
        assertEquals("7.5 hours must become 450 minutes", 450L, hours.sleepMinutes)

        // The native minutes field keeps its meaning and is never scaled.
        val minutes = WebhookPayloadParser.parse("""{"sleepMinutes":420}""")
        assertEquals(420L, minutes.sleepMinutes)

        // Shortcuts' explicit hours alias behaves the same way.
        assertEquals(90L, WebhookPayloadParser.parse("""{"sleep_hours":1.5}""").sleepMinutes)
    }

    @Test
    fun `distance and energy honour the exporter's units`() {
        // Health Auto Export's unit preferences are per-metric, so a mi/kJ payload must
        // not be written with a metres/kcal magnitude.
        val miles = WebhookPayloadParser.parse(
            """{"data":{"metrics":[{"name":"walking_running_distance","units":"mi","data":[{"qty":3.1}]}]}}"""
        )
        assertEquals(3.1 * 1609.344, miles.distanceMeters, 1e-6)

        val kilojoules = WebhookPayloadParser.parse(
            """{"data":{"metrics":[{"name":"active_energy","units":"kJ","data":[{"qty":1000}]}]}}"""
        )
        assertEquals(1000.0 / 4.184, kilojoules.activeCalories, 1e-6)

        val metres = WebhookPayloadParser.parse(
            """{"data":{"metrics":[{"name":"walking_running_distance","units":"m","data":[{"qty":900}]}]}}"""
        )
        assertEquals(900.0, metres.distanceMeters, 1e-9)

        // An unrecognised unit is skipped rather than guessed.
        val unknown = WebhookPayloadParser.parse(
            """{"data":{"metrics":[{"name":"walking_running_distance","units":"furlongs","data":[{"qty":9}]}]}}"""
        )
        assertEquals(-1.0, unknown.distanceMeters, 1e-9)
    }

    @Test
    fun `step and distance sums tolerate a mixture of sample shapes`() {
        val payload = WebhookPayloadParser.parse(
            """{"data":{"metrics":[
                 {"name":"step_count","data":[{"qty":100},{"Avg":50},{"qty":"25"}]},
                 {"name":"walking_running_distance","units":"km","data":[{"qty":1.0},{"qty":0.25}]}
               ]}}"""
        )
        assertEquals(175L, payload.steps)
        assertEquals(1250.0, payload.distanceMeters, 1e-9)
    }
}

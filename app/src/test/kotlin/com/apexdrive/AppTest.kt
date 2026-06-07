package com.apexdrive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AppTest {

    @Test
    fun destinationPointIsAccurate() {
        // Travel 100 km due north from (0, 0) — should land near (0.899°, 0°)
        val (lat, lng) = destinationPoint(0.0, 0.0, 0.0, 100.0)
        assertTrue(abs(lat - 0.899) < 0.01, "Expected lat ~0.899 but got $lat")
        assertTrue(abs(lng) < 0.001, "Expected lng ~0 but got $lng")
    }

    @Test
    fun destinationPointBearingsAreDistinct() {
        // Three bearings should produce three distinct destinations
        val bearings = listOf(45.0, 155.0, 270.0)
        val points = bearings.map { destinationPoint(51.5, -0.1, it, 50.0) }
        val unique = points.toSet()
        assertEquals(3, unique.size, "Expected 3 distinct destinations, got $unique")
    }

    @Test
    fun routeTypeEnumHasBothValues() {
        assertEquals(2, RouteType.entries.size)
        assertTrue(RouteType.entries.contains(RouteType.CLOSED_LOOP))
        assertTrue(RouteType.entries.contains(RouteType.ONE_WAY))
    }

    @Test
    fun parseQueryParamsHandlesEmpty() {
        val result = parseQueryParams(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseQueryParamsDecodesValues() {
        val result = parseQueryParams("lat=51.5%2B1&lng=-0.1&length=60")
        assertEquals("51.5+1", result["lat"])
        assertEquals("-0.1", result["lng"])
        assertEquals("60", result["length"])
    }
}

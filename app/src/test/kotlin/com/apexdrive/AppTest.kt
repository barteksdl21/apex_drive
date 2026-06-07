package com.apexdrive

import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppTest {
    private val engine = ScenicRouteEngine()

    @Test
    fun generatesTwoToThreeClosedLoopSuggestions() {
        val request = RouteRequest("Hillcrest", preferredLengthKm = 55, routeType = RouteType.CLOSED_LOOP)

        val suggestions = engine.suggestRoutes(request)

        assertTrue(suggestions.size in 2..3)
        suggestions.forEach { suggestion ->
            assertTrue(suggestion.isClosedLoop)
            assertEquals("Hillcrest", suggestion.checkpoints.first())
            assertEquals("Hillcrest", suggestion.checkpoints.last())
            assertTrue(abs(suggestion.lengthKm - 55) <= (55 * 0.3).toInt())
        }
    }

    @Test
    fun generatesTwoToThreeOneWaySuggestions() {
        val request = RouteRequest("Hillcrest", preferredLengthKm = 55, routeType = RouteType.ONE_WAY)

        val suggestions = engine.suggestRoutes(request)

        assertTrue(suggestions.size in 2..3)
        suggestions.forEach { suggestion ->
            assertFalse(suggestion.isClosedLoop)
            assertEquals("Hillcrest", suggestion.checkpoints.first())
            assertTrue(suggestion.checkpoints.last() != "Hillcrest")
        }
    }

    @Test
    fun prefersRoutesNearRequestedLength() {
        val request = RouteRequest("Hillcrest", preferredLengthKm = 60, routeType = RouteType.CLOSED_LOOP)

        val suggestions = engine.suggestRoutes(request)

        assertTrue(suggestions.isNotEmpty())
        suggestions.forEach {
            assertTrue(abs(it.lengthKm - 60) <= (60 * 0.3).toInt())
        }
    }
}

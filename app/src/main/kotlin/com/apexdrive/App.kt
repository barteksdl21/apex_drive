package com.apexdrive

import kotlin.math.abs
import kotlin.math.ceil

enum class RouteType {
    CLOSED_LOOP,
    ONE_WAY,
}

data class RouteRequest(
    val startPosition: String,
    val preferredLengthKm: Int,
    val routeType: RouteType,
)

data class RouteSuggestion(
    val checkpoints: List<String>,
    val lengthKm: Int,
    val driveQualityScore: Double,
    val isClosedLoop: Boolean,
)

data class RoadSegment(
    val from: String,
    val to: String,
    val lengthKm: Int,
    val curviness: Int,
    val elevation: Int,
    val scenery: Int,
)

class ScenicRouteEngine(
    private val roads: List<RoadSegment> = defaultRoads(),
) {
    private val roadsByStart: Map<String, List<RoadSegment>> = roads.groupBy { it.from }

    fun suggestRoutes(request: RouteRequest): List<RouteSuggestion> {
        require(request.preferredLengthKm > 0) { "preferredLengthKm must be positive" }

        val collected = linkedMapOf<String, RouteSuggestion>()
        val path = mutableListOf<String>()
        path += request.startPosition

        dfs(
            request = request,
            current = request.startPosition,
            usedEdges = mutableSetOf(),
            path = path,
            lengthSoFar = 0,
            qualitySoFar = 0.0,
            candidates = collected,
        )

        return collected
            .values
            .sortedWith(compareByDescending<RouteSuggestion> { it.driveQualityScore }.thenBy { abs(it.lengthKm - request.preferredLengthKm) })
            .take(3)
    }

    private fun dfs(
        request: RouteRequest,
        current: String,
        usedEdges: MutableSet<String>,
        path: MutableList<String>,
        lengthSoFar: Int,
        qualitySoFar: Double,
        candidates: MutableMap<String, RouteSuggestion>,
    ) {
        if (path.size > 7 || lengthSoFar > request.preferredLengthKm * 2) {
            return
        }

        if (path.size >= 3 && withinLengthWindow(lengthSoFar, request.preferredLengthKm)) {
            val closedLoop = current == request.startPosition
            if ((request.routeType == RouteType.CLOSED_LOOP && closedLoop) ||
                (request.routeType == RouteType.ONE_WAY && !closedLoop)
            ) {
                val suggestion = RouteSuggestion(
                    checkpoints = path.toList(),
                    lengthKm = lengthSoFar,
                    driveQualityScore = (qualitySoFar / (path.size - 1)) - (abs(lengthSoFar - request.preferredLengthKm) * 0.05),
                    isClosedLoop = closedLoop,
                )
                candidates[path.joinToString("->")] = suggestion
            }
        }

        roadsByStart[current].orEmpty().forEach { edge ->
            val edgeId = "${edge.from}->${edge.to}"
            if (usedEdges.contains(edgeId)) return@forEach
            if (path.size > 1 && edge.to == path[path.lastIndex - 1]) return@forEach

            usedEdges += edgeId
            path += edge.to
            dfs(
                request = request,
                current = edge.to,
                usedEdges = usedEdges,
                path = path,
                lengthSoFar = lengthSoFar + edge.lengthKm,
                qualitySoFar = qualitySoFar + edge.driveScore(),
                candidates = candidates,
            )
            path.removeAt(path.lastIndex)
            usedEdges -= edgeId
        }
    }

    private fun withinLengthWindow(length: Int, preferredLength: Int): Boolean {
        val minLength = ceil(preferredLength * 0.7).toInt()
        val maxLength = (preferredLength * 1.3).toInt()
        return length in minLength..maxLength
    }
}

private fun RoadSegment.driveScore(): Double =
    (curviness * 0.5) + (elevation * 0.3) + (scenery * 0.2)

private fun defaultRoads(): List<RoadSegment> {
    val roads = mutableListOf<RoadSegment>()

    fun addBidirectional(from: String, to: String, lengthKm: Int, curviness: Int, elevation: Int, scenery: Int) {
        roads += RoadSegment(from, to, lengthKm, curviness, elevation, scenery)
        roads += RoadSegment(to, from, lengthKm, curviness, elevation, scenery)
    }

    addBidirectional("Hillcrest", "Ridge Pass", 18, 9, 8, 7)
    addBidirectional("Ridge Pass", "Lake Bend", 12, 8, 6, 9)
    addBidirectional("Lake Bend", "Pine Hollow", 14, 9, 5, 8)
    addBidirectional("Pine Hollow", "Hillcrest", 16, 7, 7, 7)
    addBidirectional("Ridge Pass", "Canyon Fork", 20, 10, 8, 6)
    addBidirectional("Canyon Fork", "Summit View", 17, 9, 9, 7)
    addBidirectional("Summit View", "Hillcrest", 19, 8, 9, 8)
    addBidirectional("Lake Bend", "River Drop", 13, 8, 6, 9)
    addBidirectional("River Drop", "Summit View", 15, 7, 7, 8)
    addBidirectional("Pine Hollow", "River Drop", 11, 8, 5, 8)

    return roads
}

fun main() {
    val engine = ScenicRouteEngine()
    val request = RouteRequest(
        startPosition = "Hillcrest",
        preferredLengthKm = 55,
        routeType = RouteType.CLOSED_LOOP,
    )

    val suggestions = engine.suggestRoutes(request)
    suggestions.forEachIndexed { index, route ->
        println("Suggestion ${index + 1}: ${route.checkpoints.joinToString(" -> ")} | ${route.lengthKm}km | score=${"%.1f".format(route.driveQualityScore)}")
    }
}

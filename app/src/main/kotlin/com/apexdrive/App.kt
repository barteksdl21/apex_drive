package com.apexdrive

import kotlin.math.abs
import kotlin.math.ceil
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import java.net.InetSocketAddress
import java.io.File
import java.net.NetworkInterface
import java.net.Inet4Address
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

enum class RouteType {
    CLOSED_LOOP,
    ONE_WAY,
}

data class RouteRequest(
    val startPosition: String,
    val preferredLengthKm: Int,
    val routeType: RouteType,
    val curvinessWeight: Double = 0.5,
    val elevationWeight: Double = 0.3,
    val sceneryWeight: Double = 0.2
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

data class Checkpoint(
    val name: String,
    val lat: Double,
    val lng: Double,
    val description: String
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
                qualitySoFar = qualitySoFar + edge.driveScore(request.curvinessWeight, request.elevationWeight, request.sceneryWeight),
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

private fun RoadSegment.driveScore(
    curvinessWeight: Double,
    elevationWeight: Double,
    sceneryWeight: Double
): Double =
    (curviness * curvinessWeight) + (elevation * elevationWeight) + (scenery * sceneryWeight)

fun defaultRoads(): List<RoadSegment> {
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

val checkpoints = mapOf(
    "Hillcrest" to Checkpoint("Hillcrest", 39.1600, -120.0500, "Scenic ridge overlooking the valley"),
    "Ridge Pass" to Checkpoint("Ridge Pass", 39.0500, -120.1500, "High-altitude mountain pass with sharp hairpins"),
    "Lake Bend" to Checkpoint("Lake Bend", 38.9600, -120.0800, "Serene lakeside cruise with gentle sweeps"),
    "Pine Hollow" to Checkpoint("Pine Hollow", 39.0200, -119.9200, "Dense pine forest run with fast straights"),
    "Canyon Fork" to Checkpoint("Canyon Fork", 39.0800, -120.3400, "Canyon carver with sheer rock faces"),
    "Summit View" to Checkpoint("Summit View", 39.2200, -120.2200, "Highest peak viewpoint, panoramic vistas"),
    "River Drop" to Checkpoint("River Drop", 39.1100, -119.9000, "Rapid-side descent with roller-coaster elevation")
)

fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val hostAddress = addr.hostAddress
                    if (hostAddress.startsWith("192.168.") || 
                        hostAddress.startsWith("10.") || 
                        hostAddress.startsWith("172.")) {
                        return hostAddress
                    }
                }
            }
        }
    } catch (e: Exception) {
        // ignore
    }
    return "127.0.0.1"
}

fun getFrontendHtml(): String {
    val localFile = File("app/src/main/resources/public/index.html")
    if (localFile.exists()) {
        return localFile.readText(StandardCharsets.UTF_8)
    }
    val fallbackFile = File("src/main/resources/public/index.html")
    if (fallbackFile.exists()) {
        return fallbackFile.readText(StandardCharsets.UTF_8)
    }
    val resourceStream = ScenicRouteEngine::class.java.getResourceAsStream("/public/index.html")
    if (resourceStream != null) {
        return resourceStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
    return "<h1>APEX DRIVE - HTML UI Not Found</h1><p>Please build resources first.</p>"
}

fun parseQueryParams(query: String?): Map<String, String> {
    if (query.isNullOrEmpty()) return emptyMap()
    return query.split("&").associate {
        val parts = it.split("=", limit = 2)
        val key = URLDecoder.decode(parts[0], "UTF-8")
        val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
        key to value
    }
}

fun sendResponse(exchange: HttpExchange, status: Int, contentType: String, content: String) {
    val bytes = content.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.set("Content-Type", "$contentType; charset=UTF-8")
    exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
    exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type")
    
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

fun handleOptions(exchange: HttpExchange) {
    exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
    exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type")
    exchange.sendResponseHeaders(204, -1)
}

fun main() {
    val engine = ScenicRouteEngine()
    val defaultRequest = RouteRequest(
        startPosition = "Hillcrest",
        preferredLengthKm = 55,
        routeType = RouteType.CLOSED_LOOP,
    )

    println("==========================================================")
    println("              APEX DRIVE SCENIC ROUTE ENGINE              ")
    println("==========================================================")
    println("Generating default path test...")
    val suggestions = engine.suggestRoutes(defaultRequest)
    suggestions.forEachIndexed { index, route ->
        println("Suggestion ${index + 1}: ${route.checkpoints.joinToString(" -> ")} | ${route.lengthKm}km | score=${"%.1f".format(route.driveQualityScore)}")
    }
    println("Test completed. Starting web server...")

    val port = 8080
    val server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)

    // Serve HTML frontend
    server.createContext("/") { exchange ->
        val path = exchange.requestURI.path
        if (path == "/" || path == "/index.html") {
            val html = getFrontendHtml()
            sendResponse(exchange, 200, "text/html", html)
        } else {
            sendResponse(exchange, 404, "text/plain", "Not Found")
        }
    }

    // Serve API bases (Nodes)
    server.createContext("/api/nodes") { exchange ->
        if (exchange.requestMethod == "OPTIONS") {
            handleOptions(exchange)
            return@createContext
        }
        val nodesJson = checkpoints.values.joinToString(",") { cp ->
            """
            "${cp.name}": {
              "name": "${cp.name}",
              "lat": ${cp.lat},
              "lng": ${cp.lng},
              "description": "${cp.description.replace("\"", "\\\"")}"
            }
            """.trimIndent()
        }
        sendResponse(exchange, 200, "application/json", "{$nodesJson}")
    }

    // Serve API roads network
    server.createContext("/api/roads") { exchange ->
        if (exchange.requestMethod == "OPTIONS") {
            handleOptions(exchange)
            return@createContext
        }
        val roadsJson = defaultRoads().joinToString(",") { road ->
            """
            {
              "from": "${road.from}",
              "to": "${road.to}",
              "lengthKm": ${road.lengthKm},
              "curviness": ${road.curviness},
              "elevation": ${road.elevation},
              "scenery": ${road.scenery}
            }
            """.trimIndent()
        }
        sendResponse(exchange, 200, "application/json", "[$roadsJson]")
    }

    // Serve API route calculation
    server.createContext("/api/routes") { exchange ->
        if (exchange.requestMethod == "OPTIONS") {
            handleOptions(exchange)
            return@createContext
        }
        try {
            val params = parseQueryParams(exchange.requestURI.query)
            val start = params["start"] ?: "Hillcrest"
            val length = params["length"]?.toIntOrNull() ?: 55
            val typeStr = params["type"] ?: "CLOSED_LOOP"
            val routeType = if (typeStr == "ONE_WAY") RouteType.ONE_WAY else RouteType.CLOSED_LOOP
            
            val curviness = params["curviness"]?.toDoubleOrNull() ?: 0.5
            val elevation = params["elevation"]?.toDoubleOrNull() ?: 0.3
            val scenery = params["scenery"]?.toDoubleOrNull() ?: 0.2
            
            val request = RouteRequest(
                startPosition = start,
                preferredLengthKm = length,
                routeType = routeType,
                curvinessWeight = curviness,
                elevationWeight = elevation,
                sceneryWeight = scenery
            )
            
            val computed = engine.suggestRoutes(request)
            val suggestionsJson = computed.joinToString(",") { sug ->
                val checkpointsArray = sug.checkpoints.joinToString(",") { "\"$it\"" }
                """
                {
                  "checkpoints": [$checkpointsArray],
                  "lengthKm": ${sug.lengthKm},
                  "driveQualityScore": ${sug.driveQualityScore},
                  "isClosedLoop": ${sug.isClosedLoop}
                }
                """.trimIndent()
            }
            
            sendResponse(exchange, 200, "application/json", "[$suggestionsJson]")
        } catch (e: Exception) {
            sendResponse(exchange, 400, "application/json", "{\"error\": \"${e.message}\"}")
        }
    }

    // Serve API server metadata
    server.createContext("/api/meta") { exchange ->
        if (exchange.requestMethod == "OPTIONS") {
            handleOptions(exchange)
            return@createContext
        }
        val metaJson = """
        {
          "ip": "${getLocalIpAddress()}",
          "port": $port
        }
        """.trimIndent()
        sendResponse(exchange, 200, "application/json", metaJson)
    }

    server.executor = java.util.concurrent.Executors.newCachedThreadPool()
    server.start()

    val localIp = getLocalIpAddress()
    println("==========================================================")
    println("  APEX DRIVE ONLINE SERVER STATUS: SUCCESSFUL             ")
    println("==========================================================")
    println("  Local Client URL: http://localhost:$port")
    if (localIp != "127.0.0.1") {
        println("  Pixel/Mobile URL: http://$localIp:$port")
        println("  (Ensure phone and computer are on the same Wi-Fi)")
    } else {
        println("  Pixel/Mobile URL: [WiFi IP undetected; connect to WiFi]")
    }
    println("  Press Ctrl+C to terminate the server.")
    println("==========================================================")
}


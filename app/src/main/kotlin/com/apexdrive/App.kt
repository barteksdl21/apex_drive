package com.apexdrive

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.*

// ─── Domain models ───────────────────────────────────────────────────────────

enum class RouteType { CLOSED_LOOP, ONE_WAY }

data class RouteResult(
    val coordinates: List<List<Double>>,  // [[lat, lng], ...]
    val lengthKm: Double,
    val durationMin: Int,
    val isClosedLoop: Boolean,
    val qualityScore: Double,
    val elevationGainM: Int,
    val ruralRoadPercent: Int,
    val highwayPercent: Int,
)

// ─── Geo helpers ─────────────────────────────────────────────────────────────

/** Returns (lat, lng) of a point 'distanceKm' away from (lat, lng) along 'bearingDeg'. */
fun destinationPoint(lat: Double, lng: Double, bearingDeg: Double, distanceKm: Double): Pair<Double, Double> {
    val R = 6371.0
    val d = distanceKm / R
    val lat1 = Math.toRadians(lat)
    val lng1 = Math.toRadians(lng)
    val brng = Math.toRadians(bearingDeg)
    val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brng))
    val lng2 = lng1 + atan2(sin(brng) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
    return Pair(Math.toDegrees(lat2), Math.toDegrees(lng2))
}

// ─── ORS routing service ─────────────────────────────────────────────────────

class OrsRoutingService(private val apiKey: String) {
    private val gson = Gson()
    private val orsExecutor = Executors.newFixedThreadPool(4)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .executor(orsExecutor)
        .build()

    fun generateRoutes(
        lat: Double,
        lng: Double,
        lengthKm: Int,
        routeType: RouteType,
        curvinessWeight: Double,
        elevationWeight: Double,
        sceneryWeight: Double,
    ): List<RouteResult> {
        // Launch all ORS requests in parallel
        val futures: List<CompletableFuture<RouteResult?>> = when (routeType) {
            RouteType.CLOSED_LOOP -> listOf(
                // Vary seed and point count to get diverse route shapes
                CompletableFuture.supplyAsync({ callOrs(roundTripBody(lng, lat, lengthKm, seed = 1, points = 3), isLoop = true) }, orsExecutor),
                CompletableFuture.supplyAsync({ callOrs(roundTripBody(lng, lat, lengthKm, seed = 2, points = 5), isLoop = true) }, orsExecutor),
                CompletableFuture.supplyAsync({ callOrs(roundTripBody(lng, lat, lengthKm, seed = 3, points = 7), isLoop = true) }, orsExecutor),
            )
            RouteType.ONE_WAY -> listOf(45.0, 155.0, 270.0).mapIndexed { i, bearing ->
                // Route toward 3 different compass bearings; add slight mid-point offset for variety
                val (destLat, destLng) = destinationPoint(lat, lng, bearing, lengthKm * 0.9)
                val (midLat, midLng) = destinationPoint(lat, lng, bearing + (if (i % 2 == 0) 22.0 else -22.0), lengthKm * 0.45)
                CompletableFuture.supplyAsync(
                    { callOrs(oneWayBody(lng, lat, midLng, midLat, destLng, destLat), isLoop = false) },
                    orsExecutor
                )
            }
        }

        val results = futures.mapNotNull {
            try { it.get() } catch (e: Exception) { null }
        }.filter { it.qualityScore > 0 }

        // Rank by weighted quality: reward backroads + elevation per user preference
        return results.sortedByDescending { route ->
            route.qualityScore * (curvinessWeight + sceneryWeight * 0.5) +
                (route.elevationGainM / 200.0) * elevationWeight
        }.take(3)
    }

    private fun roundTripBody(lng: Double, lat: Double, lengthKm: Int, seed: Int, points: Int) = """
        {
          "coordinates": [[$lng, $lat]],
          "options": { "avoid_features": ["highways", "tollways", "ferries"] },
          "extra_info": ["steepness", "waytype"],
          "elevation": true,
          "round_trip": { "length": ${lengthKm * 1000}, "points": $points, "seed": $seed }
        }
    """.trimIndent()

    private fun oneWayBody(
        startLng: Double, startLat: Double,
        midLng: Double, midLat: Double,
        endLng: Double, endLat: Double,
    ) = """
        {
          "coordinates": [
            [$startLng, $startLat],
            [$midLng, $midLat],
            [$endLng, $endLat]
          ],
          "options": { "avoid_features": ["highways", "tollways", "ferries"] },
          "extra_info": ["steepness", "waytype"],
          "elevation": true
        }
    """.trimIndent()

    private fun callOrs(body: String, isLoop: Boolean): RouteResult? {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openrouteservice.org/v2/directions/driving-car/geojson"))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, application/geo+json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build()

            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) {
                System.err.println("ORS ${resp.statusCode()}: ${resp.body().take(300)}")
                return null
            }
            parseOrsResponse(resp.body(), isLoop)
        } catch (e: Exception) {
            System.err.println("ORS call error: ${e.message}")
            null
        }
    }

    private fun parseOrsResponse(json: String, isClosedLoop: Boolean): RouteResult? {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)
            val features = root.getAsJsonArray("features")
            if (features.size() == 0) return null

            val feature = features[0].asJsonObject
            val geometry = feature.getAsJsonObject("geometry")
            val props = feature.getAsJsonObject("properties")
            val summary = props.getAsJsonObject("summary")

            val rawCoords = geometry.getAsJsonArray("coordinates")
            // ORS returns [lng, lat, elev?]; convert to [lat, lng] for Leaflet
            val coordinates = rawCoords.map { elem ->
                val arr = elem.asJsonArray
                listOf(arr[1].asDouble, arr[0].asDouble)
            }

            val distanceM = summary["distance"].asDouble
            val durationSec = summary["duration"].asDouble

            // Cumulative elevation gain from 3D coordinate elevations
            var elevGain = 0.0
            if (rawCoords.size() > 1) {
                var prevElev = if (rawCoords[0].asJsonArray.size() > 2) rawCoords[0].asJsonArray[2].asDouble else 0.0
                for (i in 1 until rawCoords.size()) {
                    val arr = rawCoords[i].asJsonArray
                    if (arr.size() > 2) {
                        val elev = arr[2].asDouble
                        if (elev > prevElev) elevGain += (elev - prevElev)
                        prevElev = elev
                    }
                }
            }

            // Road type quality scoring
            var ruralDist = 0.0
            var highwayDist = 0.0
            var totalDist = 0.0
            val extras = props.getAsJsonObject("extras")
            extras?.getAsJsonObject("waytype")?.getAsJsonArray("summary")?.forEach { elem ->
                val e = elem.asJsonObject
                val type = e["value"].asInt
                val dist = e["distance"].asDouble
                totalDist += dist
                when (type) {
                    1 -> highwayDist += dist         // State road / fast road → penalize
                    2 -> ruralDist += dist * 1.5     // Regional/rural road → reward (best for fun drives)
                    3 -> ruralDist += dist * 0.6     // Street → small reward
                    9 -> return null                 // Ferry segment → disqualify entire route
                }
            }

            // Steepness: count segments ≥ 4% grade (|value| ≥ 2) as interesting
            var steepDist = 0.0
            extras?.getAsJsonObject("steepness")?.getAsJsonArray("summary")?.forEach { elem ->
                val e = elem.asJsonObject
                if (abs(e["value"].asInt) >= 2) steepDist += e["distance"].asDouble
            }

            val ruralPct = if (totalDist > 0) ((ruralDist / totalDist) * 100).toInt().coerceIn(0, 100) else 50
            val highwayPct = if (totalDist > 0) ((highwayDist / totalDist) * 100).toInt().coerceIn(0, 100) else 0
            val steepPct = if (totalDist > 0) ((steepDist / totalDist) * 100).toInt().coerceIn(0, 100) else 0

            // Quality score 0–10: blended from road character and elevation
            val qualityScore = ((ruralPct * 0.5 + steepPct * 0.35 + (100 - highwayPct) * 0.15) / 10.0)
                .coerceIn(0.0, 10.0)

            RouteResult(
                coordinates = coordinates,
                lengthKm = distanceM / 1000.0,
                durationMin = (durationSec / 60).toInt(),
                isClosedLoop = isClosedLoop,
                qualityScore = qualityScore,
                elevationGainM = elevGain.toInt(),
                ruralRoadPercent = ruralPct,
                highwayPercent = highwayPct,
            )
        } catch (e: Exception) {
            System.err.println("Failed to parse ORS response: ${e.message}")
            null
        }
    }
}

// ─── HTTP helpers ─────────────────────────────────────────────────────────────

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
                        hostAddress.startsWith("172.")
                    ) {
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
    if (localFile.exists()) return localFile.readText(StandardCharsets.UTF_8)
    val fallbackFile = File("src/main/resources/public/index.html")
    if (fallbackFile.exists()) return fallbackFile.readText(StandardCharsets.UTF_8)
    val resourceStream = OrsRoutingService::class.java.getResourceAsStream("/public/index.html")
    if (resourceStream != null) return resourceStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    return "<h1>APEX DRIVE - HTML UI Not Found</h1>"
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

// ─── Main ─────────────────────────────────────────────────────────────────────

fun findApiKey(): String {
    // 1. Check environment variable
    val envKey = System.getenv("ORS_API_KEY")
    if (!envKey.isNullOrBlank()) {
        println("✓ Loaded ORS_API_KEY from environment variable")
        return envKey
    }

    // 2. Check System property
    val sysKey = System.getProperty("ORS_API_KEY")
    if (!sysKey.isNullOrBlank()) {
        println("✓ Loaded ORS_API_KEY from system property")
        return sysKey
    }

    // 3. Traverse upwards from the current directory to find local.properties or .env
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        val localProps = File(dir, "local.properties")
        if (localProps.exists()) {
            try {
                val props = java.util.Properties()
                localProps.inputStream().use { props.load(it) }
                val key = props.getProperty("ORS_API_KEY")
                if (!key.isNullOrBlank()) {
                    println("✓ Loaded ORS_API_KEY from local.properties (${localProps.canonicalPath})")
                    return key
                }
            } catch (e: Exception) { /* ignore */ }
        }

        val envFile = File(dir, ".env")
        if (envFile.exists()) {
            try {
                envFile.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("ORS_API_KEY=")) {
                        val key = trimmed.substringAfter("ORS_API_KEY=").trim().removeSurrounding("\"").removeSurrounding("'")
                        if (key.isNotBlank()) {
                            println("✓ Loaded ORS_API_KEY from .env (${envFile.canonicalPath})")
                            return key
                        }
                    }
                }
            } catch (e: Exception) { /* ignore */ }
        }

        dir = dir.parentFile
    }

    return ""
}

fun main() {
    println("==========================================================")
    println("              APEX DRIVE SCENIC ROUTE ENGINE              ")
    println("==========================================================")

    val orsApiKey = findApiKey()
    val orsConfigured = orsApiKey.isNotBlank()
    val gson = Gson()

    if (!orsConfigured) {
        println("⚠ WARNING: ORS_API_KEY environment variable is not set.")
        println("  Route generation will be unavailable until configured.")
        println("  Get a free key at: https://openrouteservice.org/dev/#/signup")
        println("  Start with: ORS_API_KEY=your_key ./gradlew run")
    } else {
        println("✓ Route generation is ready.")
    }

    val orsService = if (orsConfigured) OrsRoutingService(orsApiKey) else null
    val port = 8080
    val server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)

    // Serve HTML frontend
    server.createContext("/") { exchange ->
        val path = exchange.requestURI.path
        if (path == "/" || path == "/index.html") {
            sendResponse(exchange, 200, "text/html", getFrontendHtml())
        } else {
            sendResponse(exchange, 404, "text/plain", "Not Found")
        }
    }

    // Config: lets the frontend know if routing is available
    server.createContext("/api/config") { exchange ->
        if (exchange.requestMethod == "OPTIONS") { handleOptions(exchange); return@createContext }
        sendResponse(exchange, 200, "application/json", """{"orsConfigured": $orsConfigured}""")
    }

    // Route generation via OpenRouteService
    server.createContext("/api/routes") { exchange ->
        if (exchange.requestMethod == "OPTIONS") { handleOptions(exchange); return@createContext }
        try {
            if (orsService == null) {
                sendResponse(
                    exchange, 503, "application/json",
                    """{"error": "ORS_API_KEY not set. See server logs for setup instructions."}"""
                )
                return@createContext
            }
            val params = parseQueryParams(exchange.requestURI.query)
            val lat = params["lat"]?.toDoubleOrNull() ?: throw IllegalArgumentException("Missing lat")
            val lng = params["lng"]?.toDoubleOrNull() ?: throw IllegalArgumentException("Missing lng")
            val length = params["length"]?.toIntOrNull() ?: 55
            val routeType = if (params["type"] == "ONE_WAY") RouteType.ONE_WAY else RouteType.CLOSED_LOOP
            val curviness = params["curviness"]?.toDoubleOrNull() ?: 0.5
            val elevation = params["elevation"]?.toDoubleOrNull() ?: 0.3
            val scenery = params["scenery"]?.toDoubleOrNull() ?: 0.2

            val routes = orsService.generateRoutes(lat, lng, length, routeType, curviness, elevation, scenery)
            sendResponse(exchange, 200, "application/json", gson.toJson(routes))
        } catch (e: Exception) {
            sendResponse(exchange, 400, "application/json", """{"error": "${e.message?.replace("\"", "\\\"")}"}""")
        }
    }

    // Server metadata for mobile access display
    server.createContext("/api/meta") { exchange ->
        if (exchange.requestMethod == "OPTIONS") { handleOptions(exchange); return@createContext }
        sendResponse(exchange, 200, "application/json", """{"ip": "${getLocalIpAddress()}", "port": $port}""")
    }

    server.executor = Executors.newCachedThreadPool()
    server.start()

    val localIp = getLocalIpAddress()
    println("==========================================================")
    println("  APEX DRIVE SERVER STATUS: ONLINE                        ")
    println("==========================================================")
    println("  Local URL:  http://localhost:$port")
    if (localIp != "127.0.0.1") {
        println("  Mobile URL: http://$localIp:$port")
        println("  (Ensure phone and computer are on the same Wi-Fi)")
    }
    println("  Press Ctrl+C to stop.")
    println("==========================================================")
}


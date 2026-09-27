package com.navio.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object RoutingService {

    suspend fun geocode(query: String): LatLon? = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/search?format=json&q=" +
            URLEncoder.encode(query, "UTF-8") + "&limit=1"
        val body = httpGet(url) ?: return@withContext null
        try {
            val arr = JSONArray(body)
            if (arr.length() == 0) null
            else {
                val first = arr.getJSONObject(0)
                LatLon(first.getDouble("lat"), first.getDouble("lon"))
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun route(start: LatLon, end: LatLon): RouteResult? = withContext(Dispatchers.IO) {
        val urls = listOf(
            "https://routing.openstreetmap.de/routed-bike/route/v1/driving/${start.lon},${start.lat};${end.lon},${end.lat}?overview=full&geometries=geojson&steps=true",
            "https://router.project-osrm.org/route/v1/driving/${start.lon},${start.lat};${end.lon},${end.lat}?overview=full&geometries=geojson&steps=true"
        )
        for (u in urls) {
            try {
                val body = httpGet(u) ?: continue
                val d = JSONObject(body)
                if (d.optString("code") != "Ok") continue
                val routes = d.optJSONArray("routes") ?: continue
                if (routes.length() == 0) continue
                val r = routes.getJSONObject(0)
                val geometry = r.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                val raw = ArrayList<LatLon>(geometry.length())
                for (i in 0 until geometry.length()) {
                    val c = geometry.getJSONArray(i)
                    raw.add(LatLon(GeoMath.round6(c.getDouble(1)), GeoMath.round6(c.getDouble(0))))
                }
                val coords = RouteMath.dedupe(raw)
                if (coords.isEmpty()) continue
                val names = buildRoadNames(r, coords.size)
                return@withContext RouteResult(coords, names, r.optDouble("distance", 0.0))
            } catch (e: Exception) {
                continue
            }
        }
        null
    }

    private fun buildRoadNames(route: JSONObject, total: Int): List<String> {
        val names = ArrayList<String>(total)
        repeat(total) { names.add("Route") }
        var idx = 0
        val legs = route.optJSONArray("legs") ?: return names
        for (li in 0 until legs.length()) {
            val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 0 until steps.length()) {
                val step = steps.getJSONObject(si)
                val count = step.optJSONObject("geometry")?.optJSONArray("coordinates")?.length() ?: 0
                val name = RouteMath.sanitizeRoad(step.optString("name"))
                repeat(count) {
                    if (idx < total) {
                        names[idx] = name
                        idx++
                    }
                }
            }
        }
        return names
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "NavioCompanion/1.0")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}

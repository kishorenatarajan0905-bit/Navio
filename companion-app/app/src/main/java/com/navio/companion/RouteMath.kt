package com.navio.companion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

object RouteMath {

    const val TURN_STRAIGHT = 0
    const val TURN_LEFT = 1
    const val TURN_RIGHT = 2
    const val TURN_SLIGHT_LEFT = 3
    const val TURN_SLIGHT_RIGHT = 4
    const val TURN_UTURN = 5
    const val TURN_ARRIVED = 6

    fun computeCum(coords: List<LatLon>): List<Double> {
        val out = ArrayList<Double>(coords.size)
        if (coords.isEmpty()) return out
        out.add(0.0)
        for (i in 1 until coords.size) out.add(out[i - 1] + GeoMath.haversine(coords[i - 1], coords[i]))
        return out
    }

    fun dedupe(points: List<LatLon>): List<LatLon> {
        val out = ArrayList<LatLon>(points.size)
        for (p in points) {
            val last = out.lastOrNull()
            if (last == null || last.lat != p.lat || last.lon != p.lon) out.add(p)
        }
        return out
    }

    fun downsample(c: List<LatLon>, maxN: Int): List<LatLon> {
        val n = c.size
        if (n <= maxN) return c.toList()
        val req = sortedSetOf(0, n - 1)
        var iMinLa = 0
        var iMaxLa = 0
        var iMinLo = 0
        var iMaxLo = 0
        for (i in 1 until n) {
            if (c[i].lat < c[iMinLa].lat) iMinLa = i
            if (c[i].lat > c[iMaxLa].lat) iMaxLa = i
            if (c[i].lon < c[iMinLo].lon) iMinLo = i
            if (c[i].lon > c[iMaxLo].lon) iMaxLo = i
        }
        req.add(iMinLa)
        req.add(iMaxLa)
        req.add(iMinLo)
        req.add(iMaxLo)
        val extra = max(1, maxN - req.size)
        val den = if (extra - 1 == 0) 1 else extra - 1
        for (k in 0 until extra) req.add(round(k.toDouble() * (n - 1) / den).toInt())
        return req.map { c[it] }
    }

    fun snapIndex(coords: List<LatLon>, p: LatLon): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in coords.indices) {
            val d = GeoMath.haversine(coords[i], p)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    fun estimateDistanceToTurn(coords: List<LatLon>, cum: List<Double>, i: Int): Int {
        if (coords.size < 3) return 100
        var dist = 0.0
        for (k in i until coords.size - 2) {
            dist += GeoMath.haversine(coords[k], coords[k + 1])
            val d = abs(GeoMath.angleDiff(GeoMath.bearing(coords[k], coords[k + 1]), GeoMath.bearing(coords[k + 1], coords[k + 2])))
            if (d > 30) return max(20, round(dist).toInt())
        }
        val remaining = cum.lastOrNull()?.minus(cum.getOrElse(i) { 0.0 }) ?: 100.0
        return max(20, if (remaining <= 0.0) 100 else round(remaining).toInt())
    }

    fun estimateTurnType(coords: List<LatLon>, i: Int): Int {
        if (coords.size < 3) return TURN_STRAIGHT
        for (k in i until coords.size - 2) {
            val d = GeoMath.angleDiff(GeoMath.bearing(coords[k], coords[k + 1]), GeoMath.bearing(coords[k + 1], coords[k + 2]))
            if (d < -150 || d > 150) return TURN_UTURN
            if (d < -30) return TURN_LEFT
            if (d > 30) return TURN_RIGHT
        }
        return TURN_STRAIGHT
    }

    fun plannedSpeedKmh(coords: List<LatLon>, i: Int): Double {
        if (coords.size < 3) return 18.0
        var slow = 0.0
        for (k in i until minOf(i + 6, coords.size - 2)) {
            val d = abs(GeoMath.angleDiff(GeoMath.bearing(coords[k], coords[k + 1]), GeoMath.bearing(coords[k + 1], coords[k + 2])))
            if (d > 25) {
                slow = min(1.0, (d - 25) / 60.0)
                break
            }
        }
        return GeoMath.round1(22.0 - (22.0 - 9.0) * slow)
    }

    fun sanitizeRoad(t: String?): String {
        val cleaned = (t ?: "").replace(Regex("[^A-Za-z0-9 .,_-]"), "").take(20).trim()
        return cleaned.ifEmpty { "Route" }
    }
}

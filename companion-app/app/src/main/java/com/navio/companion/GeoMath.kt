package com.navio.companion

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

data class RouteResult(val coords: List<LatLon>, val names: List<String>, val distanceM: Double)

object GeoMath {

    fun round6(v: Double): Double = round(v * 1e6) / 1e6

    fun round1(v: Double): Double = round(v * 10) / 10

    fun clamp(v: Double, a: Double, b: Double): Double = max(a, min(b, v))

    fun haversine(p1: LatLon, p2: LatLon): Double {
        val r = 6371000.0
        val dla = Math.toRadians(p2.lat - p1.lat)
        val dlo = Math.toRadians(p2.lon - p1.lon)
        val a = sin(dla / 2) * sin(dla / 2) +
            cos(Math.toRadians(p1.lat)) * cos(Math.toRadians(p2.lat)) * sin(dlo / 2) * sin(dlo / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun bearing(p1: LatLon, p2: LatLon): Double {
        val f1 = Math.toRadians(p1.lat)
        val f2 = Math.toRadians(p2.lat)
        val dl = Math.toRadians(p2.lon - p1.lon)
        val y = sin(dl) * cos(f2)
        val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun angleDiff(a: Double, b: Double): Double = ((b - a + 540) % 360) - 180
}

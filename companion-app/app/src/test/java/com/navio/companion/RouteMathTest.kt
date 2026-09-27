package com.navio.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMathTest {

    private fun line(n: Int, latStep: Double, lonStep: Double): List<LatLon> =
        (0 until n).map { LatLon(it * latStep, it * lonStep) }

    @Test
    fun `downsample keeps endpoints within 48 for long routes`() {
        val coords = line(120, 0.001, 0.001)
        val ds = RouteMath.downsample(coords, 48)
        assertTrue(ds.size in 40..48)
        assertEquals(coords.first(), ds.first())
        assertEquals(coords.last(), ds.last())
    }

    @Test
    fun `downsample returns same for short routes`() {
        val coords = line(10, 0.001, 0.0)
        assertEquals(coords, RouteMath.downsample(coords, 48))
    }

    @Test
    fun `cumulative distance starts at zero and increases`() {
        val coords = line(10, 0.001, 0.0)
        val cum = RouteMath.computeCum(coords)
        assertEquals(0.0, cum.first(), 0.0001)
        assertTrue(cum.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `straight line has no turn`() {
        val coords = line(5, 0.001, 0.0)
        assertEquals(RouteMath.TURN_STRAIGHT, RouteMath.estimateTurnType(coords, 0))
    }

    @Test
    fun `left turn detected`() {
        val coords = listOf(LatLon(0.0, 0.0), LatLon(0.01, 0.0), LatLon(0.01, -0.01))
        assertEquals(RouteMath.TURN_LEFT, RouteMath.estimateTurnType(coords, 0))
    }

    @Test
    fun `right turn detected`() {
        val coords = listOf(LatLon(0.0, 0.0), LatLon(0.01, 0.0), LatLon(0.01, 0.01))
        assertEquals(RouteMath.TURN_RIGHT, RouteMath.estimateTurnType(coords, 0))
    }

    @Test
    fun `u turn detected`() {
        val coords = listOf(LatLon(0.0, 0.0), LatLon(0.01, 0.0), LatLon(0.0, 0.0))
        assertEquals(RouteMath.TURN_UTURN, RouteMath.estimateTurnType(coords, 0))
    }

    @Test
    fun `snap index finds nearest point`() {
        val coords = listOf(LatLon(0.0, 0.0), LatLon(0.01, 0.0), LatLon(0.02, 0.0))
        assertEquals(1, RouteMath.snapIndex(coords, LatLon(0.0101, 0.0)))
    }

    @Test
    fun `planned speed is max on straight roads`() {
        val coords = line(20, 0.001, 0.0)
        assertEquals(22.0, RouteMath.plannedSpeedKmh(coords, 0), 0.01)
    }

    @Test
    fun `road name sanitized`() {
        assertEquals("College Road", RouteMath.sanitizeRoad("College Road"))
        assertEquals("Route", RouteMath.sanitizeRoad("!!!"))
        assertEquals(20, RouteMath.sanitizeRoad("A very long road name here").length)
    }
}

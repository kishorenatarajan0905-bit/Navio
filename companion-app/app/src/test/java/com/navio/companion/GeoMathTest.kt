package com.navio.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    @Test
    fun `haversine one degree lon at equator is about 111 km`() {
        val d = GeoMath.haversine(LatLon(0.0, 0.0), LatLon(0.0, 1.0))
        assertEquals(111194.9, d, 100.0)
    }

    @Test
    fun `bearing north is zero`() {
        val b = GeoMath.bearing(LatLon(13.0, 80.27), LatLon(13.05, 80.27))
        assertEquals(0.0, b, 0.5)
    }

    @Test
    fun `bearing south west quadrant`() {
        val b = GeoMath.bearing(LatLon(13.0827, 80.2707), LatLon(13.0358, 80.2565))
        assertTrue(b in 180.0..270.0)
    }

    @Test
    fun `angle diff wraps correctly`() {
        assertEquals(20.0, GeoMath.angleDiff(350.0, 10.0), 0.001)
        assertEquals(-20.0, GeoMath.angleDiff(10.0, 350.0), 0.001)
        assertEquals(-180.0, GeoMath.angleDiff(0.0, 180.0), 0.001)
    }

    @Test
    fun `round and clamp`() {
        assertEquals(12.123457, GeoMath.round6(12.12345678), 0.0000001)
        assertEquals(15.4, GeoMath.round1(15.42), 0.0000001)
        assertEquals(100.0, GeoMath.clamp(150.0, 0.0, 100.0), 0.001)
    }
}

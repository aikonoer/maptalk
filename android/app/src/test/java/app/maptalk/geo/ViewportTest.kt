package app.maptalk.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {

    private val sydney = GeoPoint(-33.8688, 151.2093)

    @Test
    fun `close in views use a geohash bounds query`() {
        val query = Viewport.queryFor(sydney, radiusKm = 2.0)
        assertEquals(ViewportQuery.Nearby(sydney, 2.0), query)
    }

    @Test
    fun `the boundary radius still counts as nearby`() {
        assertTrue(Viewport.queryFor(sydney, Viewport.NEARBY_MAX_RADIUS_KM) is ViewportQuery.Nearby)
    }

    @Test
    fun `wider views fall back to the worldwide activity query`() {
        assertEquals(ViewportQuery.GlobalRecent, Viewport.queryFor(sydney, radiusKm = 51.0))
        assertEquals(ViewportQuery.GlobalRecent, Viewport.queryFor(sydney, radiusKm = 8_000.0))
    }

    @Test
    fun `cluster prefix shortens as the camera pulls back`() {
        assertNull(Viewport.clusterPrefixLength(0.4))
        assertEquals(6, Viewport.clusterPrefixLength(3.0))
        assertEquals(5, Viewport.clusterPrefixLength(20.0))
        assertEquals(4, Viewport.clusterPrefixLength(80.0))
        assertEquals(3, Viewport.clusterPrefixLength(400.0))
        assertEquals(2, Viewport.clusterPrefixLength(5_000.0))
    }
}

class ClusterTest {

    private data class Pin(val id: String, val geohash: String, val position: GeoPoint)

    private fun cluster(items: List<Pin>, prefixLength: Int?) = clusterByGeohash(
        items = items,
        prefixLength = prefixLength,
        geohashOf = Pin::geohash,
        positionOf = Pin::position,
        keyOf = Pin::id,
    )

    @Test
    fun `a null prefix leaves every pin on its own`() {
        val pins = listOf(
            Pin("a", "r3gx2f303j", GeoPoint(-33.86, 151.20)),
            Pin("b", "r3gx2f303k", GeoPoint(-33.87, 151.21)),
        )
        val clusters = cluster(pins, prefixLength = null)
        assertEquals(2, clusters.size)
        assertTrue(clusters.all { it.size == 1 })
        assertEquals(setOf("a", "b"), clusters.map { it.key }.toSet())
    }

    @Test
    fun `pins sharing a prefix merge and sit at the mean position`() {
        val pins = listOf(
            Pin("a", "r3gx11", GeoPoint(-34.0, 151.0)),
            Pin("b", "r3gx22", GeoPoint(-34.0, 151.4)),
            Pin("c", "r3gy00", GeoPoint(-30.0, 150.0)),
        )
        val clusters = cluster(pins, prefixLength = 4)

        assertEquals(2, clusters.size)
        val merged = clusters.single { it.size == 2 }
        assertEquals("cluster:r3gx", merged.key)
        assertEquals(-34.0, merged.position.lat, 1e-9)
        assertEquals(151.2, merged.position.lng, 1e-9)

        val alone = clusters.single { it.size == 1 }
        assertEquals("c", alone.key)
        assertNull(merged.single)
        assertEquals("c", alone.single?.id)
    }

    @Test
    fun `a lone pin in its cell keeps its own identity rather than becoming a cluster`() {
        val clusters = cluster(listOf(Pin("a", "r3gx11", GeoPoint(-34.0, 151.0))), prefixLength = 2)
        assertEquals("a", clusters.single().key)
    }
}

class GeoPointTest {

    @Test
    fun `a degree of latitude is about 111 kilometres`() {
        val metres = GeoPoint(0.0, 0.0).distanceTo(GeoPoint(1.0, 0.0))
        assertEquals(111_195.0, metres, 200.0)
    }

    @Test
    fun `known city pair matches the great circle distance`() {
        // Sydney to Melbourne, roughly 713 km.
        val metres = GeoPoint(-33.8688, 151.2093).distanceTo(GeoPoint(-37.8136, 144.9631))
        assertEquals(713_000.0, metres, 5_000.0)
    }

    @Test
    fun `distance is symmetric and zero for the same point`() {
        val a = GeoPoint(51.5074, -0.1278)
        val b = GeoPoint(48.8566, 2.3522)
        assertEquals(a.distanceTo(b), b.distanceTo(a), 1e-6)
        assertEquals(0.0, a.distanceTo(a), 1e-9)
    }
}

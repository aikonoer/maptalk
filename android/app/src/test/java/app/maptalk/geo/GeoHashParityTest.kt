package app.maptalk.geo

import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The iOS app cannot use GeoFireUtils (it ships for CocoaPods only), so it carries a Swift
 * port in `ios/MapTalk/Core/GeoHash.swift`. These fixtures are the contract between the two:
 * `ios/MapTalkTests/GeoHashTests.swift` asserts the same inputs and the same outputs. The
 * library returns bounds in an unspecified order, so both sides sort by start hash before
 * comparing. If a value here ever changes, the iOS test must change with it.
 */
class GeoHashParityTest {

    @Test
    fun `hashes match the shared fixtures`() {
        // The example from the geohash literature.
        assertEquals("u4pruydqqvj", hash(57.64911, 10.40744, 11))
        // Null island sits exactly on a cell boundary, and the algorithm rounds down.
        assertEquals("7zzzzzzzzzzz", hash(0.0, 0.0, 12))
        assertEquals("000000000000", hash(-90.0, -180.0, 12))
        assertEquals("zzzzzzzzzzzz", hash(90.0, 180.0, 12))
        assertEquals("r3gx2f77bn", hash(-33.8688, 151.2093, 10))
        assertEquals("gcpvj0duq5", hash(51.5074, -0.1278, 10))
        assertEquals("9q8yyk8ytp", hash(37.7749, -122.4194, 10))
    }

    @Test
    fun `query bounds match the shared fixtures`() {
        assertEquals(
            listOf("r3gx28|r3gx2h", "r3gx2s|r3gx2w", "r3gx30|r3gx38", "r3gx3h|r3gx3n"),
            bounds(-33.8688, 151.2093, 1_000.0),
        )
        // '~' is the character just past 'z', which is how an open ended cell is expressed.
        assertEquals(
            listOf("gcpu8|gcpuh", "gcpus|gcpu~", "gcpv0|gcpv8", "gcpvh|gcpvs"),
            bounds(51.5074, -0.1278, 5_000.0),
        )
        assertEquals(
            listOf("7zzh|7zz~", "ebp0|ebph", "kpbh|kpb~", "s000|s00h"),
            bounds(0.0, 0.0, 50_000.0),
        )
    }

    private fun hash(lat: Double, lng: Double, precision: Int): String =
        GeoFireUtils.getGeoHashForLocation(GeoLocation(lat, lng), precision)

    private fun bounds(lat: Double, lng: Double, radiusMetres: Double): List<String> =
        GeoFireUtils.getGeoHashQueryBounds(GeoLocation(lat, lng), radiusMetres)
            .map { "${it.startHash}|${it.endHash}" }
            .sorted()
}

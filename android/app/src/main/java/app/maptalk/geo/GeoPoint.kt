package app.maptalk.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A plain latitude/longitude pair, deliberately free of any Maps or Firestore type so the
 * geo rules can be unit tested and stay comparable with the iOS implementation.
 */
data class GeoPoint(val lat: Double, val lng: Double) {

    /**
     * Great-circle distance in metres. Used to drop the false positives that geohash
     * bounds always include; both apps filter with the same formula so a thread never
     * appears on one platform and not the other.
     */
    fun distanceTo(other: GeoPoint): Double {
        val earthRadiusMetres = 6_371_008.8
        val dLat = Math.toRadians(other.lat - lat)
        val dLng = Math.toRadians(other.lng - lng)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) *
            sin(dLng / 2) * sin(dLng / 2)
        return 2 * earthRadiusMetres * asin(min(1.0, sqrt(a)))
    }

    /** Move south by [metres] (pin stays put while the camera centre shifts under a bottom sheet). */
    fun shiftedSouth(metres: Double): GeoPoint {
        val degrees = metres / 111_320.0
        return GeoPoint(lat = lat - degrees, lng = lng)
    }
}

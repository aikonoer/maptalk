package app.maptalk.geo

/**
 * How the map turns a camera position into a query and into markers.
 *
 * The thresholds here are a contract shared with iOS (`ios/MapTalk/Core/Viewport.swift`)
 * and documented in `docs/data-model.md`. Change them in all three places or the two apps
 * stop showing the same map.
 */
object Viewport {

    /** Beyond this the geohash bounds cover so much ground that we switch strategies. */
    const val NEARBY_MAX_RADIUS_KM = 50.0

    /** Documents fetched per geohash bound; there can be up to nine bounds. */
    const val PER_BOUND_LIMIT = 40L

    /** Threads fetched when zoomed out past [NEARBY_MAX_RADIUS_KM]. */
    const val GLOBAL_LIMIT = 200L

    fun queryFor(center: GeoPoint, radiusKm: Double): ViewportQuery =
        if (radiusKm <= NEARBY_MAX_RADIUS_KM) {
            ViewportQuery.Nearby(center, radiusKm)
        } else {
            ViewportQuery.GlobalRecent
        }

    /**
     * Geohash prefix length used to group markers, or null when the map is zoomed in far
     * enough to show every thread on its own.
     */
    fun clusterPrefixLength(radiusKm: Double): Int? = when {
        radiusKm > 500 -> 2
        radiusKm > 100 -> 3
        radiusKm > 25 -> 4
        radiusKm > 5 -> 5
        radiusKm > 1 -> 6
        else -> null
    }
}

sealed interface ViewportQuery {
    /** Geohash bounds query around the visible centre. */
    data class Nearby(val center: GeoPoint, val radiusKm: Double) : ViewportQuery

    /** Most recently active threads worldwide. */
    data object GlobalRecent : ViewportQuery
}

/** A group of items drawn as one marker. */
data class GeoCluster<T>(
    val key: String,
    val position: GeoPoint,
    val items: List<T>,
) {
    val size: Int get() = items.size
    val single: T? get() = items.singleOrNull()
}

/**
 * Groups items sharing a geohash prefix and places the marker at the mean position of the
 * group. A null [prefixLength] leaves every item on its own.
 */
fun <T> clusterByGeohash(
    items: List<T>,
    prefixLength: Int?,
    geohashOf: (T) -> String,
    positionOf: (T) -> GeoPoint,
    keyOf: (T) -> String,
): List<GeoCluster<T>> {
    if (prefixLength == null) {
        return items.map { item ->
            GeoCluster(key = keyOf(item), position = positionOf(item), items = listOf(item))
        }
    }
    return items
        .groupBy { geohashOf(it).take(prefixLength) }
        .map { (prefix, grouped) ->
            if (grouped.size == 1) {
                val only = grouped.first()
                GeoCluster(key = keyOf(only), position = positionOf(only), items = grouped)
            } else {
                GeoCluster(
                    key = "cluster:$prefix",
                    position = GeoPoint(
                        lat = grouped.sumOf { positionOf(it).lat } / grouped.size,
                        lng = grouped.sumOf { positionOf(it).lng } / grouped.size,
                    ),
                    items = grouped,
                )
            }
        }
        .sortedBy { it.key }
}

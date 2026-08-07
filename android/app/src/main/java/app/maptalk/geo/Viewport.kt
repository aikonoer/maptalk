package app.maptalk.geo

/**
 * A bubble is up to about half a screen wide and hangs up and right of the point it marks, so the
 * outermost chat needs close to another box width on that side to stay whole. Read by
 * [Viewport.drillFit], which has to predict how wide the camera will end up.
 */
private const val BUBBLE_ROOM = 0.9

/** Enough to keep the trailing edge of the group off the very edge of the screen. */
private const val EDGE_ROOM = 0.08

/** A group in a straight line has no width on one axis; about 90 m keeps the box usable. */
private const val MIN_ROOM_BASIS_DEGREES = 0.0008

/** The margin the map itself leaves around a fitted box, as a share of the box. */
private const val SCREEN_PADDING_SLACK = 1.1

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

    /**
     * Chats packed tighter than this still draw on top of each other at the deepest zoom, so
     * moving the camera cannot pull them apart.
     */
    const val MIN_DRILL_SPREAD_KM = 0.03

    /**
     * How much wider the camera ends up than the members' bare box: the room left for the bubbles
     * themselves plus the screen padding around them. Predicting the fitted view too tightly would
     * promise a group can be spread out when the camera actually lands back on the same marker.
     */
    private const val FIT_SLACK = (1 + BUBBLE_ROOM + EDGE_ROOM) * SCREEN_PADDING_SLACK

    /**
     * Where to put the camera when a grouped marker is tapped, or null when the group cannot be
     * opened up by moving the camera and the caller should list its chats instead.
     *
     * Fitting the members' own bounds is what keeps the group findable: every chat that was under
     * the marker is still on screen afterwards, rather than scattered outside it by a blind zoom
     * step. Null comes back when the members sit almost on the same spot, or when the fitted view
     * would still be wide enough to group them under one geohash prefix — in both cases the camera
     * would land on the same single marker and the chats would look lost.
     */
    fun <T> drillFit(
        items: List<T>,
        geohashOf: (T) -> String,
        positionOf: (T) -> GeoPoint,
    ): GeoBounds? {
        if (items.size < 2) return null
        val bounds = boundsOf(items.map(positionOf))
        if (bounds.radiusKm < MIN_DRILL_SPREAD_KM) return null
        val prefix = clusterPrefixLength(bounds.radiusKm * FIT_SLACK)
        if (prefix != null && items.distinctBy { geohashOf(it).take(prefix) }.size < 2) return null
        return bounds
    }
}

/**
 * The corners of the smallest box holding every given point. Local to a cluster, so it does not
 * try to reason about the antimeridian.
 */
data class GeoBounds(val southwest: GeoPoint, val northeast: GeoPoint) {
    val center: GeoPoint
        get() = GeoPoint(
            lat = (southwest.lat + northeast.lat) / 2,
            lng = (southwest.lng + northeast.lng) / 2,
        )

    /** Centre to corner, the same measure the map reports for the visible region. */
    val radiusKm: Double get() = center.distanceTo(northeast) / 1_000
}

fun boundsOf(points: List<GeoPoint>): GeoBounds = GeoBounds(
    southwest = GeoPoint(points.minOf { it.lat }, points.minOf { it.lng }),
    northeast = GeoPoint(points.maxOf { it.lat }, points.maxOf { it.lng }),
)

/**
 * The same box with room added up and to the right, where a bubble hangs from the corner that sits
 * on its coordinate. Fitting the bare box puts every chat on screen but runs the outermost label
 * off the edge, which is half of what makes a group feel lost.
 */
fun GeoBounds.withRoomForBubbles(): GeoBounds {
    val basis = maxOf(
        northeast.lat - southwest.lat,
        northeast.lng - southwest.lng,
        MIN_ROOM_BASIS_DEGREES,
    )
    return GeoBounds(
        southwest = GeoPoint(
            lat = southwest.lat - basis * EDGE_ROOM,
            lng = southwest.lng - basis * EDGE_ROOM,
        ),
        northeast = GeoPoint(
            lat = northeast.lat + basis * BUBBLE_ROOM,
            lng = northeast.lng + basis * BUBBLE_ROOM,
        ),
    )
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

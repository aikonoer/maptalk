package app.maptalk.ui

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import app.maptalk.geo.GeoPoint
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** A place the user can jump the map to. Mirrors `PlaceSearchHit` on iOS. */
data class PlaceSearchHit(
    val id: String,
    val title: String,
    val subtitle: String?,
    val latitude: Double,
    val longitude: Double,
    val zoom: Float,
)

/**
 * Natural-language place lookup for the map's search chrome. This finds a *place* to fly the
 * camera to; it is not a search over chat text. Mirrors `ios/MapTalk/Core/PlaceSearch.swift`,
 * which uses `MKLocalSearch` for the same job.
 */
object PlaceSearch {

    private const val MAX_RESULTS = 8
    private const val MIN_QUERY_LENGTH = 2

    /** Degrees either side of the map centre used to bias results toward what you're looking at. */
    private const val BIAS_DEGREES = 0.6

    /** A town or region wants a wider frame than a single building. */
    private const val AREA_ZOOM = 12.5f
    private const val PLACE_ZOOM = 14.5f

    suspend fun search(context: Context, query: String, near: GeoPoint?): List<PlaceSearchHit> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH || !Geocoder.isPresent()) return emptyList()

        // Bias toward the camera first (nearby streets / POIs). Android's Geocoder
        // treats the box as a hard filter — unlike MKLocalSearch — so if nothing
        // lands inside it (e.g. "melbourne" while looking at Cebu), fall back to
        // an unbounded lookup.
        val biased = runCatching { lookup(context, trimmed, near) }.getOrNull().orEmpty()
        val addresses = biased.ifEmpty {
            if (near == null) emptyList()
            else runCatching { lookup(context, trimmed, near = null) }.getOrNull().orEmpty()
        }
        return addresses.mapNotNull(::hit).distinctBy { it.id }
    }

    private fun hit(address: Address): PlaceSearchHit? {
        if (!address.hasLatitude() || !address.hasLongitude()) return null

        val title = listOfNotNull(
            cleaned(address.featureName),
            cleaned(address.locality),
            cleaned(address.adminArea),
        ).firstOrNull() ?: "Place"

        val subtitle = listOfNotNull(
            cleaned(address.subLocality),
            cleaned(address.locality),
            cleaned(address.adminArea),
            cleaned(address.countryName),
        )
            .filter { !it.equals(title, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }

        // A result with no street attached is a town or region, so frame it wider.
        val zoom = if (cleaned(address.thoroughfare) == null) AREA_ZOOM else PLACE_ZOOM

        return PlaceSearchHit(
            id = "$title|$subtitle|%.5f,%.5f".format(address.latitude, address.longitude),
            title = title,
            subtitle = subtitle,
            latitude = address.latitude,
            longitude = address.longitude,
            zoom = zoom,
        )
    }

    private suspend fun lookup(context: Context, query: String, near: GeoPoint?): List<Address> {
        val geocoder = Geocoder(context)
        val box = near?.let {
            doubleArrayOf(
                (it.lat - BIAS_DEGREES).coerceAtLeast(-90.0),
                (it.lng - BIAS_DEGREES).coerceAtLeast(-180.0),
                (it.lat + BIAS_DEGREES).coerceAtMost(90.0),
                (it.lng + BIAS_DEGREES).coerceAtMost(180.0),
            )
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        continuation.resume(addresses)
                    }

                    override fun onError(errorMessage: String?) {
                        continuation.resume(emptyList())
                    }
                }
                if (box == null) {
                    geocoder.getFromLocationName(query, MAX_RESULTS, listener)
                } else {
                    geocoder.getFromLocationName(
                        query, MAX_RESULTS, box[0], box[1], box[2], box[3], listener,
                    )
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                val found = if (box == null) {
                    geocoder.getFromLocationName(query, MAX_RESULTS)
                } else {
                    geocoder.getFromLocationName(query, MAX_RESULTS, box[0], box[1], box[2], box[3])
                }
                found.orEmpty()
            }
        }
    }

    private fun cleaned(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}

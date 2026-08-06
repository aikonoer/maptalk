package app.maptalk.ui

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maptalk.R
import app.maptalk.geo.GeoPoint
import app.maptalk.ui.theme.MapTalkColors
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Short area label for a map pin — neighborhood / city, not a full street address. Resolved
 * on-device through [Geocoder] and cached so peek/open doesn't spam the service. Mirrors
 * `ios/MapTalk/Core/PlaceLabel.swift`.
 */
object PlaceLabel {

    private val cache = mutableMapOf<String, String>()
    private val lock = Mutex()

    /** ~100 m bucket so nearby opens share one lookup. */
    fun cacheKey(point: GeoPoint): String = "%.3f,%.3f".format(point.lat, point.lng)

    suspend fun resolve(context: Context, point: GeoPoint): String? {
        val key = cacheKey(point)
        lock.withLock { cache[key] }?.let { return it }
        if (!Geocoder.isPresent()) return null

        val label = runCatching { lookup(context, point) }.getOrNull()?.let(::format) ?: return null
        lock.withLock { cache[key] = label }
        return label
    }

    /** Prefer area names over house numbers / precise street addresses. */
    fun format(address: Address): String? {
        cleaned(address.subLocality)?.let { return it }
        cleaned(address.locality)?.let { city ->
            val area = cleaned(address.adminArea)
            return if (area != null && area != city) "$city, $area" else city
        }
        // POI / landmark name only when it doesn't look like "123 Main St".
        cleaned(address.featureName)?.let { name ->
            if (!looksLikeStreetAddress(name)) return name
        }
        cleaned(address.thoroughfare)?.let { return it }
        return cleaned(address.adminArea)
    }

    private suspend fun lookup(context: Context, point: GeoPoint): Address? {
        val geocoder = Geocoder(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(point.lat, point.lng, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        continuation.resume(addresses.firstOrNull())
                    }

                    override fun onError(errorMessage: String?) {
                        continuation.resume(null)
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(point.lat, point.lng, 1)?.firstOrNull()
            }
        }
    }

    private fun cleaned(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun looksLikeStreetAddress(name: String): Boolean =
        name.split(' ', '\t', '\n').any { token -> token.any(Char::isDigit) }
}

/** Pin glyph + resolved area name (or a quiet loading / fallback state). */
@Composable
fun PlaceLabelLine(
    point: GeoPoint,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val context = LocalContext.current
    val key = remember(point.lat, point.lng) { PlaceLabel.cacheKey(point) }
    var label by remember(key) { mutableStateOf<String?>(null) }
    var didFail by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key) {
        val resolved = PlaceLabel.resolve(context, point)
        label = resolved
        didFail = resolved == null
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_pin),
            contentDescription = null,
            tint = MapTalkColors.Subtle,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = label ?: if (didFail) "Somewhere nearby" else "Finding area\u2026",
            style = MaterialTheme.typography.labelSmall,
            color = MapTalkColors.Subtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Text(
                text = "\u00b7 $trailing",
                style = MaterialTheme.typography.labelSmall,
                color = MapTalkColors.Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

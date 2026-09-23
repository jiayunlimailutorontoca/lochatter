package ink.jvm.chatter.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** One position fix. [address] is best-effort (system geocoder; often null on ROMs without Google services). */
data class Fix(val lat: Double, val lng: Double, val accuracyM: Int, val address: String?)

/** Location without Play Services: the platform LocationManager plus the system Geocoder. */
object Locator {
    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Wire form: `lat,lng|accuracy|address|live` (see docs/protocol-1.5.md). */
    fun encode(fix: Fix, live: Boolean): String {
        val addr = (fix.address ?: "").replace('|', ' ').replace('\n', ' ').take(120)
        return "%.6f,%.6f|%d|%s|%d".format(Locale.US, fix.lat, fix.lng, fix.accuracyM, addr, if (live) 1 else 0)
    }

    fun decode(text: String?): Pair<Fix, Boolean>? {
        if (text == null) return null
        val p = text.split('|')
        val ll = p.getOrNull(0)?.split(',') ?: return null
        if (ll.size != 2) return null
        val lat = ll[0].trim().toDoubleOrNull() ?: return null
        val lng = ll[1].trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        val acc = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        val addr = p.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
        val live = p.getOrNull(3)?.trim() == "1"
        return Fix(lat, lng, acc, addr) to live
    }

    /** Address text of a picked place: `name·address` (1.6 picker); either half may be missing. Null when both are blank. */
    fun placeText(name: String?, address: String?): String? {
        val n = name?.trim().orEmpty().replace('·', ' ')
        val a = address?.trim().orEmpty()
        return when {
            n.isEmpty() && a.isEmpty() -> null
            n.isEmpty() -> a
            a.isEmpty() || a == n -> n
            else -> "$n·$a"
        }
    }

    /** The reverse of [placeText]: (name, address). A plain address (older clients, system geocoder) comes back as (null, address). */
    fun splitPlace(text: String?): Pair<String?, String?> {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
        val i = t.indexOf('·')
        if (i <= 0 || i >= t.length - 1) return null to t.trim('·').trim().ifEmpty { null }
        return t.substring(0, i).trim() to t.substring(i + 1).trim()
    }

    /** Opens the fix in whatever map app is installed (Amap, Baidu and Google all handle geo: URIs). */
    fun mapIntent(fix: Fix): Intent {
        val label = Uri.encode(fix.address ?: "位置")
        val geo = "geo:%.6f,%.6f?q=%.6f,%.6f(%s)".format(Locale.US, fix.lat, fix.lng, fix.lat, fix.lng, label)
        return Intent(Intent.ACTION_VIEW, Uri.parse(geo)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The fix as WGS-84. GPS is always WGS-84; the network provider on Chinese ROMs without Google services (Xiaomi,
     * Huawei, vivo, OPPO location services) already answers in GCJ-02, so those are converted back once here.
     */
    fun wgs84(ctx: Context, l: Location): Pair<Double, Double> {
        val fromGps = l.provider == LocationManager.GPS_PROVIDER
        if (fromGps || hasGms(ctx) || Gcj02.outOfChina(l.latitude, l.longitude)) return l.latitude to l.longitude
        return Gcj02.toWgs84(l.latitude, l.longitude)
    }

    private var gms: Boolean? = null
    private fun hasGms(ctx: Context): Boolean = gms ?: runCatching { ctx.packageManager.getPackageInfo("com.google.android.gms", 0); true }.getOrDefault(false).also { gms = it }

    /** One fix within [timeoutMs]: a fresh GPS / network position, else a recent last-known one. */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context, timeoutMs: Long = 15_000): Fix? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        var loc: Location? = null
        for (provider in providers) {
            loc = withTimeoutOrNull(timeoutMs / providers.size.coerceAtLeast(1)) { once(lm, provider) }
            if (loc != null) break
        }
        if (loc == null) {
            val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            if (recent != null && System.currentTimeMillis() - recent.time < 2 * 60_000L) loc = recent
        }
        val l = loc ?: return null
        val (lat, lng) = wgs84(ctx, l)
        return Fix(lat, lng, l.accuracy.toInt(), geocode(ctx, lat, lng))
    }

    @SuppressLint("MissingPermission")
    private suspend fun once(lm: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { runCatching { lm.removeUpdates(this) }; if (cont.isActive) cont.resume(location) }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) { runCatching { lm.removeUpdates(this) }; if (cont.isActive) cont.resume(null) }
        }
        cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
        runCatching {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    /** Fixes every [intervalMs] (or 5 m of movement) from GPS and network; geocoded at most once a minute. */
    @SuppressLint("MissingPermission")
    fun liveUpdates(ctx: Context, intervalMs: Long = 10_000): Flow<Fix> = callbackFlow {
        if (!hasPermission(ctx)) { close(); return@callbackFlow }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run { close(); return@callbackFlow }
        var lastGeo = 0L
        var lastAddr: String? = null
        val geoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val now = System.currentTimeMillis()
                if (now - lastGeo > 60_000) {
                    lastGeo = now
                    val (la, lo) = location.latitude to location.longitude
                    geoScope.launch { lastAddr = geocode(ctx, la, lo) ?: lastAddr }
                }
                val (lat, lng) = wgs84(ctx, location)
                trySend(Fix(lat, lng, location.accuracy.toInt(), lastAddr))
            }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching { if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, intervalMs, 5f, listener, Looper.getMainLooper()) }
        }
        awaitClose { runCatching { lm.removeUpdates(listener) }; geoScope.cancel() }
    }

    /** Human-readable place, or null (no geocoder backend, offline, timeout). */
    suspend fun geocode(ctx: Context, lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(5_000) {
            runCatching {
                val g = Geocoder(ctx, Locale.getDefault())
                val addr = if (Build.VERSION.SDK_INT >= 33) {
                    suspendCancellableCoroutine<android.location.Address?> { cont ->
                        g.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<android.location.Address>) { if (cont.isActive) cont.resume(addresses.firstOrNull()) }
                            override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                        })
                    }
                } else {
                    @Suppress("DEPRECATION")
                    withContext(Dispatchers.IO) { g.getFromLocation(lat, lng, 1)?.firstOrNull() }
                }
                addr?.let { a ->
                    val parts = (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }
                    (parts.firstOrNull() ?: listOfNotNull(a.locality, a.subLocality, a.thoroughfare, a.featureName).joinToString("")).takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }
    }
}

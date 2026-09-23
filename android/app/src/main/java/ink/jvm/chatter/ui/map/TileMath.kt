package ink.jvm.chatter.ui.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web-Mercator arithmetic for XYZ tiles (256 px, "slippy map" convention: x east from -180°, y south from 85.05°N).
 * "World pixels" are the coordinates of the whole map rendered at a given (fractional) zoom: 256 · 2^zoom on a side.
 * Pure Kotlin on purpose so it runs in plain JVM unit tests (see TileMathTest).
 */
object TileMath {
    const val TILE = 256
    /** Latitude where the Mercator projection is cut off (a square world). */
    const val MAX_LAT = 85.05112878
    /** Tiles exist for z ≤ 19 on the server; the UI stops at 18. */
    const val MAX_TILE_Z = 19
    private const val EQUATOR_M_PER_PX = 156543.03392804097 // metres per world pixel at zoom 0, on the equator

    fun worldSize(zoom: Double): Double = TILE * 2.0.pow(zoom)

    fun clampLat(lat: Double): Double = lat.coerceIn(-MAX_LAT, MAX_LAT)

    fun wrapLng(lng: Double): Double {
        var l = lng
        while (l > 180.0) l -= 360.0
        while (l < -180.0) l += 360.0
        return l
    }

    fun lngToX(lng: Double, zoom: Double): Double = (wrapLng(lng) + 180.0) / 360.0 * worldSize(zoom)

    fun latToY(lat: Double, zoom: Double): Double {
        val r = Math.toRadians(clampLat(lat))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * worldSize(zoom)
    }

    fun xToLng(x: Double, zoom: Double): Double = x / worldSize(zoom) * 360.0 - 180.0

    fun yToLat(y: Double, zoom: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / worldSize(zoom)))))

    /** Tile column holding [lng] at integer zoom [z]. */
    fun tileX(lng: Double, z: Int): Int = floor(lngToX(lng, z.toDouble()) / TILE).toInt().coerceIn(0, (1 shl z) - 1)

    /** Tile row holding [lat] at integer zoom [z]. */
    fun tileY(lat: Double, z: Int): Int = floor(latToY(lat, z.toDouble()) / TILE).toInt().coerceIn(0, (1 shl z) - 1)

    /** Longitude of the west edge of tile column [x]. */
    fun tileToLng(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360.0 - 180.0

    /** Latitude of the north edge of tile row [y]. */
    fun tileToLat(y: Int, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / (1 shl z)))))

    /** Ground resolution: metres covered by one world pixel at [zoom], at latitude [lat]. */
    fun metersPerPixel(lat: Double, zoom: Double): Double = EQUATOR_M_PER_PX * cos(Math.toRadians(clampLat(lat))) / 2.0.pow(zoom)

    /** The state after dragging the map so that the content moves by (-dx, -dy) world pixels (i.e. the centre moves by +dx, +dy). */
    fun panned(s: MapState, dxWorld: Double, dyWorld: Double): MapState {
        val x = lngToX(s.centerLng, s.zoom) + dxWorld
        val y = (latToY(s.centerLat, s.zoom) + dyWorld).coerceIn(0.0, worldSize(s.zoom))
        return s.copy(centerLat = clampLat(yToLat(y, s.zoom)), centerLng = wrapLng(xToLng(x, s.zoom)))
    }

    /**
     * Zoom to [newZoom] keeping the place under the focal point where it is. [focalDx]/[focalDy] is the focal point's
     * offset from the viewport centre in world pixels (screen px divided by the tile scale); it is the same at both zooms.
     */
    fun zoomedAround(s: MapState, newZoom: Double, focalDx: Double, focalDy: Double): MapState {
        val z1 = newZoom.coerceIn(MapState.MIN_ZOOM, MapState.MAX_ZOOM)
        if (z1 == s.zoom) return s
        val ratio = 2.0.pow(z1 - s.zoom)
        val fx = (lngToX(s.centerLng, s.zoom) + focalDx) * ratio
        val fy = (latToY(s.centerLat, s.zoom) + focalDy) * ratio
        val cx = fx - focalDx
        val cy = (fy - focalDy).coerceIn(0.0, worldSize(z1))
        return MapState(clampLat(yToLat(cy, z1)), wrapLng(xToLng(cx, z1)), z1)
    }

    /** Centre of the (projected) box spanned by two points. */
    fun midpoint(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Pair<Double, Double> {
        val z = 10.0
        val x = (lngToX(lng1, z) + lngToX(lng2, z)) / 2
        val y = (latToY(lat1, z) + latToY(lat2, z)) / 2
        return yToLat(y, z) to xToLng(x, z)
    }

    /**
     * Largest zoom (≤ [maxZoom]) at which both points fit into a viewport of [widthWorldPx] × [heightWorldPx]
     * world pixels (screen px divided by the tile scale), leaving [paddingWorldPx] free on every side.
     */
    fun fitZoom(
        lat1: Double, lng1: Double, lat2: Double, lng2: Double,
        widthWorldPx: Double, heightWorldPx: Double, paddingWorldPx: Double = 0.0, maxZoom: Double = 17.0,
    ): Double {
        val dx = max(kotlin.math.abs(lngToX(lng1, 0.0) - lngToX(lng2, 0.0)), 1e-9)
        val dy = max(kotlin.math.abs(latToY(lat1, 0.0) - latToY(lat2, 0.0)), 1e-9)
        val w = max(widthWorldPx - 2 * paddingWorldPx, 1.0)
        val h = max(heightWorldPx - 2 * paddingWorldPx, 1.0)
        val z = min(log2(w / dx), log2(h / dy))
        return z.coerceIn(MapState.MIN_ZOOM, maxZoom)
    }
}

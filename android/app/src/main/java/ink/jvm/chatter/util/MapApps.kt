package ink.jvm.chatter.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.util.Locale

/**
 * Deep links into the map apps people actually have (高德 / 百度 / 腾讯 / Google) plus the generic `geo:` fallback.
 * Everything we hand over is WGS-84 (the phone's raw GPS), and every link says so (`dev=1`, `coord_type=wgs84`,
 * `coord_type=1`) so the app converts to GCJ-02 itself and the pin lands where it should.
 * The URL builders are pure so they can be unit-tested; only [installed] / [intentFor] touch Android.
 */
object MapApps {
    class App(val id: String, val label: String, val pkg: String)

    val AMAP = App("amap", "高德地图", "com.autonavi.minimap")
    val BAIDU = App("baidu", "百度地图", "com.baidu.BaiduMap")
    val TENCENT = App("tencent", "腾讯地图", "com.tencent.map")
    val GOOGLE = App("google", "Google 地图", "com.google.android.apps.maps")
    val ALL: List<App> = listOf(AMAP, BAIDU, TENCENT, GOOGLE)

    /** Percent-encodes for a query value; spaces become %20 rather than '+', which Amap would show literally. */
    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun fmt(d: Double): String = "%.6f".format(Locale.US, d)

    fun amapUri(lat: Double, lng: Double, name: String): String =
        "androidamap://viewMap?sourceApplication=lochatter&poiname=${encode(name)}&lat=${fmt(lat)}&lon=${fmt(lng)}&dev=1"

    fun baiduUri(lat: Double, lng: Double, name: String): String =
        "baidumap://map/marker?location=${fmt(lat)},${fmt(lng)}&title=${encode(name)}&coord_type=wgs84&src=ink.jvm.chatter"

    fun tencentUri(lat: Double, lng: Double, name: String): String =
        "qqmap://map/marker?marker=coord:${fmt(lat)},${fmt(lng)};title:${encode(name)}&coord_type=1&referer=lochatter"

    /** Google Maps and any other `geo:` handler. */
    fun geoUri(lat: Double, lng: Double, name: String): String =
        "geo:${fmt(lat)},${fmt(lng)}?q=${fmt(lat)},${fmt(lng)}(${encode(name)})"

    fun uriFor(app: App, lat: Double, lng: Double, name: String): String = when (app.id) {
        AMAP.id -> amapUri(lat, lng, name)
        BAIDU.id -> baiduUri(lat, lng, name)
        TENCENT.id -> tencentUri(lat, lng, name)
        else -> geoUri(lat, lng, name)
    }

    /** Amap's web marker page: opens in any browser and hands over to the app when installed. */
    fun amapWebLink(lat: Double, lng: Double, name: String): String =
        "https://uri.amap.com/marker?position=${fmt(lng)},${fmt(lat)}&name=${encode(name)}&coordinate=wgs84&callnative=1"

    /** Share-sheet text: name, address, link. */
    fun shareText(name: String?, address: String?, lat: Double, lng: Double): String {
        val lines = listOfNotNull(name?.trim()?.takeIf { it.isNotEmpty() }, address?.trim()?.takeIf { it.isNotEmpty() })
        return (lines + amapWebLink(lat, lng, name?.takeIf { it.isNotBlank() } ?: address?.takeIf { it.isNotBlank() } ?: "位置")).joinToString("\n")
    }

    /** "lat,lng" for the clipboard. */
    fun coordText(lat: Double, lng: Double): String = "${fmt(lat)},${fmt(lng)}"

    // ---- Android ----

    fun intentFor(app: App, lat: Double, lng: Double, name: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uriFor(app, lat, lng, name))).setPackage(app.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Any `geo:` handler, via the system chooser. */
    fun genericIntent(lat: Double, lng: Double, name: String): Intent =
        Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(geoUri(lat, lng, name))), "导航到「$name」").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The known map apps that are installed and accept our link (needs the `<queries>` block in the manifest on Android 11+). */
    fun installed(ctx: Context, lat: Double, lng: Double, name: String): List<App> =
        ALL.filter { app -> runCatching { ctx.packageManager.resolveActivity(intentFor(app, lat, lng, name), 0) != null }.getOrDefault(false) }
}

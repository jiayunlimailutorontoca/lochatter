package ink.jvm.chatter

import ink.jvm.chatter.ui.map.MapState
import ink.jvm.chatter.ui.map.TileMath
import ink.jvm.chatter.util.MapApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TileMathTest {
    @Test
    fun shanghaiTileAtZoom12() {
        // 31.2304, 121.4737 → x = floor((121.4737+180)/360 · 4096) = 3430, y = 1673 (standard slippy-map formula).
        assertEquals(3430, TileMath.tileX(121.4737, 12))
        assertEquals(1673, TileMath.tileY(31.2304, 12))
    }

    @Test
    fun roundTripsAtSeveralZooms() {
        for (z in listOf(3.0, 10.5, 16.0, 18.0)) {
            for ((lat, lng) in listOf(31.2304 to 121.4737, -33.86 to 151.2, 64.1 to -21.9, 0.0 to 0.0)) {
                val x = TileMath.lngToX(lng, z)
                val y = TileMath.latToY(lat, z)
                assertTrue("lat at z=$z", abs(TileMath.yToLat(y, z) - lat) < 1e-7)
                assertTrue("lng at z=$z", abs(TileMath.xToLng(x, z) - lng) < 1e-7)
            }
        }
    }

    @Test
    fun zoomAroundFocalKeepsThePlaceUnderTheFinger() {
        val s = MapState(31.2304, 121.4737, 14.0)
        val fdx = 120.0
        val fdy = -40.0
        val before = TileMath.xToLng(TileMath.lngToX(s.centerLng, s.zoom) + fdx, s.zoom) to TileMath.yToLat(TileMath.latToY(s.centerLat, s.zoom) + fdy, s.zoom)
        val z = TileMath.zoomedAround(s, 15.0, fdx, fdy)
        val after = TileMath.xToLng(TileMath.lngToX(z.centerLng, z.zoom) + fdx, z.zoom) to TileMath.yToLat(TileMath.latToY(z.centerLat, z.zoom) + fdy, z.zoom)
        assertTrue(abs(before.first - after.first) < 1e-6)
        assertTrue(abs(before.second - after.second) < 1e-6)
    }

    @Test
    fun panMovesTheCentre() {
        val s = MapState(31.2304, 121.4737, 16.0)
        val p = TileMath.panned(s, 256.0, 0.0)
        assertTrue(p.centerLng > s.centerLng)
        assertTrue(abs(p.centerLat - s.centerLat) < 1e-9)
    }

    @Test
    fun fitZoomFitsBothPoints() {
        val z = TileMath.fitZoom(31.23, 121.47, 31.24, 121.49, 800.0, 600.0, paddingWorldPx = 40.0)
        assertTrue(z in 3.0..17.0)
        val dx = abs(TileMath.lngToX(121.47, z) - TileMath.lngToX(121.49, z))
        assertTrue(dx <= 800.0 - 80.0 + 1e-6)
    }
}

class MapAppsTest {
    @Test
    fun amapLinkUsesWgs84AndEncodesTheName() {
        val u = MapApps.amapUri(31.2304, 121.4737, "人民 广场")
        assertTrue(u.startsWith("androidamap://viewMap?"))
        assertTrue(u.contains("dev=1"))
        assertTrue(u.contains("lat=31.230400&lon=121.473700"))
        assertTrue(u.contains("poiname=%E4%BA%BA%E6%B0%91%20%E5%B9%BF%E5%9C%BA"))
    }

    @Test
    fun baiduAndTencentSayWgs84() {
        assertTrue(MapApps.baiduUri(1.0, 2.0, "x").contains("coord_type=wgs84"))
        assertTrue(MapApps.tencentUri(1.0, 2.0, "x").contains("coord_type=1"))
        assertEquals("geo:1.000000,2.000000?q=1.000000,2.000000(x)", MapApps.geoUri(1.0, 2.0, "x"))
    }

    @Test
    fun shareTextHasNameAddressAndLink() {
        val t = MapApps.shareText("人民广场", "上海市黄浦区", 31.2304, 121.4737)
        val lines = t.lines()
        assertEquals("人民广场", lines[0])
        assertEquals("上海市黄浦区", lines[1])
        assertTrue(lines[2].startsWith("https://uri.amap.com/marker?position=121.473700,31.230400"))
    }
}

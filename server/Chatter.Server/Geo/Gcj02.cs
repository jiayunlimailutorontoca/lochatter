namespace Chatter.Server.Geo;

/// <summary>
/// WGS-84 ↔ GCJ-02 ("Mars coordinates"). Chinese map providers, Amap included, only speak GCJ-02, an obfuscated datum
/// that shifts positions by a few hundred metres; phones report raw WGS-84 and so does our wire format. The forward
/// transform is the well-known public reconstruction (a = 6378245, ee = 0.00669342162296594323); the inverse iterates
/// it to well under a metre. Points outside China are left alone, as the providers themselves do.
/// </summary>
public static class Gcj02
{
    private const double A = 6378245.0;
    private const double Ee = 0.00669342162296594323;

    public static bool OutOfChina(double lat, double lng) =>
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;

    public static (double Lat, double Lng) FromWgs84(double lat, double lng)
    {
        if (OutOfChina(lat, lng)) return (lat, lng);
        var (dLat, dLng) = Delta(lat, lng);
        return (lat + dLat, lng + dLng);
    }

    /// <summary>Inverse by fixed-point iteration: the offset field is smooth, so a handful of rounds converge to under 1e-8 degrees.</summary>
    public static (double Lat, double Lng) ToWgs84(double lat, double lng)
    {
        if (OutOfChina(lat, lng)) return (lat, lng);
        double wLat = lat, wLng = lng;
        for (var i = 0; i < 10; i++)
        {
            var (gLat, gLng) = FromWgs84(wLat, wLng);
            var eLat = gLat - lat;
            var eLng = gLng - lng;
            wLat -= eLat;
            wLng -= eLng;
            if (Math.Abs(eLat) < 1e-8 && Math.Abs(eLng) < 1e-8) break;
        }
        return (wLat, wLng);
    }

    private static (double DLat, double DLng) Delta(double lat, double lng)
    {
        var dLat = TransformLat(lng - 105.0, lat - 35.0);
        var dLng = TransformLng(lng - 105.0, lat - 35.0);
        var radLat = lat / 180.0 * Math.PI;
        var magic = Math.Sin(radLat);
        magic = 1 - Ee * magic * magic;
        var sqrtMagic = Math.Sqrt(magic);
        dLat = dLat * 180.0 / (A * (1 - Ee) / (magic * sqrtMagic) * Math.PI);
        dLng = dLng * 180.0 / (A / sqrtMagic * Math.Cos(radLat) * Math.PI);
        return (dLat, dLng);
    }

    private static double TransformLat(double x, double y)
    {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.Sqrt(Math.Abs(x));
        ret += (20.0 * Math.Sin(6.0 * x * Math.PI) + 20.0 * Math.Sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.Sin(y * Math.PI) + 40.0 * Math.Sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.Sin(y / 12.0 * Math.PI) + 320.0 * Math.Sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double TransformLng(double x, double y)
    {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.Sqrt(Math.Abs(x));
        ret += (20.0 * Math.Sin(6.0 * x * Math.PI) + 20.0 * Math.Sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.Sin(x * Math.PI) + 40.0 * Math.Sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.Sin(x / 12.0 * Math.PI) + 300.0 * Math.Sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}

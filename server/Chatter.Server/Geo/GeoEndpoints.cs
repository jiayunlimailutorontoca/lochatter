using System.Globalization;
using System.Text.Json.Serialization.Metadata;
using Chatter.Server.Auth;
using Microsoft.Extensions.Primitives;

namespace Chatter.Server.Geo;

/// <summary>
/// GET /geo/regeo, /geo/around, /geo/search (1.6): the location picker's reverse geocoding and place search, bearer
/// authenticated like the other REST endpoints. Parameters are checked before availability, so a bad coordinate is a
/// 400 even on a server without a key; then 503 geo_unavailable, 429 rate_limited and 502 geo_upstream, in that order.
/// Coordinates in and out are WGS-84.
/// </summary>
public static class GeoEndpoints
{
    /// <summary>Per user. Generous: the picker calls regeo while the map is dragged (throttled to one per 600 ms on the phone).</summary>
    private static readonly UserRateLimiter Limiter = new(perMinute: 120, burst: 120);

    public static void Map(WebApplication app)
    {
        var log = app.Logger;

        app.MapGet("/geo/regeo", async (HttpContext ctx, AuthService auth, GeoService geo) =>
        {
            if (Check(ctx, auth, geo, needKeywords: false, out var lat, out var lng, out _) is { } fail) return fail;
            return await Call(log, () => geo.RegeoAsync(lat, lng, ctx.RequestAborted), Json.RegeoResponse);
        });

        app.MapGet("/geo/around", async (HttpContext ctx, AuthService auth, GeoService geo) =>
        {
            if (Check(ctx, auth, geo, needKeywords: false, out var lat, out var lng, out var q) is { } fail) return fail;
            var page = int.TryParse(ctx.Request.Query["page"], NumberStyles.Integer, CultureInfo.InvariantCulture, out var p) && p > 0 ? Math.Min(p, GeoService.MaxPage) : 1;
            return await Call(log, () => geo.AroundAsync(lat, lng, q, page, ctx.RequestAborted), Json.PoiList);
        });

        app.MapGet("/geo/search", async (HttpContext ctx, AuthService auth, GeoService geo) =>
        {
            if (Check(ctx, auth, geo, needKeywords: true, out var lat, out var lng, out var q) is { } fail) return fail;
            return await Call(log, () => geo.SearchAsync(q, lat, lng, ctx.RequestAborted), Json.PoiList);
        });
    }

    /// <summary>Auth, coordinates, keywords, availability and the per-user limit, in that order. Null = go ahead.</summary>
    private static IResult? Check(HttpContext ctx, AuthService auth, GeoService geo, bool needKeywords, out double lat, out double lng, out string q)
    {
        lat = lng = 0;
        q = ctx.Request.Query["q"].ToString().Trim();
        var user = auth.Authenticate(ctx);
        if (user is null) return Http.Error(401, "unauthorized");
        if (!TryCoord(ctx.Request.Query["lat"], 90, out lat) || !TryCoord(ctx.Request.Query["lng"], 180, out lng))
            return Http.Fail(400, "bad_request", "lat 和 lng 必须是有效的经纬度（WGS-84）");
        if (needKeywords && q.Length == 0) return Http.Fail(400, "bad_request", "q 不能为空");
        if (q.Length > GeoService.MaxKeywordChars) return Http.Fail(400, "bad_request", $"q 最多 {GeoService.MaxKeywordChars} 字");
        if (!geo.Available) return Http.Fail(503, "geo_unavailable", "服务器没有配置地图服务");
        if (!Limiter.TryTake(user.Id)) return Http.Fail(429, "rate_limited", "位置查询太频繁，稍后再试");
        return null;
    }

    private static async Task<IResult> Call<T>(ILogger log, Func<Task<T>> call, JsonTypeInfo<T> type)
    {
        try
        {
            return Results.Json(await call(), type);
        }
        catch (GeoUpstreamException ex)
        {
            log.LogWarning("geo upstream error: {Err}", ex.Message);
            return Http.Fail(502, "geo_upstream", ex.Message);
        }
    }

    private static bool TryCoord(StringValues raw, double max, out double value) =>
        double.TryParse(raw.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out value) && double.IsFinite(value) && Math.Abs(value) <= max;
}

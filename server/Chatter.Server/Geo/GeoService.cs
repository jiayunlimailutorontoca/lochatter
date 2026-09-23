using System.Globalization;
using System.Text;
using System.Text.Json;
using Chatter.Server.Protocol;

namespace Chatter.Server.Geo;

/// <summary>Amap answered with an error (or something we could not read); becomes 502 geo_upstream.</summary>
public sealed class GeoUpstreamException(string message) : Exception(message);

/// <summary>
/// Reverse geocoding and place search through the Amap Web Service API (docs/protocol-1.6.md §2). Our wire format is
/// WGS-84; Amap speaks GCJ-02, so every request converts on the way out and every POI converts back on the way in.
/// The key lives in CHATTER_AMAP_KEY (/etc/chatter/geo.env); without it <see cref="Available"/> is false and the
/// endpoints answer 503. Responses are parsed with JsonDocument (no reflection: the server is Native AOT).
/// </summary>
public sealed class GeoService(Capabilities caps, ILogger<GeoService> log)
{
    public const int MaxPage = 10;
    public const int MaxKeywordChars = 60;
    private const int PageSize = 20;
    private const int AroundRadiusM = 2000;

    private static readonly HttpClient Client = new(new SocketsHttpHandler { PooledConnectionLifetime = TimeSpan.FromMinutes(10) })
    {
        Timeout = TimeSpan.FromSeconds(8),
        DefaultRequestHeaders = { { "User-Agent", "lochatter-server/1.6" } },
    };

    public bool Available => caps.AmapKey is not null;

    /// <summary>Address and nearby places of a point (the picker's list for the map centre).</summary>
    public async Task<RegeoResponse> RegeoAsync(double lat, double lng, CancellationToken ct)
    {
        var (gLat, gLng) = Gcj02.FromWgs84(lat, lng);
        var url = $"https://restapi.amap.com/v3/geocode/regeo?key={caps.AmapKey}&location={Loc(gLng, gLat)}&extensions=all&radius=500&roadlevel=0&output=json";
        using var doc = await GetAsync(url, ct);
        var root = doc.RootElement;
        var regeo = root.TryGetProperty("regeocode", out var r) && r.ValueKind == JsonValueKind.Object ? r : default;
        var address = regeo.ValueKind == JsonValueKind.Object ? Str(regeo, "formatted_address") : null;
        var name = (string?)null;
        var pois = new List<Poi>();
        if (regeo.ValueKind == JsonValueKind.Object && regeo.TryGetProperty("pois", out var arr) && arr.ValueKind == JsonValueKind.Array)
        {
            foreach (var p in arr.EnumerateArray())
            {
                var poi = ParsePoi(p);
                if (poi is not null) pois.Add(poi);
            }
        }
        pois.Sort((a, b) => a.Distance.CompareTo(b.Distance));
        if (pois.Count > PageSize) pois.RemoveRange(PageSize, pois.Count - PageSize);
        if (pois.Count > 0 && pois[0].Distance <= 60) name = pois[0].Name;
        if (string.IsNullOrWhiteSpace(address)) address = Fallback(lat, lng);
        return new RegeoResponse(address!, name, pois);
    }

    /// <summary>Places within 2 km, optionally matching [keywords]; page from 1.</summary>
    public async Task<List<Poi>> AroundAsync(double lat, double lng, string keywords, int page, CancellationToken ct)
    {
        var (gLat, gLng) = Gcj02.FromWgs84(lat, lng);
        var url = $"https://restapi.amap.com/v3/place/around?key={caps.AmapKey}&location={Loc(gLng, gLat)}&radius={AroundRadiusM}&offset={PageSize}&page={page}&extensions=base&sortrule=distance&output=json";
        if (keywords.Length > 0) url += "&keywords=" + Uri.EscapeDataString(keywords);
        return await PlacesAsync(url, ct);
    }

    /// <summary>City-wide search around a point (the picker's search box).</summary>
    public async Task<List<Poi>> SearchAsync(string keywords, double lat, double lng, CancellationToken ct)
    {
        var (gLat, gLng) = Gcj02.FromWgs84(lat, lng);
        var url = $"https://restapi.amap.com/v3/place/text?key={caps.AmapKey}&keywords={Uri.EscapeDataString(keywords)}&location={Loc(gLng, gLat)}&citylimit=false&offset={PageSize}&page=1&extensions=base&output=json";
        var list = await PlacesAsync(url, ct);
        list.Sort((a, b) => a.Distance.CompareTo(b.Distance));
        return list;
    }

    private async Task<List<Poi>> PlacesAsync(string url, CancellationToken ct)
    {
        using var doc = await GetAsync(url, ct);
        var list = new List<Poi>();
        if (doc.RootElement.TryGetProperty("pois", out var arr) && arr.ValueKind == JsonValueKind.Array)
        {
            foreach (var p in arr.EnumerateArray())
            {
                var poi = ParsePoi(p);
                if (poi is not null) list.Add(poi);
            }
        }
        return list;
    }

    private async Task<JsonDocument> GetAsync(string url, CancellationToken ct)
    {
        string body;
        try
        {
            using var resp = await Client.GetAsync(url, ct);
            body = await resp.Content.ReadAsStringAsync(ct);
            if (!resp.IsSuccessStatusCode) throw new GeoUpstreamException($"amap http {(int)resp.StatusCode}");
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            throw new GeoUpstreamException("amap timeout");
        }
        catch (HttpRequestException e)
        {
            throw new GeoUpstreamException("amap unreachable: " + e.Message);
        }
        JsonDocument doc;
        try { doc = JsonDocument.Parse(body); }
        catch (JsonException) { throw new GeoUpstreamException("amap returned no json"); }
        if (Str(doc.RootElement, "status") != "1")
        {
            var info = Str(doc.RootElement, "info") ?? "unknown";
            log.LogWarning("amap error {Info} ({Code})", info, Str(doc.RootElement, "infocode"));
            doc.Dispose();
            throw new GeoUpstreamException("amap: " + info);
        }
        return doc;
    }

    /// <summary>One Amap POI → ours (WGS-84). Null when it has no usable location.</summary>
    private static Poi? ParsePoi(JsonElement p)
    {
        if (p.ValueKind != JsonValueKind.Object) return null;
        var loc = Str(p, "location");
        if (loc is null) return null;
        var comma = loc.IndexOf(',');
        if (comma <= 0
            || !double.TryParse(loc.AsSpan(0, comma), NumberStyles.Float, CultureInfo.InvariantCulture, out var gLng)
            || !double.TryParse(loc.AsSpan(comma + 1), NumberStyles.Float, CultureInfo.InvariantCulture, out var gLat)) return null;
        var (lat, lng) = Gcj02.ToWgs84(gLat, gLng);
        var name = Str(p, "name") ?? "";
        if (name.Length == 0) return null;
        var address = Str(p, "address") ?? "";
        var distance = int.TryParse(Str(p, "distance"), NumberStyles.Integer, CultureInfo.InvariantCulture, out var d) ? d : 0;
        var type = Str(p, "type")?.Split(';').FirstOrDefault()?.Split('|').FirstOrDefault() ?? "";
        return new Poi(name, address, Math.Round(lat, 6), Math.Round(lng, 6), distance, type);
    }

    /// <summary>Amap sends "" or [] for missing strings; both count as absent.</summary>
    private static string? Str(JsonElement e, string prop) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(prop, out var v) && v.ValueKind == JsonValueKind.String && v.GetString() is { Length: > 0 } s ? s : null;

    private static string Loc(double lng, double lat) => string.Create(CultureInfo.InvariantCulture, $"{lng:F6},{lat:F6}");

    private static string Fallback(double lat, double lng) => string.Create(CultureInfo.InvariantCulture, $"纬度 {lat:F5}，经度 {lng:F5}");
}

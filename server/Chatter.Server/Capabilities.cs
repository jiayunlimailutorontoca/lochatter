using Chatter.Server.Protocol;

namespace Chatter.Server;

/// <summary>
/// Optional server-side helpers (1.6), read from the environment once at startup and advertised to every client in the
/// hello frame (Features). Stt: CHATTER_STT_URL names an OpenAI-compatible /v1/audio/transcriptions endpoint
/// (CHATTER_STT_KEY and CHATTER_STT_MODEL optional). Geo: CHATTER_AMAP_KEY holds an Amap Web Service key. Tiles: on
/// unless CHATTER_TILES is set to something other than "1" (the nginx site shipped with 1.6 proxies /tiles/).
/// SttUrlInvalid: CHATTER_STT_URL was set but is not an absolute http(s) URL, so STT stays off (logged at startup).
/// </summary>
public sealed record Capabilities(string? SttUrl, string? SttKey, string SttModel, string? AmapKey, bool Tiles, bool SttUrlInvalid = false, string TileDatum = "wgs84")
{
    public const string DefaultSttModel = "sense-voice";

    public bool Stt => SttUrl is not null;
    public bool Geo => AmapKey is not null;

    public Features ToFeatures() => new(Stt, Geo, Tiles, Tiles ? TileDatum : null);

    public static Capabilities Load(IConfiguration cfg)
    {
        var sttUrl = Blank(cfg["CHATTER_STT_URL"]);
        var sttUrlInvalid = sttUrl is not null && !(Uri.TryCreate(sttUrl, UriKind.Absolute, out var uri) && uri.Scheme is "http" or "https");
        return new Capabilities(
            sttUrlInvalid ? null : sttUrl,
            Blank(cfg["CHATTER_STT_KEY"]),
            Blank(cfg["CHATTER_STT_MODEL"]) ?? DefaultSttModel,
            Blank(cfg["CHATTER_AMAP_KEY"]),
            Blank(cfg["CHATTER_TILES"]) is null or "1" or "osm" or "amap",
            sttUrlInvalid,
            Blank(cfg["CHATTER_TILES"]) == "amap" ? "gcj02" : "wgs84");
    }

    private static string? Blank(string? s) => string.IsNullOrWhiteSpace(s) ? null : s.Trim();
}

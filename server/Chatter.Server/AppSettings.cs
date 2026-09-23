namespace Chatter.Server;

/// <summary>
/// MaxUploadBytes 0 = unlimited (the disk-space guard in MediaEndpoints still applies).
/// MediaGraceSeconds: an upload no message references is removed once older than this (a large upload can take a while before its message arrives).
/// </summary>
public sealed record AppSettings(
    string DataDir, long MaxUploadBytes, string? TurnHost, string? TurnSecret, int SweepSeconds = 30, int MediaGraceSeconds = 7200)
{
    public string DbPath => Path.Combine(DataDir, "chatter.db");
    public string MediaDir => Path.Combine(DataDir, "media");

    public static AppSettings Load(IConfiguration cfg)
    {
        var dataDir = cfg["CHATTER_DATA"] ?? cfg["Chatter:DataDir"] ?? Path.Combine(AppContext.BaseDirectory, "data");
        dataDir = Path.GetFullPath(dataDir);
        var max = long.TryParse(cfg["Chatter:MaxUploadBytes"], out var m) ? m : 0;
        var sweep = int.TryParse(cfg["CHATTER_SWEEP_SECONDS"], out var sw) && sw > 0 ? sw : 30;
        var grace = int.TryParse(cfg["CHATTER_MEDIA_GRACE_SECONDS"], out var gr) && gr > 0 ? gr : 7200;
        Directory.CreateDirectory(Path.Combine(dataDir, "media"));
        return new AppSettings(
            dataDir, max,
            cfg["CHATTER_TURN_HOST"] ?? cfg["Chatter:TurnHost"],
            cfg["CHATTER_TURN_SECRET"] ?? cfg["Chatter:TurnSecret"],
            sweep, grace);
    }

    private static string? Blank(string? s) => string.IsNullOrWhiteSpace(s) ? null : s.Trim();
}

using System.Security.Cryptography;
using System.Text.RegularExpressions;
using Chatter.Server.Auth;
using Chatter.Server.Protocol;
using Chatter.Server.Storage;
using Microsoft.Net.Http.Headers;

namespace Chatter.Server;

/// <summary>
/// Content-addressed media store: POST /media (raw body) and GET /media/{id}.
/// Files live under {MediaDir}/{id[..2]}/{id}. Any well-formed MIME type is accepted; the
/// message kind (image / audio / file) decides how clients render it.
/// </summary>
public static partial class MediaEndpoints
{
    private const int MaxNameChars = 600; // encrypted names are base64 and ~1.4x the plaintext

    [GeneratedRegex(@"^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$", RegexOptions.IgnoreCase)]
    private static partial Regex MimeRegex();

    public static void Map(WebApplication app)
    {
        var log = app.Logger;

        app.MapPost("/media", async (HttpContext ctx, AuthService auth, AppSettings settings, MediaRepo repo) =>
        {
            var user = auth.Authenticate(ctx);
            if (user is null) return Http.Error(401, "unauthorized");

            var mime = ctx.Request.ContentType?.Split(';', 2)[0].Trim().ToLowerInvariant() ?? "";
            if (mime.Length > 100 || !MimeRegex().IsMatch(mime)) return Http.Error(415, "unsupported media type");
            if (settings.MaxUploadBytes > 0 && ctx.Request.ContentLength is > 0 && ctx.Request.ContentLength > settings.MaxUploadBytes)
                return Http.Error(413, "too large");
            // No size cap by default; keep the disk from filling up completely instead.
            if (ctx.Request.ContentLength is > 0 && FreeBytes(settings.MediaDir) - ctx.Request.ContentLength < MinFreeBytes)
                return Http.Error(507, "server disk almost full");

            var q = ctx.Request.Query;
            int? w = int.TryParse(q["w"], out var wi) && wi > 0 ? wi : null;
            int? h = int.TryParse(q["h"], out var hi) && hi > 0 ? hi : null;
            int? d = int.TryParse(q["d"], out var di) && di > 0 ? di : null;
            string? thumb = q["thumb"].FirstOrDefault();
            if (thumb is not null && (!IsId(thumb) || repo.Get(thumb) is null))
                return Http.Error(400, "thumb id unknown");
            var name = CleanName(q["name"].FirstOrDefault());

            var tmpDir = Path.Combine(settings.MediaDir, "tmp");
            Directory.CreateDirectory(tmpDir);
            var tmp = Path.Combine(tmpDir, Guid.NewGuid().ToString("N"));
            long size = 0;
            string id;
            try
            {
                using var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                await using (var fs = new FileStream(tmp, FileMode.CreateNew, FileAccess.Write, FileShare.None, 64 * 1024, useAsync: true))
                {
                    var buf = new byte[64 * 1024];
                    int n;
                    while ((n = await ctx.Request.Body.ReadAsync(buf, ctx.RequestAborted)) > 0)
                    {
                        size += n;
                        if (settings.MaxUploadBytes > 0 && size > settings.MaxUploadBytes) return Http.Error(413, "too large");
                        if ((size & 0xFFFFF) == 0 && FreeBytes(settings.MediaDir) < MinFreeBytes) return Http.Error(507, "server disk almost full");
                        sha.AppendData(buf, 0, n);
                        await fs.WriteAsync(buf.AsMemory(0, n), ctx.RequestAborted);
                    }
                }
                if (size == 0) return Http.Error(400, "empty body");
                id = Convert.ToHexStringLower(sha.GetHashAndReset());

                var final = PathFor(settings, id);
                Directory.CreateDirectory(Path.GetDirectoryName(final)!);
                if (File.Exists(final)) File.Delete(tmp);
                else File.Move(tmp, final);
            }
            finally
            {
                if (File.Exists(tmp)) File.Delete(tmp);
            }

            var info = new MediaInfo(id, mime, size, w, h, d, thumb, name);
            repo.Insert(info, user.Id, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            log.LogInformation("media {Id} {Mime} {Size}B {Name} by {User}", id[..12], mime, size, name ?? "-", user.Name);
            return Results.Json(info, Json.MediaInfo, statusCode: 201);
        });

        app.MapGet("/media/{id}", (string id, HttpContext ctx, AuthService auth, AppSettings settings, MediaRepo repo) =>
        {
            if (auth.Authenticate(ctx) is null) return Http.Error(401, "unauthorized");
            if (!IsId(id)) return Http.Error(404, "not found");
            var m = repo.Get(id);
            var path = PathFor(settings, id);
            if (m is null || !File.Exists(path)) return Http.Error(404, "not found");

            ctx.Response.Headers.CacheControl = "private, max-age=31536000, immutable";
            var download = ctx.Request.Query.ContainsKey("dl");
            return Results.File(path, m.Mime,
                fileDownloadName: download ? m.Name ?? id[..16] + ExtFor(m.Mime) : null,
                entityTag: new EntityTagHeaderValue($"\"{id[..16]}\""),
                enableRangeProcessing: true);
        });
    }

    private static string ExtFor(string mime) => mime switch
    {
        "image/jpeg" => ".jpg", "image/png" => ".png", "image/webp" => ".webp", "image/gif" => ".gif",
        "audio/mp4" => ".m4a", "audio/ogg" => ".ogg", "application/pdf" => ".pdf", _ => "",
    };

    private static string PathFor(AppSettings s, string id) => Path.Combine(s.MediaDir, id[..2], id);

    private const long MinFreeBytes = 300L * 1024 * 1024;

    private static long FreeBytes(string dir)
    {
        try { return new DriveInfo(Path.GetPathRoot(Path.GetFullPath(dir)) ?? "/").AvailableFreeSpace; }
        catch { return long.MaxValue; }
    }

    private static bool IsId(string id)
    {
        if (id.Length != 64) return false;
        foreach (var ch in id)
            if (!char.IsAsciiHexDigitLower(ch)) return false;
        return true;
    }

    /// <summary>Keeps only the file name part, drops control characters, caps the length.</summary>
    private static string? CleanName(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        var name = raw.Replace('\\', '/');
        name = name[(name.LastIndexOf('/') + 1)..];
        var chars = name.Where(ch => !char.IsControl(ch)).ToArray();
        name = new string(chars).Trim();
        if (name.Length == 0 || name is "." or "..") return null;
        return name.Length > MaxNameChars ? name[..MaxNameChars] : name;
    }
}

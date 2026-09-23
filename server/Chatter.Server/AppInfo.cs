using System.Diagnostics;
using System.Reflection;

namespace Chatter.Server;

public static class AppInfo
{
    private static readonly Stopwatch Started = Stopwatch.StartNew();

    public static readonly string Version =
        Assembly.GetEntryAssembly()?.GetName().Version?.ToString(3) ?? "0.0.0";

    public static long UptimeSeconds => (long)Started.Elapsed.TotalSeconds;
}

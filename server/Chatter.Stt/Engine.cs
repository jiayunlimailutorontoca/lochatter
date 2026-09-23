using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace Chatter.Stt;

/// <summary>
/// SenseVoice through libstt_bridge.so. The recognizer is created once and destroyed on process exit;
/// one decode at a time matches the two-core box.
/// </summary>
sealed class Engine : IDisposable
{
    private readonly SemaphoreSlim gate = new(1, 1);
    private IntPtr handle;
    private int disposed;

    private Engine(IntPtr handle) => this.handle = handle;

    public static Engine Load(string modelDir, int threads)
    {
        NativeLibraryResolver.Install();
        var model = Path.Combine(modelDir, "model.int8.onnx");
        var tokens = Path.Combine(modelDir, "tokens.txt");
        if (!File.Exists(model)) throw new FileNotFoundException("missing SenseVoice model", model);
        if (!File.Exists(tokens)) throw new FileNotFoundException("missing tokens", tokens);

        var err = new byte[512];
        var modelUtf = Encoding.UTF8.GetBytes(model + "\0");
        var tokensUtf = Encoding.UTF8.GetBytes(tokens + "\0");
        var modelPin = GCHandle.Alloc(modelUtf, GCHandleType.Pinned);
        var tokensPin = GCHandle.Alloc(tokensUtf, GCHandleType.Pinned);
        var errPin = GCHandle.Alloc(err, GCHandleType.Pinned);
        try
        {
            var created = Native.stt_create(
                modelPin.AddrOfPinnedObject(), tokensPin.AddrOfPinnedObject(), threads,
                errPin.AddrOfPinnedObject(), err.Length);
            if (created == IntPtr.Zero)
            {
                var message = Encoding.UTF8.GetString(err).TrimEnd('\0').Trim();
                throw new InvalidOperationException(message.Length == 0 ? "stt_create failed" : message);
            }
            return new Engine(created);
        }
        finally
        {
            modelPin.Free();
            tokensPin.Free();
            errPin.Free();
        }
    }

    public string Decode(float[] samples)
    {
        var buf = new byte[256 * 1024];
        var samplesPin = GCHandle.Alloc(samples, GCHandleType.Pinned);
        var bufPin = GCHandle.Alloc(buf, GCHandleType.Pinned);
        try
        {
            gate.Wait();
            try
            {
                if (Volatile.Read(ref disposed) != 0 || handle == IntPtr.Zero)
                    throw new ObjectDisposedException(nameof(Engine));
                var rc = Native.stt_decode(
                    handle, samplesPin.AddrOfPinnedObject(), samples.Length, Audio.SampleRate,
                    bufPin.AddrOfPinnedObject(), buf.Length);
                if (rc == -2) throw new InvalidOperationException("transcript does not fit in 256 KB");
                if (rc != 0) throw new InvalidOperationException("decode failed");
            }
            finally
            {
                gate.Release();
            }
        }
        finally
        {
            samplesPin.Free();
            bufPin.Free();
        }
        var n = Array.IndexOf(buf, (byte)0);
        if (n < 0) n = buf.Length;
        return Encoding.UTF8.GetString(buf, 0, n).Trim();
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0) return;
        // Wait out an in-flight decode before freeing the recognizer. The semaphore stays alive so a late
        // Decode can observe the disposed flag instead of using a freed handle.
        gate.Wait();
        try
        {
            var h = Interlocked.Exchange(ref handle, IntPtr.Zero);
            if (h != IntPtr.Zero) Native.stt_destroy(h);
        }
        finally
        {
            gate.Release();
        }
    }
}

static class NativeLibraryResolver
{
    private static int installed;

    public static void Install()
    {
        if (Interlocked.Exchange(ref installed, 1) != 0) return;
        NativeLibrary.SetDllImportResolver(typeof(Engine).Assembly, (name, _, _) =>
        {
            if (name != "stt_bridge") return IntPtr.Zero;
            foreach (var path in Candidates())
            {
                if (File.Exists(path) && NativeLibrary.TryLoad(path, out var handle)) return handle;
            }
            return IntPtr.Zero;
        });
    }

    private static IEnumerable<string> Candidates()
    {
        var baseDir = AppContext.BaseDirectory;
        yield return Path.Combine(baseDir, "libstt_bridge.so");
        yield return Path.Combine(baseDir, "lib", "libstt_bridge.so");
        var env = Environment.GetEnvironmentVariable("STT_LIB_DIR");
        if (!string.IsNullOrWhiteSpace(env)) yield return Path.Combine(env, "libstt_bridge.so");
        yield return "/opt/stt/lib/libstt_bridge.so";
    }
}

static partial class Native
{
    [LibraryImport("stt_bridge")]
    internal static partial IntPtr stt_create(IntPtr model, IntPtr tokens, int threads, IntPtr error, int errorLen);

    [LibraryImport("stt_bridge")]
    internal static partial int stt_decode(IntPtr handle, IntPtr samples, int sampleCount, int sampleRate, IntPtr text, int textLen);

    [LibraryImport("stt_bridge")]
    internal static partial void stt_destroy(IntPtr handle);
}

static class Audio
{
    public const int SampleRate = 16000;
    public const int MaxSeconds = 300;
    public const int MaxSamples = SampleRate * MaxSeconds;
    public const long MaxUploadBytes = 20L * 1024 * 1024;

    public static long DurationMs(int samples) => samples / (SampleRate / 1000);

    public static async Task<AudioResult> DecodeFile(string path, CancellationToken cancel)
    {
        using var proc = new Process();
        proc.StartInfo = new ProcessStartInfo
        {
            FileName = "ffmpeg",
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
        };
        proc.StartInfo.ArgumentList.Add("-nostdin");
        proc.StartInfo.ArgumentList.Add("-loglevel");
        proc.StartInfo.ArgumentList.Add("error");
        proc.StartInfo.ArgumentList.Add("-i");
        proc.StartInfo.ArgumentList.Add(path);
        proc.StartInfo.ArgumentList.Add("-f");
        proc.StartInfo.ArgumentList.Add("f32le");
        proc.StartInfo.ArgumentList.Add("-ac");
        proc.StartInfo.ArgumentList.Add("1");
        proc.StartInfo.ArgumentList.Add("-ar");
        proc.StartInfo.ArgumentList.Add(SampleRate.ToString());
        proc.StartInfo.ArgumentList.Add("-");

        try
        {
            if (!proc.Start()) return AudioResult.Fail(400, "cannot decode audio: ffmpeg did not start");
        }
        catch (Exception e) when (e is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            return AudioResult.Fail(400, "cannot decode audio: ffmpeg is not installed");
        }
        proc.StandardInput.Close();

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        timeout.CancelAfter(TimeSpan.FromSeconds(120));
        var stderr = proc.StandardError.ReadToEndAsync(timeout.Token);
        try
        {
            var samples = await ReadCapped(proc, timeout.Token);
            if (samples is null)
            {
                TryKill(proc);
                try { await stderr; } catch (OperationCanceledException) { }
                return AudioResult.Fail(413, $"audio longer than {MaxSeconds} s");
            }
            await proc.WaitForExitAsync(timeout.Token);
            var err = Tail(await stderr);
            if (proc.ExitCode != 0)
                return AudioResult.Fail(400, err.Length == 0 ? "cannot decode audio" : "cannot decode audio: " + err);
            if (samples.Length == 0) return AudioResult.Fail(400, "empty audio");
            return AudioResult.Ok(samples);
        }
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
        {
            TryKill(proc);
            return AudioResult.Fail(400, "cannot decode audio: timed out");
        }
    }

    private static async Task<float[]?> ReadCapped(Process proc, CancellationToken cancel)
    {
        var stdout = proc.StandardOutput.BaseStream;
        var maxBytes = (MaxSamples + 1) * 4;
        using var ms = new MemoryStream();
        var buf = new byte[64 * 1024];
        while (true)
        {
            var n = await stdout.ReadAsync(buf, cancel);
            if (n == 0) break;
            if (ms.Length + n > maxBytes) return null;
            ms.Write(buf, 0, n);
        }
        var bytes = ms.GetBuffer();
        var count = (int)ms.Length / 4;
        if (count > MaxSamples) return null;
        var samples = new float[count];
        if (count > 0) Buffer.BlockCopy(bytes, 0, samples, 0, count * 4);
        return samples;
    }

    private static void TryKill(Process proc)
    {
        try { if (!proc.HasExited) proc.Kill(entireProcessTree: true); }
        catch (InvalidOperationException) { }
    }

    private static string Tail(string text)
    {
        text = text.Trim();
        return text.Length <= 200 ? text : text[^200..];
    }
}

readonly record struct AudioResult(int Status, float[]? Samples, string? Detail)
{
    public static AudioResult Ok(float[] samples) => new(0, samples, null);
    public static AudioResult Fail(int status, string detail) => new(status, null, detail);
}

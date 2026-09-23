using System.Security.Cryptography;
using System.Text;

namespace Chatter.Server;

/// <summary>
/// Opens a chat ciphertext the phones already know how to open. The 32-byte AES key is one the phone uploaded
/// (the derived session key, not a private identity key). AAD is the message id. Never returns the ciphertext.
/// </summary>
public static class PushCrypto
{
    public static string? Open(string? text, string aad, Func<int, int, byte[]?> keyOf)
    {
        if (string.IsNullOrEmpty(text)) return null;
        if (text.StartsWith("e2e2:", StringComparison.Ordinal)) return OpenV2(text, aad, keyOf);
        if (text.StartsWith("e2e:", StringComparison.Ordinal)) return OpenRaw(keyOf(0, 0), text["e2e:".Length..], aad);
        return text.Trim();
    }

    private static string? OpenV2(string text, string aad, Func<int, int, byte[]?> keyOf)
    {
        // e2e2:<uid>:<senderEpoch>.<receiverEpoch>:<base64>
        var uidEnd = text.IndexOf(':', 5);
        if (uidEnd < 0) return null;
        var end = text.IndexOf(':', uidEnd + 1);
        if (end < 0 || end + 1 >= text.Length) return null;
        var dot = text.IndexOf('.', uidEnd + 1);
        if (dot < 0 || dot > end) return null;
        if (!int.TryParse(text.AsSpan(uidEnd + 1, dot - uidEnd - 1), out var s)) return null;
        if (!int.TryParse(text.AsSpan(dot + 1, end - dot - 1), out var r)) return null;
        return OpenRaw(keyOf(s, r), text[(end + 1)..], aad);
    }

    private static string? OpenRaw(byte[]? key, string body, string aad)
    {
        if (key is not { Length: 32 } || body.Length == 0) return null;
        byte[] raw;
        try
        {
            var std = body.Replace('-', '+').Replace('_', '/');
            var pad = (4 - std.Length % 4) % 4;
            raw = Convert.FromBase64String(std + new string('=', pad));
        }
        catch (FormatException)
        {
            return null;
        }
        if (raw.Length < 12 + 16 + 1) return null;
        var nonce = raw.AsSpan(0, 12);
        var tag = raw.AsSpan(raw.Length - 16, 16);
        var ct = raw.AsSpan(12, raw.Length - 12 - 16);
        var plain = new byte[ct.Length];
        try
        {
            using var aes = new AesGcm(key, 16);
            aes.Decrypt(nonce, ct, tag, plain, Encoding.UTF8.GetBytes(aad));
            var s = Encoding.UTF8.GetString(plain).Trim();
            return s.Length == 0 ? null : s;
        }
        catch (CryptographicException)
        {
            return null;
        }
    }
}

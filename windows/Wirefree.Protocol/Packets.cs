using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Wirefree.Protocol;

public enum PacketType : byte { Hello = 1, Command = 2, Mic = 0x10, Speaker = 0x11 }

public sealed record Packet(PacketType Type, byte[] Payload, bool NewSession, ulong Counter);

public static class PacketCodec
{
    public const int MaxDatagram = 1200;
    public const int HeaderSize = 26;
    public const int TagSize = 16;
    public const ulong CounterLimit = 1UL << 62;

    public static byte[] DeriveKey(ReadOnlySpan<byte> secret, ReadOnlySpan<byte> sid, bool phoneToLaptop)
    {
        return HKDF.DeriveKey(HashAlgorithmName.SHA256, secret.ToArray(), 32, sid.ToArray(),
            Encoding.ASCII.GetBytes(phoneToLaptop ? "wirefree v1 p2l" : "wirefree v1 l2p"));
    }

    public static byte[] Seal(AesGcm cipher, ReadOnlySpan<byte> sid, ulong counter,
        PacketType type, ReadOnlySpan<byte> payload)
    {
        if (sid.Length != 16 || counter >= CounterLimit ||
            payload.Length > MaxDatagram - HeaderSize - TagSize)
            throw new ArgumentException("Invalid packet size or counter.");

        var packet = new byte[HeaderSize + payload.Length + TagSize];
        packet[0] = 1;
        packet[1] = (byte)type;
        sid.CopyTo(packet.AsSpan(2, 16));
        BinaryPrimitives.WriteUInt64BigEndian(packet.AsSpan(18, 8), counter);
        Span<byte> nonce = stackalloc byte[12];
        nonce.Clear();
        packet.AsSpan(18, 8).CopyTo(nonce[4..]);
        cipher.Encrypt(nonce, payload, packet.AsSpan(HeaderSize, payload.Length),
            packet.AsSpan(^TagSize), packet.AsSpan(0, HeaderSize));
        return packet;
    }
}

public sealed class PacketSender(byte[] secret, bool phoneToLaptop) : IDisposable
{
    private byte[] sid = [];
    private AesGcm? cipher;
    private ulong counter;
    public bool NeedsHello => counter == 0 || counter >= PacketCodec.CounterLimit;

    public byte[] Send(PacketType type, ReadOnlySpan<byte> payload)
    {
        if (NeedsHello && type != PacketType.Hello)
            throw new InvalidOperationException("A session must begin with HELLO.");
        if (cipher is null || counter >= PacketCodec.CounterLimit)
        {
            Reset();
            sid = RandomNumberGenerator.GetBytes(16);
            cipher = new AesGcm(PacketCodec.DeriveKey(secret, sid, phoneToLaptop), PacketCodec.TagSize);
        }
        return PacketCodec.Seal(cipher, sid, counter++, type, payload);
    }

    public void Reset()
    {
        cipher?.Dispose();
        cipher = null;
        counter = 0;
    }

    public void Dispose() => Reset();
}

public sealed class PacketReceiver(byte[] secret, bool phoneToLaptop) : IDisposable
{
    private byte[]? currentSid;
    private AesGcm? currentCipher;
    private long currentSessionTs = long.MinValue;
    private readonly ReplayWindow replay = new();

    public Packet? Receive(ReadOnlySpan<byte> bytes, long now)
    {
        const int header = PacketCodec.HeaderSize;
        const int tag = PacketCodec.TagSize;
        if (bytes.Length < header + tag || bytes.Length > PacketCodec.MaxDatagram || bytes[0] != 1)
            return null;
        var type = (PacketType)bytes[1];
        if (type is not (PacketType.Hello or PacketType.Command or PacketType.Mic or PacketType.Speaker))
            return null;

        var sid = bytes.Slice(2, 16);
        bool newSession = currentSid is null || !sid.SequenceEqual(currentSid);
        var counter = BinaryPrimitives.ReadUInt64BigEndian(bytes.Slice(18, 8));
        var plaintext = new byte[bytes.Length - header - tag];
        using var candidate = newSession
            ? new AesGcm(PacketCodec.DeriveKey(secret, sid, phoneToLaptop), tag)
            : null;
        Span<byte> nonce = stackalloc byte[12];
        nonce.Clear();
        bytes.Slice(18, 8).CopyTo(nonce[4..]);
        try
        {
            (candidate ?? currentCipher!).Decrypt(nonce, bytes.Slice(header, plaintext.Length),
                bytes[^tag..], plaintext, bytes[..header]);
        }
        catch (AuthenticationTagMismatchException) { return null; }

        if (newSession)
        {
            if (type != PacketType.Hello || !TryTimestamp(plaintext, out var timestamp) ||
                timestamp < now - 120_000 || timestamp > now + 120_000 || timestamp <= currentSessionTs)
                return null;
            currentCipher?.Dispose();
            currentCipher = new AesGcm(PacketCodec.DeriveKey(secret, sid, phoneToLaptop), tag);
            currentSid = sid.ToArray();
            currentSessionTs = timestamp;
            replay.Reset();
        }

        return replay.Accept(counter) ? new Packet(type, plaintext, newSession, counter) : null;
    }

    private static bool TryTimestamp(byte[] payload, out long timestamp)
    {
        timestamp = 0;
        try
        {
            using var json = JsonDocument.Parse(payload);
            return json.RootElement.ValueKind == JsonValueKind.Object &&
                json.RootElement.TryGetProperty("ts", out var member) &&
                member.ValueKind == JsonValueKind.Number && member.TryGetInt64(out timestamp);
        }
        catch (JsonException) { return false; }
    }

    public void Dispose() => currentCipher?.Dispose();
}

public sealed class ReplayWindow
{
    private const int Size = 1024;
    private readonly ulong[] bitmap = new ulong[Size / 64];
    private ulong highest;

    public void Reset()
    {
        Array.Clear(bitmap);
        highest = 0;
    }

    public bool Accept(ulong counter)
    {
        if (counter > highest)
        {
            var distance = counter - highest;
            if (distance >= Size)
                Array.Clear(bitmap);
            else
                for (ulong step = 1; step <= distance; step++)
                {
                    int slot = (int)((highest + step) % Size);
                    bitmap[slot / 64] &= ~(1UL << (slot % 64));
                }
            highest = counter;
        }
        if (highest - counter >= Size)
            return false;
        int index = (int)(counter % Size);
        ulong mask = 1UL << (index % 64);
        if ((bitmap[index / 64] & mask) != 0)
            return false;
        bitmap[index / 64] |= mask;
        return true;
    }
}

using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Wirefree.Protocol;
using Xunit;

public sealed class ProtocolTests
{
    private static readonly byte[] Key = Enumerable.Range(0, 32).Select(i => (byte)i).ToArray();
    private static readonly byte[] Sid = Enumerable.Range(0xa0, 16).Select(i => (byte)i).ToArray();
    private const string Vector = "0101a0a1a2a3a4a5a6a7a8a9aaabacadaeaf0000000000000007c889c85193969370ecfb91bb168bf42cc9e9f50828cb9815";

    private static byte[] Seal(byte[] sid, ulong counter, string message, PacketType type = PacketType.Hello)
    {
        using var aes = new AesGcm(PacketCodec.DeriveKey(Key, sid, true), 16);
        return PacketCodec.Seal(aes, sid, counter, type, Encoding.UTF8.GetBytes(message));
    }

    [Fact]
    public void Hkdf()
    {
        Assert.Equal("e64a40b8dbb8eb271e12d0c2f9909dfdd5955d0b54ec3df4fe425b71a90de19f",
            Convert.ToHexString(PacketCodec.DeriveKey(Key, Sid, true)).ToLowerInvariant());
        Assert.Equal("6b90162c8cdb4fbb38a34cbf726d3910261f4481348db2c237b13640cb025b98",
            Convert.ToHexString(PacketCodec.DeriveKey(Key, Sid, false)).ToLowerInvariant());
    }

    [Fact]
    public void PhoneHelloRoundTrip()
    {
        using var receiver = new PacketReceiver(Key, true);
        const string message = """{"ts":0}""";
        var packet = Seal(Sid, 7, message);
        Assert.Equal(Vector, Convert.ToHexString(packet).ToLowerInvariant());
        var received = Assert.IsType<Packet>(receiver.Receive(packet, 0));
        Assert.True(received.NewSession);
        Assert.Equal(PacketType.Hello, received.Type);
        Assert.Equal(message, Encoding.UTF8.GetString(received.Payload));
    }

    [Theory]
    [InlineData(18)]
    [InlineData(26)]
    [InlineData(49)]
    public void TamperDoesNotPoisonReplayState(int offset)
    {
        using var receiver = new PacketReceiver(Key, true);
        var original = Seal(Sid, 7, """{"ts":0}""");
        var changed = (byte[])original.Clone();
        changed[offset] ^= 1;
        Assert.Null(receiver.Receive(changed, 0));
        Assert.NotNull(receiver.Receive(original, 0));
    }

    [Fact]
    public void ReplayRejectsDuplicatesAndOldPackets()
    {
        using var receiver = new PacketReceiver(Key, true);
        var hello = Seal(Sid, 0, """{"ts":0}""");
        Assert.NotNull(receiver.Receive(hello, 0));
        Assert.Null(receiver.Receive(hello, 0));
        Assert.NotNull(receiver.Receive(Seal(Sid, 2048, "x", PacketType.Mic), 1));
        var reordered = Seal(Sid, 1025, "x", PacketType.Mic);
        Assert.NotNull(receiver.Receive(reordered, 1));
        Assert.Null(receiver.Receive(reordered, 1));
        Assert.Null(receiver.Receive(Seal(Sid, 1024, "x", PacketType.Mic), 1));
    }

    [Fact]
    public void OnlyFreshStrictlyNewerHelloCanSwitchSessions()
    {
        using var receiver = new PacketReceiver(Key, true);
        byte[] other = new byte[16];
        Assert.Null(receiver.Receive(Seal(Sid, 0, """{"ts":0}"""), 120001));
        Assert.NotNull(receiver.Receive(Seal(Sid, 0, """{"ts":1}"""), 120001));
        Assert.Null(receiver.Receive(Seal(other, 0, """{"ts":2}""", PacketType.Mic), 120001));
        Assert.Null(receiver.Receive(Seal(other, 0, """{"ts":1}"""), 120001));
        Assert.NotNull(receiver.Receive(Seal(other, 0, """{"ts":2}"""), 120001));
        Assert.Null(receiver.Receive(Seal(Sid, 0, """{"ts":1}"""), 120001));
    }

    [Fact]
    public void JitterReordersAcrossWrapAndUsesNextPacketForFec()
    {
        var jitter = new JitterBuffer();
        jitter.Add(0, [2], 100);
        jitter.Add(uint.MaxValue, [1], 110);
        jitter.Add(2, [4], 120);
        Assert.Equal(Playout.Silence, jitter.Read(159, out _));
        Assert.Equal(Playout.Packet, jitter.Read(160, out var first));
        Assert.Equal(new byte[] { 1 }, first);
        Assert.Equal(Playout.Packet, jitter.Read(180, out _));
        jitter.Add(uint.MaxValue, [9], 190);
        Assert.Equal(Playout.Fec, jitter.Read(200, out var fec));
        Assert.Equal(new byte[] { 4 }, fec);
        Assert.Equal(Playout.Packet, jitter.Read(220, out var last));
        Assert.Same(fec, last);
        Assert.Equal(Playout.Conceal, jitter.Read(240, out _));
    }

    [Fact]
    public void SenderStartsWithHelloAndResetsCounterAndSid()
    {
        using var sender = new PacketSender(Key, false);
        Assert.Throws<InvalidOperationException>(() => sender.Send(PacketType.Mic, []));
        var a = sender.Send(PacketType.Hello, "{}"u8);
        Assert.Equal(1, sender.Send(PacketType.Command, "{}"u8)[25]);
        sender.Reset();
        var b = sender.Send(PacketType.Hello, "{}"u8);
        Assert.Equal(0, b[25]);
        Assert.False(a.AsSpan(2, 16).SequenceEqual(b.AsSpan(2, 16)));
    }

    [Fact]
    public void AndroidVectorWhenAvailable()
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory != null; directory = directory.Parent)
        {
            var path = Path.Combine(directory.FullName, "docs", "test-vector.json");
            if (!File.Exists(path))
                continue;
            using var json = JsonDocument.Parse(File.ReadAllText(path));
            var root = json.RootElement;
            string Read(params string[] names) => root.EnumerateObject()
                .First(p => names.Contains(p.Name, StringComparer.OrdinalIgnoreCase)).Value.GetString()!;
            var packet = Convert.FromHexString(Read("packet", "packetHex", "packet_hex"));
            var key = Convert.FromHexString(Read("k", "kHex", "k_hex", "key", "keyHex", "key_hex"));
            var sid = Convert.FromHexString(Read("sid", "sidHex", "sid_hex"));
            // docs/test-vector.json stores every field as hex
            var plaintext = Convert.FromHexString(Read("plaintext"));
            var counter = BinaryPrimitives.ReadUInt64BigEndian(Convert.FromHexString(Read("ctr")));
            using var aes = new AesGcm(PacketCodec.DeriveKey(key, sid, true), 16);
            Assert.Equal(packet, PacketCodec.Seal(aes, sid, counter, PacketType.Hello, plaintext));
            return;
        }
    }
}

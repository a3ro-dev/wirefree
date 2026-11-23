using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using Wirefree.Protocol;

namespace Wirefree;

internal sealed record Phone(string Name, bool Earpods, bool Muted, bool Busy, bool MicLive);

internal sealed class Link : IDisposable
{
    private readonly object gate = new();
    private readonly CancellationTokenSource stopping = new();
    private readonly UdpClient udp = new(AddressFamily.InterNetworkV6);
    private readonly Task listen;
    private readonly JitterBuffer jitter = new();
    private PacketSender sender;
    private PacketReceiver receiver;
    private byte[] key;
    private IPEndPoint? peer;
    private Phone? phone;
    private long lastPhone;
    private long peerCtr = -1, helloCtr = -1; // reordered UDP must not roll back the address or the phone state
    private long lastHello;
    private long helloTs;
    private bool wantMic, wantSpk, dirty = true;
    private uint sequence;
    private int micGeneration;
    private int restart;
    public bool Paired { get; private set; }
    public bool NetworkError { get; private set; }
    public int MicGeneration { get { lock (gate) return micGeneration; } }

    public Link()
    {
        key = Storage.LoadKey();
        Paired = File.Exists(Storage.PathFor("paired"));
        sender = new(key, false);
        receiver = new(key, true);
        udp.Client.DualMode = true;
        udp.Client.ExclusiveAddressUse = true;
        udp.Client.Bind(new IPEndPoint(IPAddress.IPv6Any, 47391));
        listen = Listen();
        NetworkChange.NetworkAddressChanged += NetworkChanged;
    }

    private void NetworkChanged(object? sender, EventArgs args) => Interlocked.Exchange(ref restart, 1);

    public Phone? State
    {
        get { lock (gate) return Online ? phone : null; }
    }

    private bool Online => phone is not null && Environment.TickCount64 - lastPhone < 3000;

    internal static bool TailAddress(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        var bytes = address.GetAddressBytes();
        return bytes.Length == 4 ? bytes[0] == 100 && (bytes[1] & 192) == 64
            : bytes.AsSpan(0, 6).SequenceEqual(new byte[] { 0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0 });
    }

    private async Task Listen()
    {
        while (!stopping.IsCancellationRequested)
        {
            try
            {
                var item = await udp.ReceiveAsync(stopping.Token).ConfigureAwait(false);
                if (!TailAddress(item.RemoteEndPoint.Address)) continue;
                lock (gate)
                {
                    var packet = receiver.Receive(item.Buffer, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
                    if (packet is null) continue;
                    if (packet.NewSession) peerCtr = helloCtr = -1;
                    if ((long)packet.Counter > peerCtr)
                    {
                        peer = item.RemoteEndPoint;
                        peerCtr = (long)packet.Counter;
                    }
                    if (packet.NewSession)
                    {
                        phone = null;
                        ResetMic();
                        dirty = true;
                    }
                    if (packet.Type == PacketType.Hello)
                    {
                        var state = ParseHello(packet.Payload);
                        if (state is null || (long)packet.Counter < helloCtr) continue;
                        helloCtr = (long)packet.Counter;
                        if (!Online || phone!.Muted != state.Muted || phone.Busy != state.Busy ||
                            phone.MicLive != state.MicLive)
                            ResetMic();
                        phone = state;
                        lastPhone = Environment.TickCount64;
                        if (!Paired)
                        {
                            Paired = true;
                            try { Storage.Write("paired", [1]); } catch (IOException) { }
                        }
                        // The first response establishes our session even before audio discovery.
                        if (sender.NeedsHello)
                            Hello();
                    }
                    else if (packet.Type == PacketType.Mic && Online && phone is { Muted: false, Busy: false } &&
                        wantMic && packet.Payload.Length > 4)
                    {
                        jitter.Add(BinaryPrimitives.ReadUInt32BigEndian(packet.Payload), packet.Payload[4..],
                            Environment.TickCount64);
                    }
                }
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException)
            {
                NetworkError = true;
                Interlocked.Exchange(ref restart, 1);
                try { await Task.Delay(250, stopping.Token).ConfigureAwait(false); }
                catch (OperationCanceledException) { break; }
            }
        }
    }

    private static Phone? ParseHello(byte[] payload)
    {
        try
        {
            using var json = JsonDocument.Parse(payload);
            var root = json.RootElement;
            if (root.GetProperty("role").GetString() != "phone" ||
                !root.GetProperty("ts").TryGetInt64(out _)) return null;
            return new(root.GetProperty("name").GetString() ?? "Phone",
                root.GetProperty("earpods").GetBoolean(), root.GetProperty("muted").GetBoolean(),
                root.GetProperty("busy").GetBoolean(), root.GetProperty("micLive").GetBoolean());
        }
        catch (Exception ex) when (ex is JsonException or InvalidOperationException or KeyNotFoundException)
        { return null; }
    }

    public void Update(bool mic, bool speaker)
    {
        lock (gate)
        {
            if (Interlocked.Exchange(ref restart, 0) != 0)
            {
                sender.Reset();
                sequence = 0;
                peer = null;
                phone = null;
                ResetMic();
                dirty = true;
            }
            if (wantMic != mic) ResetMic();
            dirty |= wantMic != mic || wantSpk != speaker;
            wantMic = mic;
            wantSpk = speaker;
            if (peer is not null && (dirty || Environment.TickCount64 - lastHello >= 1000)) Hello();
        }
    }

    private void ResetMic()
    {
        jitter.Reset();
        micGeneration++;
    }

    public Playout ReadMic(out byte[]? opus)
    {
        lock (gate)
        {
            if (Online && phone is { Muted: false, Busy: false, MicLive: true } && wantMic)
                return jitter.Read(Environment.TickCount64, out opus);
            opus = null;
            return Playout.Silence;
        }
    }

    private void Hello()
    {
        helloTs = Math.Max(helloTs + 1, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        if (sender.NeedsHello) sequence = 0;
        Send(PacketType.Hello, JsonSerializer.SerializeToUtf8Bytes(new
        {
            ts = helloTs, role = "laptop", name = Environment.MachineName, wantMic,
            wantSpk = wantSpk && Online && phone is { Earpods: true, Busy: false }, app = "1.0.0"
        }));
        lastHello = Environment.TickCount64;
        dirty = false;
    }

    private void Send(PacketType type, ReadOnlySpan<byte> data)
    {
        if (peer is null) return;
        if (type != PacketType.Hello && sender.NeedsHello) Hello();
        try
        {
            var packet = sender.Send(type, data);
            udp.Send(packet, packet.Length, peer);
            NetworkError = false;
        }
        catch (SocketException) { NetworkError = true; }
    }

    public void Speaker(ReadOnlySpan<byte> opus)
    {
        lock (gate)
        {
            if (!wantSpk || !Online || phone is not { Earpods: true, Busy: false }) return;
            if (sender.NeedsHello) Hello();
            Span<byte> payload = stackalloc byte[opus.Length + 4];
            BinaryPrimitives.WriteUInt32BigEndian(payload, sequence++);
            opus.CopyTo(payload[4..]);
            Send(PacketType.Speaker, payload);
        }
    }

    public void ToggleMute()
    {
        lock (gate) if (Online) Send(PacketType.Command, """{"cmd":"toggleMute"}"""u8);
    }

    public void Forget()
    {
        lock (gate)
        {
            var replacement = Storage.NewKey();
            File.Delete(Storage.PathFor("paired"));
            Paired = false;
            peer = null;
            phone = null;
            wantSpk = false;
            ResetMic();
            sender.Dispose();
            receiver.Dispose();
            CryptographicOperations.ZeroMemory(key);
            key = replacement;
            sender = new(key, false);
            receiver = new(key, true);
        }
    }

    public async Task<string?> PairUri()
    {
        var ip = NetworkInterface.GetAllNetworkInterfaces()
            .Where(i => i.OperationalStatus == OperationalStatus.Up)
            .OrderByDescending(i => i.Name.Contains("Tailscale", StringComparison.OrdinalIgnoreCase))
            .SelectMany(i => i.GetIPProperties().UnicastAddresses)
            .Select(i => i.Address)
            .FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork && TailAddress(a));
        if (ip is null) return null;
        string host = ip.ToString();
        var cli = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            "Tailscale", "tailscale.exe");
        if (File.Exists(cli))
        {
            try
            {
                using var process = Process.Start(new ProcessStartInfo(cli, "status --json")
                    { RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true })!;
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                try
                {
                    string output = await process.StandardOutput.ReadToEndAsync(timeout.Token);
                    using var json = JsonDocument.Parse(output);
                    host = json.RootElement.GetProperty("Self").GetProperty("DNSName").GetString()?.TrimEnd('.') is
                        { Length: > 0 } dns ? dns : host;
                }
                catch (OperationCanceledException) { if (!process.HasExited) process.Kill(); }
            }
            catch (Exception ex) when (ex is JsonException or KeyNotFoundException or
                InvalidOperationException or System.ComponentModel.Win32Exception) { }
        }
        lock (gate)
        {
            string token = Convert.ToBase64String(key).TrimEnd('=').Replace('+', '-').Replace('/', '_');
            return $"wirefree://pair?v=1&h={Uri.EscapeDataString(host)}&ip={ip}&p=47391" +
                $"&n={Uri.EscapeDataString(Environment.MachineName)}&k={token}";
        }
    }

    public void Dispose()
    {
        NetworkChange.NetworkAddressChanged -= NetworkChanged;
        stopping.Cancel();
        udp.Dispose();
        listen.GetAwaiter().GetResult();
        stopping.Dispose();
        sender.Dispose();
        receiver.Dispose();
        CryptographicOperations.ZeroMemory(key);
    }
}

internal static class Storage
{
    private static readonly string DirectoryPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "wirefree");
    public static string PathFor(string name) => Path.Combine(DirectoryPath, name);
    public static void Write(string name, byte[] contents)
    {
        Directory.CreateDirectory(DirectoryPath);
        string path = PathFor(name);
        string temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var stream = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                stream.Write(contents);
                stream.Flush(flushToDisk: true);
            }
            File.Move(temp, path, overwrite: true);
        }
        finally { File.Delete(temp); }
    }

    public static byte[] LoadKey() => File.Exists(PathFor("key.dpapi"))
        ? ProtectedData.Unprotect(File.ReadAllBytes(PathFor("key.dpapi")), null, DataProtectionScope.CurrentUser)
        : NewKey();

    public static byte[] NewKey()
    {
        var key = RandomNumberGenerator.GetBytes(32);
        Write("key.dpapi", ProtectedData.Protect(key, null, DataProtectionScope.CurrentUser));
        return key;
    }
}

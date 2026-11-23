using System.Runtime.InteropServices;
using Concentus;
using Concentus.Enums;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;
using Wirefree.Protocol;

namespace Wirefree;

internal sealed class Audio(Link link) : IDisposable
{
    private WasapiCapture? capture;
    private WasapiOut? output;
    private MicProvider? microphone;
    private string? captureId, outputId;
    private int captureFailed, outputFailed;
    public float MicLevel => microphone?.Level ?? 0;

    public void Update(Routing routing, Phone? phone)
    {
        // Loopback on android-speaker itself: Hi-Fi Cable's capture side won't open on Windows 11.
        using var speaker = routing.WantSpeaker ? routing.Find(NAudio.CoreAudioApi.DataFlow.Render, Routing.Speaker) : null;
        using var mic = routing.WantMic && phone is { Busy: false, Muted: false, MicLive: true }
            ? routing.Find(NAudio.CoreAudioApi.DataFlow.Render, Routing.MicLink) : null;

        if (captureId != speaker?.ID || Interlocked.Exchange(ref captureFailed, 0) != 0)
        {
            StopCapture();
            if (speaker is not null)
            {
                try
                {
                    var next = new LowLatencyLoopback(speaker);
                    capture = next;
                    var streamer = new SpeakerEncoder(link, next.WaveFormat);
                    next.DataAvailable += (_, args) => streamer.Push(args.Buffer, args.BytesRecorded);
                    next.RecordingStopped += (_, args) =>
                    {
                        streamer.Dispose();
                        if (args.Exception is not null) Interlocked.Exchange(ref captureFailed, 1);
                    };
                    try { next.StartRecording(); }
                    catch { streamer.Dispose(); throw; }
                    captureId = speaker.ID;
                }
                catch (Exception ex) when (AudioUnavailable(ex)) { StopCapture(); }
            }
        }

        if (outputId != mic?.ID || Interlocked.Exchange(ref outputFailed, 0) != 0)
        {
            StopOutput();
            if (mic is not null)
            {
                try
                {
                    microphone = new MicProvider(link);
                    output = new WasapiOut(mic, NAudio.CoreAudioApi.AudioClientShareMode.Shared, true, 20);
                    output.PlaybackStopped += (_, args) =>
                    {
                        if (args.Exception is not null) Interlocked.Exchange(ref outputFailed, 1);
                    };
                    output.Init(microphone);
                    output.Play();
                    outputId = mic.ID;
                }
                catch (Exception ex) when (AudioUnavailable(ex)) { StopOutput(); }
            }
        }
    }

    public bool SpeakerRunning => captureId is not null && Volatile.Read(ref captureFailed) == 0;

    private static bool AudioUnavailable(Exception ex) =>
        ex is COMException or InvalidOperationException or ArgumentException or NotSupportedException;

    private void StopCapture()
    {
        capture?.Dispose();
        capture = null;
        captureId = null;
    }

    private void StopOutput()
    {
        output?.Dispose();
        output = null;
        outputId = null;
        microphone?.Dispose();
        microphone = null;
    }

    public void Dispose()
    {
        StopCapture();
        StopOutput();
    }
}

internal sealed class SpeakerEncoder : IDisposable
{
    private const int Samples = 480; // 10 ms frames for the speaker stream
    private const float Floor = 0.00031622777f;
    private readonly Link link;
    private readonly BufferedWaveProvider raw;
    private readonly ISampleProvider audio;
    private readonly IOpusEncoder encoder = OpusCodecFactory.CreateEncoder(48000, 2, OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY);
    private readonly float[] frame = new float[Samples * 2];
    private readonly byte[] opus = new byte[1154];
    private int filled, quiet;

    public SpeakerEncoder(Link link, WaveFormat format)
    {
        this.link = link;
        encoder.Bitrate = 510000; // Opus maximum
        encoder.UseInbandFEC = true;
        encoder.PacketLossPercent = 10;
        raw = new BufferedWaveProvider(format) { ReadFully = false };
        var stereo = new MultiplexingSampleProvider([raw.ToSampleProvider()], 2);
        stereo.ConnectInputToOutput(0, 0);
        stereo.ConnectInputToOutput(Math.Min(1, format.Channels - 1), 1);
        audio = format.SampleRate == 48000 ? stereo : new WdlResamplingSampleProvider(stereo, 48000);
    }

    public void Push(byte[] bytes, int count)
    {
        raw.AddSamples(bytes, 0, count);
        while (true)
        {
            int read = audio.Read(frame, filled, frame.Length - filled);
            if (read == 0) return;
            filled += read;
            if (filled != frame.Length) continue;
            filled = 0;
            bool silent = true;
            foreach (float sample in frame)
                if (Math.Abs(sample) >= Floor) { silent = false; break; }
            quiet = silent ? Math.Min(10, quiet + 1) : 0;
            if (quiet == 10) continue;
            int length = encoder.Encode(frame, Samples, opus, opus.Length);
            link.Speaker(opus.AsSpan(0, length));
        }
    }

    public void Dispose() => encoder.Dispose();
}

internal sealed class MicProvider : IWaveProvider, IDisposable
{
    private const int Samples = 960;
    private readonly IOpusDecoder decoder = OpusCodecFactory.CreateDecoder(48000, 1);
    private readonly BufferedWaveProvider queue;
    private readonly CancellationTokenSource stopping = new();
    private readonly Task pump;
    private int generation = -1;
    private float level;
    public float Level => Volatile.Read(ref level);
    public WaveFormat WaveFormat { get; } = new(48000, 16, 1);

    public MicProvider(Link link)
    {
        queue = new BufferedWaveProvider(WaveFormat)
        {
            BufferDuration = TimeSpan.FromMilliseconds(120), DiscardOnBufferOverflow = true
        };
        pump = Pump(link);
    }

    private async Task Pump(Link link)
    {
        var pcm = new short[Samples];
        var bytes = new byte[Samples * 2];
        using var timer = new PeriodicTimer(TimeSpan.FromMilliseconds(10));
        long due = Environment.TickCount64;
        try
        {
            while (await timer.WaitForNextTickAsync(stopping.Token).ConfigureAwait(false))
            {
                int current = link.MicGeneration;
                if (current != generation)
                {
                    generation = current;
                    decoder.ResetState();
                }
                long now = Environment.TickCount64;
                if (now < due) continue;
                due += 20;
                if (now - due >= 20) due = now + 20;
                var action = link.ReadMic(out var opus);
                Array.Clear(pcm);
                if (action != Playout.Silence)
                {
                    try
                    {
                        int samples = decoder.Decode(opus.AsSpan(), pcm, Samples, action == Playout.Fec);
                        pcm.AsSpan(samples).Clear();
                    }
                    catch (Exception ex) when (ex is OpusException or ArgumentException) { Array.Clear(pcm); }
                }
                int peak = 0;
                foreach (short sample in pcm) peak = Math.Max(peak, Math.Abs((int)sample));
                Volatile.Write(ref level, peak / 32768f);
                Buffer.BlockCopy(pcm, 0, bytes, 0, bytes.Length);
                queue.AddSamples(bytes, 0, bytes.Length);
            }
        }
        catch (OperationCanceledException) { }
    }

    public int Read(byte[] buffer, int offset, int count) => queue.Read(buffer, offset, count);

    public void Dispose()
    {
        stopping.Cancel();
        pump.GetAwaiter().GetResult();
        stopping.Dispose();
        decoder.Dispose();
    }
}

// NAudio's WasapiLoopbackCapture polls a 100 ms buffer every 50 ms; a 20 ms buffer polls every 10 ms.
internal sealed class LowLatencyLoopback(MMDevice device) : WasapiCapture(device, false, 20)
{
    protected override AudioClientStreamFlags GetAudioClientStreamFlags() => AudioClientStreamFlags.Loopback;
}

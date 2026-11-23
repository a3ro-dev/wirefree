namespace Wirefree.Protocol;

public enum Playout { Silence, Packet, Fec, Conceal }

public sealed class JitterBuffer
{
    public const int FrameMs = 20;
    public const int TargetMs = 60;
    public const int MaxMs = 200;
    private const int Window = MaxMs / FrameMs;
    private readonly Dictionary<uint, byte[]> frames = new();
    private uint next;
    private bool started;
    private long due;
    private int missing;

    private static int Distance(uint a, uint b) => unchecked((int)(a - b));

    public void Reset()
    {
        frames.Clear();
        started = false;
        missing = 0;
    }

    public void Add(uint sequence, byte[] opus, long now)
    {
        if (!started && frames.Count == 0)
        {
            next = sequence;
            due = now + TargetMs;
        }
        int distance = Distance(sequence, next);
        if (distance < 0)
        {
            if (started || distance <= -Window ||
                frames.Keys.Any(key => Distance(key, sequence) >= Window))
                return;
            next = sequence;
        }
        else if (distance >= Window)
        {
            if (frames.Count != 0)
                return;
            Reset();
            next = sequence;
            due = now + TargetMs;
        }
        frames.TryAdd(sequence, opus);
    }

    public Playout Read(long now, out byte[]? opus)
    {
        opus = null;
        if ((!started && frames.Count == 0) || now < due)
            return Playout.Silence;
        if (now - due >= MaxMs)
        {
            Reset();
            return Playout.Silence;
        }

        started = true;
        due += FrameMs;
        uint sequence = next++;
        if (frames.Remove(sequence, out opus))
        {
            missing = 0;
            return Playout.Packet;
        }
        if (frames.TryGetValue(next, out opus))
        {
            missing = 0;
            return Playout.Fec;
        }
        if (frames.Count != 0 || ++missing <= TargetMs / FrameMs)
            return Playout.Conceal;
        Reset();
        return Playout.Silence;
    }
}

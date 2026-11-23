using System.Runtime.InteropServices;
using System.Text.Json;
using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;

namespace Wirefree;

internal sealed class Routing : IMMNotificationClient, IDisposable
{
    internal const string Speaker = "android-speaker";
    internal const string SpeakerLink = "wirefree speaker link";
    internal const string Mic = "android-mic";
    internal const string MicLink = "wirefree mic link";
    private const string Journal = "defaults.json";
    private static readonly Role[] Roles = [Role.Console, Role.Multimedia, Role.Communications];
    private static readonly DataFlow[] Flows = [DataFlow.Render, DataFlow.Capture];
    private readonly MMDeviceEnumerator devices = new();
    private readonly Dictionary<string, DefaultChange> changes;
    private readonly HashSet<string> overrides = new();
    private int notify = 1;
    public bool WantMic { get; private set; }
    public bool WantSpeaker { get; private set; }
    public bool Ready { get; private set; }
    public string Route { get; private set; } = "Windows defaults";
    public bool HasNotification => Interlocked.Exchange(ref notify, 0) != 0;

    public Routing()
    {
        changes = File.Exists(Storage.PathFor(Journal))
            ? JsonSerializer.Deserialize<Dictionary<string, DefaultChange>>(File.ReadAllBytes(Storage.PathFor(Journal))) ?? []
            : [];
        devices.RegisterEndpointNotificationCallback(this);
        Update(null);
    }

    private sealed record DefaultChange(string Original, string Applied);
    private void Save() => Storage.Write(Journal, JsonSerializer.SerializeToUtf8Bytes(changes));

    public MMDevice? Find(DataFlow flow, string name)
    {
        MMDevice? result = null;
        string original = name switch
        {
            Speaker => "Hi-Fi Cable Input", SpeakerLink => "Hi-Fi Cable Output",
            Mic => "CABLE Output", MicLink => "CABLE Input", _ => name
        };
        foreach (var device in devices.EnumerateAudioEndPoints(flow, DeviceState.Active))
        {
            var friendly = device.FriendlyName;
            if (friendly == name || (result is null &&
                (friendly == original || friendly.StartsWith(original + " (", StringComparison.OrdinalIgnoreCase))))
            {
                result?.Dispose();
                result = device;
            }
            else device.Dispose();
        }
        return result;
    }

    private string? Default(DataFlow flow, Role role)
    {
        if (!devices.HasDefaultAudioEndpoint(flow, role)) return null;
        using var device = devices.GetDefaultAudioEndpoint(flow, role);
        return device.ID;
    }

    private bool Active(string id)
    {
        try
        {
            using var device = devices.GetDevice(id);
            return device.State == DeviceState.Active;
        }
        catch (COMException) { return false; }
    }

    public void Update(Phone? phone)
    {
        using var mic = Find(DataFlow.Capture, Mic);
        using var speaker = Find(DataFlow.Render, Speaker);
        using var micLink = Find(DataFlow.Render, MicLink);
        using var speakerLink = Find(DataFlow.Capture, SpeakerLink);
        Ready = mic is not null && speaker is not null && micLink is not null && speakerLink is not null;
        WantMic = mic is not null && HasRecordingSession(mic);
        WantSpeaker = false;
        bool routedMic = false;

        foreach (var flow in Flows)
        foreach (var role in Roles)
        {
            string key = $"{flow}/{role}";
            string? actual = Default(flow, role);
            if (changes.TryGetValue(key, out var previous) && actual is not null &&
                actual != previous.Applied && Active(previous.Applied))
            {
                // A manual choice becomes the baseline for this role immediately.
                changes.Remove(key);
                Save();
                if (phone is not null) overrides.Add(key);
            }
            string? target = flow == DataFlow.Render
                ? phone is { Earpods: true } && speakerLink is not null ? speaker?.ID : null
                : micLink is not null ? mic?.ID : null;
            if (phone is null) target = null;
            if (phone is not { Busy: true } && !overrides.Contains(key))
                actual = Apply(key, actual, target, role);
            WantSpeaker |= phone is { Earpods: true, Busy: false } && speaker is not null &&
                speakerLink is not null && flow == DataFlow.Render && actual == speaker.ID;
            routedMic |= mic is not null && flow == DataFlow.Capture && actual == mic.ID;
        }

        if (phone is null) overrides.Clear();
        Route = phone switch
        {
            null => changes.Count == 0 ? "Windows defaults" : "Waiting to restore Windows defaults",
            { Busy: true } => "Windows devices held · phone audio paused",
            _ when WantSpeaker && routedMic => "Windows sound → EarPods · phone mic → Windows",
            _ when WantSpeaker => "Windows sound → EarPods",
            _ when routedMic => "Windows output · phone mic → Windows",
            _ => "Windows defaults"
        };
    }

    private string? Apply(string key, string? actual, string? target, Role role)
    {
        if (target is null)
        {
            if (!changes.TryGetValue(key, out var saved)) return actual;
            if (!Active(saved.Original)) return actual;
            if (actual != saved.Original && !Policy.SetDefault(saved.Original, role)) return actual;
            changes.Remove(key);
            Save();
            return saved.Original;
        }

        if (actual is null || actual == target) return actual;
        var hadChange = changes.TryGetValue(key, out var old);
        changes[key] = new(old?.Original ?? actual, target);
        Save();
        if (Policy.SetDefault(target, role)) return target;
        if (hadChange) changes[key] = old!;
        else changes.Remove(key);
        Save();
        return actual;
    }

    private static bool HasRecordingSession(MMDevice device)
    {
        var sessions = device.AudioSessionManager.Sessions;
        for (int i = 0; i < sessions.Count; i++)
        {
            using var session = sessions[i];
            try
            {
                if (session.State == AudioSessionState.AudioSessionStateActive &&
                    session.GetProcessID != (uint)Environment.ProcessId) return true;
            }
            catch (COMException) { }
        }
        return false;
    }

    private void Changed() => Interlocked.Exchange(ref notify, 1);
    public void OnDeviceStateChanged(string id, DeviceState state) => Changed();
    public void OnDeviceAdded(string id) => Changed();
    public void OnDeviceRemoved(string id) => Changed();
    public void OnDefaultDeviceChanged(DataFlow flow, Role role, string id) => Changed();
    public void OnPropertyValueChanged(string id, PropertyKey key) => Changed();

    public void Dispose()
    {
        try { Update(null); }
        finally
        {
            devices.UnregisterEndpointNotificationCallback(this);
            devices.Dispose();
        }
    }
}

[ComImport, Guid("F8679F50-850A-41CF-9C72-430F290290C8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IPolicyConfig
{
    [PreserveSig] int GetMixFormat([MarshalAs(UnmanagedType.LPWStr)] string id, out IntPtr format);
    [PreserveSig] int GetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string id, int isDefault, out IntPtr format);
    [PreserveSig] int ResetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string id);
    [PreserveSig] int SetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string id, IntPtr endpoint, IntPtr mix);
    void GetProcessingPeriod();
    void SetProcessingPeriod();
    void GetShareMode();
    void SetShareMode();
    void GetPropertyValue();
    void SetPropertyValue();
    [PreserveSig] int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string id, Role role);
}

internal static class Policy
{
    private static readonly Guid ClassId = new("870AF99C-171D-4F9E-AF0D-E63DF40C2BC9");

    private static IPolicyConfig Open() => (IPolicyConfig)Activator.CreateInstance(Type.GetTypeFromCLSID(ClassId)!)!;
    public static bool SetDefault(string id, Role role)
    {
        var policy = Open();
        try { return policy.SetDefaultEndpoint(id, role) == 0; }
        finally { Marshal.ReleaseComObject(policy); }
    }

}

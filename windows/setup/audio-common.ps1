# Shared helpers for install-audio.ps1 and uninstall-names.ps1 (dot-sourced; not meant to be run alone).
# Reads and renames Windows audio endpoints through IMMDeviceEnumerator / IPropertyStore, with COM interop
# compiled in-process by Add-Type (C# 5 only: Windows PowerShell 5.1 ships the old csc).

if (-not ('Wirefree.Audio' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace Wirefree {
  [StructLayout(LayoutKind.Sequential)]
  public struct PropertyKey { public Guid fmtid; public int pid; }

  [ComImport, Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IPropertyStore {
    [PreserveSig] int GetCount(out int c);
    [PreserveSig] int GetAt(int i, out PropertyKey k);
    [PreserveSig] int GetValue(ref PropertyKey k, IntPtr pv);
    [PreserveSig] int SetValue(ref PropertyKey k, IntPtr pv);
    [PreserveSig] int Commit();
  }
  [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDevice {
    [PreserveSig] int Activate(ref Guid iid, int ctx, IntPtr p, [MarshalAs(UnmanagedType.IUnknown)] out object o);
    [PreserveSig] int OpenPropertyStore(int access, out IPropertyStore ps);
    [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
    [PreserveSig] int GetState(out int state);
  }
  [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDeviceCollection {
    [PreserveSig] int GetCount(out int n);
    [PreserveSig] int Item(int i, out IMMDevice d);
  }
  [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDeviceEnumerator {
    [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection c);
    [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice d);
    [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice d);
    [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr cb);
    [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr cb);
  }
  [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorCom { }

  public class Endpoint {
    public string Id;
    public string Flow;     // render | capture
    public string Desc;     // {a45c254e-...},2  device description: the big name in Windows 11 Sound settings
    public string Name;     // {a45c254e-...},14 full friendly name: "<desc> (<adapter>)", the name in classic Sound
    public string Adapter;  // {b3f8fa53-...},6  driver/adapter name: the grey subtitle
    public int State;       // 1 active, 2 disabled, 4 not present, 8 unplugged
    public int Hz, Bits, Channels;
  }

  public static class Audio {
    static readonly Guid FMT_DEVICE = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0");
    static readonly Guid FMT_ADAPTER = new Guid("b3f8fa53-0004-438e-9003-51a46e139bfc");
    static readonly Guid FMT_ENGINE = new Guid("f19f064d-082c-4e27-bc73-6882a1bb8e4c");
    const int VT_LPWSTR = 31, VT_BLOB = 65, STGM_READ = 0, STGM_READWRITE = 2;
    static int BlobPtrOffset { get { return 8 + (IntPtr.Size == 8 ? 8 : 4); } }

    [DllImport("ole32.dll")] static extern int PropVariantClear(IntPtr pv);

    static PropertyKey Key(Guid g, int pid) { PropertyKey k; k.fmtid = g; k.pid = pid; return k; }
    static void Check(int hr) { if (hr < 0) Marshal.ThrowExceptionForHR(hr); }
    static IntPtr NewPv() { IntPtr p = Marshal.AllocHGlobal(32); for (int i = 0; i < 32; i++) Marshal.WriteByte(p, i, 0); return p; }

    static string GetString(IPropertyStore ps, Guid g, int pid) {
      PropertyKey k = Key(g, pid); IntPtr pv = NewPv();
      try {
        if (ps.GetValue(ref k, pv) < 0 || Marshal.ReadInt16(pv) != VT_LPWSTR) return null;
        return Marshal.PtrToStringUni(Marshal.ReadIntPtr(pv, 8));
      } finally { PropVariantClear(pv); Marshal.FreeHGlobal(pv); }
    }
    static byte[] GetBlob(IPropertyStore ps, Guid g, int pid) {
      PropertyKey k = Key(g, pid); IntPtr pv = NewPv();
      try {
        if (ps.GetValue(ref k, pv) < 0 || Marshal.ReadInt16(pv) != VT_BLOB) return null;
        int n = Marshal.ReadInt32(pv, 8); byte[] b = new byte[n];
        if (n > 0) Marshal.Copy(Marshal.ReadIntPtr(pv, BlobPtrOffset), b, 0, n);
        return b;
      } finally { PropVariantClear(pv); Marshal.FreeHGlobal(pv); }
    }
    static void SetString(IPropertyStore ps, Guid g, int pid, string v) {
      PropertyKey k = Key(g, pid); IntPtr pv = NewPv();
      try {
        Marshal.WriteInt16(pv, VT_LPWSTR); Marshal.WriteIntPtr(pv, 8, Marshal.StringToCoTaskMemUni(v));
        Check(ps.SetValue(ref k, pv));
      } finally { PropVariantClear(pv); Marshal.FreeHGlobal(pv); }
    }
    static void SetBlob(IPropertyStore ps, Guid g, int pid, byte[] v) {
      PropertyKey k = Key(g, pid); IntPtr pv = NewPv();
      try {
        IntPtr data = Marshal.AllocCoTaskMem(v.Length); Marshal.Copy(v, 0, data, v.Length);
        Marshal.WriteInt16(pv, VT_BLOB); Marshal.WriteInt32(pv, 8, v.Length); Marshal.WriteIntPtr(pv, BlobPtrOffset, data);
        Check(ps.SetValue(ref k, pv));
      } finally { PropVariantClear(pv); Marshal.FreeHGlobal(pv); }
    }

    static IMMDeviceEnumerator Enumerator() { return (IMMDeviceEnumerator)new MMDeviceEnumeratorCom(); }
    static IMMDevice Open(string id) { IMMDevice d; Check(Enumerator().GetDevice(id, out d)); return d; }

    // stateMask 0xB = active | disabled | unplugged (skips stale "not present" ghosts)
    public static List<Endpoint> List() {
      List<Endpoint> res = new List<Endpoint>();
      for (int flow = 0; flow < 2; flow++) {
        IMMDeviceCollection col; Check(Enumerator().EnumAudioEndpoints(flow, 0xB, out col));
        int n; Check(col.GetCount(out n));
        for (int i = 0; i < n; i++) {
          IMMDevice d; Check(col.Item(i, out d));
          Endpoint e = new Endpoint(); e.Flow = flow == 0 ? "render" : "capture";
          Check(d.GetId(out e.Id)); Check(d.GetState(out e.State));
          IPropertyStore ps; Check(d.OpenPropertyStore(STGM_READ, out ps));
          e.Desc = GetString(ps, FMT_DEVICE, 2); e.Name = GetString(ps, FMT_DEVICE, 14); e.Adapter = GetString(ps, FMT_ADAPTER, 6);
          byte[] f = GetBlob(ps, FMT_ENGINE, 0);
          if (f != null && f.Length >= 18) { e.Channels = BitConverter.ToUInt16(f, 2); e.Hz = BitConverter.ToInt32(f, 4); e.Bits = BitConverter.ToUInt16(f, 14); }
          res.Add(e);
        }
      }
      return res;
    }

    // Needs admin. Sets both the description (Windows 11 Settings) and the full name (classic Sound panel).
    public static void SetName(string id, string desc, string fullName) {
      IPropertyStore ps; Check(Open(id).OpenPropertyStore(STGM_READWRITE, out ps));
      SetString(ps, FMT_DEVICE, 2, desc); SetString(ps, FMT_DEVICE, 14, fullName); Check(ps.Commit());
    }

    // PCM WAVEFORMATEXTENSIBLE (40 bytes), channel mask FL|FR for stereo, FL for mono.
    public static void SetFormat(string id, int hz, int bits, int ch) {
      byte[] b = new byte[40]; int block = ch * bits / 8;
      BitConverter.GetBytes((ushort)0xFFFE).CopyTo(b, 0); BitConverter.GetBytes((ushort)ch).CopyTo(b, 2);
      BitConverter.GetBytes(hz).CopyTo(b, 4); BitConverter.GetBytes(hz * block).CopyTo(b, 8);
      BitConverter.GetBytes((ushort)block).CopyTo(b, 12); BitConverter.GetBytes((ushort)bits).CopyTo(b, 14);
      BitConverter.GetBytes((ushort)22).CopyTo(b, 16); BitConverter.GetBytes((ushort)bits).CopyTo(b, 18);
      BitConverter.GetBytes(ch == 1 ? 1 : 3).CopyTo(b, 20);
      new Guid("00000001-0000-0010-8000-00aa00389b71").ToByteArray().CopyTo(b, 24);
      IPropertyStore ps; Check(Open(id).OpenPropertyStore(STGM_READWRITE, out ps));
      SetBlob(ps, FMT_ENGINE, 0, b); Check(ps.Commit());
    }
  }
}
'@
}

# The four endpoints we manage. Orig = description Windows gets from the driver; New = what we show.
$script:WfEndpoints = @(
  @{ Key='hifi-render';  Flow='render';  Orig='Hi-Fi Cable Input';  New='android-speaker' },
  @{ Key='hifi-capture'; Flow='capture'; Orig='Hi-Fi Cable Output'; New='wirefree speaker link' },
  @{ Key='vb-capture';   Flow='capture'; Orig='CABLE Output';       New='android-mic' },
  @{ Key='vb-render';    Flow='render';  Orig='CABLE Input';        New='wirefree mic link' }
)

# Endpoint for a spec, whether it still has the driver's name or ours.
function Find-WfEndpoint($All, $Spec) {
  $All | Where-Object { $_.Flow -eq $Spec.Flow -and ($_.Desc -eq $Spec.Orig -or $_.Desc -eq $Spec.New) } | Select-Object -First 1
}

# "<desc> (<adapter>)", the way Windows composes the full friendly name.
function Get-WfFullName($Desc, $Adapter) { if ($Adapter) { "$Desc ($Adapter)" } else { $Desc } }

function Test-WfAdmin {
  ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# Relaunch a script in 64-bit PowerShell, elevated if needed. Returns its exit code.
# (A 32-bit PowerShell on 64-bit Windows would redirect System32 under the VB setup, so hop to the 64-bit one.)
function Restart-WfElevated($Script, [string[]]$Extra) {
  $ps = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
  if (-not [Environment]::Is64BitProcess -and [Environment]::Is64BitOperatingSystem) { $ps = "$env:SystemRoot\Sysnative\WindowsPowerShell\v1.0\powershell.exe" }
  $a = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$Script`"") + @($Extra | Where-Object { $_ })
  if (Test-WfAdmin) { return (Start-Process $ps -ArgumentList $a -NoNewWindow -Wait -PassThru).ExitCode }
  Write-Host 'Administrator rights are needed: approve the prompt...'
  try { return (Start-Process $ps -ArgumentList ($a + '-Elevated') -Verb RunAs -Wait -PassThru).ExitCode }
  catch { Write-Host "Could not get administrator rights: $($_.Exception.Message)" -ForegroundColor Red; return 1 }
}

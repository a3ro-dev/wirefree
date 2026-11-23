using System.Diagnostics;

namespace Wirefree;

internal static class Setup
{
    public static void Launch(string option)
    {
        string script = option == "--uninstall-names" ? "uninstall-names.ps1" : "install-audio.ps1";
        string path = Path.Combine(Path.GetDirectoryName(Environment.ProcessPath!)!, "setup", script);
        if (!File.Exists(path))
        {
            MessageBox.Show($"The setup script is missing next to wirefree.exe.\n\nsetup\\{script}", "wirefree");
            return;
        }
        var powershell = Path.Combine(Environment.SystemDirectory, @"WindowsPowerShell\v1.0\powershell.exe");
        try
        {
            Process.Start(new ProcessStartInfo(powershell, $"-ExecutionPolicy Bypass -File \"{path}\"")
            {
                UseShellExecute = true, Verb = "runas", WorkingDirectory = Path.GetDirectoryName(path)!
            });
        }
        catch (System.ComponentModel.Win32Exception ex) when (ex.NativeErrorCode == 1223) { }
    }
}

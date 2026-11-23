using Microsoft.Win32;

namespace Wirefree;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        try
        {
            if (args is ["--setup"] or ["--uninstall-names"])
            {
                Setup.Launch(args[0]);
                return;
            }
            using var singleton = new Mutex(true, @"Local\wirefree-companion", out var first);
            if (!first) return;
            using var routing = new Routing();
            using var link = new Link();
            using var audio = new Audio(link);
            using var app = new Tray(link, routing, audio);
            using var run = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
            var command = $"\"{Environment.ProcessPath}\"";
            if (!Equals(run.GetValue("wirefree"), command)) run.SetValue("wirefree", command);
            Application.Run(app);
        }
        catch (System.ComponentModel.Win32Exception ex) when (ex.NativeErrorCode == 1223) { }
        catch (Exception ex)
        {
            MessageBox.Show($"wirefree could not start or finish an audio operation.\n\n{ex.GetType().Name} (0x{ex.HResult:X8})",
                "wirefree", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }
}

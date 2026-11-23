using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace Wirefree;

internal class UtilityWindow : Form
{
    private readonly bool dark = !SystemInformation.HighContrast &&
        Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize",
            "AppsUseLightTheme", 1) is 0;
    private readonly List<Font> ownFonts = [];
    public FlowLayoutPanel Stack { get; }
    protected Color Surface => SystemInformation.HighContrast ? SystemColors.Window :
        dark ? Color.FromArgb(31, 33, 38) : Color.FromArgb(247, 248, 251);
    protected Color Accent => SystemInformation.HighContrast ? SystemColors.Highlight :
        dark ? Color.FromArgb(148, 188, 255) : Color.FromArgb(31, 82, 163);

    public UtilityWindow(string title, int width, int height)
    {
        Text = title;
        ClientSize = new Size(width, height);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = Surface;
        ForeColor = SystemInformation.HighContrast ? SystemColors.WindowText :
            dark ? Color.FromArgb(239, 242, 248) : Color.FromArgb(29, 34, 45);
        Stack = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill, AutoScroll = true, FlowDirection = FlowDirection.TopDown,
            WrapContents = false, Padding = new Padding(20, 16, 20, 16)
        };
        Controls.Add(Stack);
    }

    public Label Label(string text, float size, int height)
    {
        var font = new Font("Segoe UI Variable Text", size, size >= 18 ? FontStyle.Bold : FontStyle.Regular);
        ownFonts.Add(font);
        return new Label { Text = text, Font = font, Width = ClientSize.Width - 46, Height = height };
    }

    public Button Action(string text, Action action, bool primary = false)
    {
        var btn = new Button
        {
            Text = text, FlatStyle = FlatStyle.Flat, Height = primary ? 42 : 32,
            Width = ClientSize.Width - 46, TextAlign = ContentAlignment.MiddleCenter,
            BackColor = primary ? Accent : Surface,
            ForeColor = primary ? (SystemInformation.HighContrast ? SystemColors.HighlightText :
                dark ? Color.FromArgb(17, 27, 45) : Color.White) : ForeColor
        };
        btn.FlatAppearance.BorderColor = Accent;
        btn.Click += (_, _) => action();
        return btn;
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        int mode = dark ? 1 : 0;
        DwmSetWindowAttribute(Handle, 20, ref mode, sizeof(int));
    }

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        if (disposing) foreach (var font in ownFonts) font.Dispose();
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr window, int key, ref int value, int size);
}

internal sealed class StatusWindow : UtilityWindow
{
    private readonly Label connection;
    private readonly Label route;
    private readonly Label mic;
    private readonly Label setupStatus;
    private readonly Button mute;
    private readonly LevelMeter meter;

    public StatusWindow(Action pair, Action setup, Action toggleMute) : base("wirefree", 424, 350)
    {
        connection = Label("", 22, 45);
        route = Label("", 11, 48);
        mic = Label("", 11, 25);
        meter = new LevelMeter(Accent) { Width = 378, Height = 12, Margin = new Padding(3, 2, 3, 12) };
        mute = Action("Mute phone mic", toggleMute, true);
        setupStatus = Label("", 10, 26);
        Stack.Controls.AddRange([connection, route, mic, meter, mute, setupStatus,
            Action("Pair phone", pair), Action("Set up audio devices", setup)]);
    }

    public void UpdateStatus(string state, string routeText, bool ready, bool networkError, Phone? phone, float level)
    {
        connection.Text = state;
        route.Text = routeText;
        mic.Text = phone switch
        {
            null => "Phone microphone offline",
            { Muted: true } => "Phone microphone muted",
            { Busy: true } => "Phone microphone paused",
            { MicLive: true } => "Phone microphone live",
            _ => "Phone microphone idle"
        };
        meter.Level = level;
        mute.Enabled = phone is not null;
        mute.Text = phone is { Muted: true } ? "Unmute phone mic" : "Mute phone mic";
        setupStatus.Text = networkError ? "UDP link unavailable" : ready ? "" : "Virtual audio devices need setup";
    }

    private sealed class LevelMeter(Color accent) : Control
    {
        private int width;
        public float Level
        {
            set
            {
                int next = value <= 0 ? 0 : (int)(ClientSize.Width *
                    Math.Clamp(1 + 20 * Math.Log10(value) / 60, 0, 1));
                if (next == width) return;
                width = next;
                Invalidate();
            }
        }
        protected override void OnPaint(PaintEventArgs e)
        {
            using var track = new SolidBrush(Color.FromArgb(70, accent));
            using var active = new SolidBrush(accent);
            e.Graphics.FillRectangle(track, ClientRectangle);
            e.Graphics.FillRectangle(active, 0, 0, width, ClientSize.Height);
        }
    }
}

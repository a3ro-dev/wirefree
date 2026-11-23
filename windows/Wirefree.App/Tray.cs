using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using QRCoder;

namespace Wirefree;

internal sealed class Tray : ApplicationContext
{
    private readonly Link link;
    private readonly Routing routing;
    private readonly Audio audio;
    private readonly NotifyIcon tray;
    private readonly ContextMenuStrip menu = new();
    private readonly System.Windows.Forms.Timer tick = new() { Interval = 40 };
    private readonly Icon[] icons;
    private StatusWindow? window;
    private Form? pairing;
    private Phone? previous;
    private int previousIcon = -1;
    private long nextPoll;
    private bool quitting;
    private static readonly string[] States = [
        "Not paired", "Waiting for phone", "Connected · EarPods", "Connected · phone mic",
        "Phone has priority", "Muted"
    ];

    public Tray(Link link, Routing routing, Audio audio)
    {
        this.link = link;
        this.routing = routing;
        this.audio = audio;
        icons = Enumerable.Range(0, States.Length).Select(TrayIcon).ToArray();
        tray = new NotifyIcon { Visible = true, Icon = icons[0], ContextMenuStrip = menu };
        menu.Items.Add("Open wirefree", null, (_, _) => Open());
        menu.Items.Add("Pair phone", null, (_, _) => Pair());
        menu.Items.Add("Set up audio devices", null, (_, _) => StartSetup());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Quit", null, (_, _) => ExitThread());
        tray.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) Open(); };
        tick.Tick += (_, _) => RefreshState();
        RefreshState();
        tick.Start();
    }

    private void Open()
    {
        if (window is null || window.IsDisposed) window = new StatusWindow(Pair, StartSetup, link.ToggleMute);
        RefreshState();
        window.Show();
        window.Activate();
    }

    private static void StartSetup()
    {
        try { Setup.Launch("--setup"); }
        catch (System.ComponentModel.Win32Exception) { MessageBox.Show("Could not open setup.", "wirefree"); }
    }

    private void RefreshState()
    {
        if (quitting) return;
        try
        {
            var phone = link.State;
            long now = Environment.TickCount64;
            if (now >= nextPoll || routing.HasNotification || phone != previous)
            {
                nextPoll = now + 500;
                routing.Update(phone);
                audio.Update(routing, phone);
            }
            link.Update(routing.WantMic, routing.WantSpeaker && audio.SpeakerRunning);
            int state = phone switch
            {
                null => link.Paired ? 1 : 0,
                { Muted: true } => 5,
                { Busy: true } => 4,
                { Earpods: true } => 2,
                _ => 3
            };
            if (state != previousIcon)
            {
                previousIcon = state;
                tray.Icon = icons[state];
                tray.Text = "wirefree · " + States[state];
            }
            string? notice = (previous, phone) switch
            {
                (null, not null) => "Phone connected",
                (not null, null) => "Phone disconnected",
                (not null, not null) when previous.Muted != phone.Muted =>
                    phone.Muted ? "Phone microphone muted" : "Phone microphone unmuted",
                _ => null
            };
            if (notice is not null)
                tray.ShowBalloonTip(2500, "wirefree", notice, ToolTipIcon.None);
            previous = phone;
            if (window is { IsDisposed: false, Visible: true })
                window.UpdateStatus(States[state], routing.Route, routing.Ready, link.NetworkError, phone, audio.MicLevel);
        }
        catch (Exception ex) when (ex is COMException or IOException or UnauthorizedAccessException)
        {
            link.Update(false, false);
            if (window is { IsDisposed: false, Visible: true })
                window.UpdateStatus("Audio devices unavailable", "Check Windows Sound settings", false, false, null, 0);
        }
    }

    private void Pair()
    {
        if (pairing is { IsDisposed: false }) { pairing.Activate(); return; }
        var form = new UtilityWindow("Pair phone", 424, 490);
        pairing = form;
        var panel = form.Stack;
        panel.Controls.Add(form.Label("Pair your phone", 24, 45));
        panel.Controls.Add(form.Label("Scan this code in wirefree on Android.", 11, 30));
        var qr = new PictureBox { Size = new Size(384, 248), SizeMode = PictureBoxSizeMode.CenterImage };
        var entry = new TextBox { ReadOnly = true, Width = 384, AccessibleName = "Pairing URI" };
        var copy = form.Action("Copy pairing URI", () => { if (entry.Text.Length > 0) Clipboard.SetText(entry.Text); });
        var forget = form.Action("Forget phone", () => { });
        forget.Click += async (_, _) =>
        {
            if (MessageBox.Show(form, "Generate a new pairing code? The current phone will need to pair again.",
                "Forget phone", MessageBoxButtons.OKCancel) != DialogResult.OK) return;
            try { link.Forget(); await LoadCode(); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            { MessageBox.Show(form, "Could not save a new pairing key.", "wirefree"); }
        };
        panel.Controls.Add(qr);
        panel.Controls.Add(entry);
        panel.Controls.Add(copy);
        panel.Controls.Add(forget);
        form.FormClosed += (_, _) => qr.Image?.Dispose();
        form.Shown += async (_, _) => await LoadCode();
        form.Show();

        async Task LoadCode()
        {
            copy.Enabled = forget.Enabled = false;
            entry.Text = "";
            qr.Image?.Dispose();
            qr.Image = null;
            string? uri;
            try { uri = await link.PairUri(); }
            catch (System.Net.NetworkInformation.NetworkInformationException) { uri = null; }
            if (form.IsDisposed) return;
            forget.Enabled = true;
            entry.Text = uri ?? "";
            entry.PlaceholderText = "Connect Tailscale, then reopen pairing.";
            if (uri is null) return;
            using var data = QRCodeGenerator.GenerateQrCode(uri, QRCodeGenerator.ECCLevel.M);
            using var image = new QRCode(data);
            qr.Image = image.GetGraphic(Math.Max(1, 238 / data.ModuleMatrix.Count));
            copy.Enabled = true;
        }
    }

    protected override void ExitThreadCore()
    {
        quitting = true;
        tray.Visible = false;
        window?.Close();
        pairing?.Close();
        base.ExitThreadCore();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            tick.Dispose();
            tray.Dispose();
            menu.Dispose();
            foreach (var icon in icons) icon.Dispose();
        }
        base.Dispose(disposing);
    }

    [DllImport("user32.dll")]
    private static extern bool DestroyIcon(IntPtr icon);

    private static Icon TrayIcon(int state)
    {
        using var bitmap = new Bitmap(32, 32);
        using var g = Graphics.FromImage(bitmap);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        Color tint = state switch
        {
            0 => Color.FromArgb(82, 88, 102), 1 => Color.FromArgb(59, 89, 137),
            2 => Color.FromArgb(0, 106, 89), 3 => Color.FromArgb(35, 88, 160),
            4 => Color.FromArgb(142, 86, 13), _ => Color.FromArgb(183, 37, 56)
        };
        using var fill = new SolidBrush(tint);
        using var pen = new Pen(Color.White, 2.5f) { StartCap = LineCap.Round, EndCap = LineCap.Round };
        g.FillEllipse(fill, 0, 0, 32, 32);
        if (state == 4)
        {
            g.DrawLine(pen, 12, 11, 12, 21);
            g.DrawLine(pen, 20, 11, 20, 21);
        }
        else if (state is 0 or 1)
        {
            g.DrawArc(pen, 8, 9, 16, 14, 215, 110);
            g.DrawArc(pen, 12, 13, 8, 8, 215, 110);
            g.FillEllipse(Brushes.White, 14.5f, 20, 3, 3);
            if (state == 0) g.DrawLine(pen, 9, 22, 23, 9);
        }
        else if (state == 2)
        {
            g.DrawArc(pen, 8, 7, 16, 17, 180, 180);
            g.DrawLine(pen, 8, 15, 8, 22);
            g.DrawLine(pen, 24, 15, 24, 22);
            g.FillRectangle(Brushes.White, 9, 16, 4, 7);
            g.FillRectangle(Brushes.White, 19, 16, 4, 7);
        }
        else
        {
            g.DrawEllipse(pen, 13, 7, 6, 11);
            g.DrawArc(pen, 9, 10, 14, 12, 0, 180);
            g.DrawLine(pen, 16, 22, 16, 25);
            if (state == 5) g.DrawLine(pen, 7, 24, 25, 7);
        }
        IntPtr handle = bitmap.GetHicon();
        try { using var source = Icon.FromHandle(handle); return (Icon)source.Clone(); }
        finally { DestroyIcon(handle); }
    }
}

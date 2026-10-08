// 작업표시줄 알림 영역의 USB 매체제어 아이콘.
// 감시 프로그램(시스템 계정)이 2초마다 남기는 status.json 을 읽어 상태를 보여주고, 차단·반출 같은 일이 생기면 알림을 띄웁니다.
// 이 프로그램은 보여주기만 합니다. 꺼도 USB 차단은 감시 프로그램이 계속 합니다.
// install.ps1 이 PC에서 컴파일해 C:\ProgramData\UsbControl\UsbControlTray.exe 로 만듭니다.
//   UsbControlTray.exe [--status 파일] [--snapshot 그림.png]   (--snapshot: 상태 창을 그림으로 저장하고 끝냄, 확인용)
using System;
using System.Collections;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

public static class UsbControlTrayMain {
    [STAThread]
    public static void Main(string[] args) {
        string status = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), @"UsbControl\status.json");
        string snapshot = null;
        for (int i = 0; i + 1 < args.Length; i += 2) {
            if (args[i] == "--status") status = args[i + 1];
            else if (args[i] == "--snapshot") snapshot = args[i + 1];
        }
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        if (snapshot != null) {
            Snapshot(status, snapshot);
            return;
        }
        bool created;
        using (Mutex once = new Mutex(true, @"Local\UsbControlTray", out created)) {
            if (!created) return;   // 이 사용자 화면에 이미 떠 있음
            Application.Run(new TrayApp(status));
        }
    }

    static void Snapshot(string statusPath, string png) {
        TrayStatus s = TrayStatus.Read(statusPath);
        using (StatusForm form = new StatusForm()) {
            form.StartPosition = FormStartPosition.Manual;
            form.Location = new Point(-3000, -3000);
            form.ShowInTaskbar = false;
            form.Show();
            form.ShowStatus(s);
            Application.DoEvents();
            using (Bitmap b = new Bitmap(form.Width, form.Height)) {
                form.DrawToBitmap(b, new Rectangle(0, 0, form.Width, form.Height));
                b.Save(png);
            }
        }
        // 아이콘 세 가지도 같이 저장 (그림.icons.png)
        using (Bitmap b = new Bitmap(112, 36)) {
            using (Graphics g = Graphics.FromImage(b)) {
                g.Clear(Color.White);
                for (int i = 0; i < 3; i++) using (Icon icon = Icons.Make(i)) g.DrawIcon(icon, 2 + i * 38, 2);
            }
            b.Save(Path.ChangeExtension(png, ".icons.png"));
        }
    }
}

// ---------------------------------------------------------------- 상태 파일

public class TrayEvent {
    public long Seq;
    public string Time, Action, Kind, Name, File, Pi;
}

public class TrayDevice {
    public string Name, Kind, State, Id;
}

public class TrayStatus {
    // 감시 프로그램은 2초마다 확인하고 10초마다는 꼭 파일을 새로 씁니다. 이보다 오래되면 멈춘 것으로 봅니다.
    static readonly TimeSpan Stale = TimeSpan.FromSeconds(45);

    public bool Missing;
    public DateTime UpdatedAt;
    public string AgentVersion = "", PcName = "", LastSyncAt = "", PiLastScanAt = "";
    public bool ServerOk, PrivacyPc, ReadOnly, Notify = true, PiRunning;
    public long AllowCount;
    public List<TrayDevice> Devices = new List<TrayDevice>();
    public List<TrayEvent> Events = new List<TrayEvent>();

    // 0 보호 중, 1 서버 연결 안 됨, 2 멈춤
    public int Level {
        get {
            if (Missing || DateTime.Now - UpdatedAt > Stale) return 2;
            return ServerOk ? 0 : 1;
        }
    }

    public string Headline {
        get {
            if (Missing) return "감시 프로그램이 동작하지 않습니다";
            if (Level == 2) return "감시 프로그램이 멈춰 있습니다";
            return ServerOk ? "보호 중" : "보호 중 (관리 서버 연결 안 됨)";
        }
    }

    public string Detail {
        get {
            if (Level == 2) return "USB 차단이 동작하지 않을 수 있습니다. 관리자에게 알려 주세요.";
            if (!ServerOk) return "마지막으로 받은 허용 목록으로 계속 막고 있습니다.";
            return PrivacyPc ? "개인정보처리 PC입니다. 허용된 USB도 읽기 전용으로 열립니다."
                             : "허용 목록에 없는 USB 저장장치와 휴대폰은 막힙니다.";
        }
    }

    public static TrayStatus Read(string path) {
        TrayStatus s = new TrayStatus();
        try {
            string text;
            // 감시 프로그램이 파일을 바꿔치기할 수 있도록 지우기·쓰기를 막지 않고 엽니다.
            using (FileStream fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            using (StreamReader r = new StreamReader(fs, Encoding.UTF8)) {
                text = r.ReadToEnd();
            }
            Dictionary<string, object> d = new JavaScriptSerializer().DeserializeObject(text) as Dictionary<string, object>;
            if (d == null) { s.Missing = true; return s; }
            DateTime.TryParse(Str(d, "updatedAt"), out s.UpdatedAt);
            s.AgentVersion = Str(d, "agentVersion");
            s.PcName = Str(d, "pcName");
            s.ServerOk = Bool(d, "serverOk");
            s.LastSyncAt = Str(d, "lastSyncAt");
            s.AllowCount = Long(d, "allowCount");
            s.PrivacyPc = Bool(d, "privacyPc");
            s.ReadOnly = Bool(d, "readOnly");
            s.Notify = !d.ContainsKey("notify") || Bool(d, "notify");
            Dictionary<string, object> pi = d.ContainsKey("piScan") ? d["piScan"] as Dictionary<string, object> : null;
            if (pi != null) {
                s.PiRunning = Bool(pi, "running");
                s.PiLastScanAt = Str(pi, "lastScanAt");
            }
            foreach (Dictionary<string, object> x in Items(d, "devices")) {
                TrayDevice dev = new TrayDevice();
                dev.Name = Str(x, "name"); dev.Kind = Str(x, "kind"); dev.State = Str(x, "state"); dev.Id = Str(x, "id");
                s.Devices.Add(dev);
            }
            foreach (Dictionary<string, object> x in Items(d, "events")) {
                TrayEvent e = new TrayEvent();
                e.Seq = Long(x, "seq"); e.Time = Str(x, "time"); e.Action = Str(x, "action"); e.Kind = Str(x, "kind");
                e.Name = Str(x, "name"); e.File = Str(x, "file"); e.Pi = Str(x, "pi");
                s.Events.Add(e);
            }
        } catch {
            s.Missing = true;
        }
        return s;
    }

    static string Str(Dictionary<string, object> d, string k) {
        object v;
        return d.TryGetValue(k, out v) && v != null ? Convert.ToString(v) : "";
    }

    static bool Bool(Dictionary<string, object> d, string k) {
        object v;
        return d.TryGetValue(k, out v) && v is bool && (bool)v;
    }

    static long Long(Dictionary<string, object> d, string k) {
        object v;
        try { return d.TryGetValue(k, out v) && v != null ? Convert.ToInt64(v) : 0; } catch { return 0; }
    }

    static IEnumerable<Dictionary<string, object>> Items(Dictionary<string, object> d, string k) {
        object v;
        if (!d.TryGetValue(k, out v) || !(v is IEnumerable) || v is string) yield break;
        foreach (object o in (IEnumerable)v) {
            Dictionary<string, object> x = o as Dictionary<string, object>;
            if (x != null) yield return x;
        }
    }
}

// ---------------------------------------------------------------- 아이콘과 알림

public class TrayApp : ApplicationContext {
    readonly string statusPath;
    readonly NotifyIcon tray = new NotifyIcon();
    readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();
    readonly Icon[] icons = { Icons.Make(0), Icons.Make(1), Icons.Make(2) };
    StatusForm form;
    TrayStatus current;
    long lastSeq = -1;
    int ticks;

    public TrayApp(string statusPath) {
        this.statusPath = statusPath;
        ContextMenuStrip menu = new ContextMenuStrip();
        menu.Items.Add("상태 보기", null, delegate { ShowForm(); });
        menu.Items.Add("USB 사용 신청 방법", null, delegate { ShowHowTo(); });
        tray.ContextMenuStrip = menu;
        tray.DoubleClick += delegate { ShowForm(); };
        tray.BalloonTipClicked += delegate { ShowForm(); };
        tray.Icon = icons[2];
        tray.Text = "USB 매체제어";
        tray.Visible = true;
        timer.Interval = 2000;
        timer.Tick += delegate { Refresh(); };
        timer.Start();
        Refresh();
    }

    void Refresh() {
        TrayStatus s = TrayStatus.Read(statusPath);
        current = s;
        tray.Icon = icons[s.Level];
        tray.Text = Cut("USB 매체제어 - " + s.Headline, 63);   // 알림 영역 글자는 63자까지
        if (form != null && form.Visible) form.ShowStatus(s);
        Notify(s);
        // 윈도우 11은 새 아이콘을 숨김 영역에 넣으므로 처음 한 번 작업표시줄에 보이게 합니다. (사용자가 나중에 숨기면 그대로 둠)
        if (++ticks == 3) Promote();
    }

    void Notify(TrayStatus s) {
        if (s.Missing) return;
        long max = lastSeq;
        TrayEvent important = null;
        foreach (TrayEvent e in s.Events) {
            if (e.Seq > max) max = e.Seq;
            if (lastSeq >= 0 && e.Seq > lastSeq && Message(e) != null) important = e;
        }
        bool first = lastSeq < 0;
        lastSeq = max;
        if (first || important == null || !s.Notify) return;   // 처음 읽을 때는 지난 기록을 다시 알리지 않음
        string[] m = Message(important);
        tray.ShowBalloonTip(10000, m[0], m[1], m[2] == "warn" ? ToolTipIcon.Warning : ToolTipIcon.Info);
    }

    // 알림 제목, 내용, 종류. 알리지 않을 기록이면 null
    static string[] Message(TrayEvent e) {
        string a = e.Action ?? "";
        if (a.StartsWith("차단"))
            return new[] { "USB를 차단했습니다", e.Name + "\n허용되지 않은 " + e.Kind + "입니다. 쓰려면 관리자에게 사용 신청을 하세요.", "warn" };
        if (a == "허용(읽기 전용)")
            return new[] { "읽기 전용으로 연결했습니다", e.Name + "\n개인정보처리 PC라서 USB에 파일을 저장할 수 없습니다.", "info" };
        if (a.StartsWith("허용"))
            return new[] { "허용된 USB입니다", e.Name, "info" };
        if (a == "파일 반출" && !string.IsNullOrEmpty(e.Pi))
            return new[] { "개인정보가 든 파일을 USB에 복사했습니다", Path.GetFileName(e.File) + "\n" + e.Pi + "\n관리자에게 기록이 남습니다.", "warn" };
        return null;
    }

    void ShowForm() {
        if (form == null || form.IsDisposed) form = new StatusForm();
        form.Icon = icons[current == null ? 2 : current.Level];
        form.ShowStatus(current ?? TrayStatus.Read(statusPath));
        form.Show();
        if (form.WindowState == FormWindowState.Minimized) form.WindowState = FormWindowState.Normal;
        form.Activate();
    }

    static void ShowHowTo() {
        MessageBox.Show(
            "회사에서 허용한 USB만 쓸 수 있습니다.\n\n" +
            "1. 쓰려는 USB를 꽂으면 막히고 알림이 뜹니다.\n" +
            "2. 회사 양식이나 메일로 관리자에게 사용 신청을 합니다.\n" +
            "   ('상태 보기'에서 장치를 오른쪽 클릭하면 장치 ID를 복사할 수 있습니다)\n" +
            "3. 관리자가 등록하면 30초 안에 쓸 수 있습니다.",
            "USB 사용 신청 방법", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    static void Promote() {
        try {
            const string root = @"Control Panel\NotifyIconSettings";
            using (RegistryKey settings = Registry.CurrentUser.OpenSubKey(root)) {
                if (settings == null) return;
                foreach (string name in settings.GetSubKeyNames()) {
                    using (RegistryKey k = Registry.CurrentUser.OpenSubKey(root + @"\" + name, true)) {
                        if (k == null) continue;
                        string exe = k.GetValue("ExecutablePath") as string;
                        if (exe != null && exe.EndsWith(@"\UsbControlTray.exe", StringComparison.OrdinalIgnoreCase) && k.GetValue("IsPromoted") == null)
                            k.SetValue("IsPromoted", 1, RegistryValueKind.DWord);
                    }
                }
            }
        } catch {
        }
    }

    static string Cut(string s, int max) {
        return s.Length <= max ? s : s.Substring(0, max);
    }
}

public static class Icons {
    public static readonly Color[] Colors = { Color.FromArgb(26, 127, 69), Color.FromArgb(214, 140, 0), Color.FromArgb(180, 35, 24) };

    // 방패 모양: 0 체크(보호 중), 1 느낌표(서버 연결 안 됨), 2 X(멈춤)
    public static Icon Make(int level) {
        Bitmap b = new Bitmap(32, 32);
        using (Graphics g = Graphics.FromImage(b)) {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using (GraphicsPath p = new GraphicsPath()) {
                p.AddLine(16, 1, 29, 6);
                p.AddLine(29, 6, 29, 15);
                p.AddBezier(29, 15, 29, 23, 23, 28, 16, 31);
                p.AddBezier(16, 31, 9, 28, 3, 23, 3, 15);
                p.AddLine(3, 15, 3, 6);
                p.CloseFigure();
                using (SolidBrush brush = new SolidBrush(Colors[level])) g.FillPath(brush, p);
            }
            using (Pen pen = new Pen(Color.White, 3.6f)) {
                pen.StartCap = LineCap.Round;
                pen.EndCap = LineCap.Round;
                pen.LineJoin = LineJoin.Round;
                if (level == 0) {
                    g.DrawLines(pen, new[] { new PointF(10, 16), new PointF(14.5f, 21), new PointF(22.5f, 11) });
                } else if (level == 1) {
                    g.DrawLine(pen, 16, 9, 16, 18);
                    g.DrawLine(pen, 16, 23.5f, 16, 23.6f);
                } else {
                    g.DrawLine(pen, 11, 11, 21, 21);
                    g.DrawLine(pen, 21, 11, 11, 21);
                }
            }
        }
        return Icon.FromHandle(b.GetHicon());
    }
}

// ---------------------------------------------------------------- 상태 창

public class StatusForm : Form {
    readonly Panel header = new Panel();
    readonly Label headline = new Label();
    readonly Label detail = new Label();
    readonly Label info = new Label();
    readonly ListView devices = new ListView();
    readonly ListView events = new ListView();

    public StatusForm() {
        Text = "USB 매체제어";
        Font = new Font("맑은 고딕", 9f);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(580, 560);
        BackColor = Color.White;

        header.SetBounds(0, 0, 580, 70);
        headline.SetBounds(18, 10, 540, 30);
        headline.Font = new Font("맑은 고딕", 15f, FontStyle.Bold);
        headline.ForeColor = Color.White;
        headline.BackColor = Color.Transparent;
        detail.SetBounds(20, 42, 540, 20);
        detail.ForeColor = Color.White;
        detail.BackColor = Color.Transparent;
        header.Controls.Add(headline);
        header.Controls.Add(detail);

        info.SetBounds(18, 82, 545, 78);

        Label devicesTitle = Title("지금 꽂혀 있는 USB 저장장치·휴대폰", 166);
        devices.SetBounds(18, 188, 545, 110);
        Columns(devices, new[] { "장치", "종류", "상태" }, new[] { 280, 110, 130 });
        ContextMenuStrip copy = new ContextMenuStrip();
        copy.Items.Add("장치 ID 복사 (사용 신청할 때 붙여 넣기)", null, delegate { CopyId(); });
        devices.ContextMenuStrip = copy;

        Label eventsTitle = Title("최근 기록", 308);
        events.SetBounds(18, 330, 545, 180);
        Columns(events, new[] { "시간", "내용", "장치 / 파일" }, new[] { 110, 140, 270 });

        Button close = new Button();
        close.Text = "닫기";
        close.SetBounds(483, 520, 80, 28);
        close.Click += delegate { Hide(); };
        CancelButton = close;

        Controls.AddRange(new Control[] { header, info, devicesTitle, devices, eventsTitle, events, close });
        // X 를 눌러도 끝내지 않고 숨깁니다 (아이콘은 계속 떠 있음)
        FormClosing += delegate(object s, FormClosingEventArgs e) {
            if (e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); }
        };
    }

    static Label Title(string text, int y) {
        Label l = new Label();
        l.Text = text;
        l.Font = new Font("맑은 고딕", 9.5f, FontStyle.Bold);
        l.SetBounds(18, y, 545, 20);
        return l;
    }

    static void Columns(ListView v, string[] names, int[] widths) {
        v.View = View.Details;
        v.FullRowSelect = true;
        v.HeaderStyle = ColumnHeaderStyle.Nonclickable;
        for (int i = 0; i < names.Length; i++) v.Columns.Add(names[i], widths[i]);
    }

    void CopyId() {
        if (devices.SelectedItems.Count == 0) return;
        string id = devices.SelectedItems[0].Tag as string;
        if (!string.IsNullOrEmpty(id)) Clipboard.SetText(id);
    }

    public void ShowStatus(TrayStatus s) {
        header.BackColor = Icons.Colors[s.Level];
        headline.Text = s.Headline;
        detail.Text = s.Detail;

        StringBuilder sb = new StringBuilder();
        sb.AppendLine("PC 이름: " + s.PcName + "        프로그램 버전: " + s.AgentVersion);
        sb.AppendLine("관리 서버: " + (s.ServerOk ? "연결됨" : "연결 안 됨") + (s.LastSyncAt.Length > 0 ? " (마지막 연결 " + When(s.LastSyncAt) + ")" : ""));
        sb.AppendLine("허용 매체: " + s.AllowCount + "개        개인정보처리 PC: " + (s.PrivacyPc ? "예 (USB는 읽기 전용)" : "아니오"));
        sb.Append("개인정보 검사: " + (s.PiRunning ? "검사 중" : s.PiLastScanAt.Length > 0 ? "마지막 검사 " + When(s.PiLastScanAt) : "아직 안 함"));
        info.Text = s.Missing ? "감시 프로그램의 상태를 읽을 수 없습니다." : sb.ToString();

        devices.BeginUpdate();
        devices.Items.Clear();
        foreach (TrayDevice d in s.Devices) {
            ListViewItem item = new ListViewItem(new[] { d.Name, d.Kind, d.State });
            item.Tag = d.Id;
            if (d.State.StartsWith("차단")) item.ForeColor = Icons.Colors[2];
            devices.Items.Add(item);
        }
        if (s.Devices.Count == 0) devices.Items.Add(new ListViewItem(new[] { "꽂혀 있는 USB 저장장치가 없습니다.", "", "" }));
        devices.EndUpdate();

        events.BeginUpdate();
        events.Items.Clear();
        for (int i = s.Events.Count - 1; i >= 0; i--) {
            TrayEvent e = s.Events[i];
            string what = string.IsNullOrEmpty(e.File) ? e.Name : Path.GetFileName(e.File) + (string.IsNullOrEmpty(e.Pi) ? "" : " (" + e.Pi + ")");
            ListViewItem item = new ListViewItem(new[] { When(e.Time), e.Action, what });
            if ((e.Action ?? "").StartsWith("차단") || !string.IsNullOrEmpty(e.Pi)) item.ForeColor = Icons.Colors[2];
            events.Items.Add(item);
        }
        events.EndUpdate();
    }

    static string When(string iso) {
        DateTime t;
        if (!DateTime.TryParse(iso, out t)) return iso;
        return t.Date == DateTime.Today ? t.ToString("HH:mm:ss") : t.ToString("MM-dd HH:mm");
    }
}

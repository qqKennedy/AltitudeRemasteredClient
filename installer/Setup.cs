// Altitude REMASTERED installer: one exe with the client jar and the HD sprite pack inside. It installs into the
// player's own Altitude folder, can also swap in a Java 21 runtime and an updated OpenAL Soft audio library (downloaded
// from their official releases and checked against fixed SHA-256 values), and undoes exactly what it did.
// Built by installer\build-setup.ps1 with the C# compiler of the .NET Framework (C# 5).
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("Altitude REMASTERED Setup")]
[assembly: AssemblyProduct("Altitude REMASTERED")]
[assembly: AssemblyVersion("1.0.0.0")]

namespace AltitudeRemastered
{
    /// <summary>Downloads, pinned: exact release files and their SHA-256.</summary>
    static class Pins
    {
        public const string JavaUrl = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip";
        public const string JavaSha = "d35f31e712f0fcf6ac5a093edc90204fbff22f720ba3950bd09d331d5e621636";
        public const string JavaVersion = "21.0.12.1";
        public const string JavaFolder = "jdk-21.0.12.1+1-jre";
        public const string OpenAlUrl = "https://github.com/kcat/openal-soft/releases/download/1.25.2/openal-soft-1.25.2-bin.zip";
        public const string OpenAlSha = "67a0c4b800bd860c93c04f38caf8cbe4875f9c84700ac430efc451f70e265434";
        public const string OpenAlDllSha = "3963b06e319180700be0bcc0d027336664bb3a4975876b4a5442f10746fab5b4";
        public const string OpenAlDll = "openal-soft-1.25.2-bin/bin/Win64/soft_oal.dll";
        public const string OpenAlCopying = "openal-soft-1.25.2-bin/COPYING";
    }

    /// <summary>What was installed, kept in app\remastered-install.txt (key=value lines), so uninstall undoes exactly that.</summary>
    class State
    {
        readonly string file;
        public readonly Dictionary<string, string> Values = new Dictionary<string, string>();

        public State(string file)
        {
            this.file = file;
            if (!File.Exists(file)) return;
            foreach (string line in File.ReadAllLines(file))
            {
                int eq = line.IndexOf('=');
                if (eq > 0) Values[line.Substring(0, eq)] = line.Substring(eq + 1);
            }
        }

        public string Get(string key) { string v; return Values.TryGetValue(key, out v) ? v : null; }
        public void Set(string key, string value) { if (value == null) Values.Remove(key); else Values[key] = value; }

        public void Save()
        {
            if (Values.Count == 0) { if (File.Exists(file)) File.Delete(file); return; }
            File.WriteAllLines(file, Values.Select(kv => kv.Key + "=" + kv.Value).ToArray());
        }
    }

    /// <summary>The installation steps. Every step checks what is there first, so running it again repairs.</summary>
    class Installer
    {
        public const string JarName = "Altitude-1.1.9-REMASTERED.jar", PackName = "Altitude-1.1.9-hdsprites.zip";
        const string OurLine = "app.classpath=$APPDIR\\lib\\" + JarName, StockLine = "app.classpath=$APPDIR\\lib\\Altitude-1.1.9.jar";
        static readonly string[] JavaOptions = { "java-options=-XX:+UseZGC", "java-options=-XX:+ZGenerational", "java-options=-Xmx2g", "java-options=-Xms2g" };
        static readonly Regex Replaced = new Regex(@"^java-options=(-XX:[+-]Use\w*GC|-XX:[+-]ZGenerational|-Xm[sx]\S*)$");

        public readonly string Game;
        readonly Action<string> log;
        readonly string downloads;
        readonly State state;

        public Installer(string game, Action<string> log, string downloads)
        {
            Game = game;
            this.log = log;
            this.downloads = downloads;
            state = new State(Path.Combine(App, "remastered-install.txt"));
        }

        string App { get { return Path.Combine(Game, "app"); } }
        string Lib { get { return Path.Combine(App, "lib"); } }
        string Cfg { get { return Path.Combine(App, "Altitude.cfg"); } }
        string Native { get { return Path.Combine(App, "native"); } }
        string Runtime { get { return Path.Combine(Game, "runtime"); } }
        string OpenAl { get { return Path.Combine(Native, "OpenAL64.dll"); } }

        public bool HasHd { get { return File.Exists(Path.Combine(Lib, PackName)); } }
        public bool HasJava { get { return state.Get("javaoptions") != null; } }
        public bool HasOpenAl { get { return File.Exists(OpenAl) && Sha(OpenAl) == Pins.OpenAlDllSha; } }
        public bool Installed { get { return File.Exists(Path.Combine(Lib, JarName)) || state.Values.Count > 0; } }

        /// <summary>The Altitude folder: ALTITUDE_DIR, else Altitude in any Steam library. Null when not found.</summary>
        public static string Find()
        {
            string env = Environment.GetEnvironmentVariable("ALTITUDE_DIR");
            if (IsGame(env)) return env;
            var libraries = new List<string>();
            foreach (string key in new[] { @"HKEY_CURRENT_USER\Software\Valve\Steam", @"HKEY_LOCAL_MACHINE\SOFTWARE\WOW6432Node\Valve\Steam" })
            {
                string steam = (Registry.GetValue(key, "SteamPath", null) ?? Registry.GetValue(key, "InstallPath", null)) as string;
                if (string.IsNullOrEmpty(steam)) continue;
                steam = steam.Replace('/', '\\');
                libraries.Add(steam);
                string vdf = Path.Combine(steam, @"steamapps\libraryfolders.vdf");
                if (File.Exists(vdf))
                    foreach (Match m in Regex.Matches(File.ReadAllText(vdf), "\"path\"\\s+\"([^\"]+)\"")) libraries.Add(m.Groups[1].Value.Replace(@"\\", @"\"));
            }
            foreach (string l in libraries)
            {
                string dir = Path.Combine(l, @"steamapps\common\Altitude");
                if (IsGame(dir)) return Path.GetFullPath(dir);
            }
            return null;
        }

        public static bool IsGame(string dir)
        {
            return !string.IsNullOrEmpty(dir) && File.Exists(Path.Combine(dir, @"app\Altitude.cfg")) && File.Exists(Path.Combine(dir, @"app\lib\Altitude-1.1.9.jar"));
        }

        public static bool AltitudeRunning() { return Process.GetProcessesByName("Altitude").Length > 0; }

        /// <summary>Whether this process may write in the game folder (Program Files needs administrator rights).</summary>
        public bool CanWrite()
        {
            try
            {
                string probe = Path.Combine(App, ".remastered-write-test");
                File.WriteAllText(probe, "");
                File.Delete(probe);
                probe = Path.Combine(Game, ".remastered-write-test");
                File.WriteAllText(probe, "");
                File.Delete(probe);
                return true;
            }
            catch (UnauthorizedAccessException) { return false; }
            catch (IOException) { return false; }
        }

        public void Install(bool hd, bool java, bool openal)
        {
            log("Altitude: " + Game);
            CopyResource("client.jar", Path.Combine(Lib, JarName), "client");
            SetGameJar();
            if (hd) CopyResource("hdsprites.zip", Path.Combine(Lib, PackName), "HD sprite pack");
            else RemoveHd();
            if (java) InstallJava(); else UndoJava();
            if (openal) InstallOpenAl(); else UndoOpenAl();
            state.Save();
            log("Done. Start Altitude from Steam as usual; /opt in the game console shows the options.");
        }

        public void Uninstall()
        {
            log("Altitude: " + Game);
            UndoOpenAl();
            UndoJava();
            RemoveHd();
            var lines = ReadCfg();
            int i = lines.FindIndex(l => l.StartsWith("app.classpath="));
            if (i >= 0 && lines[i] == OurLine)
            {
                lines[i] = state.Get("classpath") ?? StockLine;
                WriteCfg(lines);
                log("Game jar set back to " + lines[i].Substring(lines[i].LastIndexOf('\\') + 1));
            }
            state.Set("classpath", null);
            string backup = Cfg + ".before-remastered";
            if (File.Exists(backup) && File.ReadAllText(backup).Replace("\r\n", "\n").TrimEnd() == File.ReadAllText(Cfg).Replace("\r\n", "\n").TrimEnd()) File.Delete(backup);
            Delete(Path.Combine(Lib, JarName), "client");
            state.Save();
            log("Removed. Altitude is back to its own files.");
        }

        // ---- client and HD sprites

        void CopyResource(string name, string target, string what)
        {
            using (Stream s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
            {
                if (s == null) throw new InvalidOperationException("This setup has no " + name);
                byte[] bytes = new byte[s.Length];
                int read = 0;
                while (read < bytes.Length) read += s.Read(bytes, read, bytes.Length - read);
                if (File.Exists(target) && Sha(File.ReadAllBytes(target)) == Sha(bytes)) { log("The " + what + " is up to date"); return; }
                File.WriteAllBytes(target + ".tmp", bytes);
                if (File.Exists(target)) File.Delete(target);
                File.Move(target + ".tmp", target);
                log("Installed the " + what);
            }
        }

        void SetGameJar()
        {
            var lines = ReadCfg();
            int i = lines.FindIndex(l => l.StartsWith("app.classpath="));
            if (i < 0) throw new InvalidOperationException("No app.classpath line in " + Cfg);
            if (lines[i] == OurLine) return;
            if (state.Get("classpath") == null) state.Set("classpath", lines[i]);
            string backup = Cfg + ".before-remastered";
            if (!File.Exists(backup)) File.Copy(Cfg, backup);
            lines[i] = OurLine;
            WriteCfg(lines);
            log("Altitude now starts with the REMASTERED client (Altitude.cfg backed up)");
        }

        void RemoveHd() { Delete(Path.Combine(Lib, PackName), "HD sprite pack"); }

        // ---- Java 21

        void InstallJava()
        {
            string version = JavaVersion(Runtime);
            string backup = state.Get("runtime");
            if (version == Pins.JavaVersion && (backup == null || Directory.Exists(Path.Combine(Game, backup))))
                log("Java " + version + " is already the game's runtime");
            else if (version != null && version.StartsWith("21") && backup == null)
                log("The game already runs on Java " + version + "; left as it is");
            else
            {
                string zip = Download(Pins.JavaUrl, Pins.JavaSha, "Java 21");
                string temp = Path.Combine(Game, "runtime-remastered-new");
                if (Directory.Exists(temp)) Directory.Delete(temp, true);
                ZipFile.ExtractToDirectory(zip, temp);
                string jre = Path.Combine(temp, Pins.JavaFolder);
                if (JavaVersion(jre) != Pins.JavaVersion) throw new InvalidOperationException("The Java download is not Java " + Pins.JavaVersion);
                if (backup != null && Directory.Exists(Path.Combine(Game, backup)))
                {
                    // Repair (for example after Steam's file check put Java 17 files back): the original is already saved.
                    if (Directory.Exists(Runtime)) Directory.Delete(Runtime, true);
                }
                else if (Directory.Exists(Runtime))
                {
                    backup = "runtime-before-remastered";
                    if (Directory.Exists(Path.Combine(Game, backup))) backup += "-" + DateTime.Now.ToString("yyyyMMdd-HHmmss");
                    Directory.Move(Runtime, Path.Combine(Game, backup));
                    state.Set("runtime", backup);
                }
                Directory.Move(jre, Runtime);
                Directory.Delete(temp, true);
                log("Installed Java " + Pins.JavaVersion + " as the game's runtime (Java " + (version ?? "?") + " kept in " + backup + ")");
            }
            SetJavaOptions();
        }

        /// <summary>The garbage collector and heap lines for Java 21; the lines they replace are kept in the state.</summary>
        void SetJavaOptions()
        {
            var lines = ReadCfg();
            var current = lines.Where(l => Replaced.IsMatch(l)).ToList();
            if (state.Get("javaoptions") == null) state.Set("javaoptions", string.Join("|", current.ToArray()));
            if (current.SequenceEqual(JavaOptions)) { log("The Java 21 options are set"); return; }
            lines = lines.Where(l => !Replaced.IsMatch(l)).ToList();
            lines.InsertRange(OptionsAt(lines), JavaOptions);
            WriteCfg(lines);
            log("Set the Java 21 options (ZGC, 2 GB heap)");
        }

        /// <summary>Where java-options lines go: after the last one, else after [JavaOptions] (added when missing).</summary>
        static int OptionsAt(List<string> lines)
        {
            int at = lines.FindLastIndex(l => l.StartsWith("java-options="));
            if (at < 0) at = lines.FindIndex(l => l.Trim() == "[JavaOptions]");
            if (at < 0) { lines.Add("[JavaOptions]"); at = lines.Count - 1; }
            return at + 1;
        }

        void UndoJava()
        {
            string options = state.Get("javaoptions");
            if (options != null)
            {
                var lines = ReadCfg().Where(l => !Replaced.IsMatch(l)).ToList();
                if (options.Length > 0) lines.InsertRange(OptionsAt(lines), options.Split('|'));
                WriteCfg(lines);
                state.Set("javaoptions", null);
                log("Java options set back");
            }
            string backup = state.Get("runtime");
            if (backup != null)
            {
                string saved = Path.Combine(Game, backup);
                if (Directory.Exists(saved))
                {
                    if (Directory.Exists(Runtime)) Directory.Delete(Runtime, true);
                    Directory.Move(saved, Runtime);
                    log("The game's own Java (" + JavaVersion(Runtime) + ") is back");
                }
                state.Set("runtime", null);
            }
        }

        static string JavaVersion(string dir)
        {
            string release = Path.Combine(dir, "release");
            if (!File.Exists(release)) return null;
            foreach (string line in File.ReadAllLines(release))
                if (line.StartsWith("JAVA_VERSION=")) return line.Substring(13).Trim('"');
            return null;
        }

        // ---- OpenAL Soft

        void InstallOpenAl()
        {
            if (HasOpenAl) { log("The audio library is OpenAL Soft 1.25.2"); return; }
            string zip = Download(Pins.OpenAlUrl, Pins.OpenAlSha, "OpenAL Soft");
            byte[] dll, copying;
            using (ZipArchive z = ZipFile.OpenRead(zip))
            {
                dll = ReadEntry(z, Pins.OpenAlDll);
                copying = ReadEntry(z, Pins.OpenAlCopying);
            }
            if (Sha(dll) != Pins.OpenAlDllSha) throw new InvalidOperationException("The OpenAL Soft DLL does not match its checksum");
            string backup = OpenAl + ".before-remastered";
            if (File.Exists(OpenAl) && !File.Exists(backup)) File.Copy(OpenAl, backup);
            state.Set("openal", "1");
            File.WriteAllBytes(OpenAl, dll);
            File.WriteAllBytes(Path.Combine(Native, "OpenAL-Soft-COPYING.txt"), copying);
            log("Installed OpenAL Soft 1.25.2 (the old audio library kept as OpenAL64.dll.before-remastered)");
        }

        void UndoOpenAl()
        {
            if (state.Get("openal") == null) return;
            string backup = OpenAl + ".before-remastered";
            if (File.Exists(backup))
            {
                File.Copy(backup, OpenAl, true);
                File.Delete(backup);
                log("The game's own audio library is back");
            }
            string copying = Path.Combine(Native, "OpenAL-Soft-COPYING.txt");
            if (File.Exists(copying)) File.Delete(copying);
            state.Set("openal", null);
        }

        static byte[] ReadEntry(ZipArchive z, string name)
        {
            ZipArchiveEntry e = z.GetEntry(name);
            if (e == null) throw new InvalidOperationException("Missing " + name + " in the download");
            using (Stream s = e.Open())
            using (var m = new MemoryStream())
            {
                s.CopyTo(m);
                return m.ToArray();
            }
        }

        // ---- helpers

        /// <summary>A pinned file: from the downloads folder when given, else from the internet; always checked.</summary>
        string Download(string url, string sha, string what)
        {
            string name = Uri.UnescapeDataString(url.Substring(url.LastIndexOf('/') + 1));
            if (downloads != null && File.Exists(Path.Combine(downloads, name)))
            {
                string local = Path.Combine(downloads, name);
                if (Sha(local) != sha) throw new InvalidOperationException(local + " does not match its checksum");
                log("Using " + local);
                return local;
            }
            string file = Path.Combine(Path.GetTempPath(), "altitude-remastered-" + name);
            if (File.Exists(file) && Sha(file) == sha) return file;
            log("Downloading " + what + "...");
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            using (var client = new WebClient())
            {
                client.Headers.Add("User-Agent", "Altitude-REMASTERED-Setup");
                client.DownloadFile(url, file);
            }
            if (Sha(file) != sha)
            {
                File.Delete(file);
                throw new InvalidOperationException("The " + what + " download does not match its checksum; nothing was changed for it");
            }
            log("Downloaded and checked " + what);
            return file;
        }

        List<string> ReadCfg() { return File.ReadAllText(Cfg).Replace("\r\n", "\n").TrimEnd('\n').Split('\n').ToList(); }

        void WriteCfg(List<string> lines)
        {
            string newline = File.ReadAllText(Cfg).Contains("\r\n") ? "\r\n" : "\n";
            File.WriteAllText(Cfg, string.Join(newline, lines) + newline, new UTF8Encoding(false));
        }

        void Delete(string file, string what)
        {
            if (!File.Exists(file)) return;
            File.Delete(file);
            log("Removed the " + what);
        }

        static string Sha(string file) { using (var s = File.OpenRead(file)) return Hex(SHA256.Create().ComputeHash(s)); }
        static string Sha(byte[] bytes) { return Hex(SHA256.Create().ComputeHash(bytes)); }
        static string Hex(byte[] b) { return BitConverter.ToString(b).Replace("-", "").ToLowerInvariant(); }
    }

    /// <summary>The window: game folder, three choices, Install and Uninstall, and a log.</summary>
    class SetupForm : Form
    {
        readonly TextBox folder = new TextBox(), output = new TextBox();
        readonly CheckBox hd = new CheckBox(), java = new CheckBox(), openal = new CheckBox();
        readonly Button install = new Button(), uninstall = new Button(), browse = new Button();
        readonly string downloads;

        public SetupForm(string dir, string downloads)
        {
            this.downloads = downloads;
            // Rows stacked in a table that sizes itself, so the window works at any DPI (WinForms scales the font).
            Text = "Altitude REMASTERED Setup";
            Font = new Font("Segoe UI", 9f);
            AutoScaleMode = AutoScaleMode.Font;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            AutoSize = true;
            AutoSizeMode = AutoSizeMode.GrowAndShrink;
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch (Exception) { }

            var rows = new TableLayoutPanel { ColumnCount = 1, AutoSize = true, Padding = new Padding(12), Dock = DockStyle.Fill };
            rows.Controls.Add(new Label { Text = "HD sprites, sharp text and more for Altitude 1.1.9. No gameplay changes.", AutoSize = true, Margin = new Padding(3, 3, 3, 9) });

            var folderRow = new TableLayoutPanel { ColumnCount = 3, AutoSize = true, Dock = DockStyle.Fill, Margin = new Padding(0, 0, 0, 6) };
            folderRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            folderRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            folderRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            folderRow.Controls.Add(new Label { Text = "Altitude folder:", AutoSize = true, Anchor = AnchorStyles.Left });
            folder.Text = dir ?? "";
            folder.Width = 360;
            folder.Anchor = AnchorStyles.Left | AnchorStyles.Right;
            folderRow.Controls.Add(folder);
            browse.Text = "Browse...";
            browse.AutoSize = true;
            browse.Click += delegate
            {
                using (var d = new FolderBrowserDialog { Description = "The Altitude folder (it has app and runtime inside)" })
                    if (d.ShowDialog(this) == DialogResult.OK) { folder.Text = d.SelectedPath; Refresh(); }
            };
            folderRow.Controls.Add(browse);
            rows.Controls.Add(folderRow);

            hd.Text = "HD sprites (recommended)";
            java.Text = "Java 21 runtime: steadier frame times (downloads 49 MB)";
            openal.Text = "Updated audio library, OpenAL Soft 1.25.2 (downloads 19 MB)";
            foreach (CheckBox c in new[] { hd, java, openal }) { c.Checked = true; c.AutoSize = true; rows.Controls.Add(c); }

            var buttons = new FlowLayoutPanel { AutoSize = true, Margin = new Padding(0, 9, 0, 6) };
            install.Text = "Install";
            uninstall.Text = "Uninstall";
            foreach (Button b in new[] { install, uninstall }) { b.AutoSize = true; b.MinimumSize = new Size(110, 30); buttons.Controls.Add(b); }
            install.Click += delegate { Run(false); };
            uninstall.Click += delegate { Run(true); };
            rows.Controls.Add(buttons);

            output.Multiline = true;
            output.ReadOnly = true;
            output.ScrollBars = ScrollBars.Vertical;
            output.Size = new Size(540, 180);
            output.Dock = DockStyle.Fill;
            rows.Controls.Add(output);
            Controls.Add(rows);
            Load += delegate { Refresh(); };
        }

        new void Refresh()
        {
            output.Clear();
            if (!Installer.IsGame(folder.Text)) { Log("Altitude 1.1.9 was not found. Use Browse to pick its folder."); install.Enabled = uninstall.Enabled = false; return; }
            var inst = new Installer(folder.Text, s => { }, downloads);
            install.Enabled = true;
            uninstall.Enabled = inst.Installed;
            if (inst.Installed)
            {
                hd.Checked = inst.HasHd;
                java.Checked = inst.HasJava;
                openal.Checked = inst.HasOpenAl;
                Log("REMASTERED is installed. Change the choices and press Install to update, or Uninstall to remove it.");
            }
            else Log("Ready to install into " + folder.Text);
        }

        void Log(string line)
        {
            if (InvokeRequired) { BeginInvoke(new Action<string>(Log), line); return; }
            output.AppendText(line + Environment.NewLine);
        }

        void Run(bool remove)
        {
            if (Installer.AltitudeRunning()) { MessageBox.Show(this, "Close Altitude first.", Text); return; }
            var inst = new Installer(folder.Text, Log, downloads);
            if (!inst.CanWrite())
            {
                // The game folder needs administrator rights (for example under Program Files): start again elevated.
                try
                {
                    Process.Start(new ProcessStartInfo(Application.ExecutablePath, "/dir:\"" + folder.Text + "\"") { Verb = "runas", UseShellExecute = true });
                    Close();
                }
                catch (Win32Exception) { Log("Administrator rights are needed to change files in " + folder.Text); }
                return;
            }
            bool h = hd.Checked, j = java.Checked, o = openal.Checked;
            install.Enabled = uninstall.Enabled = browse.Enabled = false;
            output.Clear();
            new Thread(() =>
            {
                try
                {
                    if (remove) inst.Uninstall(); else inst.Install(h, j, o);
                }
                catch (Exception e)
                {
                    Log("Stopped: " + e.Message);
                    Log("Run Setup again to finish, or Uninstall to undo what was done.");
                }
                BeginInvoke(new Action(() => { install.Enabled = browse.Enabled = true; uninstall.Enabled = !remove || inst.Installed; }));
            }) { IsBackground = true }.Start();
        }
    }

    static class Program
    {
        /// <summary>
        /// No arguments: the window. /install or /uninstall: run without a window (exit code 0 when it worked), with
        /// /dir:FOLDER, /nohd /nojava /noopenal, /downloads:FOLDER (pre-downloaded pinned files) and /log:FILE.
        /// </summary>
        [STAThread]
        static int Main(string[] args)
        {
            string dir = null, downloads = null, logFile = null;
            bool quietInstall = false, quietUninstall = false, hd = true, java = true, openal = true;
            foreach (string a in args)
            {
                string arg = a.Trim();
                if (arg.StartsWith("/dir:", StringComparison.OrdinalIgnoreCase)) dir = arg.Substring(5).Trim('"');
                else if (arg.StartsWith("/downloads:", StringComparison.OrdinalIgnoreCase)) downloads = arg.Substring(11).Trim('"');
                else if (arg.StartsWith("/log:", StringComparison.OrdinalIgnoreCase)) logFile = arg.Substring(5).Trim('"');
                else if (arg.Equals("/install", StringComparison.OrdinalIgnoreCase)) quietInstall = true;
                else if (arg.Equals("/uninstall", StringComparison.OrdinalIgnoreCase)) quietUninstall = true;
                else if (arg.Equals("/nohd", StringComparison.OrdinalIgnoreCase)) hd = false;
                else if (arg.Equals("/nojava", StringComparison.OrdinalIgnoreCase)) java = false;
                else if (arg.Equals("/noopenal", StringComparison.OrdinalIgnoreCase)) openal = false;
            }
            if (dir == null) dir = Installer.Find();
            if (quietInstall || quietUninstall)
            {
                var lines = new List<string>();
                Action<string> log = s => { lines.Add(s); if (logFile != null) File.AppendAllText(logFile, s + Environment.NewLine); };
                try
                {
                    if (!Installer.IsGame(dir)) throw new InvalidOperationException("Altitude 1.1.9 not found: " + dir);
                    if (Installer.AltitudeRunning()) throw new InvalidOperationException("Close Altitude first");
                    var inst = new Installer(dir, log, downloads);
                    if (quietUninstall) inst.Uninstall(); else inst.Install(hd, java, openal);
                    return 0;
                }
                catch (Exception e)
                {
                    log("Stopped: " + e.Message);
                    return 1;
                }
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new SetupForm(dir, downloads));
            return 0;
        }
    }
}

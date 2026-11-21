using System.Diagnostics;

string baseDir = AppContext.BaseDirectory;
string jattach = Path.Combine(baseDir, "jattach");
string agentJar = Path.Combine(baseDir, "agent.jar");

if (args.Length >= 2 && (args[0] == "attach" || args[0] == "eject"))
    return Inject(args[1], args[0]);

if (args.Length == 1 && args[0] == "list")
{
    foreach (var mc in FindMinecraft())
        Console.WriteLine($"[PID {mc.Pid}] {mc.Cmd}");
    return 0;
}

return Menu();

int Menu()
{
    Console.WriteLine("donut-audit (macOS)");
    while (true)
    {
        Console.WriteLine();
        Console.WriteLine("  1) Attach agent to Minecraft");
        Console.WriteLine("  2) Eject agent from Minecraft");
        Console.WriteLine("  3) List Java processes");
        Console.WriteLine("  4) Exit");
        Console.Write("Choose an option: ");
        string choice = (Console.ReadLine() ?? "").Trim();
        Console.WriteLine();
        if (choice == "4") return 0;
        else if (choice == "3") { foreach (var mc in FindMinecraft()) Console.WriteLine($"[PID {mc.Pid}] {mc.Cmd}"); }
        else if (choice == "1") Auto("attach");
        else if (choice == "2") Auto("eject");
        else Console.WriteLine("Please type 1, 2, 3, or 4.");
    }
}

void Auto(string mode)
{
    var found = FindMinecraft();
    if (found.Count == 0)
    {
        Console.WriteLine("No Minecraft process found.");
        Console.WriteLine("Launch Minecraft and enter a world, then try again.");
        return;
    }
    string pid;
    if (found.Count == 1)
    {
        pid = found[0].Pid;
        Console.WriteLine($"Found Minecraft (PID {pid}).");
    }
    else
    {
        Console.WriteLine("Found more than one Minecraft process:");
        for (int i = 0; i < found.Count; i++)
            Console.WriteLine($"  {i + 1}) PID {found[i].Pid}");
        Console.Write("Pick one: ");
        if (!int.TryParse((Console.ReadLine() ?? "").Trim(), out int idx) || idx < 1 || idx > found.Count)
        {
            Console.WriteLine("Cancelled.");
            return;
        }
        pid = found[idx - 1].Pid;
    }
    Console.WriteLine(mode == "attach" ? $"Attaching to PID {pid}..." : $"Ejecting from PID {pid}...");
    int code = Inject(pid, mode);
    Console.WriteLine(code == 0 ? "Done." : $"Failed (exit code {code}).");
}

int Inject(string pid, string mode)
{
    if (!File.Exists(jattach)) { Console.Error.WriteLine("Missing 'jattach' next to this program."); return 2; }
    if (!File.Exists(agentJar)) { Console.Error.WriteLine("Missing 'agent.jar' next to this program."); return 2; }
    try { Run("/bin/chmod", new[] { "+x", jattach }, false); } catch { }
    return Run(jattach, new[] { pid, "load", "instrument", "false", $"{agentJar}={mode}" }, true);
}

int Run(string file, string[] argv, bool echo)
{
    var psi = new ProcessStartInfo(file) { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true };
    foreach (var a in argv) psi.ArgumentList.Add(a);
    using var p = Process.Start(psi);
    if (p == null) { if (echo) Console.Error.WriteLine($"Failed to start {file}."); return 2; }
    string so = p.StandardOutput.ReadToEnd();
    string se = p.StandardError.ReadToEnd();
    p.WaitForExit();
    if (echo) { Console.Write(so); Console.Error.Write(se); }
    return p.ExitCode;
}

List<Mc> FindMinecraft()
{
    var list = new List<Mc>();
    try
    {
        var psi = new ProcessStartInfo("/bin/ps") { UseShellExecute = false, RedirectStandardOutput = true };
        foreach (var a in new[] { "-axww", "-o", "pid=,command=" }) psi.ArgumentList.Add(a);
        using var p = Process.Start(psi);
        if (p == null) return list;
        string outp = p.StandardOutput.ReadToEnd();
        p.WaitForExit();
        foreach (var raw in outp.Split('\n'))
        {
            string line = raw.TrimStart();
            if (line.Length == 0) continue;
            int sp = line.IndexOf(' ');
            if (sp <= 0) continue;
            string pid = line.Substring(0, sp);
            string cmd = line.Substring(sp + 1);
            string low = cmd.ToLowerInvariant();
            if ((low.Contains("minecraft") || low.Contains("lunar")) && !low.Contains("audit-tool"))
                list.Add(new Mc { Pid = pid, Cmd = cmd.Length > 90 ? cmd.Substring(0, 90) + "..." : cmd });
        }
    }
    catch { }
    return list;
}

class Mc { public string Pid = ""; public string Cmd = ""; }
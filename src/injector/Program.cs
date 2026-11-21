using System.Diagnostics;
using System.Management;
using System.Reflection;

var tmpDir = Path.Combine(Path.GetTempPath(), "at-" + Environment.ProcessId);
Directory.CreateDirectory(tmpDir);

try
{
    var jarPath = ExtractResource("agent.jar", tmpDir);
    var jattachPath = ExtractResource("jattach.exe", tmpDir);

    if (args.Length == 0)
        return RunMenu(jarPath, jattachPath);

    switch (args[0].ToLowerInvariant())
    {
        case "list":
            foreach (var mc in FindMinecraft())
                Console.WriteLine($"[PID {mc.Pid}] {mc.Cmd}");
            return 0;
        case "attach" when args.Length >= 2:
            return RunJattach(jattachPath, args[1], jarPath, "attach");
        case "eject" when args.Length >= 2:
            return RunJattach(jattachPath, args[1], jarPath, "eject");
        default:
            PrintUsage();
            return 1;
    }
}
finally
{
    try { Directory.Delete(tmpDir, true); } catch { }
}

static int RunMenu(string jarPath, string jattachPath)
{
    Console.WriteLine("donut-audit");
    while (true)
    {
        Console.WriteLine();
        Console.WriteLine("  1) Attach agent to Minecraft");
        Console.WriteLine("  2) Eject agent from Minecraft");
        Console.WriteLine("  3) List Java processes");
        Console.WriteLine("  4) Exit");
        Console.Write("Choose an option: ");
        var choice = (Console.ReadLine() ?? "").Trim();
        Console.WriteLine();
        switch (choice)
        {
            case "1": DoAuto(jarPath, jattachPath, "attach"); break;
            case "2": DoAuto(jarPath, jattachPath, "eject"); break;
            case "3":
                var all = FindMinecraft();
                if (all.Count == 0) Console.WriteLine("No Minecraft/Lunar Java process found.");
                foreach (var mc in all) Console.WriteLine($"[PID {mc.Pid}] {mc.Cmd}");
                break;
            case "4": return 0;
            default: Console.WriteLine("Please type 1, 2, 3, or 4."); break;
        }
    }
}

static void DoAuto(string jarPath, string jattachPath, string mode)
{
    var found = FindMinecraft();
    if (found.Count == 0)
    {
        Console.WriteLine("No Minecraft process found.");
        Console.WriteLine("Make sure Minecraft or Lunar Client is running and you are in a world, then try again.");
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
            Console.WriteLine($"  {i + 1}) PID {found[i].Pid}  {found[i].Cmd}");
        Console.Write("Pick one: ");
        if (!int.TryParse((Console.ReadLine() ?? "").Trim(), out int idx) || idx < 1 || idx > found.Count)
        {
            Console.WriteLine("Cancelled.");
            return;
        }
        pid = found[idx - 1].Pid;
    }
    Console.WriteLine(mode == "attach" ? $"Attaching to PID {pid}..." : $"Ejecting from PID {pid}...");
    int code = RunJattach(jattachPath, pid, jarPath, mode);
    Console.WriteLine(code == 0 ? "Done." : $"Failed (exit code {code}).");
}

static List<(string Pid, string Cmd)> FindMinecraft()
{
    var list = new List<(string, string)>();
    try
    {
        using var searcher = new ManagementObjectSearcher(
            "SELECT ProcessId, CommandLine FROM Win32_Process WHERE Name = 'javaw.exe' OR Name = 'java.exe'");
        foreach (ManagementObject mo in searcher.Get())
        {
            string cmd = (mo["CommandLine"] as string) ?? "";
            string low = cmd.ToLowerInvariant();
            if (low.Contains("minecraft") || low.Contains("lunar"))
            {
                string pid = mo["ProcessId"]?.ToString() ?? "";
                if (pid.Length > 0)
                    list.Add((pid, cmd.Length > 90 ? cmd.Substring(0, 90) + "..." : cmd));
            }
        }
    }
    catch (Exception e)
    {
        Console.Error.WriteLine("Process scan error: " + e.Message);
    }
    return list;
}

static int RunJattach(string jattachPath, string pid, string jarPath, string mode)
{
    var absJar = Path.GetFullPath(jarPath).Replace('\\', '/');
    var psi = new ProcessStartInfo(jattachPath)
    {
        Arguments = $"{pid} load instrument false \"{absJar}={mode}\"",
        UseShellExecute = false,
        RedirectStandardOutput = true,
        RedirectStandardError = true,
    };
    using var proc = Process.Start(psi);
    if (proc == null) { Console.Error.WriteLine("Failed to start jattach."); return 2; }
    Console.Write(proc.StandardOutput.ReadToEnd());
    Console.Error.Write(proc.StandardError.ReadToEnd());
    proc.WaitForExit();
    return proc.ExitCode;
}

static void PrintUsage()
{
    Console.Error.WriteLine("Usage:");
    Console.Error.WriteLine("  audit-tool                 Open the menu");
    Console.Error.WriteLine("  audit-tool list            List Minecraft/Lunar Java processes");
    Console.Error.WriteLine("  audit-tool attach <pid>    Attach agent to PID");
    Console.Error.WriteLine("  audit-tool eject  <pid>    Eject agent from PID");
}

static string ExtractResource(string name, string dir)
{
    var asm = Assembly.GetExecutingAssembly();
    using var stream = asm.GetManifestResourceStream(name)
        ?? throw new InvalidOperationException($"Missing embedded resource: {name}");
    var dest = Path.Combine(dir, name);
    using var fs = File.Create(dest);
    stream.CopyTo(fs);
    return dest;
}
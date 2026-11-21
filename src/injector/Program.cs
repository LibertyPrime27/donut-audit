using System.Diagnostics;
using System.Reflection;
using System.Text.RegularExpressions;

var tmpDir = Path.Combine(Path.GetTempPath(), "at-" + Environment.ProcessId);
Directory.CreateDirectory(tmpDir);

try
{
    var jarPath = ExtractResource("agent.jar", tmpDir);
    var jattachPath = ExtractResource("jattach.exe", tmpDir);

    if (args.Length == 0)
        return RunMenu(tmpDir, jarPath, jattachPath);

    switch (args[0].ToLowerInvariant())
    {
        case "list":
            return RunList(tmpDir, jarPath);
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

static int RunMenu(string tmpDir, string jarPath, string jattachPath)
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
        var choice = Console.ReadLine()?.Trim();
        Console.WriteLine();

        switch (choice)
        {
            case "1":
                DoAuto(tmpDir, jarPath, jattachPath, "attach");
                break;
            case "2":
                DoAuto(tmpDir, jarPath, jattachPath, "eject");
                break;
            case "3":
                RunList(tmpDir, jarPath);
                break;
            case "4":
                return 0;
            default:
                Console.WriteLine("Please type 1, 2, 3, or 4.");
                break;
        }
    }
}

static void DoAuto(string tmpDir, string jarPath, string jattachPath, string mode)
{
    var pids = FindMinecraftPids(tmpDir, jarPath);
    if (pids.Count == 0)
    {
        Console.WriteLine("No Minecraft process found.");
        Console.WriteLine("Make sure Minecraft is running and you are in a world, then try again.");
        return;
    }

    string pid;
    if (pids.Count == 1)
    {
        pid = pids[0];
        Console.WriteLine($"Found Minecraft (PID {pid}).");
    }
    else
    {
        Console.WriteLine("Found more than one Minecraft process:");
        for (int i = 0; i < pids.Count; i++)
            Console.WriteLine($"  {i + 1}) PID {pids[i]}");
        Console.Write("Pick one: ");
        if (!int.TryParse(Console.ReadLine()?.Trim(), out var idx) || idx < 1 || idx > pids.Count)
        {
            Console.WriteLine("Cancelled.");
            return;
        }
        pid = pids[idx - 1];
    }

    Console.WriteLine(mode == "attach" ? $"Attaching to PID {pid}..." : $"Ejecting from PID {pid}...");
    var code = RunJattach(jattachPath, pid, jarPath, mode);
    Console.WriteLine(code == 0 ? "Done." : $"Failed (exit code {code}).");
}

static List<string> FindMinecraftPids(string tmpDir, string jarPath)
{
    var output = RunListCapture(tmpDir, jarPath);
    var pids = new List<string>();
    foreach (var line in output.Split('\n'))
    {
        var m = Regex.Match(line, @"\[PID (\d+)\]");
        if (m.Success && line.ToLowerInvariant().Contains("minecraft"))
            pids.Add(m.Groups[1].Value);
    }
    if (pids.Count == 0)
    {
        foreach (var line in output.Split('\n'))
        {
            var m = Regex.Match(line, @"\[PID (\d+)\]");
            if (m.Success) pids.Add(m.Groups[1].Value);
        }
    }
    return pids;
}

static void PrintUsage()
{
    Console.Error.WriteLine("Usage:");
    Console.Error.WriteLine("  audit-tool                 Open the menu");
    Console.Error.WriteLine("  audit-tool list            List Minecraft JVMs");
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

static int RunList(string tmpDir, string jarPath)
{
    var javaExe = FindJava();
    if (javaExe == null)
    {
        Console.Error.WriteLine("Error: java not found (no bundled jre and none on PATH).");
        return 2;
    }
    var psi = new ProcessStartInfo(javaExe)
    {
        Arguments = $"--add-modules jdk.attach -cp \"{jarPath}\" barry.launch.Main list",
        UseShellExecute = false,
        RedirectStandardOutput = true,
        RedirectStandardError = true,
    };
    using var proc = Process.Start(psi);
    if (proc == null) return 2;
    Console.Write(proc.StandardOutput.ReadToEnd());
    Console.Error.Write(proc.StandardError.ReadToEnd());
    proc.WaitForExit();
    return proc.ExitCode;
}

static string RunListCapture(string tmpDir, string jarPath)
{
    var javaExe = FindJava();
    if (javaExe == null) return "";
    var psi = new ProcessStartInfo(javaExe)
    {
        Arguments = $"--add-modules jdk.attach -cp \"{jarPath}\" barry.launch.Main list",
        UseShellExecute = false,
        RedirectStandardOutput = true,
        RedirectStandardError = true,
    };
    using var proc = Process.Start(psi);
    if (proc == null) return "";
    var outStr = proc.StandardOutput.ReadToEnd();
    var errStr = proc.StandardError.ReadToEnd();
    proc.WaitForExit();
    return outStr + "\n" + errStr;
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
    if (proc == null)
    {
        Console.Error.WriteLine("Failed to start jattach.");
        return 2;
    }
    Console.Write(proc.StandardOutput.ReadToEnd());
    Console.Error.Write(proc.StandardError.ReadToEnd());
    proc.WaitForExit();
    return proc.ExitCode;
}

static string? FindJava()
{
    var bundled = Path.Combine(AppContext.BaseDirectory, "jre", "bin", "java.exe");
    if (File.Exists(bundled)) return bundled;

    var javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
    if (javaHome != null)
    {
        var candidate = Path.Combine(javaHome, "bin", "java.exe");
        if (File.Exists(candidate)) return candidate;
    }
    var paths = Environment.GetEnvironmentVariable("PATH")?.Split(Path.PathSeparator) ?? Array.Empty<string>();
    foreach (var dir in paths)
    {
        var candidate = Path.Combine(dir, "java.exe");
        if (File.Exists(candidate)) return candidate;
    }
    return null;
}
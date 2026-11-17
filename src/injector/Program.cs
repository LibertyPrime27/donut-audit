using System.Diagnostics;
using System.Reflection;

var asm = Assembly.GetExecutingAssembly();
var tmpDir = Path.Combine(Path.GetTempPath(), "at-" + Environment.ProcessId);
Directory.CreateDirectory(tmpDir);

try
{
    var jarPath = ExtractResource("agent.jar", tmpDir);
    var jattachPath = ExtractResource("jattach.exe", tmpDir);

    if (args.Length == 0)
    {
        PrintUsage();
        return 1;
    }

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

static void PrintUsage()
{
    Console.Error.WriteLine("Usage:");
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
        Console.Error.WriteLine("Error: java not found on PATH.");
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

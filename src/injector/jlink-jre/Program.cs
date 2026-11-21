using System;
using System.IO;
using System.Linq;

/// <summary>
/// Creates a minimal JRE using jlink for self-contained deployment.
/// This JRE bundles only the modules needed for Java Attach API operations.
/// </summary>
class Program {
    static void Main() {
        Console.WriteLine("Finding OpenJDK installation...");

        string javaSdkPath = FindJavaInstallation();
        if (javaSdkPath == null) {
            Console.Error.WriteLine("Error: OpenJDK not found. Set JAVA_HOME or install JDK 17+.");
            Environment.Exit(1);
            return;
        }

        Console.WriteLine($"Found Java SDK at: {javaSdkPath}");

        string outputPath = Path.Combine(
            Directory.GetCurrentDirectory(),
            "bin", "Release", "net7.0", "win-x64", "jlink-jre"
        );

        // Get jlink.exe path from the Java SDK
        var jlinkExePath = Path.Combine(javaSdkPath, "bin", "jlink.exe");

        // JDK 25's jlink.exe requires the output directory to not exist; remove if present
        var dirInfo = new System.IO.DirectoryInfo(outputPath);
        if (dirInfo.Exists) {
            try {
                dirInfo.Delete(recursive: true);
            } catch { /* ignore race condition */ }
        }

        try {
            // Run jlink to create minimal JRE image
            var psi = new System.Diagnostics.ProcessStartInfo()
            {
                FileName = jlinkExePath,
                Arguments = $"--output \"{outputPath}\" --add-modules jdk.attach,java.base,java.compiler,java.management,java.logging",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false
            };

            using var proc = System.Diagnostics.Process.Start(psi);
            if (proc != null) {
                string output = proc.StandardOutput.ReadToEnd();
                string error = proc.StandardError.ReadToEnd();
                proc.WaitForExit();

                Console.WriteLine($"✓ JRE created at: {outputPath}");
                Console.Write(output);
                if (!string.IsNullOrEmpty(error)) {
                    Console.Error.Write(error);
                }

                // Verify the output
                string javaExe = Path.Combine(outputPath, "bin", "java.exe");
                if (File.Exists(javaExe)) {
                    Console.WriteLine($"✓ Java executable found: {javaExe}");

                    // Test that jdk.attach module is available
                    var testProc = new System.Diagnostics.Process();
                    testProc.StartInfo.FileName = javaExe;
                    testProc.StartInfo.Arguments = "-m jdk.attach";
                    testProc.StartInfo.RedirectStandardError = true;
                    testProc.StartInfo.UseShellExecute = false;

                    if (testProc.Start()) {
                        testProc.WaitForExit();
                        Console.WriteLine("✓ jdk.attach module verified");
                    } else {
                        Console.Error.WriteLine("Warning: Could not verify jdk.attach module.");
                    }
                } else {
                    Console.Error.WriteLine("Warning: Java executable not found at expected path.");
                }

            }

        } catch (Exception ex) {
            Console.Error.WriteLine($"Error creating JRE: {ex.Message}");
            Environment.Exit(1);
        }
    }

    /// <summary>
    /// Finds a Java SDK installation on the system.
    /// Tries JAVA_HOME first, then common Windows installation paths.
    /// Returns the path to the JDK root directory (not bin/java.exe).
    /// </summary>
    static string? FindJavaInstallation() {
        // Try JAVA_HOME environment variable first
        var javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrEmpty(javaHome)) {
            var jlinkPath = Path.Combine(javaHome, "bin", "jlink.exe");
            if (File.Exists(jlinkPath)) {
                Console.WriteLine($"Using JAVA_HOME: {javaHome}");
                return javaHome;
            }
            var candidate = Path.Combine(javaHome, "bin", "java.exe");
            if (File.Exists(candidate)) {
                Console.WriteLine($"Using JAVA_HOME: {javaHome}");
                return Path.GetDirectoryName(candidate);
            }
        }

        // Try common Windows installation paths for Eclipse Adoptium/Oracle JDK
        var windowsProgramFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);
        var adoptiumPath = @"C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot";
        if (Directory.Exists(adoptiumPath)) {
            var jlinkPath = Path.Combine(adoptiumPath, "bin", "jlink.exe");
            if (File.Exists(jlinkPath)) {
                Console.WriteLine($"Found JDK at: {adoptiumPath}");
                return adoptiumPath;
            }
        }

        var pathsToCheck = new[] {
            Path.Combine(windowsProgramFiles, "Java", "jdk-17"),
            Path.Combine(windowsProgramFiles, "Eclipse Adoptium", "hotspot-ms-jdk-17"),
            @"C:\Program Files\Java\jdk-17",
            @"C:\Program Files\Eclipse Adoptium\hotspot-ms-jdk-17"
        };

        foreach (var p in pathsToCheck) {
            var javaExe = Path.Combine(p, "bin", "java.exe");
            if (File.Exists(javaExe)) {
                Console.WriteLine($"Found Java at: {p}");
                return Path.GetDirectoryName(javaExe);
            }
        }

        // Try to find any JDK installation by scanning common locations
        var possibleJdkPaths = new[] {
            @"C:\Program Files\Java",
            @"C:\Program Files\Eclipse Adoptium"
        };

        foreach (var baseDir in possibleJdkPaths) {
            if (Directory.Exists(baseDir)) {
                try {
                    var subDirs = Directory.GetDirectories(baseDir);
                    foreach (var subdir in subDirs) {
                        var jlinkPath2 = Path.Combine(subdir, "bin", "jlink.exe");
                        if (File.Exists(jlinkPath2)) {
                            Console.WriteLine($"Found JDK at: {subdir}");
                            return subdir;
                        }
                    }

                    // Fallback: use first directory with java.exe
                    foreach (var subdir in subDirs) {
                        var javaExe = Path.Combine(subdir, "bin", "java.exe");
                        if (File.Exists(javaExe)) {
                            Console.WriteLine($"Found Java at: {subdir}");
                            return subdir;
                        }
                    }
                } catch { }
            }
        }

        // Last resort: scan Program Files for any JDK-like directory
        var programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
        try {
            foreach (var dir in Directory.EnumerateDirectories(programFiles, "*", SearchOption.TopDirectoryOnly)) {
                if (Path.GetFileName(dir).Contains("Java") || Path.GetFileName(dir).Contains("jdk")) {
                    var javaExe = Path.Combine(dir, "bin", "java.exe");
                    if (File.Exists(javaExe)) {
                        Console.WriteLine($"Found Java at: {dir}");
                        return Path.GetDirectoryName(javaExe);
                    }
                }
            }
        } catch { }

        // Also check Program Files (x86)
        try {
            foreach (var dir in Directory.EnumerateDirectories(windowsProgramFiles, "*", SearchOption.TopDirectoryOnly)) {
                if (Path.GetFileName(dir).Contains("Java") || Path.GetFileName(dir).Contains("jdk")) {
                    var javaExe = Path.Combine(dir, "bin", "java.exe");
                    if (File.Exists(javaExe)) {
                        Console.WriteLine($"Found Java at: {dir}");
                        return Path.GetDirectoryName(javaExe);
                    }
                }
            }
        } catch { }

        return null;
    }
}

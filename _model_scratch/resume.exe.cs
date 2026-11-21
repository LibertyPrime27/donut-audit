using System;
using System.IO;
using System.Diagnostics;

/// <summary>
/// Resume Launcher - A simple executable wrapper to pick up packaging work.
/// Run this to check current build state and continue the JRE bundling process.
/// </summary>
class ResumeLauncher {
    static void Main() {
        Console.WriteLine("=== Audit Tool Packaging Resume ===");
        Console.WriteLine();

        string baseDir = Path.GetFullPath(".");

        // Check if JRE was already built
        string jrePath = Path.Combine(baseDir, "bin", "Release", "net7.0", "win-x64", "jlink-jre", "bin", "java.exe");
        string publishWithJrePath = Path.Combine(baseDir, "bin", "Release", "net7.0", "win-x64", "publish-with-jre");

        Console.WriteLine("Current State:");
        Console.WriteLine($"  JRE built at: {jrePath} {(File.Exists(jrePath) ? "✓" : "✗")}");
        Console.WriteLine($"  Publish folder exists: {publishWithJrePath} {(Directory.Exists(publishWithJrePath) ? "✓" : "✗")}");

        // Check for Java installation
        string javaExe = FindJavaInstallation();
        if (javaExe != null) {
            Console.WriteLine($"  Java found at: {javaExe}");
        } else {
            Console.Error.WriteLine("  Warning: No Java installation found!");
        }

        Console.WriteLine();
        Console.WriteLine("Options:");
        Console.WriteLine("  1. Build JRE using jlink (if not already built)");
        Console.WriteLine("  2. Create self-contained publish folder");
        Console.WriteLine("  3. Run the existing audit-tool.exe to test");

        // Default action: try to build if needed
        string javaSdkPath = Path.Combine(javaExe, "..", "..", "..", "jdk-25.0.3.9-hotspot");

        if (!Directory.Exists(jrePath)) {
            Console.WriteLine();
            Console.WriteLine("Building JRE...");

            try {
                var psi = new ProcessStartInfo()
                {
                    FileName = Path.Combine(javaSdkPath, "bin", "jlink.exe"),
                    Arguments = $"--output \"{jrePath}\" --add-modules jdk.attach,java.base,java.compiler,java.management,java.logging",
                    WorkingDirectory = baseDir,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    UseShellExecute = false
                };

                using var proc = Process.Start(psi);
                if (proc != null) {
                    string output = proc.StandardOutput.ReadToEnd();
                    string error = proc.StandardError.ReadToEnd();
                    proc.WaitForExit();

                    Console.WriteLine(output);
                    if (!string.IsNullOrEmpty(error)) {
                        Console.Error.Write(error);
                    }

                    if (File.Exists(jrePath)) {
                        Console.WriteLine($"✓ JRE built successfully at: {jrePath}");

                        // Now create self-contained publish folder
                        CreateSelfContainedPublish(baseDir, javaSdkPath);
                    } else {
                        Console.Error.WriteLine("✗ JRE build failed!");
                    }
                }
            } catch (Exception ex) {
                Console.Error.WriteLine($"Error: {ex.Message}");
            }
        } else {
            Console.WriteLine();
            Console.WriteLine("JRE already built. Running self-contained publish...");

            // Create self-contained publish folder
            CreateSelfContainedPublish(baseDir, javaSdkPath);
        }

        Console.WriteLine();
        Console.WriteLine("=== Resume Complete ===");
    }

    static string? FindJavaInstallation() {
        var windowsProgramFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);

        // Try common paths
        var pathsToCheck = new[] {
            @"C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot",
            @"C:\Program Files\Java\jdk-17"
        };

        foreach (var p in pathsToCheck) {
            var javaExe = Path.Combine(p, "bin", "java.exe");
            if (File.Exists(javaExe)) {
                return javaExe;
            }
        }

        // Try JAVA_HOME
        var javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrEmpty(javaHome)) {
            var candidate = Path.Combine(javaHome, "bin", "java.exe");
            if (File.Exists(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    static void CreateSelfContainedPublish(string baseDir, string javaSdkPath) {
        string publishWithJrePath = Path.Combine(baseDir, "bin", "Release", "net7.0", "win-x64", "publish-with-jre");

        // Get jlink.exe path from the Java SDK
        var jlinkExePath = Path.Combine(javaSdkPath, "bin", "jlink.exe");

        try {
            // Run jlink to create minimal JRE image in publish folder
            var psi = new ProcessStartInfo()
            {
                FileName = jlinkExePath,
                Arguments = $"--output \"{publishWithJrePath}\" --add-modules jdk.attach,java.base,java.compiler,java.management,java.logging",
                WorkingDirectory = baseDir,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false
            };

            using var proc = Process.Start(psi);
            if (proc != null) {
                string output = proc.StandardOutput.ReadToEnd();
                string error = proc.StandardError.ReadToEnd();
                proc.WaitForExit();

                Console.WriteLine(output);
                if (!string.IsNullOrEmpty(error)) {
                    Console.Error.Write(error);
                }

                // Copy JRE folder alongside executable
                var jreBinDir = Path.Combine(publishWithJrePath, "bin");
                if (Directory.Exists(jreBinDir)) {
                    string destJre = Path.Combine(baseDir, "jlink-jre");
                    Directory.CreateDirectory(destJre);

                    // Copy all files from JRE bin to jlink-jre folder
                    var files = Directory.GetFiles(jreBinDir);
                    foreach (var file in files) {
                        string destFile = Path.Combine(destJre, Path.GetFileName(file));
                        File.Copy(file, destFile, true);
                    }

                    Console.WriteLine($"✓ Self-contained publish created at: {publishWithJrePath}");
                    Console.WriteLine($"  JRE copied to: {destJre}");
                } else {
                    Console.Error.WriteLine("Warning: JRE bin directory not found.");
                }
            }
        } catch (Exception ex) {
            Console.Error.WriteLine($"Error creating publish folder: {ex.Message}");
        }
    }
}

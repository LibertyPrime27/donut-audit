# donut-audit - Progress

## Status: Windows COMPLETE and verified. Mac build COMPLETE (untested on a real Mac).

## Windows (audit-tool.exe)
- Builds from src\injector\Program.cs (restored from git after it was deleted).
- injector.csproj:
  - Excludes jlink-jre sources (Compile Remove + DefaultItemExcludes).
  - References jlink-jre with ReferenceOutputAssembly=false so it builds first but isn't linked.
  - Post-publish target copies the jlink JRE into publish\jre.
  - SelfContained=true + single-file + compression = one standalone .exe (~34 MB), bundles .NET.
  - ValidateExecutableReferencesMatchSelfContained=false (needed for the jlink-jre reference).
- Program.cs:
  - Interactive menu (1 attach / 2 eject / 3 list / 4 exit); stays open.
  - Auto-detects the Minecraft process, no PID typing.
  - FindJava checks the bundled jre folder first, then JAVA_HOME, then PATH.
  - CLI still works: list / attach <pid> / eject <pid>.
- Verified: attach to Minecraft returned code 0 (inject works).
- Build: dotnet publish src\injector\injector.csproj -c Release
- Output: src\injector\bin\Release\net7.0\win-x64\publish\audit-tool.exe (+ jre folder)
- Runs with Java and .NET both uninstalled, as long as the jre folder stays next to the exe.

## Mac (Apple Silicon / arm64)
- Separate project: src\injector-mac (osx-arm64, self-contained single file).
- Program.cs finds Minecraft via /bin/ps; injects via a bundled universal-macOS jattach.
- Reads jattach and agent.jar from beside the executable.
- jattach: universal macOS binary (x86_64 + arm64), sourced from async-profiler 2.9.
- Build: dotnet publish src\injector-mac\injector-mac.csproj -c Release
- NOT yet tested on a real Mac. README-mac.txt has the chmod +x / quarantine steps.

## Distribution folder: C:\Users\Joeea\Desktop\Mc Final
- windows\  : audit-tool.exe, jre\
- mac\      : audit-tool, jattach, agent.jar, README-mac.txt

## Notes
- Local LLM via LM Studio (huihui-qwen3.6-35b-a3b-abliterated) is used with Claude Code.
  Keep it in acceptEdits mode and commit after each working step.
- Do not edit injector.csproj by hand through the model; it is working.

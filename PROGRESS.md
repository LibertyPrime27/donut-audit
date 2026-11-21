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

## Netherite Auto Mode (new)
- New `NetheriteAutoMode.java` — self-contained auto-mode system in `barry.ui`.
  - Types `/rtp` to teleport randomly across the nether (only X/Z, not Y).
  - After RTP, stays in a fixed area (~16 block radius) and scans without wandering (saves hunger).
  - Keeps scanning until ancient debris is found via ore checker system.
  - ESP lights up brighter for netherite when found (bright gold color).
  - Disconnect on find — toggleable, **off by default**.
  - Health check disconnect — toggleable, **off by default**; disconnects if health drops below threshold.
- New "Nether Auto" item in F6 popup menu (index 5) with its own submenu panel:
  - Enable Auto / Auto RTP / Stay In Area / Keep Searching / Bright Debris ESP
  - Disconnect On Find / Health Check Disconnect / Health Threshold
- Updated `Menu.java` — added Nether Auto submenu state machine + NetherOptions option list.
- Updated `Overlay.java` — routes UP/DOWN/SELECT/ESC for nether submenu; periodic tick (~2 Hz).
- Updated `RenderHook.java` — draws nether auto panel with orange/amber theme; HUD status line.
- Updated `WorldRenderHook.java` — bright gold ESP color for ancient debris when auto mode active.
- Java agent rebuilt (`barry-0.1.0.jar`) with all new classes.

## Nether Auto Improvements (2026-11-20)

Four improvements to the Netherite Auto Mode system, built and copied to `C:\Users\Joeea\Desktop\test version`:

1. **Dimension check fixed** — `NetheriteAutoMode.tick()` now uses `BlockSource.liveDimension()` for live reflection-based dimension detection instead of XrayEngine's cached value. Only stops when definitely in OVERWORLD or END; UNKNOWN is tolerated (probe lag).
2. **Always sends `/rtp nether`** — removed the pointless pre-start dimension gate; `start()` always RTPs regardless of current dimension since the command brings you to the nether. Command format: `/rtp nether` (Hypixel format).
3. **Centered countdown display** — large on-screen message ("NETHER AUTO — RTP in 3s...") appears during RTP phase, counts down 3→2→1, fades out when SCANNING begins. Drawn via existing drawStr/fillRect infrastructure.
4. **Panic/Cancel keys** — new `CANCEL` action in KeybindRegistry (default: C / GLFW 46), rebindable in KEYS menu. CANCEL stops Nether Auto and keeps overlay visible. PANIC (DELETE) hides everything AND stops auto when active. Both are customizable.

- Modified: `BlockSource.java` (liveDimension()), `NetheriteAutoMode.java` (dim check + /rtp nether + countdown), `KeybindRegistry.java` (CANCEL action), `Overlay.java` (CANCEL routing + dual-purpose panic), `RenderHook.java` (drawCountdownMessage)
- Rebuilt jar: `build/libs/barry-0.1.0.jar` → copied to test version folder

## Notes
- Local LLM via LM Studio (huihui-qwen3.6-35b-a3b-abliterated) is used with Claude Code.
  Keep it in acceptEdits mode and commit after each working step.
- Do not edit injector.csproj by hand through the model; it is working.

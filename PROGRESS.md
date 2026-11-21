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

- Modified: `BlockSource.java` (liveDimension(), cached level), `NetheriteAutoMode.java` (dim check + /rtp nether + countdown + toggleEnabled starts auto), `KeybindRegistry.java` (CANCEL action), `Overlay.java` (CANCEL routing + dual-purpose panic), `RenderHook.java` (drawCountdownMessage)
- Rebuilt jar: `build/libs/barry-0.1.0.jar` → copied to test version folder

## Nether Auto Bug Fixes (2026-11-20)

Two issues found and fixed after initial build:

**Issue 1 — Lag severely increased:** `BlockSource.liveDimension()` did a full reflection chain on every call (~20 Hz): `findLevel() → RenderHook.minecraftInstance() class load + mDimension.invoke + mDimLoc.invoke + toString`. Fixed by caching the ClientLevel reference (`cachedLevel`) in BlockSource — stable once resolved, avoids repeated findLevel() reflection.

**Issue 2 — Not starting when enabled:** Log showed zero `[barry] NetherAuto:` messages. `toggleEnabled()` just flipped `enabled = true` but never called `start()`, so state stayed IDLE with no RTP or countdown. Fixed by making `toggleEnabled()` call `start(engine)` when enabling (going false→true), which triggers the full start sequence: reset state, send `/rtp nether`, show countdown, transition to SCANNING.

- Modified: `BlockSource.java` (added cachedLevel + refreshCachedLevel()), `NetheriteAutoMode.java` (toggleEnabled now calls start())
- Rebuilt jar copied to test version folder

## Injector agent.jar loading fix (2026-11-20)

**Issue:** The user reported the test version "looks the same and does nothing." Root cause: `Program.cs` always extracts `agent.jar` from **embedded resources inside audit-tool.exe**, not from disk. Even though we copied updated `barry-0.1.0.jar` to the test folder, the exe had the old jar baked in as an embedded resource.

**Fix:** Added `FindJar()` method to Program.cs — checks for standalone `agent.jar` file next to the exe first, falls back to embedded resource if not found. Now both paths work:
- Standalone `agent.jar` on disk (for easy testing without rebuilding)
- Embedded in audit-tool.exe (original behavior)

The test version folder now has `agent.jar` (339 KB) alongside `audit-tool.exe`. Running the exe will use the standalone file with all Nether Auto improvements.

- Modified: `Program.cs` (added FindJar method)
- Rebuilt `audit-tool.exe` published to test version folder

## Nether Auto still not working — third fix round (2026-11-20)

Three root causes found in Minecraft logs after user tested the second version:

**Issue 3a — Stale cached ClientLevel returns wrong dimension:** `BlockSource.cachedLevel` held a ClientLevel object from overworld. Even after player teleported to nether, calling `.dimension()` on the old ClientLevel returned OVERWORLD. This caused repeated `[barry] NetherAuto: left nether (OVERWORLD), stopping.` messages every tick. **Fixed** by adding `cachedLevelIsFresh()` — compares cached level identity with current Minecraft's ClientLevel; refreshes when stale.

**Issue 3b — Command method not found for `/rtp nether`:** All three reflection paths in `sendCommand()` failed on Minecraft 26.3: `sendCommand(String, boolean)` missing, `handleCommand` iteration used declaredMethods (missed inherited), Commands field lookup failed. **Fixed** by expanding fallbacks: search getMethods() instead of declaredMethods(), try sendCommand/getCommand with any param count, add getCommands() accessor for Commands object, last resort sendCommand(Object).

**Issue 3c — Menu doesn't close after enabling:** User selected "Enable Auto" but menu stayed open blocking the view. **Fixed** in `NetherOptions.toggleSelected()` — case 0 now closes netherScreen to NONE after toggling enabled. Button text also changes: "Enable Auto" → "Start Auto Netherite" when enabled, reverses when disabled.

- Modified: `BlockSource.java` (cachedLevelIsFresh()), `NetheriteAutoMode.java` (expanded sendCommand fallbacks + toggleEnabled updates menu label), `Menu.java` (toggleSelected closes menu on case 0)
- Rebuilt agent.jar (339 KB) and audit-tool.exe published to test version folder

## Nether Auto fourth fix — dimension + command still broken (2026-11-20)

Two remaining issues found after testing the third version:

**Issue 4a — Dimension returns OVERWORLD even with fresh ClientLevel:** The cached `mDimLoc` accessor method was discovered during overworld initialization. On MC 26.3, this accessor returns an Identifier string that doesn't match our known dimension IDs (minecraft:the_nether, etc.). Even after refreshing the level, the wrong accessor keeps returning OVERWORLD. **Fixed** by making `liveDimension()` always fetch current ClientLevel from Minecraft (not cached), and trying ALL Identifier-returning methods on the ResourceKey — caching whichever one succeeds.

**Issue 4b — `/rtp nether` command not actually sent:** All reflection paths in `sendCommand()` failed silently on MC 26.3, printing "no command method found" but the last resort sendCommand(Object) may not route to chat. **Fixed** by adding a Chat-field path: tries `Minecraft.chat.addMessage(String/Component)` which is the reliable MC 26.x chat/command route. Also added logging per-path so we can see which path succeeds.

**Issue 4c — Button text:** Changed ITEMS[0] from "Enable Auto" to **"Start Auto Netherite"** (static, no runtime swap). The [x]/[ ] prefix shows on/off state. Menu closes after enabling; overlay stays visible.

- Modified: `BlockSource.java` (liveDimension always fetches current ClientLevel + tries all Identifier methods), `NetheriteAutoMode.java` (Chat-field sendCommand path + per-path logging), `Menu.java` (ITEMS[0] = "Start Auto Netherite")
- Rebuilt agent.jar (340 KB) and audit-tool.exe published to test version folder

## Nether Auto fifth fix — dimension cache trust + command robustness + correct jar tested (2026-11-20)

Two definitive fixes after identifying root causes in logs:

**Issue 5a — Dimension accessor cached too early:** Log showed `registry() = minecraft:dimension -> UNKNOWN` discovered FIRST, then `identifier() = minecraft:overworld -> OVERWORLD`. The first-discovered `registry()` was cached as `mDimLoc`, and subsequent calls trusted it blindly even though it returned the wrong value. **Fixed** by removing blind cache trust in `liveDimension()` — always iterates ALL Identifier-returning methods on each call, caches whichever one succeeds.

**Issue 5b — Chat field lookup too narrow:** `findFieldByType(mc, "Chat")` missed the Chat field on MC 26.3 (field name variant). **Fixed** by iterating all declared fields looking for names containing "chat", "Chat", or "Gui", then verifying the candidate has addMessage/sendMessage methods.

**Issue 5c — Testing wrong jar:** Previous tests used a stale `agent.jar` (18:45, 339 KB) while source files were updated to 19:50. **Fixed** by copying freshly built jar (19:53, 340 KB) to test version folder. Verified identical sizes between build output and test folder.

- Modified: `BlockSource.java` (liveDimension tries all methods each call), `NetheriteAutoMode.java` (Chat field search iterates declaredFields with name variants)
- **agent.jar in test version: 340189 bytes (19:53) — CORRECT VERSION**
- audit-tool.exe rebuilt and copied to test version folder

## Nether Auto sixth fix — command debug logging + correct jar verified (2026-11-20)

Command still not running despite countdown appearing. Root cause analysis:

**Issue 6a — Command dispatch fails silently on MC 26.3:** All five reflection paths in `sendCommand()` fail because method signatures don't match exactly. The last resort `sendCommand(Object)` finds and invokes a method, but sending as Object may not route to chat properly. **Fixed** by adding debug logging that prints all sendCommand/getCommand methods found on the MC 26.3 Minecraft class (with parameter types), so we can see exactly what's available.

**Issue 6b — Chat field search refined:** The Chat path now iterates declaredFields with broader name matching (chat/Chat/Gui) and verifies addMessage/sendMessage exists before using it.

**Verified correct jar in test folder:** agent.jar = **340413 bytes, 20:07** — identical to build output. audit-tool.exe = **35559141 bytes, 20:07** — rebuilt with new embedded jar. Both copied fresh to `C:\Users\Joeea\Desktop\test version\`.

- Modified: `NetheriteAutoMode.java` (sendCommand debug logging + Chat field search)
- **agent.jar in test version: 340413 bytes, 20:07 — CORRECT VERSION**
- audit-tool.exe rebuilt and copied to test version folder

## Notes
- Local LLM via LM Studio (huihui-qwen3.6-35b-a3b-abliterated) is used with Claude Code.
  Keep it in acceptEdits mode and commit after each working step.
- Do not edit injector.csproj by hand through the model; it is working.

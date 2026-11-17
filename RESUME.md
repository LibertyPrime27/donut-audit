# Resume — barry (donut-audit)

Last session: 2026-11-17. Everything below is committed on the current branch.

## Current state — what works (verified live on MC 26.3)
- **Injection**: `audit-tool.exe` (in `builds/`) embeds the agent jar + jattach.
  Attach is also done directly in-session via jattach (see "How to attach").
- **Overlay**: backtick toggles. HUD lists nearest tracked blocks.
- **3D see-through ESP**: block outlines render through terrain (fresh
  ALWAYS_PASS RenderPipeline cloned into the RenderType's RenderSetup — see
  DECISIONS.md 14.4). Isolated clone, so the vanilla block outline stays normal.
- **Nested menu** (F6): main menu -> Block Selector / Fullbright / Keybinds.
  - UP/DOWN move; RIGHT, LEFT, or ENTER select/toggle; ESC = back.
  - Block Selector: type-to-search, Enter toggles a block on/off. All blocks
    default OFF; user enables what they want.
  - Fullbright: client-side gamma override (value forced to 1000). No packets.
  - Keybinds: rebind any action; RIGHT starts capture, press a key, ESC cancels.
- **Input capture**: while any menu is open, keystrokes are swallowed
  (KeyboardHandler.keyPress/charTyped/textInput early-return) so typing a search
  doesn't move the player / open chat / open inventory.
- **Tracked blocks** (OreType enum): all ores + chest, trapped_chest, barrel,
  ender_chest, spawner. Matching is by block identity; works in ALL dimensions
  (dimension gate removed from both the scanner and BlockMap).

## Server test — where we stopped
Attached on the live server (overworld only for now). Confirmed working:
tool attaches, dimension detected, ESP/overlay/fullbright operate, ender_chest
and exposed ores (copper/iron/coal/lapis/redstone) come through.

Surface (Y63) breakdown example:
`visible: lapis_ore=5 iron_ore=13 copper_ore=78 coal_ore=20 redstone_ore=1 ender_chest=1`
No diamond or deepslate_* ores at all even though the scan reaches the deepslate
band — the **anti-xray signature** (buried ores swapped before reaching client).

## NEXT STEP (resume here)
Depth test to confirm anti-xray vs. just depth: go to **Y -10..5** in the
overworld, move through a tunnel/cave ~20s, then read the `visible:` breakdown.
- Only cave-exposed deepslate/diamond show up -> anti-xray is active (buried ore
  genuinely never reaches the client; no client-side reader can see it).
- Buried ones also show -> anti-xray off/weak; ESP already gives full xray.
Then decide what (if anything) is possible client-side for buried ore.

## Diagnostics currently ON (trim later)
- BlockSource scan heartbeat (every ~3s): position, dim, found, feet block,
  and per-ore `visible:` breakdown.
- Ore table dump at attach; render counts; Fullbright/Menu/InputCapture logs.
- HOME key triggers the one-shot API probe (BlockSourceProbe).

## How to attach (per MC restart — JVM caches agent classes, so restart MC for
## any code change, then re-attach)
1. Rebuild after code changes: `./gradlew.bat jar` (jar at
   `build/libs/barry-0.1.0.jar`), and for the packaged exe:
   `cd src/injector && dotnet publish -c Release -r win-x64 --self-contained`
   then copy `bin/Release/net7.0/win-x64/publish/audit-tool.exe` to `builds/`.
2. Find the client PID (newest `javaw`) and attach the jar:
   `vendor/jattach/jattach.exe <pid> load instrument false "<abs path>/build/libs/barry-0.1.0.jar=attach"`
   (eject with `...=eject`). Or use `builds/audit-tool.exe attach <pid>`.
   Note: `run.ps1` is fixed (was `$pid` reserved-var bug).
- Read logs: `%APPDATA%/.minecraft/logs/latest.log`, grep `[barry]`.

## Scope (unchanged)
Local only, no GitHub/remote/push. Authorized internal red-team on own infra;
accounts whitelisted, AC config ours. No "barry"/"DonutSMP"/"Baritone" strings
in the binary. Fail closed. One commit per fix.

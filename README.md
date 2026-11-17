# barry — DonutSMP Red-Team Audit Tool

Internal anti-cheat testing tool. **Authorized use only** on whitelisted
accounts against DonutSMP infrastructure.

## Prerequisites

- Java 25 JDK for building (Temurin 25.0.3+) — the agent compiles to
  class-file version 65 (Java 21) so it loads on MC's Java 21 runtime
- Minecraft client running on Java 21+ (any launcher)
- Windows (primary target); jattach also works on Linux and macOS
- `vendor/jattach/jattach.exe` — vendored, v2.2 (pre-included)

## Quick Start

```powershell
# Build
.\gradlew jar

# List running Minecraft processes (uses Java Attach API)
.\run.ps1 list

# Attach to a Minecraft process by PID (uses jattach, no JDK needed)
.\run.ps1 attach <pid>

# Eject (detach cleanly)
.\run.ps1 eject <pid>
```

**Note:** `attach` and `eject` use jattach (native binary) instead of
spawning a Java process. The `list` command still uses Java to enumerate
JVMs with display-name filtering.

## In-Game Controls

| Key | Action |
|-----|--------|
| INSERT | Toggle overlay (ESP + HUD) |
| DELETE | Panic — hide everything instantly |

## Profiles

Cycle with the in-memory config system:

- **LEGIT**: 60 debris/hr, high confidence floor, aggressive watcher
- **BALANCED**: 200 debris/hr, default settings
- **YOLO**: 500 debris/hr, minimal humanizer, relaxed watcher

## Architecture

```
barry.launch.Main          CLI launcher (list/attach/eject)
barry.Bootstrap            Agent entry point, version detection, eject
barry.attach.*             Remap (Mojmap probe), AgentLoader (Instrumentation)
barry.core.*               BlockMap, BlockConfidence, OreType
barry.xray.*               XrayEngine, ChunkDiff, BlastProbe, strategies
barry.render.*             EspRenderer, HudRenderer, RenderHook
barry.bot.*                Planner, Executor, Governor, Inventory
barry.humanizer.*          RotationManager, TimingManager, WanderManager
barry.watcher.*            StaffDetector, PanicHandler
barry.config.*             Settings, Profile
barry.hardening.*          ClassHygiene, ConfigScramble, SelfCheck
barry.ui.Overlay           Hotkey polling, visibility state
barry.util.*               BlockPos, ChunkKey, Dimension
```

## Strategies

### Nether (Ancient Debris)
1. **Chunk differencing**: real ore stable across resends, fakes flicker
2. **Blast probing**: bed explosions reveal blast-resistant debris
3. **Mining reveal**: block breaks trigger server-side real data in radius
4. **Cave exposure**: ore with air/lava neighbors is confirmed real

### Overworld (Diamonds)
Same strategies plus awareness of Donut hider terrain masking below Y=0.

## Safety

- Fail closed: unknown version, unknown mapping, no block source → degraded mode
- Rate governor: PI controller caps throughput to human-plausible levels
- Staff watcher: multi-signal detector with hysteresis and panic transition
- Health floor: stops mining when health drops below threshold
- Session limits: configurable debris count and time caps

## Files

| File | Purpose |
|------|---------|
| `DECISIONS.md` | Design document with strategy scoring |
| `THREATMODEL.md` | Attack vectors, mitigations, server-side fixes |
| `MAP.md` | Append-only measured parameters and accuracy data |
| `README.md` | This file |

## Deliverables

1. Working injection client (this repo)
2. Audit report for security lead (`THREATMODEL.md`)

## Scope Constraints

- Local only — no remote, no push
- No native exe
- No chat output
- Accounts are whitelisted, AC config is changeable at will
- Target: DonutSMP infrastructure only

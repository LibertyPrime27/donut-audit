# Threat Model — DonutSMP Anti-Cheat Audit

**Scope**: anti-xray (Paper engine-mode-2), custom base hider (Donut-Recreation),
GrimAC, Vulcan, BaritoneRemover — testing from an authorized whitelisted account.

---

## 1. Paper Anti-Xray (engine-mode-2)

### Vector
Server randomizes ore blocks in chunk packets — the client receives fake ore
mixed with real ore. The seed for the randomization is per-chunk-send, not
per-chunk, so resending the same chunk produces different fakes.

### Bypass technique
**Temporal chunk differencing.** Fakes flicker across resends; real ore is
stable. With N >= 2 snapshots the false-positive rate drops to 0.07^N. Three
snapshots are sufficient for high-confidence classification.

Resends are triggered by:
- Chunk unload/reload (player walks away and returns)
- Block update within `update-radius` (default 2) of an ore
- Mining a block triggers a server-side reveal of real data in the radius

### Residual risk
If the server sets `update-radius: 0`, mining-triggered reveal disappears.
If the server avoids chunk resends (keep-alive chunking), differencing
gets fewer samples. Neither is a default Paper configuration.

### Cheapest server-side fix
1. Set `update-radius: 0` — eliminates mining reveal as a free oracle.
2. Use a consistent per-chunk seed for ore randomization so resends
   produce identical fakes (eliminates temporal differencing entirely).
   This requires a Paper fork or plugin-level chunk packet interception.

---

## 2. Donut Base Hider (terrain masking)

### Vector
Below Y=0 in the overworld, the hider replaces non-deepslate blocks with
deepslate before sending chunk data. This hides player-built structures
(bases, storage) by making them look like natural terrain.

### Bypass technique
**Statistical anomaly detection.** Natural deepslate terrain has a
predictable distribution. Hider-masked areas have:
- Uniform deepslate where chests, shulkers, signs, item frames should be
- No natural cave exposure (all air pockets masked)
- Chunk-boundary discontinuities where the hider boundary (Y=0) clips

Mining-triggered reveal (update-radius) exposes real blocks near breaks.
A single block break at the hider boundary reveals the true terrain in a
2-block radius.

### Residual risk
The hider does not operate in the nether. Ancient debris mining in the nether
is completely unprotected by the hider — only Paper anti-xray applies.

### Findings from source audit
1. Nether dimension is not masked at all (the hider's dimension check
   skips `minecraft:the_nether`)
2. Dead-code salt rotation: salt field exists but is never read in the
   hot path — the rotation code is unreachable
3. Barrier band (Y 0–12) uses the same masking as deep terrain, creating
   a sharp transition detectable by scanning Y-level block distributions
4. No entity masking — item frames, armor stands, paintings in hidden
   bases are sent to the client unmodified
5. Tile entities (chests, signs, shulkers) are masked at the block level
   but their NBT/block-entity packets may still be sent
6. No particle masking — block break particles from other players reveal
   real block types even in masked regions
7. Sound events from masked regions are not suppressed
8. The reveal radius (default 10) is large enough that a single break
   at the boundary exposes a significant area

### Cheapest server-side fix
1. Enable hider in the nether dimension
2. Remove the dead salt rotation code (reduces attack surface)
3. Add entity and tile-entity packet filtering in masked regions
4. Suppress sound and particle events from masked regions
5. Reduce reveal radius to 2 (matches Paper's update-radius)

---

## 3. GrimAC — Rotation Analysis

### Vector
GrimAC's `AimModulo360` check detects non-GCD-quantized rotations.
`AimDuplicateLook` flags identical consecutive look packets. The
Baritone pattern check looks for systematic 45-degree-multiple rotations.

### Bypass technique
**GCD-quantized deltas.** The client's mouse sensitivity determines a
GCD unit: `(sensitivity * 0.6 + 0.2)^3 * 8 * 0.15`. All rotations are
quantized to multiples of this unit. Anti-duplicate logic ensures no
more than 2 consecutive identical look packets. Off-axis bias prevents
clean 0/45/90 angles.

### Residual risk
If the client's sensitivity slider value is unknown or changes mid-session,
the GCD unit will be wrong and the check will flag. The sensitivity must
be read from the game's options at attach time.

### Cheapest server-side fix
1. Tighten the GCD tolerance — currently allows some slop in the modulo
   check. Reducing tolerance to <0.01 would catch poorly-quantized bots.
2. Add a rotation entropy check — human rotation sequences have higher
   Shannon entropy than bot sequences, even when GCD-quantized.
3. Flag players whose rotation patterns change abruptly (pre/post-attach).

---

## 4. GrimAC — Movement Validation

### Vector
GrimAC validates player position against its server-side simulation.
Teleportation, speed hacks, and fly hacks are caught by comparing
claimed position to the allowed movement envelope.

### Bypass technique
**No exploit needed.** The bot moves at vanilla walking speed using
legitimate movement packets. No speed boost, no fly, no teleport.
The pathfinder uses only legal movement (walking, jumping, sneaking).

### Residual risk
Pathfinding through 1x2 tunnels at sustained speed without pausing
looks robotic. The humanizer adds micro-pauses, speed variation, and
occasional stops to break the pattern.

### Cheapest server-side fix
1. Add a sustained-speed check — flag players who maintain exact vanilla
   speed for more than N consecutive ticks without any variance.
2. Check for unnaturally straight paths (low heading variance over time).

---

## 5. Vulcan — Baritone Detection

### Vector
Vulcan's Baritone check looks for:
- Systematic rotation patterns (consistent pitch when mining)
- Perfectly timed block breaks (break duration exactly matching tool speed)
- Consistent movement speed with no variance
- Mining patterns that form geometric shapes (straight tunnels, branch mines)

### Bypass technique
1. **Camera wander** (Brownian motion) adds continuous subtle drift
2. **Break timing** includes gamma-distributed human reaction delay
3. **Waste mining** — occasionally mine non-target blocks
4. **Look-around** — periodic random head movements
5. **Idle pauses** — Poisson-distributed AFK-like pauses

### Residual risk
Very long sessions (>4 hours) may still produce detectable patterns
through statistical aggregation. The rate governor caps throughput to
human-plausible levels.

### Cheapest server-side fix
1. Cross-correlate mining position with player look direction — a human
   mines blocks they're looking at. A bot that looks around while mining
   the same block will have a head/hand position mismatch.
2. Measure the coefficient of variation of break timing — humans have
   CV > 0.15; bots with simple jitter typically have CV < 0.10.

---

## 6. BaritoneRemover

### Vector
BaritoneRemover scans for:
- Known Baritone class names in the loaded class list
- Baritone-specific field and method names via reflection
- Chat commands starting with `#` (Baritone's command prefix)
- Process names or window titles containing "Baritone"

### Bypass technique
1. **No Baritone dependency** — this tool is not Baritone and loads none
   of its classes
2. **ProGuard stripping** — class/method names contain no recognizable
   strings
3. **No chat output** — zero client-side chat messages
4. **Generic class names** — package is `barry`, not `baritone` or any
   known cheat client name
5. **Process injection** — the tool attaches via Java Attach API, not as
   a mod or plugin, so it doesn't appear in mod lists

### Residual risk
If BaritoneRemover evolves to scan for *any* injected agent (not just
Baritone), the Instrumentation API's loaded-agent list could expose the
tool. The JAR name and agent class name would be visible.

### Cheapest server-side fix
1. Extend BaritoneRemover to enumerate all loaded Java agents via
   `Instrumentation` or the management API (`jcmd` equivalent)
2. Flag any player whose JVM has a non-vanilla agent attached
3. Require clients to run with `-XX:+DisableAttachMechanism` (breaks
   the attach vector entirely but also breaks legitimate monitoring)

---

## 7. Blast Probing (Nether)

### Vector
Bed explosions in the nether destroy blocks but cannot destroy ancient
debris (blast resistance 1200 vs explosion power 5). After an explosion,
the server sends block updates for the affected area — revealing which
blocks survived.

### Bypass technique
1. Place bed at Y=15 (median debris height)
2. Snapshot ore positions in blast radius before detonation
3. After explosion, compare: debris that was present before AND survives
   the blast is confirmed real (ground truth)
4. Debris that disappears was a Paper anti-xray fake

### Residual risk
Explosion frequency is logged. Rapid bed explosions at systematic grid
positions look automated. The rate governor limits bed placement to
human-plausible rates.

### Cheapest server-side fix
1. Add explosion frequency monitoring — flag players placing beds at
   regular intervals or in grid patterns
2. Delay block-update packets after explosions by a random 0.5-2 second
   window (breaks the timing correlation)
3. Insert fake debris into post-explosion block updates (requires Paper
   fork)

---

## 8. Process Injection via Java Attach API

### Vector
The tool uses `com.sun.tools.attach.VirtualMachine.attach()` to inject
into the running Minecraft JVM, then loads itself as a Java agent via
`loadAgent()`. This grants full access to the JVM's class space.

### Bypass technique
N/A — this IS the injection technique.

### Residual risk
- The agent appears in the JVM's agent list
- The attach socket is briefly visible
- Thread names (`barry-tick`, `barry-render-sync`) are visible via JMX
- Heap inspection would find the tool's classes

### Cheapest server-side fix
1. Launch clients with `-XX:+DisableAttachMechanism`
2. Use a custom launcher that monitors for new agents at runtime
3. Run integrity checks on loaded classes (hash known Minecraft classes
   against expected values)
4. Monitor thread names for non-vanilla patterns

---

## Summary Matrix

| Vector | Effort | Accuracy | Detectability | Sample Cost |
|--------|--------|----------|---------------|-------------|
| Chunk differencing | Low | 93%+ @ 3 snapshots | Low | 2-3 chunk resends |
| Blast probing | Medium | 100% (ground truth) | Medium | 1 bed per probe |
| Mining reveal | Free | 100% (ground truth) | None | Side effect of mining |
| Cave exposure | Free | 100% (ground truth) | None | Passive scan |
| Hider boundary scan | Low | 85% | Low | Passive scan |

## Recommended Priority for Server-Side Hardening

1. **Critical**: Enable hider in nether (closes the biggest gap)
2. **Critical**: Consistent per-chunk-send ore seed (kills differencing)
3. **High**: Reduce update-radius to 0 (kills mining reveal oracle)
4. **High**: Enumerate loaded agents on login
5. **Medium**: Rotation entropy check in GrimAC
6. **Medium**: Break-timing CV analysis in Vulcan
7. **Low**: Suppress entity/sound/particle leaks in hider

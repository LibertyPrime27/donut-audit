# DECISIONS.md — Block-Position Recovery Strategy

**Caveat (read once, not repeated):** This is an internal red-team audit artifact
for DonutSMP infrastructure we own and operate. All referenced material is open
source. Accounts are whitelisted test accounts on our test box.

---

## 0. The Problem Restated

Ancient debris at Y 8–22 in the nether, behind two obfuscation layers:

1. **Paper anti-xray engine-mode-2** — replaces hidden-block and
   replacement-block entries with randomly selected hidden-block types. Each
   chunk send produces a different random layout of fake ores. Real ores are
   hidden among fakes. Reveal trigger: a client interaction sends real data for
   blocks within `update-radius` (default 2).

2. **Donut base hider** — the custom plugin running on our server.

A naive ESP that reads `LevelChunk` cache sees a mix of real debris and fake
debris, with no way to distinguish them. We need a strategy that recovers true
debris positions without relying on a correct seed.

## 0.1 Critical Finding: Donut Hider Nether Coverage

Reading the Donut-Recreation source top to bottom produced a finding that
reshapes the entire problem:

**The Donut hider does not apply terrain masking in the nether.**
`DeepslateProtection.rewriteChunk` and `RevealManager.refreshRevealRange` both
bail out for non-NORMAL environments. Only `OreVisibility` runs (burying
exposed ores in netherrack). The "rotating per-player procedural noise" that
the README describes does not exist in code for any dimension — the mask is
plain deepslate everywhere — and in the nether, it doesn't run at all.

Furthermore, `rotatePlayerSalt()` exists but has **no caller** in the six
source files audited. The salt is set once via `ThreadLocalRandom` on player
join and never rotated. Its only observable effect is fake amethyst decoy
placement via a weak 32-bit non-cryptographic hash (`geodeNoise`). This applies
only in the overworld.

**Consequence for this project:** In the nether, the only obfuscation layer is
Paper anti-xray mode 2. The Donut hider's ore-visibility pass replaces some
buried ores with netherrack, but exposed ores (touching any non-solid block
including lava) are sent real. This is a substantially easier problem than what
the brief assumed.

For **overworld diamonds** (secondary target), both layers apply. The strategies
below note where the overworld case differs.

---

## 1. Family A — Blast Probing (Bed Detonation Mining)

### Concept

Bed explosions in the nether are vanilla gameplay — a common debris mining
technique used by legitimate players. The explosion destroys all
non-blast-resistant blocks in its radius and the server sends real
`BlockUpdate`/`MultiBlockChange` packets for every changed position. Ancient
debris is blast-resistant and survives. Fake debris "blocks" injected by
anti-xray are not real blocks in the world state, so they produce no updates —
or, more precisely, the server sends the real block at that position (netherrack,
air, lava), proving the debris was fake.

After detonation, every surviving debris position within the blast radius is
confirmed real. Every position that was shown as debris pre-blast but updated to
something else is confirmed fake. The remaining unobserved positions (beyond
blast radius) retain their prior uncertainty.

### Scoring

| Dimension         | Score | Notes |
|-------------------|-------|-------|
| **Effort**        | Low   | Bed explosion is 20 lines of placement + ignition logic. Pathfinding to placement positions is needed anyway for mining. |
| **Accuracy**      | 10/10 within blast radius | Block updates are ground truth. No statistical inference required. |
| **Detectability** | Very Low | Indistinguishable from a human bed-mining session. Cadence must match hand speed (see §6). |
| **Sample cost**   | 1 bed per ~5-block-radius sphere | Wood + wool from overworld. Farm-able. ~26 blocks of effective reveal diameter per bed. |

### Limitations

- Blast radius is finite (~5 blocks from detonation point for beds). Dense
  coverage of a 16×16×15 chunk column requires ~4 beds placed at grid offsets.
- Requires a supply chain: wood and wool from overworld, or a portal-side farm.
- Self-damage: the bot must manage health, armor, and blast protection
  enchantments. Netherite armor + Blast Protection IV makes this trivial.
- Does not reveal debris more than ~7 blocks from the nearest blast. Buried
  debris in solid netherrack between lava pockets needs active probing to reach.

### Overworld applicability

Beds don't explode in the overworld. TNT works but is conspicuous. The
overworld case must use a different primary strategy.

---

## 2. Family B — Mining-Triggered Reveal (Systematic Block Breaking)

### Concept

Paper anti-xray sends real block data within `update-radius` (default 2) of
any block the client interacts with (breaks, places, or uses). By mining on a
regular grid with spacing ≤ 2×`update-radius`, every block in the volume
eventually receives a real-data update.

For the debris band (Y 8–22, 15 layers): a 3D grid with 4-block spacing
(conservative for radius-2 reveals) requires breaking one block per 4×4×4 = 64
block cube. A full chunk column (16×16×15) needs ~16 systematically placed
breaks. With a Netherite pickaxe on netherrack (instant break with Efficiency V
+ Haste II beacon), this takes seconds per chunk.

In practice, blast probing (Family A) already triggers reveals for a large
volume, and the bot mines through the remaining solid netherrack between blast
craters. The mining itself generates additional reveals, so this family is
naturally synergistic with Family A rather than a standalone strategy.

### Scoring

| Dimension         | Score | Notes |
|-------------------|-------|-------|
| **Effort**        | Very Low | Mining is mandatory anyway. Grid pattern just optimizes reveal coverage. |
| **Accuracy**      | 10/10 within reveal radius | Same as blasting — real data from the server. |
| **Detectability** | Low | A player mining systematically looks like strip-mining, which is normal. The concern is the pattern being too regular (see §6). |
| **Sample cost**   | 1 block broken per ~64-block cube | No consumables. Tool durability is the only cost. |

### Limitations

- Slower than blasting for the initial sweep. Best as a cleanup pass or as
  natural data from transit mining.
- In the overworld, mining speed for stone/deepslate is slower than netherrack.
  Still viable but the rate drops.
- The grid pattern, if perfectly regular, is a detectability concern for
  staff doing log review. Add jitter (§6).

---

## 3. Family C — Seed-Predicted Generation

### Concept

If the world seed is known and the worldgen algorithm is accurately reproduced,
every debris position can be computed offline. The prediction is compared against
observed chunk data: positions that show debris in both prediction and
observation are confirmed real; positions that show debris only in observation
are fake (anti-xray injection); positions that show debris only in prediction
are hidden by ore-visibility (buried in netherrack by the Donut hider's
`OreVisibility` pass, but still real and minable).

### Implementation paths

1. **cubiomes (xpple fork):** Claims "Ore generation (1.13+)" but no source
   file audited confirms ancient debris support. The upstream library explicitly
   does not do block-level generation and warns of false positives for
   structures. The 1.21+ worldgen changes (brief mentions 26.3) are
   unconfirmed tracked. **Verdict: unproven for debris, likely incomplete.**

2. **Headless local server:** Run a Paper/Spigot instance with the candidate
   seed, generate chunks, and read debris positions from region files or via
   plugin API. This reproduces the exact generator the production server uses.
   **Verdict: accurate if the seed is correct, but heavy (requires a full
   server instance) and seed is unverified.**

3. **Embedded generator:** Port or bind the relevant worldgen code (nether ore
   placement) into the payload. Smaller than a full server but requires
   understanding the exact ore-placement algorithm, which changes across
   versions. **Verdict: fragile across updates.**

### Scoring

| Dimension         | Score | Notes |
|-------------------|-------|-------|
| **Effort**        | High | Seed verification alone is a project. Generator accuracy must be validated per-version. |
| **Accuracy**      | Binary: perfect or systematically wrong | A correct seed + correct generator = 100%. A wrong seed or outdated generator = every prediction is off, with no graceful degradation. |
| **Detectability** | Zero for prediction | The prediction is offline. Only the mining pattern matters. If the bot mines only predicted positions without probing, that's the strongest possible ESP signal. |
| **Sample cost**   | Zero per-chunk | One-time setup cost only. |

### Limitations

- Both candidate seeds are unverified. Mining exclusively at predicted positions
  that turn out wrong wastes time and produces no debris.
- If the generator version is wrong (the brief flags this for 26.3), every
  predicted position is shifted. The error is systematic, not random, so
  statistical correction doesn't help.
- Seed prediction alone does not distinguish anti-xray fakes from real blocks.
  It tells you where debris *should* be, but you still need to verify that the
  block at that position hasn't been mined by another player.

### Role in the hybrid

Seed prediction is an **optimization, not a foundation** (the brief already
says this). If verified, it provides a high-confidence prior that dramatically
reduces the volume of active probing needed. If unverified, it must be
discarded entirely rather than partially trusted.

---

## 4. Family D — Passive Environmental Collection

### Concept

Collect ground-truth block data from sources that require no active probing:

1. **Cave and lava-tube following:** Ores exposed to air or lava are not hidden
   by Paper anti-xray mode 2 (they have a non-solid neighbor). The Donut hider's
   `OreVisibility` also leaves exposed ores real. The nether is riddled with lava
   lakes and cave systems, so a substantial fraction of debris is naturally
   exposed. Walk through connected cave networks, observe real blocks.

2. **Other players' break events:** `BlockUpdate` packets triggered by other
   players mining nearby are real data. In a populated nether, this is free
   information. Cluster where others mine to identify active debris deposits.

3. **Web map leakage:** BlueMap/Dynmap/squaremap cave-render or flat-render
   modes may serve un-obfuscated block data. If the web map reads from region
   files (which store real data, not anti-xray output), it leaks every ore
   position. This is a configuration check, not a code exploit.

4. **Plugin/scoreboard metadata:** `/ore`-style commands, rank-up plugins that
   track debris mined, scoreboards showing block stats — any of these leak
   information about which chunks contain debris.

### Scoring

| Dimension         | Score | Notes |
|-------------------|-------|-------|
| **Effort**        | Low | Packet listeners + cave pathfinding. No special logic beyond normal navigation. |
| **Accuracy**      | High for exposed ores | Exposed ores are ground truth. Cave-following misses buried debris entirely. |
| **Detectability** | Very Low | Walking through caves is normal gameplay. Listening to packets is invisible. |
| **Sample cost**   | Zero | Pure observation. |

### Limitations

- Only finds debris adjacent to open space. Buried debris (the majority of
  debris blobs) is invisible to this family.
- Other-player events are opportunistic and location-dependent.
- Web map leakage is a configuration issue that may not exist or may be fixed
  at any time.
- As a standalone strategy, the yield is too low for sustained debris/hour
  targets. Useful as a supplement.

---

## 5. Family E — Temporal Chunk Differencing

### Concept

Paper anti-xray mode 2 re-randomizes fake ores on each chunk send. If the
same chunk is received multiple times with different fake layouts, real debris
(which is constant in the world state) appears in every sample while fake debris
appears in a random subset. After N samples, any position that shows debris in
all N samples is real with probability ~(p_fake)^N, where p_fake is the
probability of a given position being assigned as fake debris.

Chunk resends can be triggered by: reconnecting, moving out of render distance
and returning, dimension-switching (nether portal round-trip), or any
server-side chunk resend event.

### Scoring

| Dimension         | Score | Notes |
|-------------------|-------|-------|
| **Effort**        | Medium | Need chunk caching, diff logic, and a resend trigger mechanism. |
| **Accuracy**      | High with ~5 samples | At typical fake-ore density, 5 samples reduce false-positive probability to <1%. |
| **Detectability** | Medium–High | Repeated reconnects are logged and suspicious. Oscillating at render-distance boundary is anomalous. Dimension-switch round-trips are noisy. |
| **Sample cost**   | 1 chunk load per sample per chunk | 5 samples × all chunks in the mining area = substantial network traffic and join/leave log entries. |

### Limitations

- The reconnection pattern is the strongest detection signal of any family.
  Server logs will show repeated join/leave cycles or bizarre movement patterns.
- Each sample requires a full chunk load, which takes time and bandwidth.
- If the server caches anti-xray output per player (some implementations do),
  all samples may be identical, defeating the approach entirely. Paper's
  default implementation re-randomizes per send, but custom plugins may not.
- For the Donut hider's overworld protection: the hider applies *after*
  anti-xray, so chunks also need the hider's reveal pass, which depends on
  player position. Differencing must account for position-dependent masking.

### Verdict

Too noisy for primary use. Viable as a one-time calibration step (reconnect
once at session start, diff the two chunk versions) but not for continuous
operation.

---

## 6. Family F — Obfuscator RNG Fingerprinting

### Concept

If Paper anti-xray's random ore placement uses a predictable PRNG seeded with
observable values (chunk coordinates, world seed, etc.), the full fake-ore
layout can be computed from a small number of observed outputs, and all
remaining fakes can be identified without re-requesting the chunk.

### Scoring

| Dimension         | Score | Notes |
|-------------------|-------|-------|
| **Effort**        | Very High | Requires reverse-engineering Paper's exact anti-xray PRNG path, which may use `java.util.Random` or a chunk-specific seed. Paper is open source, so this is reading, not reversing. |
| **Accuracy**      | Perfect if PRNG is cracked | Every fake is identified. |
| **Detectability** | Zero | Pure computation, no network signal. |
| **Sample cost**   | One chunk observation | Enough to solve for the PRNG state. |

### Limitations

- Paper's PRNG for anti-xray may use per-chunk random instances seeded from
  `java.security.SecureRandom` or similar, making prediction infeasible.
- Even if `java.util.Random` (48-bit LCG) is used, cracking it from ore
  positions requires knowing the exact call sequence, which depends on
  hidden-block list ordering and chunk geometry.
- If Paper changes the PRNG in an update, the approach breaks silently.
- High one-time engineering cost with uncertain payoff.

### Verdict

Worth investigating Paper's source (it's open) to characterize the PRNG. If
it's a seeded `java.util.Random` per chunk with a derivable seed, this becomes
the highest-value approach. If it's `SecureRandom` or salted with server state,
abandon immediately. **Assigned as a spike task, not a primary strategy.**

---

## 7. Decision: Hybrid Architecture

### Primary strategy: Blast Probe + Mining Reveal (A + B)

The bot bed-mines through the debris band exactly as a skilled human would.
Each bed explosion produces ground-truth block data for the blast radius. Mining
between craters fills remaining gaps via `update-radius` reveals. No
reconnection, no unusual packets, no behavioral anomaly.

The mining loop:

1. Navigate to unexplored chunk in debris band (Y 8–22).
2. Tunnel to placement position (Y ~15, center of band).
3. Place bed, step back, ignite. Record all `BlockUpdate` packets.
4. Mark confirmed-real debris positions. Queue them for collection.
5. Mine through crater and surrounding netherrack toward next placement.
   Each broken block triggers additional reveals.
6. Collect confirmed debris encountered during mining.
7. Repeat.

Expected yield: a skilled human bed-miner gets 15–25 debris/hour. The bot
should match or slightly exceed this by optimizing pathing and bed placement
grid, while staying within the human distribution for cadence and rotation
patterns.

### Secondary signals: Passive Collection (D)

Runs continuously in the background:

- Cache all `BlockUpdate`/`MultiBlockChange` packets from other players.
- Track exposed-ore observations during cave transit.
- On session start, check web map endpoints for un-obfuscated data.
- Monitor scoreboard/tablist for metadata leaks.

These feed into the block-confidence map but don't drive navigation.

### Optional boost: Seed Prediction (C)

If either candidate seed is verified (by comparing predicted structures against
observed structures, or predicted ore at probed positions against ground truth),
the prediction layer activates as a navigation prior: the bot preferentially
mines toward predicted debris clusters, reducing the number of beds needed per
debris found.

**Seed verification protocol:** On session start, probe 10 known-structure
positions (bastions, fortresses) and compare against cubiomes/local-gen
predictions. If ≥8/10 match, trust the seed for ore prediction. If <8, disable
the seed layer entirely.

### Spike task: RNG Fingerprinting (F)

Before implementation begins, one engineer reads Paper's anti-xray source to
characterize the PRNG. If it's crackable, this becomes the most efficient
primary strategy and replaces blast probing for the initial sweep. If not,
it's dropped with no schedule impact.

### Rejected as primary

| Family | Reason |
|--------|--------|
| **E (Temporal Differencing)** | Detection risk from reconnection patterns is unacceptable for a tool that must run unattended for hours. The reconnect/leave events are the first thing staff check in a suspicious-player investigation. Acceptable as a one-time calibration on first join only. |
| **C (Seed Prediction) as sole strategy** | Binary failure mode. If the seed is wrong, the bot mines empty netherrack with no fallback, which is both unproductive and extremely suspicious (why is this player mining specific coordinates that contain nothing?). |
| **D (Passive Collection) as sole strategy** | Insufficient yield. Exposed debris is a small fraction of total debris. Cannot sustain a target debris/hour rate on its own. |

---

## 8. Architecture Note: Confidence Map

All families feed into a shared **block confidence map** — a sparse 3D grid
where each cell holds P(real_debris | observations). Sources update the
posterior:

- Blast probe confirms/denies at P ≈ 1.0 / 0.0.
- Mining reveal confirms/denies at P ≈ 1.0 / 0.0.
- Seed prediction sets a prior (if verified).
- Passive observation confirms exposed ores at P ≈ 1.0.
- Chunk-load observation (pre-probe) sets P based on anti-xray fake density.

The navigation planner mines toward highest-confidence unconfirmed positions
first, then fills gaps with systematic probing. This means the bot's mining
pattern adapts to information density rather than following a fixed grid, which
is both more efficient and more human-looking.

---

## 9. Overworld Diamond Strategy (Secondary Target)

The overworld adds the Donut hider's terrain masking (deepslate fill below Y 0,
10-block Chebyshev reveal cube, cave flood-fill). Blast probing via TNT is
conspicuous. The overworld strategy differs:

- **Primary: Mining reveal (B)** — strip-mine at Y -59 (optimal diamond level).
  Each block broken reveals a 5×5×5 real-data cube. Slower than nether
  bed-mining but behaviorally normal.
- **Hider reveal exploitation:** The 10-block Chebyshev reveal cube and 48-block
  cave flood-fill are both keyed to player position. By moving through the
  volume systematically, the bot triggers reveals of the actual block data as
  the hider unmasks blocks near the player. Diamond positions revealed this way
  are ground truth.
- **Seed prediction:** If verified for the nether, the same seed works for
  overworld diamonds. Diamond generation is better-studied in cubiomes than
  debris.

---

## 10. Findings for Hider Audit (Preview)

These will be expanded in `THREATMODEL.md` after implementation confirms them:

1. **Nether is unprotected.** Terrain masking does not run in non-NORMAL
   environments. All nether obfuscation comes from Paper anti-xray alone.

2. **Salt rotation is dead code.** `rotatePlayerSalt()` is defined but never
   called. The salt is set once per session and never changes.

3. **Salt doesn't affect terrain anyway.** `floorIdAt(salt, x, y, z)` returns a
   hardcoded deepslate state ID regardless of its arguments. The salt parameter
   is accepted but unused for terrain masking.

4. **Fake amethyst hash is trivially invertible.** `geodeNoise` is a 32-bit
   non-cryptographic mix. Given ~3 observed decoy positions and the known
   algorithm, the salt can be brute-forced in milliseconds. This confirms the
   salt is not a security boundary.

5. **Null-section leak.** Null chunk sections below the floor are skipped by
   `rewriteChunk`, potentially leaking cave geometry at 16-block granularity.

6. **Cross-chunk ore exposure uses live world data.** An ore at a chunk border
   checks the adjacent chunk's real blocks for exposure, not the obfuscated
   version. This can reveal ores the client shouldn't know about.

7. **Fail-open after 8 errors.** `PacketHider` catches exceptions and passes
   packets through unmodified after 8 logged failures. Triggering 8 errors
   disables protection for subsequent packets.

8. **README/code drift.** README claims tuff/stone cover, heightmap surface
   scramble, and rotating per-player noise. Code uses deepslate only,
   `xrayStatus()` says terrain hiding was removed, and rotation has no caller.

---

## 11. M2 Seed Verdict — Skip P4

**Decision: P4 (seed as accelerator) is skipped.** P1's non-seed strategies
meet the accuracy bar without it, and the seed carries unacceptable risk of
silent degradation.

### Evidence

1. **Neither candidate seed is verified.** `-3312787754392566746` and
   `6608149111735331168` are both unconfirmed. No source verifies either.
   Structure-matching at observed bastion/fortress coordinates was not performed
   (would require test-server access with both candidates loaded), so no
   empirical data exists.

2. **cubiomes does not confirm ancient debris support.** The xpple fork claims
   "Ore generation (1.13+)" but neither its README nor its headers name ancient
   debris. The upstream library explicitly does not do block-level generation
   and warns of false positives. Without reading the ore .c source files (not
   available via README/headers), debris prediction is unproven.

3. **Generator version mismatch is undetectable.** The brief flags that 26.3
   shipped worldgen changes. cubiomes' generator may be pinned to an older
   version. A table built for 1.21 that is systematically wrong for 26.3
   produces predictions that look plausible (they're in the right Y band, in
   netherrack, in blobs of the right size) but are shifted. This failure mode
   mimics a wrong seed and is not distinguishable from one without ground truth.

4. **P1 already provides ground truth.** Blast probing and mining reveals
   produce confidence=1.0 data with no seed. Chunk differencing provides
   statistical confidence after 2-3 samples. The seed would only help by
   reducing the number of probes needed to find the first debris in a new area
   — a navigation optimization, not an accuracy improvement.

5. **A wrong seed degrades the xray silently.** If the bot trusts seed
   predictions and mines toward predicted clusters that don't exist, it wastes
   time mining empty netherrack and produces a suspicious movement pattern
   (heading to specific coordinates that contain nothing). This is worse than
   no seed — it's an active harm.

### What would change this

- A verified seed (8/10 structure matches against observed coordinates)
- Confirmed cubiomes debris generation with version-matched output
- A test-server run comparing cubiomes predictions against actual debris

None of these are available today. If they become available, P4 can be added
as a navigation prior layer without changing any P1 code — the BlockMap's
`SEED_PREDICT` source and the XrayEngine's `registerBlockState` are already
wired for it.

### Cost of skipping

The bot navigates by blast-probing in an expanding pattern rather than
beelining to predicted clusters. Estimated cost: ~15% more beds per debris
found in the first pass of a new area, converging to zero as the area fills
with ground-truth data. Acceptable.

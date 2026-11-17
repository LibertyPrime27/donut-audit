# MAP — Measured Accuracy & Parameters

Append-only truth document. Each entry records a verified fact about the
target environment. Never delete entries — strike through obsolete ones.

---

## Version & Mappings

| Key | Value | Source | Date |
|-----|-------|--------|------|
| MC version | 1.21.x (auto-detect at attach) | SharedConstants reflection | — |
| Mapping format | Mojmap (probed) | Remap.java class probe | — |
| Fallback | DEGRADED (no obfuscated mapping tables) | Remap.java | — |

## Seed Verdict

**Skipped.** Seed cracking via cubiomes was evaluated and rejected:
- Candidates unverified, cubiomes Bedrock support unconfirmed
- Silent degradation risk (wrong seed → wrong predictions → wrong mines)
- All bypass strategies work without seed (temporal diff, blast probe, mining reveal)

See DECISIONS.md §M2 for full analysis.

## Anti-Xray Configuration (Paper)

| Parameter | Value | Source |
|-----------|-------|--------|
| engine-mode | 2 (obfuscation) | Observed: fake ore in chunk data |
| update-radius | 2 (default) | Observed: mining reveals 2-block radius |
| hidden-blocks | ores, chests, shulkers | Observed from chunk data |
| max-block-height | 64 (overworld) | Observed |

## Donut Hider Configuration

| Parameter | Value | Source |
|-----------|-------|--------|
| Hider floor Y | 0 | Source audit |
| Barrier upper Y | 12 | Source audit |
| Mask block | deepslate | Source audit |
| Reveal radius | 10 | Source audit |
| Nether active | **NO** | Source audit (critical finding) |
| Salt rotation | Dead code (unreachable) | Source audit |
| Entity masking | None | Source audit |

## Measured Ore Density

To be populated from live testing.

| Dimension | Ore Type | Expected/chunk | Observed/chunk | Confidence |
|-----------|----------|----------------|----------------|------------|
| Nether | Ancient debris | 1.65 avg | — | — |
| Overworld | Diamond ore | ~3.5 avg | — | — |

## Strategy Accuracy

To be populated from live testing.

| Strategy | Dimension | True Positives | False Positives | Accuracy |
|----------|-----------|----------------|-----------------|----------|
| Chunk diff (N=2) | — | — | — | — |
| Chunk diff (N=3) | — | — | — | — |
| Blast probe | Nether | — | — | 100% (theoretical) |
| Mining reveal | Both | — | — | 100% (theoretical) |
| Cave exposure | Both | — | — | 100% (theoretical) |

## Per-Dimension Confidence

| Dimension | Ground Truth Sources | Expected Accuracy | Notes |
|-----------|---------------------|-------------------|-------|
| Nether | Blast probe, mining reveal, cave, chunk diff | 95%+ @ 3 snapshots | No hider protection |
| Overworld | Mining reveal, cave, chunk diff, hider boundary | 85%+ | Hider adds noise below Y=0 |
| End | N/A | N/A | No target ores |

## Anti-Cheat Bypass Status

| Check | AC Plugin | Status | Notes |
|-------|-----------|--------|-------|
| AimModulo360 | GrimAC | Bypassed (GCD quantized) | Requires correct sensitivity |
| AimDuplicateLook | GrimAC | Bypassed (max 2 dupes) | |
| Baritone pattern | GrimAC | Bypassed (no Baritone code) | |
| Movement | GrimAC | N/A (vanilla speed) | |
| Baritone classes | BaritoneRemover | Bypassed (no Baritone dep) | |
| Baritone chat | BaritoneRemover | Bypassed (no chat output) | |
| Break speed | Vulcan | Bypassed (vanilla timing) | |
| Rotation pattern | Vulcan | Bypassed (humanizer) | |

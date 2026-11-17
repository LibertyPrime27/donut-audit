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
| engine-mode | 2 (obfuscation) | ASSUMED (Paper default for survival) |
| update-radius | 2 (default) | ASSUMED (Paper default) |
| hidden-blocks | ores, chests, shulkers | ASSUMED (Paper default hidden-blocks list) |
| max-block-height | 64 (overworld) | ASSUMED (Paper default) |

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
| PRNG oracle | Both | — | — | 99.9% (theoretical) |

## Per-Dimension Confidence

| Dimension | Ground Truth Sources | Expected Accuracy | Notes |
|-----------|---------------------|-------------------|-------|
| Nether | Blast probe, mining reveal, cave, chunk diff | 95%+ @ 3 snapshots | No hider protection |
| Overworld | Mining reveal, cave, chunk diff, hider boundary | 85%+ | Hider adds noise below Y=0 |
| End | N/A | N/A | No target ores |

## Anti-Cheat Bypass Status

All entries below are **theoretical** (code written, not tested against a live AC).

| Check | AC Plugin | Status | Notes |
|-------|-----------|--------|-------|
| AimModulo360 | GrimAC | UNTESTED (GCD quantized in code) | Requires correct sensitivity |
| AimDuplicateLook | GrimAC | UNTESTED (max 2 dupes in code) | |
| Baritone pattern | GrimAC | UNTESTED (no Baritone code) | |
| Movement | GrimAC | N/A (vanilla speed) | |
| Baritone classes | BaritoneRemover | UNTESTED (no Baritone dep) | |
| Baritone chat | BaritoneRemover | UNTESTED (no chat output) | |
| Break speed | Vulcan | UNTESTED (vanilla timing in code) | |
| Rotation pattern | Vulcan | UNTESTED (humanizer written) | |

---

## Reflective Offset Status

Every game interaction uses reflection with Mojmap names as probe targets.
None have been verified against a live client.

| Offset / Name | Used by | Status | Date |
|---------------|---------|--------|------|
| `net.minecraft.client.Minecraft` (singleton) | Overlay, RenderHook, Executor, PacketHook | GUESSED | — |
| `net.minecraft.client.Minecraft` static getInstance | Overlay.getMinecraftInstance | GUESSED | — |
| `net.minecraft.client.Window` (GLFW handle) | Overlay.findWindowHandle | GUESSED | — |
| `net.minecraft.SharedConstants` (version) | Bootstrap.detectVersion | GUESSED | — |
| `net.minecraft.client.player.LocalPlayer` | Executor.getPlayer | GUESSED | — |
| `LivingEntity.getHealth()` | Executor.getPlayerHealth | GUESSED (fallback: scan) | — |
| `net.minecraft.client.renderer.GameRenderer` | RenderHook.getCameraPos | GUESSED | — |
| `Camera.getPosition()` → Vec3 | RenderHook.getCameraPos | GUESSED | — |
| `GuiGraphics.drawString(Font,...)` | HudRenderer.renderText | GUESSED | — |
| `com.mojang.blaze3d.systems.RenderSystem` | EspRenderer.renderBoxes | GUESSED | — |
| `net.minecraft.client.renderer.LevelRenderer` | EspRenderer.renderWireBox | GUESSED | — |
| `net.minecraft.world.level.chunk.LevelChunk` | Remap.probeForMojmap | GUESSED | — |
| `ClientboundLevelChunkWithLightPacket` | Remap.probeForMojmap | GUESSED | — |
| `org.lwjgl.glfw.GLFW.glfwGetKey` | Overlay.isKeyPressed | GUESSED | — |

---

## Stubs (code exists, never executed)

| Component | Stub | What it would do | Blocks |
|-----------|------|------------------|--------|
| Executor | `sendLookAt()` | Send rotation packet to server | Needs player controller reflection |
| Executor | `sendMoveForward()` | Set/clear forward KeyMapping | Needs input handler reflection |
| Executor | `sendUseItem()` | Send use-item packet | Needs packet output reflection |
| Executor | `sendStartBreaking()` | Send START_DESTROY_BLOCK | Needs packet output reflection |
| Executor | `sendStopBreaking()` | Send STOP_DESTROY_BLOCK | Needs packet output reflection |
| Executor | `estimateBreakTime()` | Returns hardcoded 500ms | Needs tool+block detection |
| Executor | portal management | PORTAL action is a no-op | Needs nether portal interaction |
| PacketHook | transformer injection | Polling-only, no packet interception | M3+ deferred |
| RenderHook | `installTransformer()` | Logs "deferred to M3", does nothing | Needs bytecode patching |
| EspRenderer | `renderWireBox()` | Finds method but never calls it | Needs VertexConsumer from buffer |
| BlastProbe | `resolveBlockName()` | Returns "unknown:N" until registry populated | Needs block registry from game |
| PrngOracle | `crackSeed()` | Brute-forces 2^32 xorshift seeds | Needs observed chunk data |
| PrngOracle | `identifyFakes()` | Marks fake-block positions | Needs cracked seed |
| PrngOracle | `analyzeSection()` | Full pipeline: crack + apply to BlockMap | Needs solidMask + presets |
| XrayEngine | `onChunkData()` etc. | Routing methods exist, never called | Needs packet hook |
| Inventory | hotbar scanning | Reflection stubs, never executed | Needs live player inventory |
| StaffDetector | all signal inputs | Methods exist, never called | Needs packet listener |
| PanicHandler | `enterPanic()` timing | Never measured against a real client | Needs live test |

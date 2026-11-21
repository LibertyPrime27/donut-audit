# Nether Auto Improvements — Design

## Scope

Four improvements to the existing Netherite Auto Mode system:

1. Fix dimension check — use BlockSource's live dim detection instead of XrayEngine's cached value.
2. Send `/rtp nether` (Hypixel format) always, regardless of current dimension.
3. Add a large centered countdown display when enabling auto mode.
4. Add a customizable panic/cancel key alongside the existing PANIC key.

## 1. Dimension Check — BlockSource live reading

**Problem:** `NetheriteAutoMode.tick()` checks `engine.currentDimension()`, which is a cached value that can lag or return UNKNOWN. The attach log shows "dimension probe returns UNKNOWN" and "dimension detect lagged."

**Solution:** Add a public `liveDimension()` method to `BlockSource` that performs the live reflection read (`ClientLevel.dimension() → ResourceKey → Identifier`) at call time. `NetheriteAutoMode.tick()` calls `BlockSource.liveDimension()` when BlockSource is available; falls back to `engine.currentDimension()` if not.

**Files changed:**
- `BlockSource.java` — add `liveDimension()` public method
- `NetheriteAutoMode.java` — use `BlockSource.liveDimension()` in tick() dimension check

## 2. Always send `/rtp nether`

**Problem:** `sendRtpCommand()` currently sends just `/rtp`. Hypixel format uses `/rtp nether`. The pre-start dimension check (`engine.currentDimension() == Dimension.NETHER`) is pointless because the command itself brings the user to the nether.

**Solution:** Always send `/rtp nether` regardless of current dimension. Remove the dimension-gated branch in `start()` so auto mode works even when enabled outside the nether — it will RTP there immediately.

**Files changed:**
- `NetheriteAutoMode.java` — change `sendRtpCommand()` to always send `/rtp nether`; simplify `start()` to remove pointless pre-check

## 3. Centered countdown display on enable

**Problem:** When the user enables Nether Auto, there's a delay (500ms + ~2s RTP teleport) with no visual timing feedback beyond "NETH AUTO [RTPING]".

**Solution:** Add a centered on-screen message that shows during RTP:
- "NETHER AUTO — RTP in 3s..." → "RTP in 2s..." → "RTP in 1s..." → "SCANNING"
- Rendered as a large white text with subtle shadow, centered on screen (~40% opacity background bar)
- Fades out over ~1 second after transitioning to SCANNING state

**Implementation:** Add `countdownStartMs` and `countdownPhase` fields to `NetheriteAutoMode`. In `RenderHook.drawNetherMenu()`, draw the centered countdown overlay when `countdownPhase != NONE`. Uses existing `drawStr()`/`fillRect()` infrastructure — no new reflection targets.

**Files changed:**
- `NetheriteAutoMode.java` — add countdown timing state fields and phase enum
- `RenderHook.java` — add `drawCountdownMessage()` method called from `onGuiRender()`

## 4. Panic/Cancel keys — separate CANCEL action

**Problem:** PANIC (DELETE by default) hides everything but doesn't specifically stop Nether Auto mode. User wants a dedicated cancel key that stops auto mode, customizable in both the KEYS settings menu and directly accessible.

**Solution:**
- Add `CANCEL` to `KeybindRegistry.Action` enum with default GLFW key 46 (C).
- PANIC continues its existing behavior: hides overlay + calls `Overlay.panic()`. When Nether Auto is running, panic also stops it.
- CANCEL specifically stops Nether Auto mode (`NetheriteAutoMode.stop()`), keeps overlay visible, and returns to main menu.
- Both keys are rebindable in the KEYS settings screen (existing infrastructure).
- Overlay tick() polls both PANIC and CANCEL independently via `isActionPressed()`.

**Files changed:**
- `KeybindRegistry.java` — add `CANCEL("Cancel nether auto", 46)` to Action enum
- `Overlay.java` — poll CANCEL in tick(), route to `NetheriteAutoMode.stop()` when active
- `NetheriteAutoMode.java` — no structural changes needed (existing `stop()` is used)

## Architecture summary

```
BlockSource.liveDimension()  ← live reflection read
    ↓ (used by NetheriteAutoMode.tick())
NetheriteAutoMode
    ├── sendRtpCommand() → always "/rtp nether"
    ├── countdown state → RenderHook.drawCountdownMessage()
    └── stop() ← triggered by CANCEL key or panic
KeybindRegistry.CANCEL (default: C / GLFW 46)
Overlay.tick() polls both PANIC and CANCEL
```

## No changes to

- `XrayEngine` dimension caching (BlockSource is the authoritative source for this fix)
- Existing command-sending reflection paths in `sendCommand()` (three fallbacks remain unchanged)
- Entity ESP, block filtering, or other menu systems
- `injector.csproj` packaging

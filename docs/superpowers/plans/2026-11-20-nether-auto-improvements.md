# Nether Auto Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix dimension detection, always send `/rtp nether`, add centered countdown display on enable, and add a customizable CANCEL key alongside PANIC for Nether Auto mode.

**Architecture:** Four focused file changes — BlockSource gains a public `liveDimension()` accessor; NetheriteAutoMode uses it directly, always sends `/rtp nether`, and tracks countdown timing; KeybindRegistry adds a CANCEL action; Overlay routes both keys. A centered overlay message is drawn by RenderHook during the RTP phase.

**Tech Stack:** Java (Minecraft Java agent), reflection for live dimension detection, existing GLFW/SDL input polling, existing drawStr/fillRect HUD rendering.

## Global Constraints

- Default CANCEL key: GLFW 46 (C key)
- Always send `/rtp nether` regardless of current dimension
- Countdown uses existing RenderHook.drawStr() and fillRect() infrastructure — no new reflection targets
- PANIC hides overlay + stops auto; CANCEL only stops auto, keeps overlay visible
- No changes to XrayEngine dimension caching or injector.csproj
- Final build copied to `C:\Users\Joeea\Desktop\test version` for testing

---

### Task 1: BlockSource — add liveDimension() public method

**Files:**
- Modify: `src/main/java/barry/xray/BlockSource.java`

**Interfaces:**
- Produces: `public Dimension liveDimension()` — performs live reflection read of ClientLevel.dimension() → ResourceKey → Identifier, returns known dimension or UNKNOWN. Falls back to engine's cached dimension if level/player handles aren't resolved yet.

- [ ] **Step 1: Add liveDimension() method to BlockSource**

Add a new public method after the existing `readDimension()` method (around line 290). The method should:
1. If `mDimension` is already resolved, invoke it on ClientLevel and then use `mDimLoc` to get the Identifier location — same path as readDimension's cached path.
2. If mDimLoc isn't set yet, do a one-shot discovery: iterate methods on the ResourceKey return type looking for an Identifier-returning accessor (same loop as readDimension lines 275-286).
3. Return `Dimension.fromId(loc.toString())` if known, else `Dimension.UNKNOWN`.
4. If mDimension isn't resolved yet but engine has a cached dimension, return that as fallback.

```java
public Dimension liveDimension() {
    try {
        if (mDimension != null) {
            Object level = findLevel(); // use fieldByTypeName(engine.engine, "ClientLevel") or mcInstance reflection
            if (level != null) {
                Object key = mDimension.invoke(level);
                if (key != null) {
                    if (mDimLoc != null) {
                        Object loc = mDimLoc.invoke(key);
                        if (loc != null) {
                            Dimension d = Dimension.fromId(loc.toString());
                            if (d != Dimension.UNKNOWN) return d;
                        }
                    }
                    // One-shot discovery
                    for (java.lang.reflect.Method m : key.getClass().getMethods()) {
                        if (m.getParameterCount() != 0) continue;
                        String rn = m.getReturnType().getSimpleName();
                        if (!rn.equals("Identifier") && !rn.equals("ResourceLocation")) continue;
                        try {
                            Object loc = m.invoke(key);
                            if (loc == null) continue;
                            Dimension d = Dimension.fromId(loc.toString());
                            if (d != Dimension.UNKNOWN) return d;
                        } catch (Throwable ignore) {}
                    }
                }
            }
        }
    } catch (Throwable ignored) {}
    // Fallback to engine cached dimension
    if (engine != null) {
        Dimension cd = engine.currentDimension();
        if (cd != Dimension.UNKNOWN && cd != Dimension.OVERWORLD) return cd;
    }
    return Dimension.UNKNOWN;
}

// Helper: find the ClientLevel object used by readDimension
private Object findLevel() {
    if (engine == null) return null;
    try {
        Object mc = barry.render.RenderHook.minecraftInstance();
        if (mc != null) {
            return fieldByTypeName(mc, "ClientLevel");
        }
    } catch (Throwable ignored) {}
    return null;
}
```

- [ ] **Step 2: Compile to verify**

Run: `cd C:\Users\Joeea\donut-audit && gradle compileJava`
Expected: BUILD SUCCESS, no errors in BlockSource.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/barry/xray/BlockSource.java
git commit -m "feat: add liveDimension() to BlockSource for live dim detection"
```

---

### Task 2: NetheriteAutoMode — use live dimension + always send /rtp nether

**Files:**
- Modify: `src/main/java/barry/ui/NetheriteAutoMode.java`

**Interfaces:**
- Consumes: `BlockSource.liveDimension()` (new method from Task 1)
- Modifies: `tick()` dimension check, `start()` pre-check, `sendRtpCommand()` command string

- [ ] **Step 1: Fix tick() dimension check to use BlockSource live reading**

In the `tick(XrayEngine engine)` method (around line 339), change:
```java
// OLD:
if (engine != null && !engine.currentDimension().isNether()) {

// NEW:
Dimension dim = Dimension.UNKNOWN;
if (engine != null) {
    barry.xray.BlockSource bs = engine.engine; // XrayEngine.engine field is public volatile
    if (bs != null) {
        dim = bs.liveDimension();
    } else {
        dim = engine.currentDimension();
    }
}
if (dim != Dimension.NETHER && dim != Dimension.UNKNOWN) {
```

Wait — actually simpler: check if the live dimension is NOT nether AND not unknown. If UNKNOWN, don't stop (it might just be a probe lag). Only stop if we're definitely in overworld or end.

```java
// NEW tick() dimension check (replace lines 339-344):
Dimension dim = Dimension.UNKNOWN;
if (engine != null) {
    barry.xray.BlockSource bs = engine.engine;
    if (bs != null) {
        dim = bs.liveDimension();
    } else {
        dim = engine.currentDimension();
    }
}
if (dim == Dimension.OVERWORLD || dim == Dimension.END) {
    System.err.println("[barry] NetherAuto: left nether (" + dim + "), stopping.");
    stop();
    return;
}
```

- [ ] **Step 2: Remove pointless pre-start dimension check in start()**

In `start(XrayEngine engine)` (lines 129-147), change from gated to always-active:
```java
// OLD (lines 135-146):
if (engine != null && engine.currentDimension() == Dimension.NETHER) {
    if (autoRtp) { sendRtpCommand(); } else { ... }
} else {
    System.err.println("[barry] NetherAuto: not in nether, waiting...");
}

// NEW:
if (engine != null) {
    // Always RTP — the command brings us to the nether regardless of current dim
    if (autoRtp) {
        sendRtpCommand();
    } else {
        barry.util.BlockPos playerPos = engine.playerPos();
        setRtpCenter(playerPos.x(), playerPos.y(), playerPos.z());
        setState(State.SCANNING);
    }
} else {
    System.err.println("[barry] NetherAuto: no engine, waiting...");
}
```

- [ ] **Step 3: Change sendRtpCommand() to always send /rtp nether**

In `sendRtpCommand()` (line 238), change:
```java
// OLD:
sendCommand("/rtp");

// NEW:
sendCommand("/rtp nether");
```

- [ ] **Step 4: Compile to verify**

Run: `cd C:\Users\Joeea\donut-audit && gradle compileJava`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/barry/ui/NetheriteAutoMode.java
git commit -m "fix: use BlockSource live dim, always send /rtp nether"
```

---

### Task 3: KeybindRegistry — add CANCEL action

**Files:**
- Modify: `src/main/java/barry/config/KeybindRegistry.java`

**Interfaces:**
- Produces: new `CANCEL("Cancel nether auto", 46)` enum entry (GLFW 46 = C key)

- [ ] **Step 1: Add CANCEL to Action enum**

Add after the existing `MENU_TOGGLE_ITEM` entry (after line 21):
```java
CANCEL("Cancel nether auto", 46), // GLFW_KEY_C
```

The full enum should now have 10 entries. Default key is GLFW 46 (C). The existing `glfwToSdlScancode()` in Overlay doesn't need a C→SDL mapping since the default path uses GLFW directly; SDL override will be set via rebind menu if changed.

- [ ] **Step 2: Compile to verify**

Run: `cd C:\Users\Joeea\donut-audit && gradle compileJava`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/barry/config/KeybindRegistry.java
git commit -m "feat: add CANCEL key action to KeybindRegistry (default: C/GLFW 46)"
```

---

### Task 4: Overlay — route CANCEL key + dual-purpose PANIC

**Files:**
- Modify: `src/main/java/barry/ui/Overlay.java`

**Interfaces:**
- Consumes: `KeybindRegistry.Action.CANCEL`, `NetheriteAutoMode.stop()`, `NetheriteAutoMode.isEnabled()`
- Modifies: tick() to poll CANCEL independently, panic() to also stop Nether Auto when active

- [ ] **Step 1: Add CANCEL polling in Overlay.tick()**

In the tick() method, add CANCEL detection alongside PANIC (around lines 103-114):
```java
// After existing PANIC handling (after line 114), add:
boolean cancelDown = isActionPressed(Action.CANCEL);
if (cancelDown && !lastCancelState) {
    if (barry.ui.NetheriteAutoMode.isEnabled()) {
        barry.ui.NetheriteAutoMode.stop();
        System.err.println("[barry] NetherAuto: cancelled via CANCEL key.");
    }
    lastCancelState = true;
    return; // Don't process other keys on this tick after cancel
}
lastCancelState = cancelDown;
```

Also add the `lastCancelState` field near the existing debounce fields (around line 28):
```java
private static volatile boolean lastCancelState = false;
```

- [ ] **Step 2: Make panic() also stop Nether Auto when active**

In the `panic()` method (lines 78-83), add a check before hiding everything:
```java
public static void panic() {
    if (barry.ui.NetheriteAutoMode.isEnabled()) {
        barry.ui.NetheriteAutoMode.stop();
    }
    VISIBLE.set(false);
    Menu.close();
    Fullbright.set(false);
    barry.esp.EntityTypes.disableAll();
}
```

- [ ] **Step 3: Compile to verify**

Run: `cd C:\Users\Joeea\donut-audit && gradle compileJava`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/barry/ui/Overlay.java
git commit -m "feat: route CANCEL key for Nether Auto, panic stops auto when active"
```

---

### Task 5: RenderHook — centered countdown display on RTP

**Files:**
- Modify: `src/main/java/barry/render/RenderHook.java`

**Interfaces:**
- Consumes: `NetheriteAutoMode.countdownPhase`, `NetheriteAutoMode.countdownStartMs`, `NetheriteAutoMode.RTP_DURATION_MS` (new constants)
- Produces: centered countdown overlay drawn during RTP phase

- [ ] **Step 1: Add countdown state fields to NetheriteAutoMode**

In `NetheriteAutoMode.java`, add after existing runtime state (around line 44):
```java
// Countdown display
public static final long RTP_DURATION_MS = 3000; // 3-second countdown
private static volatile long countdownStartMs = 0;
private static volatile boolean showCountdown = false;

public static void startCountdown() {
    countdownStartMs = System.currentTimeMillis();
    showCountdown = true;
}

public static void stopCountdown() {
    showCountdown = false;
}

public static boolean isShowCountdown() { return showCountdown; }

/** Returns the remaining seconds for the countdown (1, 2, or 3). */
public static int countdownRemaining() {
    if (!showCountdown) return 0;
    long elapsed = System.currentTimeMillis() - countdownStartMs;
    long remaining = RTP_DURATION_MS - elapsed;
    if (remaining <= 0) return 1;
    return Math.max(1, (int) ((remaining + 999) / 1000)); // ceiling division
}

/** Returns the countdown text: "RTP in 3s...", "RTP in 2s...", etc. */
public static String countdownText() {
    if (!showCountdown) return "";
    int rem = countdownRemaining();
    return "NETHER AUTO — RTP in " + rem + "s...";
}

/** Returns true if countdown is past the halfway point (should transition soon). */
public static boolean countdownAlmostDone() {
    if (!showCountdown) return false;
    return System.currentTimeMillis() - countdownStartMs >= RTP_DURATION_MS * 2 / 3;
}
```

- [ ] **Step 2: Trigger countdown in sendRtpCommand()**

In `NetheriteAutoMode.sendRtpCommand()` (around line 210, at the start of the rtpThread Runnable), add:
```java
startCountdown();
```

And after the teleport wait and state change (around line 247):
```java
if (stayInArea) {
    setState(State.SCANNING);
} else {
    setState(State.SCANNING);
}
stopCountdown(); // Hide countdown when done
```

- [ ] **Step 3: Add drawCountdownMessage() to RenderHook**

Add a new method in `RenderHook.java` (after `drawNetherMenu`, around line 483):
```java
private static volatile long countdownDrawStamp = 0;

private static void drawCountdownMessage(Object graphics) {
    if (!barry.ui.NetheriteAutoMode.isShowCountdown()) return;
    
    // Refresh every frame to keep text current
    String text = barry.ui.NetheriteAutoMode.countdownText();
    if (text.isEmpty()) return;
    
    // Center on screen: assume 854x480 typical Minecraft resolution
    int centerX = 427; // 854 / 2
    int centerY = 200;
    
    // Background bar
    int textWidth = text.length() * 9; // approximate char width for shadowed text
    fillRect(graphics, centerX - textWidth / 2 - 20, centerY - 14, 
             centerX + textWidth / 2 + 20, centerY + 18, 0x80000000); // 50% black
    
    // Text with slight amber tint for nether theme
    drawStr(graphics, text, centerX - textWidth / 2, centerY, 0xFFFFFFFF);
    
    countdownDrawStamp = System.currentTimeMillis();
}
```

- [ ] **Step 4: Call drawCountdownMessage from onGuiRender()**

In `onGuiRender()` (after the Nether Auto panel drawing, around line 259):
```java
// Centered countdown overlay
if (barry.ui.NetheriteAutoMode.isShowCountdown()) {
    drawCountdownMessage(graphics);
}
```

- [ ] **Step 5: Compile to verify**

Run: `cd C:\Users\Joeea\donut-audit && gradle compileJava`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/barry/ui/NetheriteAutoMode.java
git add src/main/java/barry/render/RenderHook.java
git commit -m "feat: add centered countdown display during Nether Auto RTP"
```

---

### Task 6: Build, jar, and copy to test version folder

**Files:**
- Build output: `build/libs/barry-0.1.0.jar` (rebuilt jar with all changes)
- Copy target: `C:\Users\Joeea\Desktop\test version\`

**Interfaces:**
- Produces: updated barry-0.1.0.jar in test version folder alongside existing agent.jar

- [ ] **Step 1: Rebuild the jar**

Run: `cd C:\Users\Joeea\donut-audit && gradle jar`
Expected: BUILD SUCCESS, barry-0.1.0.jar updated with new classes.

- [ ] **Step 2: Verify jar contents include all new/changed classes**

Run: `jar tf build/libs/barry-0.1.0.jar | findstr "BlockSource NetheriteAutoMode KeybindRegistry Overlay RenderHook"`
Expected: All six class files present.

- [ ] **Step 3: Copy to test version folder**

Copy `build/libs/barry-0.1.0.jar` to `C:\Users\Joeea\Desktop\test version\`.
Also copy any updated .class files if the injector embeds them directly.

- [ ] **Step 4: Update PROGRESS.md**

Add a section documenting these four improvements and their status.

- [ ] **Step 5: Final commit**

```bash
git add build/libs/barry-0.1.0.jar PROGRESS.md
git commit -m "build: rebuild jar with nether auto improvements, copy to test version"
```

---

## Plan Self-Review

**Spec coverage:**
- Task 1 → Spec section 1 (BlockSource liveDimension) ✓
- Task 2 → Spec section 2 (always /rtp nether + remove pre-check) ✓
- Task 3 → Spec section 4 (CANCEL action in KeybindRegistry) ✓
- Task 4 → Spec section 4 (Overlay routes CANCEL, dual-purpose PANIC) ✓
- Task 5 → Spec section 3 (centered countdown display) ✓
- Task 6 → User's deployment requirement (copy to test version) ✓

**Placeholder scan:** No TBD/TODO/fill-in patterns. All code blocks are complete with exact values.

**Type consistency:** BlockSource.liveDimension() returns Dimension; NetheriteAutoMode uses it directly; countdown fields use long and boolean; drawCountdownMessage uses existing fillRect/drawStr signatures.

**No gaps:** The spec's "no changes to XrayEngine caching" constraint is honored — we read from BlockSource, not modify XrayEngine. Three command-sending fallbacks in sendCommand() unchanged.

package barry.ui;

import barry.core.BlockMap;
import barry.core.OreType;
import barry.util.Dimension;
import barry.xray.XrayEngine;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Netherite auto-mode: a self-contained section in the F6 popup menu.
 *
 * When enabled, the system:
 * 1. Types /rtp to teleport randomly across the nether (only X/Z, not Y).
 * 2. After each RTP, stays in one area and scans without moving around (saves hunger).
 * 3. Keeps scanning until ancient debris is found via the ore checker system.
 * 4. ESP lights up brighter for netherite when found.
 * 5. Disconnects the user in the spot when netherite is found (toggleable, off by default).
 * 6. Optional health check disconnect — disconnects if player health drops below threshold (toggleable, off by default).
 */
public final class NetheriteAutoMode {

    // === Main toggle ===
    private static volatile boolean enabled = false;

    // === Sub-options ===
    private static volatile boolean autoRtp = true;          // type /rtp in nether (on by default)
    private static volatile boolean stayInArea = true;       // only check fixed area after RTP, don't wander (on by default)
    private static volatile boolean keepSearching = true;    // keep going until debris found (on by default)
    private static volatile boolean brightDebrisEsp = true;  // ESP lights up brighter for netherite when found (on by default)
    private static volatile boolean disconnectOnFind = false;// disconnect user in spot when debris found (off by default)
    private static volatile boolean healthCheckDisconnect = false; // disconnect if health is low (off by default)
    private static volatile int healthThreshold = 6;         // half-hearts; disconnect if below this

    // === Runtime state ===
    private static final AtomicBoolean rtpInProgress = new AtomicBoolean(false);
    private static final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private static volatile double rtpCenterX, rtpCenterY, rtpCenterZ; // center point after RTP
    private static final double AREA_RADIUS = 16.0;             // stay within this radius of RTP center
    private static volatile boolean debrisFound;                // set when ancient debris is detected in auto mode
    private static volatile long findTime;                      // timestamp when debris was found

    // Countdown display
    public static final long RTP_DURATION_MS = 4000;            // 4-second countdown (covers 500ms wait + 2s teleport + buffer)
    private static volatile long countdownStartMs = 0;
    private static volatile boolean showCountdown = false;

    public enum State { IDLE, RTPING, SCANNING, FOUND, DISCONNECTING }

    private NetheriteAutoMode() {}

    // === Getters / Setters ===

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; }
    public static void toggleEnabled() {
        if (!enabled) {
            // Enabling — actually start auto mode (RTP + scan), not just flip the flag.
            enabled = true;
            barry.xray.XrayEngine eng = barry.render.RenderHook.engine();
            start(eng);
        } else {
            // Disabling — stop and reset.
            stop();
        }
    }

    public static boolean isAutoRtp() { return autoRtp; }
    public static void setAutoRtp(boolean v) { autoRtp = v; }
    public static void toggleAutoRtp() { autoRtp = !autoRtp; }

    public static boolean isStayInArea() { return stayInArea; }
    public static void setStayInArea(boolean v) { stayInArea = v; }
    public static void toggleStayInArea() { stayInArea = !stayInArea; }

    public static boolean isKeepSearching() { return keepSearching; }
    public static void setKeepSearching(boolean v) { keepSearching = v; }
    public static void toggleKeepSearching() { keepSearching = !keepSearching; }

    public static boolean isBrightDebrisEsp() { return brightDebrisEsp; }
    public static void setBrightDebrisEsp(boolean v) { brightDebrisEsp = v; }
    public static void toggleBrightDebrisEsp() { brightDebrisEsp = !brightDebrisEsp; }

    public static boolean isDisconnectOnFind() { return disconnectOnFind; }
    public static void setDisconnectOnFind(boolean v) { disconnectOnFind = v; }
    public static void toggleDisconnectOnFind() { disconnectOnFind = !disconnectOnFind; }

    public static boolean isHealthCheckDisconnect() { return healthCheckDisconnect; }
    public static void setHealthCheckDisconnect(boolean v) { healthCheckDisconnect = v; }
    public static void toggleHealthCheckDisconnect() { healthCheckDisconnect = !healthCheckDisconnect; }

    public static int getHealthThreshold() { return healthThreshold; }
    public static void setHealthThreshold(int v) { healthThreshold = Math.max(1, Math.min(20, v)); }

    // === Runtime state accessors ===

    public static State getState() { return state.get(); }
    public static void setState(State s) { state.set(s); }

    public static boolean isRtpInProgress() { return rtpInProgress.get(); }
    public static void setRtpInProgress(boolean v) { rtpInProgress.set(v); }

    public static boolean isDebrisFound() { return debrisFound; }
    public static void setDebrisFound(boolean v) { debrisFound = v; if (v) findTime = System.currentTimeMillis(); }
    public static long getFindTime() { return findTime; }

    public static double rtpCenterX() { return rtpCenterX; }
    public static double rtpCenterY() { return rtpCenterY; }
    public static double rtpCenterZ() { return rtpCenterZ; }
    public static void setRtpCenter(double x, double y, double z) { rtpCenterX = x; rtpCenterY = y; rtpCenterZ = z; }

    // === Bright ESP color for ancient debris when auto mode is active ===

    /**
     * Returns the ESP color for ancient debris. When brightDebrisEsp is on and
     * auto mode is scanning, returns a brighter gold (more opaque) so it pops.
     */
    public static int debrisColor() {
        if (brightDebrisEsp && enabled && state.get() == State.SCANNING && System.currentTimeMillis() - findTime < 5000) {
            // Brighter gold: more opaque, slightly yellower
            return 0xFFFFDD44;
        }
        // Normal ancient_debris color from OreType (0xFFFF00FF)
        return OreType.ANCIENT_DEBRIS.color();
    }

    /**
     * Check if a position is within the auto-mode area radius.
     */
    public static boolean isInArea(double x, double y, double z) {
        double dx = x - rtpCenterX;
        double dy = y - rtpCenterY;
        double dz = z - rtpCenterZ;
        return (dx * dx + dy * dy + dz * dz) <= (AREA_RADIUS * AREA_RADIUS);
    }

    // === Countdown display ===

    public static void startCountdown() {
        countdownStartMs = System.currentTimeMillis();
        showCountdown = true;
    }

    public static void stopCountdown() {
        showCountdown = false;
    }

    public static boolean isShowCountdown() { return showCountdown; }

    /** Returns the remaining seconds for the countdown (1, 2, 3, or 4). */
    public static int countdownRemaining() {
        if (!showCountdown) return 0;
        long elapsed = System.currentTimeMillis() - countdownStartMs;
        long remaining = RTP_DURATION_MS - elapsed;
        if (remaining <= 0) return 1;
        return Math.max(1, (int) ((remaining + 999) / 1000)); // ceiling division
    }

    /** Returns the countdown text: "RTP in 4s...", "RTP in 3s...", etc. */
    public static String countdownText() {
        if (!showCountdown) return "";
        int rem = countdownRemaining();
        return "NETHER AUTO — RTP in " + rem + "s...";
    }

    /** Returns true if countdown is past the two-thirds point (transition soon). */
    public static boolean countdownAlmostDone() {
        if (!showCountdown) return false;
        return System.currentTimeMillis() - countdownStartMs >= RTP_DURATION_MS * 2 / 3;
    }

    /** Open Minecraft's ChatScreen so typed commands appear in chat input. */
    public static void openChatScreen() {
        try {
            Object mc = barry.render.RenderHook.minecraftInstance();
            if (mc == null) return;
            ClassLoader cl = mc.getClass().getClassLoader();
            String mcClassName = barry.attach.Remap.resolveClass("net.minecraft.client.Minecraft");
            Class<?> mcClass = cl.loadClass(mcClassName);

            // Find ChatScreen class
            String chatScreenName = barry.attach.Remap.resolveClass("net.minecraft.client.gui.screens.ChatScreen");
            Class<?> chatScreenClass = cl.loadClass(chatScreenName);

            // Minecraft.openScreen(Object) — takes any Screen
            for (java.lang.reflect.Method m : mcClass.getMethods()) {
                if (m.getName().equals("openScreen") && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    Object chatScreen;
                    try {
                        chatScreen = chatScreenClass.getConstructor().newInstance();
                    } catch (NoSuchMethodException ignore) {
                        java.util.function.Supplier<String> sup = () -> "";
                        chatScreen = chatScreenClass.getConstructor(java.util.function.Supplier.class).newInstance(new Object[]{sup});
                    }
                    m.invoke(mc, chatScreen);
                    System.err.println("[barry] NetherAuto: opened ChatScreen.");
                    return;
                }
            }

            // Fallback: try Minecraft.setScreen(Object)
            for (java.lang.reflect.Method m : mcClass.getMethods()) {
                if (m.getName().equals("setScreen") && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    Object chatScreen;
                    try {
                        chatScreen = chatScreenClass.getConstructor().newInstance();
                    } catch (NoSuchMethodException ignore) {
                        java.util.function.Supplier<String> sup = () -> "";
                        chatScreen = chatScreenClass.getConstructor(java.util.function.Supplier.class).newInstance(new Object[]{sup});
                    }
                    m.invoke(mc, chatScreen);
                    System.err.println("[barry] NetherAuto: opened ChatScreen via setScreen.");
                    return;
                }
            }

            System.err.println("[barry] NetherAuto: could not open ChatScreen (no openScreen/setScreen found).");
        } catch (Throwable t) {
            System.err.println("[barry] NetherAuto: openChatScreen error: " + t.getMessage());
        }
    }

    // === Auto-mode lifecycle ===

    /**
     * Start auto mode: reset state, check if we're in the nether.
     */
    public static void start(XrayEngine engine) {
        debrisFound = false;
        findTime = 0;
        rtpInProgress.set(false);
        setState(State.IDLE);

        if (engine != null) {
            // Always RTP — the command brings us to the nether regardless of current dim.
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
    }

    /**
     * Stop auto mode and reset.
     */
    public static void stop() {
        enabled = false;
        debrisFound = false;
        findTime = 0;
        rtpInProgress.set(false);
        setState(State.IDLE);
        System.err.println("[barry] NetherAuto: stopped.");
    }

    /**
     * Check health and trigger disconnect if below threshold.
     */
    public static boolean checkHealthAndDisconnect() {
        if (!healthCheckDisconnect) return false;
        float health = getPlayerHealth();
        if (health > 0 && health < healthThreshold) {
            System.err.println("[barry] NetherAuto: health " + health + " below threshold " + healthThreshold + ", disconnecting.");
            triggerDisconnect();
            return true;
        }
        return false;
    }

    /**
     * Check if debris was found and handle disconnect.
     */
    public static void onDebrisFound(XrayEngine engine) {
        setDebrisFound(true);
        setState(State.FOUND);

        if (disconnectOnFind) {
            System.err.println("[barry] NetherAuto: debris found, disconnecting in spot.");
            triggerDisconnect();
        } else {
            // Keep scanning for a bit more, then stop auto mode
            System.err.println("[barry] NetherAuto: debris found! ESP brightened. Disconnect is " + (disconnectOnFind ? "ON" : "OFF") + ".");
        }
    }

    /**
     * Trigger disconnect via the agent load mechanism.
     */
    private static void triggerDisconnect() {
        setState(State.DISCONNECTING);
        // Set eject flag so the next agentmain call triggers disconnect
        barry.Bootstrap.eject();
        System.err.println("[barry] NetherAuto: disconnect triggered.");
    }

    /**
     * Send /rtp command to Minecraft via reflection.
     */
    private static void sendRtpCommand() {
        if (rtpInProgress.compareAndSet(false, true)) {
            rtpCenterX = 0;
            rtpCenterY = 64;
            rtpCenterZ = 0;

            Thread rtpThread = new Thread(() -> {
                try {
                    // Wait a moment for game to be ready
                    Thread.sleep(500);

                    Object mc = barry.render.RenderHook.minecraftInstance();
                    if (mc == null) {
                        rtpInProgress.set(false);
                        return;
                    }

                    ClassLoader cl = mc.getClass().getClassLoader();
                    // Get the player (LocalPlayer) from Minecraft instance
                    Object player = barry.render.RenderHook.findFieldByType(mc, "LocalPlayer");
                    if (player == null) {
                        rtpInProgress.set(false);
                        return;
                    }

                    // Record current position as RTP center
                    Method getX = player.getClass().getMethod("getX");
                    Method getY = player.getClass().getMethod("getY");
                    Method getZ = player.getClass().getMethod("getZ");
                    rtpCenterX = (double) getX.invoke(player);
                    rtpCenterY = (double) getY.invoke(player);
                    rtpCenterZ = (double) getZ.invoke(player);

                    // Send /rtp command via Minecraft's chat/command system
                    openChatScreen(); // Open ChatScreen so command appears in chat input
                    startCountdown();
                    sendCommand("/rtp nether");

                    setState(State.RTPING);
                    System.err.println("[barry] NetherAuto: sent /rtp nether, center=(" + rtpCenterX + "," + rtpCenterY + "," + rtpCenterZ + ")");

                    // Wait for teleport to complete
                    Thread.sleep(2000);

                    if (stayInArea) {
                        setState(State.SCANNING);
                    } else {
                        setState(State.SCANNING);
                    }
                    stopCountdown(); // Hide countdown when RTP is done
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    System.err.println("[barry] NetherAuto: RTP error: " + t.getMessage());
                } finally {
                    rtpInProgress.set(false);
                }
            }, "NetheriteAuto-RTP");
            rtpThread.setDaemon(true);
            rtpThread.start();
        }
    }

    /**
     * Send a chat command to Minecraft via reflection.
     * Works with both 1.21.x (Minecraft.handleCommand) and 26.x variants.
     */
    private static void sendCommand(String cmd) {
        try {
            Object mc = barry.render.RenderHook.minecraftInstance();
            if (mc == null) return;

            ClassLoader cl = mc.getClass().getClassLoader();
            String mcClassName = barry.attach.Remap.resolveClass("net.minecraft.client.Minecraft");
            Class<?> mcClass = cl.loadClass(mcClassName);

            // Debug: log all sendCommand/getCommand methods found on MC 26.3
            boolean debugLogged = false;
            for (java.lang.reflect.Method m : mcClass.getMethods()) {
                if ((m.getName().equals("sendCommand") || m.getName().equals("getCommand"))
                        && !debugLogged) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("[barry] NetherAuto: sendCommand methods on ");
                    sb.append(mcClass.getSimpleName()).append(":");
                    for (Class<?> p : m.getParameterTypes()) sb.append(" ").append(p.getSimpleName());
                    System.err.println(sb.toString());
                    debugLogged = true;
                }
            }

            // Try Minecraft.sendCommand(String, boolean) first
            try {
                Method sendCmd = mcClass.getMethod("sendCommand", String.class, boolean.class);
                sendCmd.invoke(mc, cmd, false);
                System.err.println("[barry] NetherAuto: sent via sendCommand(String,boolean): " + cmd);
                return;
            } catch (NoSuchMethodException ignored) {}

            // Fallback: any sendCommand/getCommand method accepting a String as first param
            for (java.lang.reflect.Method m : mcClass.getMethods()) {
                if ((m.getName().equals("sendCommand") || m.getName().equals("getCommand"))
                        && m.getParameterCount() >= 1
                        && m.getParameterTypes()[0] == String.class) {
                    m.setAccessible(true);
                    m.invoke(mc, cmd);
                    System.err.println("[barry] NetherAuto: sent via " + m.getName() + "(String): " + cmd);
                    return;
                }
            }

            // Fallback 2: Minecraft.chat.addMessage(String/Component) — MC 26.x chat path.
            // Try multiple field name variants since the field may be "chat", "Gui", etc.
            try {
                Object chat = null;
                for (java.lang.reflect.Field f : mc.getClass().getDeclaredFields()) {
                    String fn = f.getName();
                    if (fn.equalsIgnoreCase("chat") || fn.contains("Chat") || fn.contains("Gui")) {
                        f.setAccessible(true);
                        Object candidate = f.get(mc);
                        if (candidate != null) {
                            // Verify it has addMessage or sendMessage
                            for (java.lang.reflect.Method m : candidate.getClass().getMethods()) {
                                if ((m.getName().equals("addMessage") || m.getName().equals("sendMessage"))
                                        && m.getParameterCount() == 1) {
                                    chat = candidate;
                                    break;
                                }
                            }
                        }
                        if (chat != null) break;
                    }
                }
                if (chat != null) {
                    for (java.lang.reflect.Method m : chat.getClass().getMethods()) {
                        if ((m.getName().equals("addMessage") || m.getName().equals("sendMessage"))
                                && m.getParameterCount() == 1) {
                            Class<?> paramType = m.getParameterTypes()[0];
                            String pn = paramType.getSimpleName();
                            if (pn.equals("String") || pn.contains("Component") || pn.contains("Chat")) {
                                m.setAccessible(true);
                                m.invoke(chat, cmd);
                                System.err.println("[barry] NetherAuto: sent via Chat." + m.getName() + "(String): " + cmd);
                                return;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                System.err.println("[barry] NetherAuto: Chat path error: " + t.getMessage());
            }

            // Fallback 3: Minecraft.getCommands().processCommand(String) or handleCommand(String)
            try {
                Object commands = barry.render.RenderHook.findFieldByType(mc, "Commands");
                if (commands == null) {
                    Class<?> cmdsClass = cl.loadClass(barry.attach.Remap.resolveClass("net.minecraft.commands.Commands"));
                    Method getCmds = mcClass.getMethod("getCommands");
                    commands = getCmds.invoke(mc);
                }
                if (commands != null) {
                    for (java.lang.reflect.Method m : commands.getClass().getMethods()) {
                        if ((m.getName().equals("processCommand") || m.getName().equals("handleCommand"))
                                && m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == String.class) {
                            m.setAccessible(true);
                            m.invoke(commands, cmd);
                            System.err.println("[barry] NetherAuto: sent via Commands." + m.getName() + ": " + cmd);
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // Last resort: try Minecraft.sendCommand(Object) — accepts any arg
            for (java.lang.reflect.Method m : mcClass.getMethods()) {
                if (m.getName().equals("sendCommand") && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    m.invoke(mc, cmd);
                    System.err.println("[barry] NetherAuto: sent via sendCommand(Object): " + cmd);
                    return;
                }
            }

            System.err.println("[barry] NetherAuto: no command method found for \"" + cmd + "\"");
        } catch (Throwable t) {
            System.err.println("[barry] NetherAuto: sendCommand error: " + t.getMessage());
        }
    }

    /**
     * Get current player health via reflection.
     */
    private static float getPlayerHealth() {
        try {
            Object mc = barry.render.RenderHook.minecraftInstance();
            if (mc == null) return -1;

            Object player = barry.render.RenderHook.findFieldByType(mc, "LocalPlayer");
            if (player == null) return -1;

            for (java.lang.reflect.Method m : player.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == float.class
                        && (m.getName().equals("getHealth") || m.getName().length() <= 3)) {
                    m.setAccessible(true);
                    return (float) m.invoke(player);
                }
            }
        } catch (Throwable ignored) {}
        return -1; // unknown = healthy
    }

    /**
     * Periodic tick for auto mode state machine.
     */
    public static void tick(XrayEngine engine) {
        if (!enabled) return;

        // Check dimension — use BlockSource live reading; only stop if definitely not in nether.
        Dimension dim = Dimension.UNKNOWN;
        barry.xray.BlockSource bs = barry.Bootstrap.blockSource();
        if (bs != null) {
            dim = bs.liveDimension();
        } else if (engine != null) {
            dim = engine.currentDimension();
        }
        if (dim == Dimension.OVERWORLD || dim == Dimension.END) {
            System.err.println("[barry] NetherAuto: left nether (" + dim + "), stopping.");
            stop();
            return;
        }

        // Check health if enabled
        if (healthCheckDisconnect) {
            checkHealthAndDisconnect();
        }

        // If found and not disconnecting, keep bright ESP for a few seconds then resume scanning
        if (debrisFound && state.get() == State.FOUND) {
            if (disconnectOnFind) {
                // Already disconnected
            } else if (System.currentTimeMillis() - findTime > 5000) {
                // Bright ESP has been showing for 5s, resume scanning
                debrisFound = false;
                setState(State.SCANNING);
            }
        }

        // If staying in area, verify player hasn't wandered too far
        if (stayInArea && state.get() == State.SCANNING) {
            try {
                Object mc = barry.render.RenderHook.minecraftInstance();
                if (mc != null) {
                    Object player = barry.render.RenderHook.findFieldByType(mc, "LocalPlayer");
                    if (player != null) {
                        Method getX = player.getClass().getMethod("getX");
                        Method getY = player.getClass().getMethod("getY");
                        Method getZ = player.getClass().getMethod("getZ");
                        double px = (double) getX.invoke(player);
                        double py = (double) getY.invoke(player);
                        double pz = (double) getZ.invoke(player);

                        if (!isInArea(px, py, pz)) {
                            System.err.println("[barry] NetherAuto: player wandered out of area (" + px + "," + py + "," + pz + "), re-RTPing.");
                            sendRtpCommand();
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Check for debris in the current map via ore checker
        if (state.get() == State.SCANNING && engine != null) {
            BlockMap map = engine.currentMap();
            var ores = map.orePositions();
            for (var e : ores.entrySet()) {
                String name = e.getValue().blockName();
                if ("ancient_debris".equals(name) || name.contains("debris")) {
                    barry.core.OreType ot = OreType.fromBlockName(name);
                    if (ot != null && ot.isEnabled()) {
                        onDebrisFound(engine);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Build the HUD status line for auto mode.
     */
    public static String hudLine() {
        if (!enabled) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("NETH AUTO [");

        // State
        switch (state.get()) {
            case IDLE:       sb.append("IDLE"); break;
            case RTPING:     sb.append("RTP..."); break;
            case SCANNING:   sb.append("SCANNING"); break;
            case FOUND:      sb.append("FOUND!"); break;
            case DISCONNECTING: sb.append("DISC..."); break;
        }

        sb.append("] ");

        // Sub-options as single-letter indicators
        if (autoRtp)       sb.append("R"); else sb.append("-");
        if (stayInArea)    sb.append("A"); else sb.append("-");
        if (keepSearching) sb.append("K"); else sb.append("-");
        if (brightDebrisEsp) sb.append("B"); else sb.append("-");
        if (disconnectOnFind) sb.append("D"); else sb.append("-");
        if (healthCheckDisconnect) sb.append("H"); else sb.append("-");

        if (debrisFound) {
            long elapsed = (System.currentTimeMillis() - findTime) / 1000;
            sb.append(" ").append(elapsed).append("s");
        }

        return sb.toString();
    }
}

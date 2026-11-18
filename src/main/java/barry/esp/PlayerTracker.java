package barry.esp;

import barry.render.RenderHook;

import java.lang.reflect.*;
import java.util.*;

/**
 * Scans the client level for other players via reflection on MC 26.3 Mojmap.
 * Called from the Overlay tick thread (~10 Hz). The tracked list is consumed
 * by WorldRenderHook (3D outlines) and RenderHook (HUD text).
 */
public final class PlayerTracker {

    private static volatile boolean enabled = false;
    private static volatile List<TrackedPlayer> tracked = List.of();
    private static volatile long lastScan;
    private static volatile boolean resolveFailed;

    // Cached reflection handles — resolved lazily on first scan.
    private static volatile Method mPlayers;
    private static volatile Method mGetX, mGetY, mGetZ;
    private static volatile Method mGetHealth, mGetMaxHealth;
    private static volatile Method mGetGameProfile, mGetProfileName;
    private static volatile Method mGetNameComponent, mGetString;

    public record TrackedPlayer(
            String name, double x, double y, double z,
            float health, float maxHealth, double distance, int color) {}

    private PlayerTracker() {}

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean e) { enabled = e; }
    public static void toggle() { enabled = !enabled; }
    public static List<TrackedPlayer> tracked() { return tracked; }

    /** Called from the tick loop. Scans the client level for nearby players. */
    public static void scan() {
        if (!enabled) { tracked = List.of(); return; }
        if (resolveFailed) return;
        long now = System.currentTimeMillis();
        if (now - lastScan < 100) return; // 10 Hz
        lastScan = now;

        try {
            Object mc = RenderHook.minecraftInstance();
            if (mc == null) return;

            Object level = getLevel(mc);
            if (level == null) return;

            Object self = getLocalPlayer(mc);

            List<?> players = getPlayers(level);
            if (players == null || players.isEmpty()) { tracked = List.of(); return; }

            double selfX = 0, selfY = 0, selfZ = 0;
            if (self != null) {
                selfX = entityX(self); selfY = entityY(self); selfZ = entityZ(self);
            }

            List<TrackedPlayer> result = new ArrayList<>();
            for (Object player : players) {
                if (player == self) continue;

                double px = entityX(player), py = entityY(player), pz = entityZ(player);
                String name = playerName(player);
                float hp = entityHealth(player);
                float maxHp = entityMaxHealth(player);

                double dx = px - selfX, dy = py - selfY, dz = pz - selfZ;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

                result.add(new TrackedPlayer(name, px, py, pz, hp, maxHp, dist, healthColor(hp, maxHp)));
            }

            result.sort(Comparator.comparingDouble(TrackedPlayer::distance));
            tracked = result;
        } catch (Throwable t) {
            // Transient errors (e.g. world unloading) are fine — don't set resolveFailed.
        }
    }

    /** Resets cached state. Called on eject. */
    public static void reset() {
        enabled = false;
        tracked = List.of();
        mPlayers = null;
        mGetX = mGetY = mGetZ = null;
        mGetHealth = mGetMaxHealth = null;
        mGetGameProfile = mGetProfileName = null;
        mGetNameComponent = mGetString = null;
        resolveFailed = false;
    }

    // ---- Reflection helpers ------------------------------------------------

    private static Object getLevel(Object mc) {
        try {
            // ClientLevel / ClientWorld / MultiplayerLevel
            for (Field f : mc.getClass().getDeclaredFields()) {
                String tn = f.getType().getSimpleName();
                if (tn.contains("ClientLevel") || tn.contains("ClientWorld")
                        || tn.contains("MultiplayerLevel")) {
                    f.setAccessible(true);
                    return f.get(mc);
                }
            }
            // Fallback: any non-primitive field with "Level" in the type name.
            for (Field f : mc.getClass().getDeclaredFields()) {
                if (!f.getType().isPrimitive() && f.getType().getSimpleName().contains("Level")) {
                    f.setAccessible(true);
                    Object v = f.get(mc);
                    if (v != null) return v;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object getLocalPlayer(Object mc) {
        try {
            for (Field f : mc.getClass().getDeclaredFields()) {
                String tn = f.getType().getSimpleName();
                if (tn.contains("LocalPlayer") || tn.contains("ClientPlayerEntity")) {
                    f.setAccessible(true);
                    return f.get(mc);
                }
            }
            // Fallback: first Player-ish field that isn't a list.
            for (Field f : mc.getClass().getDeclaredFields()) {
                String tn = f.getType().getName();
                if (tn.contains("Player") && !tn.contains("List") && !tn.contains("Manager")) {
                    f.setAccessible(true);
                    Object v = f.get(mc);
                    if (v != null) return v;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<?> getPlayers(Object level) throws Exception {
        if (mPlayers == null) {
            for (Method m : level.getClass().getMethods()) {
                if (("players".equals(m.getName()) || "getPlayers".equals(m.getName()))
                        && m.getParameterCount() == 0
                        && List.class.isAssignableFrom(m.getReturnType())) {
                    mPlayers = m; break;
                }
            }
        }
        return mPlayers != null ? (List<?>) mPlayers.invoke(level) : null;
    }

    // ---- Entity position ---------------------------------------------------

    private static double entityX(Object e) throws Exception {
        if (mGetX == null) mGetX = findDoubleGetter(e, "getX");
        return (double) mGetX.invoke(e);
    }

    private static double entityY(Object e) throws Exception {
        if (mGetY == null) mGetY = findDoubleGetter(e, "getY");
        return (double) mGetY.invoke(e);
    }

    private static double entityZ(Object e) throws Exception {
        if (mGetZ == null) mGetZ = findDoubleGetter(e, "getZ");
        return (double) mGetZ.invoke(e);
    }

    private static Method findDoubleGetter(Object e, String name) throws Exception {
        for (Method m : e.getClass().getMethods()) {
            if (name.equals(m.getName()) && m.getParameterCount() == 0
                    && m.getReturnType() == double.class) {
                return m;
            }
        }
        throw new NoSuchMethodException(name);
    }

    // ---- Health ------------------------------------------------------------

    private static float entityHealth(Object e) {
        try {
            if (mGetHealth == null) {
                for (Method m : e.getClass().getMethods()) {
                    if ("getHealth".equals(m.getName()) && m.getParameterCount() == 0
                            && m.getReturnType() == float.class) {
                        mGetHealth = m; break;
                    }
                }
            }
            if (mGetHealth != null) return (float) mGetHealth.invoke(e);
        } catch (Throwable ignored) {}
        return 20f;
    }

    private static float entityMaxHealth(Object e) {
        try {
            if (mGetMaxHealth == null) {
                for (Method m : e.getClass().getMethods()) {
                    if ("getMaxHealth".equals(m.getName()) && m.getParameterCount() == 0
                            && m.getReturnType() == float.class) {
                        mGetMaxHealth = m; break;
                    }
                }
            }
            if (mGetMaxHealth != null) return (float) mGetMaxHealth.invoke(e);
        } catch (Throwable ignored) {}
        return 20f;
    }

    // ---- Player name -------------------------------------------------------

    private static String playerName(Object player) {
        try {
            // 1. getGameProfile().getName()
            if (mGetGameProfile == null) {
                for (Method m : player.getClass().getMethods()) {
                    if ("getGameProfile".equals(m.getName()) && m.getParameterCount() == 0) {
                        mGetGameProfile = m; break;
                    }
                }
            }
            if (mGetGameProfile != null) {
                Object profile = mGetGameProfile.invoke(player);
                if (profile != null) {
                    if (mGetProfileName == null) mGetProfileName = profile.getClass().getMethod("getName");
                    Object name = mGetProfileName.invoke(profile);
                    if (name instanceof String s && !s.isEmpty()) return s;
                }
            }
            // 2. getName().getString()
            if (mGetNameComponent == null) {
                for (Method m : player.getClass().getMethods()) {
                    if ("getName".equals(m.getName()) && m.getParameterCount() == 0
                            && m.getReturnType() != String.class) {
                        mGetNameComponent = m; break;
                    }
                }
            }
            if (mGetNameComponent != null) {
                Object comp = mGetNameComponent.invoke(player);
                if (comp != null) {
                    if (mGetString == null) {
                        for (Method gs : comp.getClass().getMethods()) {
                            if ("getString".equals(gs.getName()) && gs.getParameterCount() == 0
                                    && gs.getReturnType() == String.class) {
                                mGetString = gs; break;
                            }
                        }
                    }
                    if (mGetString != null) {
                        Object str = mGetString.invoke(comp);
                        if (str instanceof String s) return s;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "???";
    }

    // ---- Color: green (full HP) -> yellow (half) -> red (low) --------------

    private static int healthColor(float health, float maxHealth) {
        float pct = maxHealth > 0 ? Math.max(0, Math.min(1, health / maxHealth)) : 1f;
        int r, g;
        if (pct > 0.5f) {
            r = (int) ((1.0f - pct) * 2 * 255);
            g = 255;
        } else {
            r = 255;
            g = (int) (pct * 2 * 255);
        }
        return 0xFF000000 | (r << 16) | (g << 8);
    }
}

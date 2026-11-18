package barry.esp;

import barry.render.RenderHook;

import java.lang.reflect.*;
import java.util.*;

/**
 * Scans the client level for all entities whose type is enabled in
 * {@link EntityTypes}, via reflection on MC 26.3 Mojmap. Replaces the earlier
 * player-only tracker. Players keep their username + health; other entities show
 * their type name (or custom name). Called from the Overlay tick thread.
 */
public final class EntityTracker {

    private static volatile List<TrackedEntity> tracked = List.of();
    private static volatile long lastScan;
    private static volatile long lastDiag;

    // Cached reflection handles.
    private static volatile Method mEntitiesForRendering;
    private static volatile Method mGetType;
    private static volatile Method mGetX, mGetY, mGetZ;
    private static volatile Method mBbWidth, mBbHeight;
    private static volatile Method mGetHealth, mGetMaxHealth;
    private static volatile Method mGetCustomName, mGetCustomString;
    private static volatile Method mGetGameProfile, mGetProfileName;

    public record TrackedEntity(
            String typeName, String label,
            double x, double y, double z,
            double width, double height,
            float health, float maxHealth, boolean living, boolean player,
            double distance, int color) {}

    private EntityTracker() {}

    public static List<TrackedEntity> tracked() { return tracked; }

    /** Convenience: is the "player" entity type enabled (drives the Player ESP toggle). */
    public static boolean playersEnabled() { return EntityTypes.isEnabled("player"); }

    public static void scan() {
        if (EntityTypes.enabledCount() == 0) { tracked = List.of(); return; }
        long now = System.currentTimeMillis();
        if (now - lastScan < 100) return; // 10 Hz
        lastScan = now;

        try {
            Object mc = RenderHook.minecraftInstance();
            if (mc == null) return;

            Object level = getLevel(mc);
            if (level == null) return;
            Object self = getLocalPlayer(mc);

            Iterable<?> entities = entitiesForRendering(level);
            if (entities == null) { tracked = List.of(); return; }

            double sx = 0, sy = 0, sz = 0;
            if (self != null) { sx = entityX(self); sy = entityY(self); sz = entityZ(self); }

            List<TrackedEntity> result = new ArrayList<>();
            int raw = 0; String sampleType = null, samplePath = null;
            for (Object e : entities) {
                raw++;
                Object type = entityType(e);
                String path = EntityTypes.typePath(type);
                if (sampleType == null && type != null) {
                    sampleType = type.getClass().getSimpleName();
                    samplePath = path;
                }
                if (path == null || !EntityTypes.isEnabled(path)) continue;

                boolean player = "player".equals(path);
                if (player && e == self) continue; // never box ourselves

                double x = entityX(e), y = entityY(e), z = entityZ(e);
                double w = bb(e, true), h = bb(e, false);
                if (w <= 0) w = 0.6; if (h <= 0) h = 1.8;

                boolean living = false;
                float hp = 0, maxHp = 0;
                Float hv = health(e);
                if (hv != null) { living = true; hp = hv; maxHp = maxHealth(e); }

                String label;
                if (player) label = playerName(e);
                else {
                    String cn = customName(e);
                    label = (cn != null && !cn.isEmpty()) ? cn : path;
                }

                double dx = x - sx, dy = y - sy, dz = z - sz;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

                int color = player ? healthColor(hp, maxHp) : EntityTypes.color(path);
                result.add(new TrackedEntity(path, label, x, y, z, w, h,
                        hp, maxHp, living, player, dist, color));
            }

            result.sort(Comparator.comparingDouble(TrackedEntity::distance));
            if (result.size() > 400) result = result.subList(0, 400);
            tracked = result;

            if (now - lastDiag > 2000) {
                lastDiag = now;
                System.err.println("[barry] entity diag: level=" + level.getClass().getSimpleName()
                        + " method=" + (mEntitiesForRendering != null ? mEntitiesForRendering.getName() : "null")
                        + " raw=" + raw + " matched=" + result.size()
                        + " sampleType=" + sampleType + " samplePath=" + samplePath);
            }
        } catch (Throwable t) {
            // transient (world unloading, etc.) — keep last list, don't spam
        }
    }

    public static void reset() {
        tracked = List.of();
        mEntitiesForRendering = null;
        mGetType = null;
        mGetX = mGetY = mGetZ = null;
        mBbWidth = mBbHeight = null;
        mGetHealth = mGetMaxHealth = null;
        mGetCustomName = mGetCustomString = null;
        mGetGameProfile = mGetProfileName = null;
    }

    // ---- Reflection --------------------------------------------------------

    private static Object getLevel(Object mc) {
        try {
            for (Field f : mc.getClass().getDeclaredFields()) {
                String tn = f.getType().getSimpleName();
                if (tn.contains("ClientLevel") || tn.contains("ClientWorld")
                        || tn.contains("MultiplayerLevel")) {
                    f.setAccessible(true);
                    return f.get(mc);
                }
            }
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

    private static Iterable<?> entitiesForRendering(Object level) throws Exception {
        if (mEntitiesForRendering == null) {
            // Prefer the exact client method.
            for (Method m : level.getClass().getMethods()) {
                if ("entitiesForRendering".equals(m.getName()) && m.getParameterCount() == 0
                        && Iterable.class.isAssignableFrom(m.getReturnType())) {
                    mEntitiesForRendering = m; break;
                }
            }
            // Fallback: any no-arg Iterable getter with "entit" in the name.
            if (mEntitiesForRendering == null) {
                for (Method m : level.getClass().getMethods()) {
                    if (m.getParameterCount() == 0
                            && Iterable.class.isAssignableFrom(m.getReturnType())
                            && m.getName().toLowerCase().contains("entit")) {
                        mEntitiesForRendering = m; break;
                    }
                }
            }
        }
        return mEntitiesForRendering != null ? (Iterable<?>) mEntitiesForRendering.invoke(level) : null;
    }

    private static Object entityType(Object e) throws Exception {
        if (mGetType == null) {
            for (Method m : e.getClass().getMethods()) {
                if ("getType".equals(m.getName()) && m.getParameterCount() == 0
                        && m.getReturnType().getSimpleName().equals("EntityType")) {
                    mGetType = m; break;
                }
            }
        }
        return mGetType != null ? mGetType.invoke(e) : null;
    }

    private static double entityX(Object e) throws Exception {
        if (mGetX == null) mGetX = doubleGetter(e, "getX");
        return (double) mGetX.invoke(e);
    }
    private static double entityY(Object e) throws Exception {
        if (mGetY == null) mGetY = doubleGetter(e, "getY");
        return (double) mGetY.invoke(e);
    }
    private static double entityZ(Object e) throws Exception {
        if (mGetZ == null) mGetZ = doubleGetter(e, "getZ");
        return (double) mGetZ.invoke(e);
    }

    private static Method doubleGetter(Object e, String name) throws Exception {
        for (Method m : e.getClass().getMethods()) {
            if (name.equals(m.getName()) && m.getParameterCount() == 0
                    && m.getReturnType() == double.class) return m;
        }
        throw new NoSuchMethodException(name);
    }

    private static double bb(Object e, boolean width) {
        try {
            Method m = width ? mBbWidth : mBbHeight;
            if (m == null) {
                String want = width ? "getBbWidth" : "getBbHeight";
                for (Method cand : e.getClass().getMethods()) {
                    if (want.equals(cand.getName()) && cand.getParameterCount() == 0
                            && cand.getReturnType() == float.class) { m = cand; break; }
                }
                if (width) mBbWidth = m; else mBbHeight = m;
            }
            if (m != null) return (float) m.invoke(e);
        } catch (Throwable ignored) {}
        return width ? 0.6 : 1.8;
    }

    private static Float health(Object e) {
        try {
            if (mGetHealth == null) {
                for (Method m : e.getClass().getMethods()) {
                    if ("getHealth".equals(m.getName()) && m.getParameterCount() == 0
                            && m.getReturnType() == float.class) { mGetHealth = m; break; }
                }
            }
            if (mGetHealth != null) return (Float) mGetHealth.invoke(e);
        } catch (Throwable ignored) {}
        return null; // non-living (item, orb, projectile)
    }

    private static float maxHealth(Object e) {
        try {
            if (mGetMaxHealth == null) {
                for (Method m : e.getClass().getMethods()) {
                    if ("getMaxHealth".equals(m.getName()) && m.getParameterCount() == 0
                            && m.getReturnType() == float.class) { mGetMaxHealth = m; break; }
                }
            }
            if (mGetMaxHealth != null) return (float) mGetMaxHealth.invoke(e);
        } catch (Throwable ignored) {}
        return 20f;
    }

    private static String customName(Object e) {
        try {
            if (mGetCustomName == null) {
                for (Method m : e.getClass().getMethods()) {
                    if ("getCustomName".equals(m.getName()) && m.getParameterCount() == 0) {
                        mGetCustomName = m; break;
                    }
                }
            }
            if (mGetCustomName != null) {
                Object comp = mGetCustomName.invoke(e);
                if (comp != null) {
                    if (mGetCustomString == null) {
                        for (Method gs : comp.getClass().getMethods()) {
                            if ("getString".equals(gs.getName()) && gs.getParameterCount() == 0
                                    && gs.getReturnType() == String.class) { mGetCustomString = gs; break; }
                        }
                    }
                    if (mGetCustomString != null) {
                        Object s = mGetCustomString.invoke(comp);
                        if (s instanceof String str) return str;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String playerName(Object player) {
        try {
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
        } catch (Throwable ignored) {}
        String cn = customName(player);
        return cn != null ? cn : "player";
    }

    private static int healthColor(float health, float maxHealth) {
        float pct = maxHealth > 0 ? Math.max(0, Math.min(1, health / maxHealth)) : 1f;
        int r, g;
        if (pct > 0.5f) { r = (int) ((1.0f - pct) * 2 * 255); g = 255; }
        else { r = 255; g = (int) (pct * 2 * 255); }
        return 0xFF000000 | (r << 16) | (g << 8);
    }
}

package barry.esp;

import barry.render.RenderHook;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entity-type catalog, analogous to {@link barry.core.OreType} for blocks.
 * Enumerates all registered entity type names from BuiltInRegistries.ENTITY_TYPE
 * so the entity filter menu can list every kind of entity (searchable), and
 * tracks which type names the user has enabled. All default OFF.
 */
public final class EntityTypes {

    private static volatile List<String> names;             // all type paths, sorted
    private static volatile Object registry;                // Registry<EntityType<?>>
    private static volatile Method mGetKey;                 // Registry.getKey(T) -> ResourceLocation
    private static volatile Method mGetPath;                // ResourceLocation.getPath()

    private static final Set<String> enabled = ConcurrentHashMap.newKeySet();

    private EntityTypes() {}

    /** All entity type names (lazy-enumerated from the registry). */
    public static List<String> allNames() {
        ensureLoaded();
        return names != null ? names : List.of();
    }

    public static boolean isEnabled(String name) { return name != null && enabled.contains(name); }
    public static void setEnabled(String name, boolean on) {
        if (name == null) return;
        if (on) enabled.add(name); else enabled.remove(name);
    }
    public static void toggle(String name) {
        if (name == null) return;
        boolean on;
        if (enabled.remove(name)) on = false; else { enabled.add(name); on = true; }
        System.err.println("[barry] entity type " + name + " -> " + (on ? "ON" : "OFF")
                + " (enabled=" + enabled.size() + ")");
    }
    public static int enabledCount() { return enabled.size(); }

    /** Disable every type (used by panic) without dropping the cached type list. */
    public static void disableAll() { enabled.clear(); }

    public static void reset() {
        enabled.clear();
        names = null;
        registry = null;
        mGetKey = null;
        mGetPath = null;
    }

    // ---- Registry enumeration ---------------------------------------------

    private static synchronized void ensureLoaded() {
        if (names != null) return;
        try {
            Object mc = RenderHook.minecraftInstance();
            ClassLoader cl = (mc != null) ? mc.getClass().getClassLoader()
                                          : barry.Bootstrap.findMinecraftClassLoader();
            if (cl == null) { names = fallback(); return; }

            Class<?> builtIn = cl.loadClass("net.minecraft.core.registries.BuiltInRegistries");
            Object reg = builtIn.getField("ENTITY_TYPE").get(null);
            registry = reg;

            // Registry.keySet() -> Set<ResourceLocation>
            Method keySet = null;
            for (Method m : reg.getClass().getMethods()) {
                if ("keySet".equals(m.getName()) && m.getParameterCount() == 0
                        && Set.class.isAssignableFrom(m.getReturnType())) { keySet = m; break; }
            }
            if (keySet == null) { names = fallback(); return; }

            Set<?> keys = (Set<?>) keySet.invoke(reg);
            List<String> list = new ArrayList<>(keys.size());
            for (Object rl : keys) {
                String p = pathOf(rl);
                if (p != null) list.add(p);
            }
            Collections.sort(list);
            names = list;
            System.err.println("[barry] entity types loaded: " + list.size());
        } catch (Throwable t) {
            System.err.println("[barry] entity registry enumerate failed: " + t);
            names = fallback();
        }
    }

    /** Registry path for a live entity's EntityType (e.g. "zombie"), or null. */
    public static String typePath(Object entityType) {
        try {
            Object reg = registry;
            if (reg == null) { ensureLoaded(); reg = registry; }
            if (reg == null || entityType == null) return null;
            if (mGetKey == null) {
                for (Method m : reg.getClass().getMethods()) {
                    if ("getKey".equals(m.getName()) && m.getParameterCount() == 1) {
                        String rt = m.getReturnType().getSimpleName();
                        // 26.3 renamed ResourceLocation -> Identifier.
                        if (rt.equals("ResourceLocation") || rt.equals("Identifier")) {
                            mGetKey = m; break;
                        }
                    }
                }
            }
            if (mGetKey == null) return null;
            Object rl = mGetKey.invoke(reg, entityType);
            return pathOf(rl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String pathOf(Object resourceLocation) {
        try {
            if (resourceLocation == null) return null;
            if (mGetPath == null) mGetPath = resourceLocation.getClass().getMethod("getPath");
            Object p = mGetPath.invoke(resourceLocation);
            return (p instanceof String s) ? s : null;
        } catch (Throwable t) {
            // last resort: parse toString() "namespace:path"
            String s = String.valueOf(resourceLocation);
            int i = s.indexOf(':');
            return i >= 0 ? s.substring(i + 1) : s;
        }
    }

    /** Stable per-type color (players are colored by health in the tracker). */
    public static int color(String name) {
        if (name == null) return 0xFFFFFFFF;
        int h = name.hashCode();
        float hue = (Math.floorMod(h, 360)) / 360f;
        return 0xFF000000 | (hsv(hue, 0.65f, 1.0f) & 0xFFFFFF);
    }

    private static int hsv(float h, float s, float v) {
        float r = 0, g = 0, b = 0;
        int i = (int) (h * 6) % 6;
        float f = h * 6 - (float) Math.floor(h * 6);
        float p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        switch (i) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            default: r = v; g = p; b = q; break;
        }
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    // Minimal fallback if the registry can't be read, so the menu still works.
    private static List<String> fallback() {
        return new ArrayList<>(List.of(
            "player", "zombie", "skeleton", "creeper", "spider", "enderman",
            "witch", "slime", "blaze", "ghast", "piglin", "hoglin", "wither_skeleton",
            "cow", "pig", "sheep", "chicken", "horse", "wolf", "cat", "villager",
            "iron_golem", "armor_stand", "item", "experience_orb", "arrow", "boat",
            "minecart", "item_frame", "allay", "axolotl", "warden", "end_crystal"
        ));
    }
}

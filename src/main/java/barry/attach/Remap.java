package barry.attach;

import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Remap {

    public enum State { UNINITIALIZED, READY, DEGRADED, SHUTDOWN }

    private static volatile State state = State.UNINITIALIZED;
    private static String clientVersion;

    // obfuscated name -> deobfuscated name
    private static final Map<String, String> CLASS_MAP = new ConcurrentHashMap<>();
    // "class.method desc" -> obfuscated method name
    private static final Map<String, String> METHOD_MAP = new ConcurrentHashMap<>();
    // "class.field" -> obfuscated field name
    private static final Map<String, String> FIELD_MAP = new ConcurrentHashMap<>();

    private Remap() {}

    public static void init(String version, Instrumentation inst) {
        clientVersion = version;
        // In modern MC (1.20.2+), the client ships with Mojang official mappings
        // applied at runtime by the launcher. The class names in memory ARE the
        // Mojmap names (net.minecraft.client.Minecraft etc). We only need a
        // mapping table if the client uses obfuscated (intermediary/srg) names.
        //
        // Detection: try to load a known Mojmap class name. If it succeeds,
        // we're running against Mojmap and need no remapping.
        if (probeForMojmap()) {
            System.err.println("[barry] Client uses Mojmap names. No remapping needed.");
            state = State.READY;
            return;
        }

        // Client uses obfuscated names. We need mappings.
        // For now, fail closed: we don't bundle mappings yet, so enter degraded mode.
        System.err.println("[barry] Client uses obfuscated names. Mappings not yet loaded.");
        System.err.println("[barry] Entering degraded mode (overlay only, no game hooks).");
        state = State.DEGRADED;
    }

    public static void initDegraded() {
        state = State.DEGRADED;
    }

    public static void shutdown() {
        CLASS_MAP.clear();
        METHOD_MAP.clear();
        FIELD_MAP.clear();
        state = State.SHUTDOWN;
    }

    public static State state() {
        return state;
    }

    public static String version() {
        return clientVersion;
    }

    public static boolean isReady() {
        return state == State.READY;
    }

    public static String resolveClass(String mojmapName) {
        if (state == State.READY && CLASS_MAP.isEmpty()) {
            return mojmapName;
        }
        return CLASS_MAP.getOrDefault(mojmapName, mojmapName);
    }

    public static String resolveMethod(String className, String methodName, String desc) {
        if (state == State.READY && METHOD_MAP.isEmpty()) {
            return methodName;
        }
        return METHOD_MAP.getOrDefault(className + "." + methodName + " " + desc, methodName);
    }

    public static String resolveField(String className, String fieldName) {
        if (state == State.READY && FIELD_MAP.isEmpty()) {
            return fieldName;
        }
        return FIELD_MAP.getOrDefault(className + "." + fieldName, fieldName);
    }

    private static boolean probeForMojmap() {
        ClassLoader cl = barry.Bootstrap.findMinecraftClassLoader();
        if (cl == null) return false;

        String[] probes = {
            "net.minecraft.client.Minecraft",
            "net.minecraft.world.level.chunk.LevelChunk",
            "net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket",
        };

        for (String probe : probes) {
            try {
                cl.loadClass(probe);
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
        return true;
    }
}
